package com.metalsyntax.yihomelocal.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import kotlin.math.abs

/** Qué le interesa a una cámara de seguridad de las 80 clases COCO del modelo. */
enum class Kind(val label: String) { PERSON("PERSONA"), VEHICLE("VEHÍCULO"), ANIMAL("MASCOTA"), MOTION("MOVIMIENTO"), SHADOW("SOMBRA") }

/** Detección con la caja normalizada a 0..1 sobre el cuadro. */
data class Det(val kind: Kind, val name: String, val score: Float, val box: RectF)

/**
 * Detector de objetos en el teléfono: EfficientDet-Lite0 (COCO, 4,5 MB, modelo de los ejemplos de TensorFlow Lite en GitHub)
 * con la Task Library. No usa el SDK de Yi ni la nube: mira los cuadros que ya se ven en pantalla.
 */
object Detector {
    private var detector: ObjectDetector? = null
    private var failed = false

    private val KINDS = mapOf(
        "person" to Kind.PERSON,
        "car" to Kind.VEHICLE, "truck" to Kind.VEHICLE, "bus" to Kind.VEHICLE, "motorcycle" to Kind.VEHICLE, "bicycle" to Kind.VEHICLE,
        // sin "paquete": COCO no tiene caja y maleta/mochila/bolso confundía sillas y muebles
        "dog" to Kind.ANIMAL, "cat" to Kind.ANIMAL, "bird" to Kind.ANIMAL,
    )

    /**
     * Confianza mínima por clase (el modelo corre a 0,45). Gatos y perros se confunden con cojines, sillas o bultos
     * a baja confianza; las aves pequeñas aún más.
     */
    private val MIN_SCORE = mapOf("cat" to 0.55f, "dog" to 0.55f, "bird" to 0.6f)

    @Synchronized
    private fun get(ctx: Context): ObjectDetector? {
        if (detector == null && !failed) detector = runCatching {
            ObjectDetector.createFromFileAndOptions(
                ctx.applicationContext, "efficientdet_lite0.tflite",
                ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setNumThreads(2).build())
                    .setMaxResults(10).setScoreThreshold(0.45f).build(),
            )
        }.onFailure { failed = true; android.util.Log.e("YiAI", "No se pudo cargar el modelo", it) }.getOrNull()
        return detector
    }

    @Synchronized
    fun detect(ctx: Context, bmp: Bitmap): List<Det> {
        val d = get(ctx) ?: return emptyList()
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        return d.detect(TensorImage.fromBitmap(bmp)).mapNotNull { r ->
            val c = r.categories.firstOrNull() ?: return@mapNotNull null
            val kind = KINDS[c.label] ?: return@mapNotNull null
            if (c.score < (MIN_SCORE[c.label] ?: 0f)) return@mapNotNull null
            val b = r.boundingBox
            // una caja que ocupa casi todo el cuadro suele ser un error del modelo (pared, cama, sofá)
            if (b.width() * b.height() > 0.8f * w * h) return@mapNotNull null
            Det(kind, c.label, c.score, RectF((b.left / w).coerceIn(0f, 1f), (b.top / h).coerceIn(0f, 1f), (b.right / w).coerceIn(0f, 1f), (b.bottom / h).coerceIn(0f, 1f)))
        }
    }
}

/** Lo que se sabe del cuadro sin modelo: noche (IR en blanco y negro), zona con movimiento y si parece una sombra. */
data class Scene(val night: Boolean, val motion: RectF?, val shadow: Boolean)

/**
 * Análisis barato sobre una rejilla de 64×36: noche = casi sin color (la cámara pasa a infrarrojo);
 * movimiento = celdas que cambiaron de brillo respecto al cuadro anterior; sombra = la zona solo se oscureció,
 * en proporción pareja (una sombra baja el brillo sin cambiar la textura).
 */
class SceneAnalyzer {
    private var prev: FloatArray? = null

    fun analyze(bmp: Bitmap): Scene {
        val small = Bitmap.createScaledBitmap(bmp, W, H, true)
        val px = IntArray(W * H).also { small.getPixels(it, 0, W, 0, 0, W, H) }
        if (small !== bmp) small.recycle()
        val luma = FloatArray(px.size)
        var chroma = 0f
        px.forEachIndexed { i, p ->
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            luma[i] = 0.299f * r + 0.587f * g + 0.114f * b
            chroma += (abs(r - g) + abs(g - b) + abs(b - r)) / 3f
        }
        val night = chroma / px.size < 6f
        val old = prev
        prev = luma
        if (old == null) return Scene(night, null, false)

        var minX = W; var minY = H; var maxX = -1; var maxY = -1
        var changed = 0; var darker = 0
        val ratios = ArrayList<Float>()
        for (i in luma.indices) {
            val d = luma[i] - old[i]
            if (abs(d) < 25f) continue
            changed++
            if (d < 0) { darker++; if (old[i] > 10f) ratios += luma[i] / old[i] }
            val x = i % W; val y = i / W
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        val frac = changed.toFloat() / luma.size
        // <0,4 % = ruido; >60 % = cambio de luz de toda la escena (IR que se enciende, nube, auto-exposición)
        if (frac < 0.004f || frac > 0.6f) return Scene(night, null, false)
        val box = RectF(minX / W.toFloat(), minY / H.toFloat(), (maxX + 1) / W.toFloat(), (maxY + 1) / H.toFloat())
        val mean = ratios.average().toFloat()
        val spread = if (ratios.size > 1) kotlin.math.sqrt(ratios.map { (it - mean) * (it - mean) }.average()).toFloat() else 1f
        val shadow = darker > changed * 0.85f && ratios.size >= 4 && spread < 0.12f && mean < 0.8f
        return Scene(night, box, shadow)
    }

    private companion object { const val W = 64; const val H = 36 }
}

/** Evento que vale la pena guardar (con foto) en "Eventos recientes detectados". */
data class Finding(val kind: Kind, val title: String, val desc: String)

/**
 * Reglas sobre lo que se detecta cuadro a cuadro para sacar "acciones": quién entra, quién merodea, quién se va,
 * un vehículo que se mueve o se estaciona, una mascota que pasa o se queda. Un objeto por tipo (el más seguro).
 */
class Sentinel {
    /** [startArea]/[area] = tamaño de la caja: si crece se acerca a la cámara, si se achica se aleja. */
    private class Track(val first: Long, var last: Long, val startX: Float, var x: Float, val startArea: Float = 0f, var area: Float = 0f) {
        val reported = HashSet<String>()
        fun seconds(now: Long) = ((now - first) / 1000).toInt()
    }

