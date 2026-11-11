package com.metalsyntax.yihomelocal.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import com.metalsyntax.yihomelocal.data.LocalEvents
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Qué le interesa a una cámara de seguridad de las 80 clases COCO del modelo. */
enum class Kind(val label: String) { PERSON("PERSONA"), VEHICLE("VEHÍCULO"), ANIMAL("MASCOTA"), MOTION("MOVIMIENTO"), SHADOW("SOMBRA") }

/**
 * Detección con la caja normalizada a 0..1 sobre el cuadro. [id] = número del objeto mientras sigue en escena
 * (lo pone [Sentinel]: PERSONA #1, PERSONA #2…), [person] = postura/edad/emoción, [color] = color de un vehículo.
 */
data class Det(
    val kind: Kind, val name: String, val score: Float, val box: RectF,
    val id: Int = 0, val person: PersonInfo? = null, val color: String = "",
) {
    /** "CAMIÓN #02 · probable" */
    fun label(): String {
        if (name.isEmpty()) return kind.label
        return LocalEvents.objLabel(name) + (if (id > 0) " #%02d".format(id) else "") + " · " + certainty(score)
    }

    /** Segunda línea de la etiqueta: "adulto · de pie · alegre" o "blanco". */
    fun traits() = person?.text() ?: color
}

/**
 * Qué tan seguro está el modelo, en palabras (el número suelto confundía: un camión al 45 % es "posible", no "a medias").
 * Los cortes siguen la escala de EfficientDet: por encima de 0,7 casi nunca se equivoca.
 */
fun certainty(score: Float) = when { score >= 0.7f -> "seguro"; score >= 0.5f -> "probable"; else -> "posible" }

