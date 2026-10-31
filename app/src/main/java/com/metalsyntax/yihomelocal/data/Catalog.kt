package com.metalsyntax.yihomelocal.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray

/** Modelos que lista Kami Home al añadir (DeviceAllType de Kami 4.9.9 → assets/catalog.json). */
data class CatalogItem(val model: String, val name: String, val category: String, val image: String) {
    /** Foto del modelo si tools/extract_sdk.sh la copió a assets/models; si no, null (se muestra un ícono). */
    fun bitmap(ctx: Context): Bitmap? = listOf("png", "webp").firstNotNullOfOrNull { ext ->
        runCatching { ctx.assets.open("models/$image.$ext").use(BitmapFactory::decodeStream) }.getOrNull()
    }
}

object Catalog {
    fun load(ctx: Context): List<CatalogItem> =
        JSONArray(ctx.assets.open("catalog.json").bufferedReader().readText()).let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                CatalogItem(it.getString("model"), it.getString("name"), it.getString("category"), it.getString("image"))
            }
        }

    /**
     * La etiqueta QR de la cámara empieza con el código de región en las posiciones 2..4 (AppVersionQRCodeScanActivity
     * de Kami); una cámara solo se registra en el servidor de su región. null = no parece una etiqueta Yi/Kami.
     */
    fun regionOfLabel(label: String): String? {
        if (label.length < 4) return null
        return when (label.substring(2, 4)) {
            "US" -> "us"
            "EU", "IL", "PL", "RU", "NL", "CZ" -> "eu"
            "TW", "KR", "KP", "PH", "ID", "S1", "JP" -> "sg"
            else -> null
        }
    }
}