    private val tracks = HashMap<Kind, Track>()
    private val lastEmit = HashMap<Kind, Long>()

    fun update(now: Long, dets: List<Det>, scene: Scene): List<Finding> {
        val out = ArrayList<Finding>()
        val best = dets.groupBy { it.kind }.mapValues { (_, l) -> l.maxBy { it.score } }

        // se fue: más de 15 s sin verlo
        tracks.entries.removeAll { (k, t) ->
            val gone = now - t.last > 15_000
            if (gone && k == Kind.PERSON && t.seconds(t.last) >= 3) out += Finding(k, "Persona salió",
                "Estuvo ${t.seconds(t.last)} s en escena · salió por ${side(t.x)}")
            gone
        }

        best.forEach { (k, d) ->
            val cx = d.box.centerX()
            val t = tracks[k]
            if (t == null) {
                val a0 = d.box.width() * d.box.height()
                tracks[k] = Track(now, now, cx, cx, a0, a0)
                out += when (k) {
                    Kind.PERSON -> Finding(k, "Persona detectada", "Entró por ${side(cx)} · confianza ${(d.score * 100).toInt()} %")
                    Kind.VEHICLE -> Finding(k, "Vehículo detectado", "${name(d.name)} · confianza ${(d.score * 100).toInt()} %")
                    else -> Finding(k, "${name(d.name)} detectado", "En ${side(cx)} · confianza ${(d.score * 100).toInt()} %")
                }
                return@forEach
            }
            t.last = now; t.x = cx; t.area = d.box.width() * d.box.height()
            val secs = t.seconds(now)
            fun once(tag: String, f: () -> Finding) { if (t.reported.add(tag)) out += f() }
            when (k) {
                Kind.PERSON -> {
                    if (secs >= 20) once("loiter") { Finding(k, "Merodeo", "Persona en escena más de $secs s") }
                    // dirección por el tamaño de la caja (≥ 1,8x más grande = se acercó; ≤ 0,5x = se alejó)
                    if (t.startArea > 0f && t.area / t.startArea >= 1.8f) once("near") { Finding(k, "Persona se acerca", "Se acercó a la cámara en $secs s") }
                    if (t.startArea > 0f && t.area / t.startArea <= 0.5f) once("far") { Finding(k, "Persona se aleja", "Se alejó de la cámara en $secs s") }
                }
                Kind.VEHICLE -> {
                    if (abs(cx - t.startX) > 0.15f) once("moving") { Finding(k, "Vehículo en movimiento", "Cruzó hacia ${if (cx > t.startX) "la derecha" else "la izquierda"}") }
                    else if (secs >= 30) once("parked") { Finding(k, "Vehículo estacionado", "Quieto más de $secs s") }
                }
                Kind.ANIMAL -> {
                    val person = tracks[Kind.PERSON]
                    // mascota sola un buen rato (saber qué hace el gato/perro cuando no hay nadie)
                    if (secs >= 60 && (person == null || now - person.last > 30_000)) once("alone") { Finding(k, "${name(d.name)} solo en casa", "Lleva $secs s en escena sin personas") }
                    if (abs(cx - t.startX) > 0.3f) once("moving") { Finding(k, "${name(d.name)} en movimiento", "Cruzó hacia ${if (cx > t.startX) "la derecha" else "la izquierda"}") }
                }
                else -> {}
            }
        }

        // sin objetos: sombra o movimiento genérico, con freno para no llenar la lista
        if (best.isEmpty() && scene.motion != null) {
            val k = if (scene.shadow) Kind.SHADOW else Kind.MOTION
            val gap = if (k == Kind.SHADOW) 60_000 else 120_000
            if (now - (lastEmit[k] ?: 0L) > gap) {
                lastEmit[k] = now
                out += if (scene.shadow) Finding(k, "Sombra intensa", "Zona oscurecida de golpe sin objeto reconocido")
                else Finding(k, "Movimiento", "Cambio en ${side(scene.motion.centerX())} sin objeto reconocido")
            }
        }
        return out
    }

    private fun side(x: Float) = when { x < 0.33f -> "la izquierda"; x > 0.66f -> "la derecha"; else -> "el centro" }

    private fun name(n: String) = when (n) {
        "car" -> "Auto"; "truck" -> "Camión"; "bus" -> "Autobús"; "motorcycle" -> "Moto"; "bicycle" -> "Bicicleta"
        "dog" -> "Perro"; "cat" -> "Gato"; "bird" -> "Ave"
        else -> n
    }
}