/**
 * Detector de objetos en el teléfono: EfficientDet-Lite2 int8 (COCO, 7,5 MB, entrada 448 px; modelo oficial de MediaPipe)
 * con MediaPipe Tasks (la Task Library de TFLite no lee este modelo: espera 4 salidas y trae 2). No usa el SDK de Yi ni la nube: mira los cuadros que ya se ven en pantalla.
 *
 * El modelo reduce todo el cuadro a 448×448: un auto al fondo de la calle queda en pocos píxeles y se pierde.
 * Por eso se analiza el cuadro entero (objetos grandes) y además dos recortes cuadrados, izquierdo y derecho,
 * que lo ven casi a resolución completa (objetos lejanos); luego se unen las cajas repetidas.
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
     * Confianza mínima por clase (el modelo corre a 0,3). Los vehículos lejanos dan puntajes bajos aunque estén bien,
     * por eso quedan en 0,35; gatos y perros se confunden con cojines, sillas o bultos; las aves pequeñas aún más.
     */
    private val MIN_SCORE = mapOf("person" to 0.45f, "cat" to 0.55f, "dog" to 0.55f, "bird" to 0.6f,
        // motos y bicicletas bajas confundían mesas con cosas encima dentro de la casa
        "motorcycle" to 0.5f, "bicycle" to 0.5f)
    private const val DEFAULT_MIN = 0.35f

    @Synchronized
    private fun get(ctx: Context): ObjectDetector? {
        if (detector == null && !failed) detector = runCatching {
            ObjectDetector.createFromOptions(
                ctx.applicationContext,
                ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("efficientdet_lite2.tflite").build())
                    .setRunningMode(RunningMode.IMAGE).setMaxResults(25).setScoreThreshold(0.3f).build(),
            )
        }.onFailure { failed = true; android.util.Log.e("YiAI", "No se pudo cargar el modelo", it) }.getOrNull()
        return detector
    }

    /**
     * [tiles] = analizar además los dos recortes (objetos lejanos). Triplica el costo: quien llama lo pide solo
     * cuando hay movimiento o cada pocos cuadros.
     */
    @Synchronized
    fun detect(ctx: Context, bmp: Bitmap, tiles: Boolean = true): List<Det> {
        val d = get(ctx) ?: return emptyList()
        val w = bmp.width; val h = bmp.height
        val regions = buildList {
            add(Rect(0, 0, w, h))
            if (tiles && w > h * 1.3f) { add(Rect(0, 0, h, h)); add(Rect(w - h, 0, w, h)) }
        }
        val raw = regions.flatMapIndexed { i, r -> run(d, bmp, r, whole = i == 0).map { it to i } }
        val all = merge(raw)
        // el modelo confunde peluches con personas: una "persona" encima de un peluche se descarta
        val teddies = all.filter { it.name == TEDDY }
        return all.filter { it.name != TEDDY && !(it.kind == Kind.PERSON && teddies.any { t -> iou(t.box, it.box) > 0.3f || inside(t.box, it.box) > 0.6f }) }
            .map { if (it.kind == Kind.VEHICLE) it.copy(color = colorOf(bmp, it.box)) else it }
    }

    private const val TEDDY = "teddy bear"

    private fun run(d: ObjectDetector, bmp: Bitmap, r: Rect, whole: Boolean): List<Det> {
        val src = if (whole) bmp else Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        try {
            return d.detect(BitmapImageBuilder(src).build()).detections().mapNotNull { res ->
                val c = res.categories().firstOrNull() ?: return@mapNotNull null
                val label = c.categoryName()
                // el peluche se detecta solo para descartar personas falsas (ver detect)
                val kind = KINDS[label] ?: (if (label == TEDDY) Kind.ANIMAL else return@mapNotNull null)
                if (c.score() < (MIN_SCORE[label] ?: DEFAULT_MIN)) return@mapNotNull null
                val b = res.boundingBox()
                // una caja que ocupa casi todo el cuadro suele ser un error del modelo (pared, cama, sofá)
                if (whole && b.width() * b.height() > 0.8f * w * h) return@mapNotNull null
                Det(kind, label, c.score(), RectF(((b.left + r.left) / w).coerceIn(0f, 1f), ((b.top + r.top) / h).coerceIn(0f, 1f),
                    ((b.right + r.left) / w).coerceIn(0f, 1f), ((b.bottom + r.top) / h).coerceIn(0f, 1f)))
            }
        } finally { if (src !== bmp) src.recycle() }
    }

    /**
     * Une las cajas del mismo objeto vistas en el cuadro entero y en los recortes. Mismo tipo (auto/camión cuentan como
     * vehículo) y mucho solape = el mismo objeto: queda el de mayor puntaje. Si una caja está casi dentro de otra que
     * viene de otra región, es un objeto cortado por el borde del recorte: queda la unión.
     */
    private fun merge(raw: List<Pair<Det, Int>>): List<Det> {
        val out = ArrayList<Pair<Det, Int>>()
        raw.sortedByDescending { it.first.score }.forEach { (d, reg) ->
            val i = out.indexOfFirst { (o, oreg) ->
                // casi dentro de otra del mismo tipo: una parte (la cabeza de una persona) o un objeto cortado por el recorte
                o.kind == d.kind && (iou(o.box, d.box) > 0.45f || inside(o.box, d.box) > 0.8f)
            }
            if (i < 0) { out += d to reg; return@forEach }
            val (o, oreg) = out[i]
            if (inside(o.box, d.box) > 0.8f) out[i] = o.copy(box = RectF(o.box).apply { union(d.box) }) to oreg
        }
        return out.map { it.first }
    }

    fun iou(a: RectF, b: RectF): Float {
        val i = inter(a, b)
        return i / (a.width() * a.height() + b.width() * b.height() - i).coerceAtLeast(1e-6f)
    }

    /** Qué fracción de la caja más chica queda dentro de la otra. */
    private fun inside(a: RectF, b: RectF) = inter(a, b) / min(a.width() * a.height(), b.width() * b.height()).coerceAtLeast(1e-6f)

    private fun inter(a: RectF, b: RectF) = max(0f, min(a.right, b.right) - max(a.left, b.left)) * max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))

    /**
     * Color de la carrocería: muestras del centro de la caja (sin ruedas ni techo). Si la mayoría tiene color fuerte,
     * el tono más común; si no, blanco/gris/negro por el brillo. Con IR nocturno todo sale gris: no se informa.
     */
    private fun colorOf(bmp: Bitmap, box: RectF): String {
        val hsv = FloatArray(3)
        val hues = IntArray(6); var colored = 0; var n = 0; var bright = 0f; var chroma = 0f
        for (gy in 0 until 8) for (gx in 0 until 12) {
            val x = ((box.left + box.width() * (0.2f + 0.6f * gx / 11f)) * (bmp.width - 1)).toInt()
            val y = ((box.top + box.height() * (0.3f + 0.4f * gy / 7f)) * (bmp.height - 1)).toInt()
            val p = bmp.getPixel(x, y)
            Color.colorToHSV(p, hsv); n++; bright += hsv[2]
            chroma += (abs(Color.red(p) - Color.green(p)) + abs(Color.green(p) - Color.blue(p))) / 2f
            if (hsv[1] > 0.35f && hsv[2] > 0.25f) {
                colored++
                // rojo, naranja, amarillo, verde, azul, morado
                hues[when (hsv[0]) { in 15f..40f -> 1; in 40f..70f -> 2; in 70f..170f -> 3; in 170f..260f -> 4; in 260f..330f -> 5; else -> 0 }]++
            }
        }
        if (chroma / n < 4f) return "" // escena en blanco y negro (IR)
        if (colored > n * 0.3f) return listOf("rojo", "naranja", "amarillo", "verde", "azul", "morado")[hues.indices.maxBy { hues[it] }]
        val v = bright / n
        return when { v > 0.7f -> "blanco"; v > 0.45f -> "gris"; else -> "oscuro" }
    }
}

