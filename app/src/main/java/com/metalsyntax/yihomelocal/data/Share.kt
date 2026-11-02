package com.metalsyntax.yihomelocal.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File

/** Menú "Compartir" de Android para imágenes y clips de eventos (FileProvider sobre cache/ y files/ai/). */
object Share {
    fun file(ctx: Context, f: File, mime: String, text: String) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.share", f)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Compartir evento").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun image(ctx: Context, bmp: Bitmap, name: String, text: String) {
        val f = File(ctx.cacheDir, "share_$name.jpg").apply { outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
        file(ctx, f, "image/jpeg", text)
    }
}
