package com.metalsyntax.yihomelocal.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.metalsyntax.yihomelocal.ai.Det
import com.metalsyntax.yihomelocal.ai.Finding
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Evento detectado por la IA del teléfono mientras se miraba la cámara; [file] = foto con las cajas dibujadas.
 * [obj] = clase COCO del objeto principal ("cat", "person"…; vacío en movimiento/sombra), [score] su confianza,
 * [night] = la cámara estaba en infrarrojo, [fav] = favorito (no se borra al rotar).
 */
data class LocalEvent(
    val uid: String, val time: Long, val kind: String, val title: String, val desc: String, val file: String,
    val obj: String = "", val score: Float = 0f, val night: Boolean = false, val fav: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("time", time).put("kind", kind).put("title", title).put("desc", desc).put("file", file)
        .put("obj", obj).put("score", score.toDouble()).put("night", night).put("fav", fav)
}

/** Eventos de IA guardados en el teléfono (files/ai/<uid>/): índice JSON + JPEG por evento, últimos [MAX] por cámara. */
object LocalEvents {
    private const val MAX = 100

    private fun dir(ctx: Context, uid: String) = File(ctx.filesDir, "ai/$uid").apply { mkdirs() }
    private fun index(ctx: Context, uid: String) = File(dir(ctx, uid), "events.json")

    @Synchronized
    fun list(ctx: Context, uid: String): List<LocalEvent> = runCatching {
        val a = JSONArray(index(ctx, uid).readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            LocalEvent(uid, it.getLong("time"), it.getString("kind"), it.getString("title"), it.optString("desc"), it.getString("file"),
                it.optString("obj"), it.optDouble("score", 0.0).toFloat(), it.optBoolean("night"), it.optBoolean("fav"))
        }.sortedByDescending { it.time }
    }.getOrDefault(emptyList())

    fun all(ctx: Context, uids: List<String>) = uids.flatMap { list(ctx, it) }.sortedByDescending { it.time }

    /** Guarda el evento con la foto del cuadro y las cajas de [dets] dibujadas. */
    @Synchronized
    fun add(ctx: Context, uid: String, time: Long, f: Finding, frame: Bitmap, dets: List<Det>, night: Boolean = false): LocalEvent {
        val d = dir(ctx, uid)
        // varios hallazgos en el mismo cuadro: un archivo por cada uno
        val img = generateSequence(0) { it + 1 }.map { File(d, if (it == 0) "$time.jpg" else "${time}_$it.jpg") }.first { !it.exists() }
        val out = frame.copy(Bitmap.Config.ARGB_8888, true)
        drawBoxes(out, dets)
        img.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        out.recycle()
        val top = dets.filter { it.kind == f.kind }.maxByOrNull { it.score }
        // el objeto del hallazgo (Camión #02), no el más seguro de su tipo
        val ev = LocalEvent(uid, time, f.kind.label, f.title, f.desc, img.path, f.obj.ifEmpty { top?.name.orEmpty() },
            if (f.obj.isNotEmpty()) f.score else top?.score ?: 0f, night)
        // rotación: los favoritos no cuentan ni se borran
        val all = listOf(ev) + list(ctx, uid)
        val (favs, rest) = all.partition { it.fav }
        rest.drop(MAX).forEach { File(it.file).delete() }
        write(ctx, uid, (favs + rest.take(MAX)).sortedByDescending { it.time })
        return ev
    }

    private fun write(ctx: Context, uid: String, l: List<LocalEvent>) = index(ctx, uid).writeText(JSONArray(l.map { it.toJson() }).toString())

    /** Marca o desmarca favorito; devuelve el evento actualizado. */
    @Synchronized
    fun setFav(ctx: Context, e: LocalEvent, fav: Boolean): LocalEvent {
        val upd = e.copy(fav = fav)
        write(ctx, e.uid, list(ctx, e.uid).map { if (it.time == e.time && it.file == e.file) upd else it })
        return upd
    }

    /** Nombre en español de la clase COCO, en mayúsculas ("" si no es un objeto). */
    fun objLabel(n: String) = when (n) {
        "person" -> "PERSONA"; "cat" -> "GATO"; "dog" -> "PERRO"; "bird" -> "AVE"
        "car" -> "AUTO"; "truck" -> "CAMIÓN"; "bus" -> "AUTOBÚS"; "motorcycle" -> "MOTO"; "bicycle" -> "BICICLETA"
        else -> n.uppercase()
    }

    fun delete(ctx: Context, uid: String) { File(ctx.filesDir, "ai/$uid").deleteRecursively() }

    /** Cajas rojas con etiqueta, como en el diseño (PERSONA #01 · seguro). */
    fun drawBoxes(bmp: Bitmap, dets: List<Det>) {
        if (dets.isEmpty()) return
        val c = Canvas(bmp)
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        val red = 0xFFFF5451.toInt()
        val stroke = Paint().apply { color = red; style = Paint.Style.STROKE; strokeWidth = maxOf(2f, w / 240f) }
        val fill = Paint().apply { color = red }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = maxOf(12f, w / 40f); typeface = android.graphics.Typeface.MONOSPACE }
        // PERSONA #01 · seguro / adulto · de pie · alegre (número = el que le dio el seguimiento)
        dets.sortedByDescending { it.score }.forEach { d ->
            val l = d.box.left * w; val t = d.box.top * h
            c.drawRect(l, t, d.box.right * w, d.box.bottom * h, stroke)
            val lines = listOf(d.label(), d.traits()).filter { it.isNotEmpty() }
            val lh = text.textSize + 4
            val tw = lines.maxOf { text.measureText(it) } + 8
            val ty = if (t > lh * lines.size) t - lh * lines.size else t
            c.drawRect(l, ty, l + tw, ty + lh * lines.size, fill)
            lines.forEachIndexed { i, s -> c.drawText(s, l + 4, ty + text.textSize + i * lh, text) }
        }
    }
}