/** Lo que se sabe del cuadro sin modelo: noche (IR en blanco y negro), zona con movimiento y si parece una sombra. */
data class Scene(val night: Boolean, val motion: RectF?, val shadow: Boolean, val changed: BooleanArray? = null) {
    /** Fracción de la rejilla 64×36 que cambió dentro de [box] (0 si no hubo movimiento). */
    fun motionIn(box: RectF): Float {
        val m = changed ?: return 0f
        val x0 = (box.left * GW).toInt().coerceIn(0, GW - 1); val x1 = (box.right * GW).toInt().coerceIn(x0, GW - 1)
        val y0 = (box.top * GH).toInt().coerceIn(0, GH - 1); val y1 = (box.bottom * GH).toInt().coerceIn(y0, GH - 1)
        var n = 0
        for (y in y0..y1) for (x in x0..x1) if (m[y * GW + x]) n++
        return n.toFloat() / ((x1 - x0 + 1) * (y1 - y0 + 1))
    }

    companion object { const val GW = 64; const val GH = 36 }
}

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
        val mask = BooleanArray(luma.size)
        for (i in luma.indices) {
            val d = luma[i] - old[i]
            if (abs(d) < 25f) continue
            changed++; mask[i] = true
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
        return Scene(night, box, shadow, mask)
    }

    private companion object { const val W = 64; const val H = 36 }
}

/** Evento que vale la pena guardar (con foto) en "Eventos recientes detectados"; [obj]/[score] = el objeto que lo causó. */
data class Finding(val kind: Kind, val title: String, val desc: String, val obj: String = "", val score: Float = 0f)

/**
 * Sigue cada objeto por separado (dos personas = PERSONA #1 y #2, cada auto con su número) y saca "acciones":
 * quién entra, quién merodea, corre, se cae o se va; un vehículo que se mueve o se estaciona; una mascota que pasa
 * o se queda. Los objetos que ya estaban al empezar a mirar se informan juntos en un solo resumen.
 */
class Sentinel {
    private class Track(val id: Int, val kind: Kind, val first: Long, var box: RectF, var baseline: Boolean) {
        var last = first
        val startBox = RectF(box)
        val names = HashMap<String, Int>()
        val reported = HashSet<String>()
        var score = 0f
        var color = ""
        var person: PersonInfo? = null
        /** cuadros seguidos en el suelo / brazos en alto / mirando la cámara (una sola lectura puede ser error) */
        var lying = 0; var arms = 0; var look = 0
        var speed = 0f
        /** cuadros en que se vio; un objeto visto una sola vez puede ser un error del modelo */
        var hits = 0
        /**
         * cambiaron los píxeles dentro de su caja: un peluche o un póster nunca lo hacen. (Ni el recuadro de movimiento
         * de la escena ni el desplazamiento de la caja sirven: el primero abarca todo lo que cambió y la caja de un peluche
         * tiembla entre análisis.)
         */
        var moved = false
        fun confirmed() = if (kind == Kind.PERSON) (hits >= 2 && moved) || score >= 0.75f else hits >= 2 || score >= 0.7f
        fun name() = names.maxByOrNull { it.value }?.key.orEmpty()
        fun seconds(now: Long) = ((now - first) / 1000).toInt()
        fun area() = box.width() * box.height()
    }

