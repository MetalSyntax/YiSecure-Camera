package com.metalsyntax.yihomelocal.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Lo que se puede leer de una persona en el cuadro. Todo es estimado y queda vacío si la persona se ve muy pequeña:
 * postura y edad salen del cuerpo (pose), emoción y mirada de la cara (blendshapes, solo con la cara de frente y cerca).
 */
data class PersonInfo(
    val posture: String = "", val age: String = "", val emotion: String = "",
    val facing: String = "", val looking: Boolean = false, val armsUp: Boolean = false,
) {
    val lying get() = posture == POSTURE_FLOOR

    /** "adulto · de pie · alegre · mira a la cámara" */
    fun text() = listOf(age, posture, emotion, if (looking) "mira a la cámara" else facing, if (armsUp) "brazos en alto" else "")
        .filter { it.isNotEmpty() }.joinToString(" · ")

    companion object { const val POSTURE_FLOOR = "en el suelo" }
}

/** Análisis de personas con los modelos oficiales de MediaPipe (pose_landmarker_lite + face_landmarker, en assets). */
object People {
    private var pose: PoseLandmarker? = null
    private var face: FaceLandmarker? = null
    private var failed = false

    /** Por debajo de esta altura (px del cuadro) la pose no sirve. */
    private const val MIN_PERSON_PX = 90
    /** Cara más chica que esto (px) = no se pueden leer expresiones. */
    private const val MIN_FACE_PX = 28

    @Synchronized
    private fun load(ctx: Context): Boolean {
        if (pose == null && !failed) runCatching {
            pose = PoseLandmarker.createFromOptions(ctx.applicationContext, PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").build())
                .setRunningMode(RunningMode.IMAGE).setNumPoses(1).setMinPoseDetectionConfidence(0.4f).build())
            face = FaceLandmarker.createFromOptions(ctx.applicationContext, FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
                // exigente: con 0,4 "encontraba" caras en la nuca de alguien de espaldas
                .setRunningMode(RunningMode.IMAGE).setNumFaces(1).setMinFaceDetectionConfidence(0.7f).setMinFacePresenceConfidence(0.7f)
                .setOutputFaceBlendshapes(true).build())
        }.onFailure { failed = true; android.util.Log.e("YiAI", "No se pudieron cargar los modelos de personas", it) }
        return pose != null
    }

    /** Añade [PersonInfo] a las personas lo bastante grandes (las 3 más grandes, para no gastar batería). */
    @Synchronized
    fun describe(ctx: Context, bmp: Bitmap, dets: List<Det>): List<Det> {
        if (dets.none { it.kind == Kind.PERSON } || !load(ctx)) return dets
        val w = bmp.width; val h = bmp.height
        val big = dets.filter { it.kind == Kind.PERSON && it.box.height() * h >= MIN_PERSON_PX }
            .sortedByDescending { it.box.width() * it.box.height() }.take(3).toSet()
        return dets.map { d ->
            if (d !in big) d else runCatching { analyze(bmp, d.box.toPx(w, h)) }.getOrNull()?.let { d.copy(person = it) } ?: d
        }
    }

    private fun android.graphics.RectF.toPx(w: Int, h: Int): Rect {
        // margen del 15 % para que la pose vea pies y cabeza enteros
        val mx = width() * 0.15f; val my = height() * 0.1f
        return Rect(((left - mx) * w).toInt().coerceAtLeast(0), ((top - my) * h).toInt().coerceAtLeast(0),
            ((right + mx) * w).toInt().coerceAtMost(w), ((bottom + my) * h).toInt().coerceAtMost(h))
    }

