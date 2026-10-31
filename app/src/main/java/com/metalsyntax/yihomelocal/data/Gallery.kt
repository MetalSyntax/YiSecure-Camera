package com.metalsyntax.yihomelocal.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Capturas y grabaciones del vivo a la galería del teléfono (Pictures/YiSecure, Movies/YiSecure). */
object Gallery {
    private fun stamp(name: String) = "${name.replace(Regex("[^A-Za-z0-9_-]"), "_")}_" +
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Devuelve la ruta visible para el usuario. */
    fun saveImage(ctx: Context, camName: String, bmp: Bitmap): String {
        val name = stamp(camName) + ".jpg"
        save(ctx, name, "image/jpeg", Environment.DIRECTORY_PICTURES, MediaStore.Images.Media.EXTERNAL_CONTENT_URI) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
        }
        return "${Environment.DIRECTORY_PICTURES}/YiSecure/$name"
    }

    fun tempVideo(ctx: Context, camName: String) = File(ctx.cacheDir, stamp(camName) + ".mp4")

    /** Mueve un MP4 terminado a la galería y borra el temporal. */
    fun saveVideo(ctx: Context, tmp: File): String {
        save(ctx, tmp.name, "video/mp4", Environment.DIRECTORY_MOVIES, MediaStore.Video.Media.EXTERNAL_CONTENT_URI) { out ->
            tmp.inputStream().use { it.copyTo(out) }
        }
        tmp.delete()
        return "${Environment.DIRECTORY_MOVIES}/YiSecure/${tmp.name}"
    }

    private fun save(ctx: Context, name: String, mime: String, dir: String, collection: android.net.Uri, write: (java.io.OutputStream) -> Unit) {
        if (Build.VERSION.SDK_INT < 29) {
            // ponytail: Android < 10 guarda en la carpeta privada de la app (sin permiso de almacenamiento); el TECNO es Android 13
            val f = File(ctx.getExternalFilesDir(dir), "YiSecure/$name").apply { parentFile?.mkdirs() }
            f.outputStream().use(write)
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/YiSecure")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val r = ctx.contentResolver
        val uri = r.insert(collection, values) ?: error("No se pudo crear $name en la galería")
        try {
            r.openOutputStream(uri)!!.use(write)
            r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            r.delete(uri, null, null)
            throw e
        }
    }
}
