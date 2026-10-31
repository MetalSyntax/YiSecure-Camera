package com.metalsyntax.yihomelocal.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Última imagen vista de cada cámara (la cámara no tiene microSD ni nube: es lo único que queda al salir del vivo). */
object Snapshots {
    private fun file(ctx: Context, uid: String) = File(File(ctx.filesDir, "snap").apply { mkdirs() }, "$uid.jpg")

    fun save(ctx: Context, uid: String, bmp: Bitmap) {
        val f = file(ctx, uid)
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        tmp.renameTo(f) // atómico: la lista nunca lee un JPEG a medio escribir
    }

    /** (imagen, epoch ms) o null si nunca se vio. */
    fun load(ctx: Context, uid: String): Pair<Bitmap, Long>? {
        val f = file(ctx, uid)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.path)?.let { it to f.lastModified() }
    }

    fun delete(ctx: Context, uid: String) = file(ctx, uid).delete()
}