    private fun analyze(bmp: Bitmap, r: Rect): PersonInfo? {
        if (r.width() < 16 || r.height() < 16) return null
        // ojo: createBitmap devuelve el mismo bitmap si el recorte es el cuadro entero → no reciclarlo
        val crop = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
        try {
            val lm = pose!!.detect(BitmapImageBuilder(crop).build()).landmarks().firstOrNull() ?: return null
            val cw = crop.width.toFloat(); val ch = crop.height.toFloat()
            // puntos en px del recorte (la pose da 0..1 por eje)
            fun p(i: Int) = lm[i].x() * cw to lm[i].y() * ch
            fun vis(i: Int) = lm[i].visibility().orElse(0f)
            fun mid(a: Int, b: Int) = (p(a).first + p(b).first) / 2 to (p(a).second + p(b).second) / 2

            val sh = mid(11, 12); val hip = mid(23, 24)
            val torso = hypot(sh.first - hip.first, sh.second - hip.second).coerceAtLeast(1f)
            val tilt = Math.toDegrees(atan2(abs(sh.first - hip.first).toDouble(), abs(sh.second - hip.second).toDouble()))
            val kneesSeen = vis(25) > 0.8f && vis(26) > 0.8f
            val anklesSeen = vis(27) > 0.8f && vis(28) > 0.8f
            val knee = mid(25, 26); val ankle = mid(27, 28)
            val posture = when {
                tilt > 60 -> PersonInfo.POSTURE_FLOOR
                kneesSeen && anklesSeen && ankle.second - hip.second < 0.6f * torso -> "agachada"
                kneesSeen && knee.second - hip.second < 0.35f * torso -> "sentada"
                kneesSeen -> "de pie"
                else -> ""
            }
            // la pose "inventa" puntos tapados (escritorio, silla): solo se usan los muy visibles
            // muñeca claramente sobre la cabeza (un tercio de torso), no a la altura de la cara (escribir, rascarse)
            val above = 0.33f * torso
            val armsUp = vis(0) > 0.8f && ((vis(15) > 0.8f && p(15).second < p(0).second - above) || (vis(16) > 0.8f && p(16).second < p(0).second - above))
            val front = vis(0) > 0.8f && vis(2) > 0.8f && vis(5) > 0.8f

            // edad por proporciones (cabezas por altura): adulto ≈ 7, niño de 5 años ≈ 5. Solo de pie y con el cuerpo entero.
            val age = if (posture == "de pie" && anklesSeen && front && vis(9) > 0.5f && vis(10) > 0.5f) {
                val eyes = mid(2, 5); val mouth = mid(9, 10)
                val head = 3.2f * hypot(eyes.first - mouth.first, eyes.second - mouth.second)
                val heads = if (head > 2f) (ankle.second - p(0).second + head / 2) / head else 0f
                when { heads <= 0f -> ""; heads < 5.6f -> "niño (≈3–8 años)"; heads < 6.6f -> "joven (≈9–15 años)"; else -> "adulto" }
            } else ""

            // cara: recorte cuadrado alrededor de la nariz, del ancho de los hombros
            // "de frente", emoción y mirada solo si el detector de caras confirma una cara (la pose sola se equivocaba de espaldas)
            var emotion = ""; var looking = false; var facing = ""
            val faceSize = (hypot(p(11).first - p(12).first, p(11).second - p(12).second) * 0.9f).toInt()
            if (front && faceSize >= MIN_FACE_PX) faceOf(crop, p(0), faceSize)?.let { (e, l) -> emotion = e; looking = l; facing = "de frente" }
            return PersonInfo(posture, age, emotion, facing, looking, armsUp)
        } finally { if (crop !== bmp) crop.recycle() }
    }

    /** Emoción (por blendshapes) y si mira de frente a la cámara (nariz centrada entre los ojos). */
    private fun faceOf(crop: Bitmap, nose: Pair<Float, Float>, size: Int): Pair<String, Boolean>? {
        val half = size
        val l = (nose.first - half).toInt().coerceAtLeast(0); val t = (nose.second - half).toInt().coerceAtLeast(0)
        val r = (nose.first + half).toInt().coerceAtMost(crop.width); val b = (nose.second + half).toInt().coerceAtMost(crop.height)
        if (r - l < 16 || b - t < 16) return null
        val raw = Bitmap.createBitmap(crop, l, t, r - l, b - t)
        // el detector de caras de MediaPipe trabaja a 192 px: agrandar caras chicas ayuda
        val img = if (raw.width < 192) Bitmap.createScaledBitmap(raw, 192, 192 * raw.height / raw.width, true).also { if (raw !== crop) raw.recycle() } else raw
        try {
            val res = face!!.detect(BitmapImageBuilder(img).build())
            val bs = res.faceBlendshapes().orElse(null)?.firstOrNull() ?: return null
            val s = bs.associate { it.categoryName() to it.score() }
            fun v(n: String) = s[n] ?: 0f
            fun both(n: String) = (v("${n}Left") + v("${n}Right")) / 2
            val emotion = when {
                both("mouthSmile") > 0.45f -> "alegre"
                v("jawOpen") > 0.35f && (v("browInnerUp") > 0.35f || both("eyeWide") > 0.3f) -> "sorprendida"
                both("browDown") > 0.4f && (both("noseSneer") > 0.2f || both("mouthPress") > 0.3f) -> "enojada o tensa"
                both("mouthFrown") > 0.25f && v("browInnerUp") > 0.25f -> "triste"
                both("eyeBlink") > 0.6f -> "ojos cerrados"
                else -> "neutral"
            }
            val lm: List<NormalizedLandmark> = res.faceLandmarks().firstOrNull() ?: return emotion to false
            // 1 = punta de la nariz, 33/263 = rabillos externos de los ojos
            val ratio = (lm[1].x() - lm[33].x()) / (lm[263].x() - lm[33].x()).let { if (abs(it) < 1e-3f) 1e-3f else it }
            return emotion to (ratio in 0.38f..0.62f)
        } finally { if (img !== crop) img.recycle() }
    }
}