    class Result(val dets: List<Det>, val findings: List<Finding>)

    private val tracks = ArrayList<Track>()
    private val lastEmit = HashMap<Kind, Long>()
    private var started = 0L
    private var summarized = false
    private var groupReported = 0
    private var groupAt = 0L

    fun update(now: Long, dets: List<Det>, scene: Scene): Result {
        if (started == 0L) started = now
        val out = ArrayList<Finding>()
        val baselineWindow = now - started < BASELINE_MS

        // se fue: un rato sin verlo (los vehículos parpadean más en el modelo: más margen)
        tracks.removeAll { t ->
            val gone = now - t.last > when (t.kind) { Kind.VEHICLE -> 45_000; Kind.ANIMAL -> 20_000; else -> 20_000 } // alguien quieto de espaldas se pierde varios cuadros seguidos
            if (gone && t.kind == Kind.PERSON && "new" in t.reported && t.seconds(t.last) >= 3) out += Finding(t.kind, "${who(t)} salió",
                "Estuvo ${t.seconds(t.last)} s en escena · salió por ${side(t.box.centerX())}", t.name(), t.score)
            gone
        }
        if (tracks.count { it.kind == Kind.PERSON } < 2) groupReported = 0

        // emparejar cada detección con el objeto ya seguido más parecido (solape o cercanía), del más seguro al menos
        val pairs = ArrayList<Triple<Track, Det, Float>>()
        dets.forEach { d ->
            tracks.filter { it.kind == d.kind }.forEach { t ->
                val iou = Detector.iou(t.box, d.box)
                val dist = kotlin.math.hypot(t.box.centerX() - d.box.centerX(), t.box.centerY() - d.box.centerY())
                if (iou > 0.1f || dist < 0.12f) pairs += Triple(t, d, iou - dist)
            }
        }
        val usedT = HashSet<Track>(); val matched = java.util.IdentityHashMap<Det, Track>()
        pairs.sortedByDescending { it.third }.forEach { (t, d) -> if (t !in usedT && d !in matched) { usedT += t; matched[d] = t } }

        val fresh = ArrayList<Track>()
        val labeled = dets.map { d ->
            val t = matched[d] ?: Track(freeId(d.kind), d.kind, now, RectF(d.box), baselineWindow).also { tracks += it; fresh += it }
            if (t !in fresh) {
                val dt = (now - t.last).coerceAtLeast(1) / 1000f
                val moved = kotlin.math.hypot(d.box.centerX() - t.box.centerX(), d.box.centerY() - t.box.centerY()) / d.box.height().coerceAtLeast(0.05f)
                t.speed = 0.6f * t.speed + 0.4f * (moved / dt) // en alturas del objeto por segundo, suavizado
            }
            if (scene.motionIn(d.box) > 0.08f) t.moved = true
            t.last = now; t.box = RectF(d.box); t.score = max(t.score, d.score); t.hits++
            t.names[d.name] = (t.names[d.name] ?: 0) + 1
            if (d.color.isNotEmpty()) t.color = d.color
            d.person?.let { p ->
                t.person = p
                t.lying = if (p.lying) t.lying + 1 else 0
                t.arms = if (p.armsUp) t.arms + 1 else 0
                t.look = if (p.looking) t.look + 1 else 0
            }
            // el nombre más visto, para que un auto no salte a "camión" en un cuadro suelto
            t to d.copy(id = t.id, name = t.name(), color = t.color)
        }.filter { (t, _) -> t.kind != Kind.PERSON || t.confirmed() }.map { it.second } // personas sin confirmar: ni caja ni evento

        // lo que ya estaba al empezar: un único resumen ("En escena: 2 personas, 3 autos, 1 camión")
        if (!baselineWindow && !summarized) {
            summarized = true
            // lo visto una sola vez al arrancar no entra al resumen: si se confirma luego, se avisa como nuevo
            tracks.filter { it.baseline && !it.confirmed() }.forEach { it.baseline = false }
            val base = tracks.filter { it.baseline }
            if (base.isNotEmpty()) {
                val main = base.minBy { it.kind.ordinal }
                out += Finding(main.kind, "En escena: ${count(base)}", base.joinToString(" · ") { describe(it) }, main.name(), main.score)
                base.forEach { it.reported += "new" }
                groupReported = base.count { it.kind == Kind.PERSON }
            }
        }

        // objetos nuevos (después del arranque), solo cuando se confirmaron (vistos 2 veces o muy seguros)
        if (!baselineWindow) {
            val newOnes = tracks.filter { !it.baseline && "new" !in it.reported && it.last == now && it.confirmed() }
            newOnes.groupBy { it.kind }.forEach { (k, l) ->
                l.forEach { it.reported += "new" }
                out += if (l.size == 1) l[0].let { t ->
                    val title = when (k) {
                        Kind.PERSON -> "${who(t)} detectada"
                        else -> "${objName(t.name())} detectad${if (t.name() in FEMININE) "a" else "o"}"
                    }
                    Finding(k, title, (if (k == Kind.PERSON) "Entró por ${side(t.box.centerX())}" else "En ${side(t.box.centerX())}") +
                        " · " + describe(t), t.name(), t.score)
                } else Finding(k, "${count(l)} detectados", l.joinToString(" · ") { describe(it) }, l[0].name(), l.maxOf { it.score })
            }
            // grupo: más de una persona a la vez (si no se acaba de avisar con "2 personas detectadas")
            val people = tracks.filter { it.kind == Kind.PERSON && "new" in it.reported }
            // como mucho uno por minuto: las personas lejanas aparecen y desaparecen del modelo
            if (people.size >= 2 && people.size > groupReported && now - groupAt > 60_000) {
                groupAt = now
                if (newOnes.count { it.kind == Kind.PERSON } < people.size) out += Finding(Kind.PERSON, "Grupo de ${people.size} personas",
                    people.joinToString(" · ") { describe(it) }, "person", people.maxOf { it.score })
                groupReported = people.size
            }
        }

        // acciones de cada objeto
        tracks.filter { it.last == now && it !in fresh && "new" in it.reported }.forEach { t ->
            val secs = t.seconds(now)
            fun once(tag: String, title: String, desc: String) { if (t.reported.add(tag)) out += Finding(t.kind, title, desc, t.name(), t.score) }
            val cx = t.box.centerX()
            when (t.kind) {
                Kind.PERSON -> {
                    val w = who(t)
                    if (secs >= 20) once("loiter", "Merodeo · $w", "Lleva más de $secs s en escena · ${describe(t)}")
                    // dirección por el tamaño de la caja (≥ 1,8x más grande = se acercó; ≤ 0,5x = se alejó)
                    val a0 = t.startBox.width() * t.startBox.height()
                    if (a0 > 0f && t.area() / a0 >= 1.8f) once("near", "$w se acerca", "Se acercó a la cámara en $secs s · ${describe(t)}")
                    if (a0 > 0f && t.area() / a0 <= 0.5f) once("far", "$w se aleja", "Se alejó de la cámara en $secs s")
                    if (t.speed > 1.2f) once("run", "$w corre", "Se mueve rápido hacia ${dir(cx, t.startBox.centerX())}")
                    if (t.lying >= 2) once("floor", "$w en el suelo", "Posible caída: revisar · ${describe(t)}")
                    if (t.arms >= 2) once("arms", "$w con brazos en alto", describe(t))
                    if (t.look >= 3) once("look", "$w mira a la cámara", describe(t))
                }
                Kind.VEHICLE -> {
                    val n = objName(t.name()) + " #%02d".format(t.id)
                    if (abs(cx - t.startBox.centerX()) > 0.15f) once("moving", "$n en movimiento", "Cruzó hacia ${dir(cx, t.startBox.centerX())} · ${describe(t)}")
                    else if (secs >= 30 && !t.baseline) once("parked", "$n estacionad${if (t.name() in FEMININE) "a" else "o"}", "Quieto más de $secs s · ${describe(t)}")
                }
                Kind.ANIMAL -> {
                    val n = objName(t.name())
                    val alone = tracks.none { it.kind == Kind.PERSON && now - it.last < 30_000 }
                    // mascota sola un buen rato (saber qué hace el gato/perro cuando no hay nadie)
                    if (secs >= 60 && alone) once("alone", "$n solo en casa", "Lleva $secs s en escena sin personas")
                    if (abs(cx - t.startBox.centerX()) > 0.3f) once("moving", "$n en movimiento", "Cruzó hacia ${dir(cx, t.startBox.centerX())}")
                }
                else -> {}
            }
        }

        // sin objetos: sombra o movimiento genérico, con freno para no llenar la lista
        if (dets.isEmpty() && scene.motion != null) {
            val k = if (scene.shadow) Kind.SHADOW else Kind.MOTION
            val gap = if (k == Kind.SHADOW) 60_000 else 120_000
            if (now - (lastEmit[k] ?: 0L) > gap) {
                lastEmit[k] = now
                out += if (scene.shadow) Finding(k, "Sombra intensa", "Zona oscurecida de golpe sin objeto reconocido")
                else Finding(k, "Movimiento", "Cambio en ${side(scene.motion.centerX())} sin objeto reconocido")
            }
        }
        return Result(labeled, out)
    }

