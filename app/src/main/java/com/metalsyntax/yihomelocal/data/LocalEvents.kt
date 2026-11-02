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

/** Evento detectado por la IA del teléfono mientras se miraba la cámara; [file] = foto con las cajas dibujadas. */
data class LocalEvent(val uid: String, val time: Long, val kind: String, val title: String, val desc: String, val file: String)

/** Eventos de IA guardados en el teléfono (files/ai/<uid>/): índice JSON + JPEG por evento, últimos [MAX] por cámara. */
object LocalEvents {
    private const val MAX = 100

    private fun dir(ctx: Context, uid: String) = File(ctx.filesDir, "ai/$uid").apply { mkdirs() }
    private fun index(ctx: Context, uid: String) = File(dir(ctx, uid), "events.json")

    @Synchronized
    fun list(ctx: Context, uid: String): List<LocalEvent> = runCatching {
        val a = JSONArray(index(ctx, uid).readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            LocalEvent(uid, it.getLong("time"), it.getString("kind"), it.getString("title"), it.optString("desc"), it.getString("file"))
        }.sortedByDescending { it.time }
    }.getOrDefault(emptyList())

    fun all(ctx: Context, uids: List<String>) = uids.flatMap { list(ctx, it) }.sortedByDescending { it.time }

    /** Guarda el evento con la foto del cuadro y las cajas de [dets] dibujadas. */
    @Synchronized
    fun add(ctx: Context, uid: String, time: Long, f: Finding, frame: Bitmap, dets: List<Det>): LocalEvent {
        val d = dir(ctx, uid)
        val img = File(d, "$time.jpg")
        val out = frame.copy(Bitmap.Config.ARGB_8888, true)
        drawBoxes(out, dets)
        img.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        out.recycle()
        val ev = LocalEvent(uid, time, f.kind.label, f.title, f.desc, img.path)
        val keep = (listOf(ev) + list(ctx, uid)).take(MAX)
        list(ctx, uid).drop(MAX - 1).forEach { File(it.file).delete() }
        index(ctx, uid).writeText(JSONArray(keep.map {
            JSONObject().put("time", it.time).put("kind", it.kind).put("title", it.title).put("desc", it.desc).put("file", it.file)
        }).toString())
        return ev
    }

    fun delete(ctx: Context, uid: String) { File(ctx.filesDir, "ai/$uid").deleteRecursively() }

    /** Cajas rojas con etiqueta, como en el diseño (PERSONA 96%). */
    fun drawBoxes(bmp: Bitmap, dets: List<Det>) {
        if (dets.isEmpty()) return
        val c = Canvas(bmp)
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        val red = 0xFFFF5451.toInt()
        val stroke = Paint().apply { color = red; style = Paint.Style.STROKE; strokeWidth = maxOf(2f, w / 240f) }
        val fill = Paint().apply { color = red }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = maxOf(12f, w / 40f); typeface = android.graphics.Typeface.MONOSPACE }
        dets.forEach { d ->
            val l = d.box.left * w; val t = d.box.top * h
            c.drawRect(l, t, d.box.right * w, d.box.bottom * h, stroke)
            val label = "${d.kind.label} ${(d.score * 100).toInt()}%"
            val tw = text.measureText(label) + 8
            val ty = if (t > text.textSize + 4) t - text.textSize - 4 else t
            c.drawRect(l, ty, l + tw, ty + text.textSize + 4, fill)
            c.drawText(label, l + 4, ty + text.textSize, text)
        }
    }
}
