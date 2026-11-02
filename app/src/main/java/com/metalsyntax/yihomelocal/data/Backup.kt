package com.metalsyntax.yihomelocal.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Respaldo de los eventos de la IA del teléfono: files/ai/<uid>/ (events.json + JPEG) a un ZIP elegido por el usuario. */
object Backup {
    /** Escribe el ZIP en [uri]; devuelve cuántas fotos se incluyeron. */
    fun export(ctx: Context, uri: Uri): Int {
        val root = File(ctx.filesDir, "ai")
        val files = root.walkTopDown().filter { it.isFile }.toList()
        val out = ctx.contentResolver.openOutputStream(uri) ?: error("No se pudo abrir el destino")
        ZipOutputStream(out.buffered()).use { zip ->
            files.forEach { f ->
                zip.putNextEntry(ZipEntry(f.relativeTo(root).path))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return files.count { it.extension == "jpg" }
    }
}