    /** El número libre más bajo de ese tipo, para que los números sigan cortos (#01, #02…). */
    private fun freeId(k: Kind): Int {
        val used = tracks.filter { it.kind == k }.map { it.id }.toSet()
        return generateSequence(1) { it + 1 }.first { it !in used }
    }

    private fun who(t: Track) = "Persona #%02d".format(t.id)

    /** "Persona #01: adulto · de pie · alegre (seguro)" / "Camión #02: color blanco (probable)" */
    private fun describe(t: Track): String {
        val head = if (t.kind == Kind.PERSON) who(t) else objName(t.name()) + " #%02d".format(t.id)
        val extra = when (t.kind) {
            Kind.PERSON -> t.person?.text().orEmpty()
            Kind.VEHICLE -> if (t.color.isNotEmpty()) "color ${t.color}" else ""
            else -> ""
        }
        return head + (if (extra.isNotEmpty()) ": $extra" else "") + " (${certainty(t.score)})"
    }

    /** "2 personas, 3 autos, 1 camión" */
    private fun count(l: List<Track>) = l.groupBy { if (it.kind == Kind.PERSON) "person" else it.name() }.entries
        .sortedByDescending { it.value.size }.joinToString(", ") { (n, v) ->
            "${v.size} " + (if (v.size == 1) objName(n).lowercase() else PLURAL[n] ?: objName(n).lowercase())
        }

    private fun side(x: Float) = when { x < 0.33f -> "la izquierda"; x > 0.66f -> "la derecha"; else -> "el centro" }
    private fun dir(x: Float, x0: Float) = if (x > x0) "la derecha" else "la izquierda"

    private fun objName(n: String) = when (n) {
        "person" -> "Persona"; "car" -> "Auto"; "truck" -> "Camión"; "bus" -> "Autobús"; "motorcycle" -> "Moto"; "bicycle" -> "Bicicleta"
        "dog" -> "Perro"; "cat" -> "Gato"; "bird" -> "Ave"
        else -> n
    }

    private companion object {
        /** primeros segundos: lo que se ve es "lo que ya estaba", no algo que llegó */
        const val BASELINE_MS = 4_000L
        val FEMININE = setOf("person", "motorcycle", "bicycle", "bird")
        val PLURAL = mapOf("person" to "personas", "car" to "autos", "truck" to "camiones", "bus" to "autobuses",
            "motorcycle" to "motos", "bicycle" to "bicicletas", "dog" to "perros", "cat" to "gatos", "bird" to "aves")
    }
}
