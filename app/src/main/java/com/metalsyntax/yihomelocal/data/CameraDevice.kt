package com.metalsyntax.yihomelocal.data

import org.json.JSONObject

/** Lo que el SDK P2P necesita para conectar; se guarda localmente tras el primer login. */
data class CameraDevice(
    val uid: String,
    val name: String,
    val model: String,
    val online: Boolean,
    val password: String,
    val encrypted: Boolean,
    val localIp: String,
    val p2pId: String,
    val tnpServer: String,
    val tnpKey: String,
    /** Cámara de otra cuenta compartida con esta (no se puede renombrar, borrar ni volver a compartir). */
    val shared: Boolean = false,
) {
    fun toJson() = JSONObject().apply {
        put("uid", uid); put("name", name); put("model", model); put("online", online)
        put("password", password); put("encrypted", encrypted); put("localIp", localIp)
        put("p2pId", p2pId); put("tnpServer", tnpServer); put("tnpKey", tnpKey); put("shared", shared)
    }

    companion object {
        // modelo numérico de /v5/devices/list → nombre interno que espera el SDK (assets/feature_config de Yi Home 6.9.9)
        private val MODELS = mapOf(
            "1" to "yunyi.camera.v1", "2" to "yunyi.camera.htwo1", "3" to "h19", "4" to "yunyi.camera.mj1", "5" to "h20",
            "6" to "yunyi.camera.y20", "7" to "h30", "12" to "y30", "13" to "y31", "14" to "y19", "15" to "y25", "17" to "y32",
            "19" to "w10", "20" to "n10", "21" to "n20", "22" to "n30", "34" to "y501c", "35" to "h31", "36" to "w102",
            "38" to "y20ga", "39" to "y25ga", "40" to "y30ga", "41" to "r30gb", "43" to "y32gc", "46" to "y29ga", "47" to "h50ga",
            "50" to "y30gc", "51" to "y21ga", "52" to "h51ga", "55" to "y28ga", "56" to "h52ga", "57" to "y502c", "60" to "h53ga",
            "61" to "d201", "62" to "h30ga", "63" to "h31ga", "64" to "w12ga", "65" to "w102s", "66" to "y281ga", "67" to "y211ga",
            "68" to "h60ga", "69" to "h32ga", "70" to "r40ga", "71" to "r35gb", "72" to "y26ga", "76" to "b621", "78" to "y621",
            "83" to "y291ga", "84" to "y311ga", "85" to "r36ga", "89" to "y623", "90" to "r37gb", "92" to "y624", "93" to "y625",
            "96" to "r44a",
        )

        /** Idempotente: un nombre ya traducido se devuelve igual. Desconocido → "common" (lo mismo que hace Yi Home). */
        fun modelName(server: String) = MODELS[server] ?: if (server.all(Char::isDigit)) "common" else server

        fun fromJson(o: JSONObject) = CameraDevice(
            o.getString("uid"), o.getString("name"), modelName(o.optString("model")), o.optBoolean("online"),
            o.getString("password"), o.optBoolean("encrypted", true), o.optString("localIp"),
            o.getString("p2pId"), o.optString("tnpServer"), o.optString("tnpKey"), o.optBoolean("shared"),
        )
    }
}
