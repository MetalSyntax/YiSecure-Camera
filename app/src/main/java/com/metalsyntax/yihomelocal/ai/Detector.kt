package com.metalsyntax.yihomelocal.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import kotlin.math.abs

/** Qué le interesa a una cámara de seguridad de las 80 clases COCO del modelo. */
enum class Kind(val label: String) { PERSON("PERSONA"), VEHICLE("VEHÍCULO"), PACKAGE("PAQUETE"), ANIMAL("ANIMAL"), MOTION("MOVIMIENTO"), SHADOW("SOMBRA") }

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
        // COCO no tiene "caja": mochilas, bolsos y maletas son lo más parecido a un paquete dejado en la puerta
        "suitcase" to Kind.PACKAGE, "backpack" to Kind.PACKAGE, "handbag" to Kind.PACKAGE,
        "dog" to Kind.ANIMAL, "cat" to Kind.ANIMAL, "bird" to Kind.ANIMAL, "horse" to Kind.ANIMAL,
    )

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
            val b = r.boundingBox
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
 * un vehículo que se mueve o se estaciona, un paquete que queda solo. Un objeto por tipo (el más seguro).
 */
class Sentinel {
    private class Track(val first: Long, var last: Long, val startX: Float, var x: Float) {
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
            if (gone && k == Kind.PERSON && t.seconds(t.last) >= 3) out += Finding(k, "Persona salió", "Estuvo ${t.seconds(t.last)} s en escena")
            gone
        }

        best.forEach { (k, d) ->
            val cx = d.box.centerX()
            val t = tracks[k]
            if (t == null) {
                tracks[k] = Track(now, now, cx, cx)
                out += when (k) {
                    Kind.PERSON -> Finding(k, "Persona detectada", "Entró por ${side(cx)} · confianza ${(d.score * 100).toInt()} %")
                    Kind.VEHICLE -> Finding(k, "Vehículo detectado", "${name(d.name)} · confianza ${(d.score * 100).toInt()} %")
                    Kind.PACKAGE -> Finding(k, "Objeto detectado", "${name(d.name)} en ${side(cx)}")
                    else -> Finding(k, "Animal detectado", "${name(d.name)} · confianza ${(d.score * 100).toInt()} %")
                }
                return@forEach
            }
            t.last = now; t.x = cx
            val secs = t.seconds(now)
            fun once(tag: String, f: () -> Finding) { if (t.reported.add(tag)) out += f() }
            when (k) {
                Kind.PERSON -> if (secs >= 20) once("loiter") { Finding(k, "Merodeo", "Persona en escena más de $secs s") }
                Kind.VEHICLE -> {
                    if (abs(cx - t.startX) > 0.15f) once("moving") { Finding(k, "Vehículo en movimiento", "Cruzó hacia ${if (cx > t.startX) "la derecha" else "la izquierda"}") }
                    else if (secs >= 30) once("parked") { Finding(k, "Vehículo estacionado", "Quieto más de $secs s") }
                }
                Kind.PACKAGE -> {
                    val person = tracks[Kind.PERSON]
                    if (secs >= 10 && (person == null || now - person.last > 5_000)) once("left") { Finding(k, "Paquete dejado", "${name(d.name)} quieto sin nadie cerca") }
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
        "suitcase" -> "Maleta/caja"; "backpack" -> "Mochila"; "handbag" -> "Bolso"
        "dog" -> "Perro"; "cat" -> "Gato"; "bird" -> "Ave"; "horse" -> "Caballo"
        else -> n
    }
}
