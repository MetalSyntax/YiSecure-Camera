package com.metalsyntax.yihomelocal.data

import android.content.Context
import org.json.JSONArray

/** Sesión y cámaras guardadas en el teléfono: después del primer login la app no necesita la nube para conectar. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("yi", Context.MODE_PRIVATE)

    var region: String
        get() = p.getString("region", "us")!!
        set(v) = p.edit().putString("region", v).apply()

    var session: YiCloud.Session?
        get() = p.getString("userid", null)?.let {
            YiCloud.Session(it, p.getString("token", "")!!, p.getString("token_secret", "")!!)
        }
        set(s) = p.edit().apply {
            if (s == null) remove("userid").remove("token").remove("token_secret")
            else putString("userid", s.userId).putString("token", s.token).putString("token_secret", s.tokenSecret)
        }.apply()

    /** Calidad del vivo (YiP2p.QUALITY_*), por defecto alta (2K). */
    var quality: Int
        get() = p.getInt("quality", 1)
        set(v) = p.edit().putInt("quality", v).apply()

    var lastCamera: String?
        get() = p.getString("last_camera", null)
        set(v) = p.edit().putString("last_camera", v).apply()

    var audio: Boolean
        get() = p.getBoolean("audio", true)
        set(v) = p.edit().putBoolean("audio", v).apply()

    /** Detección de objetos con IA en el teléfono mientras se mira el vivo. */
    /** Controles e información sobre el video visibles (ojo abierto). */
    var hud: Boolean
        get() = p.getBoolean("hud", true)
        set(v) = p.edit().putBoolean("hud", v).apply()

    /** Hora (epoch ms) hasta la que el usuario marcó los eventos como leídos. */
    var eventsSeen: Long
        get() = p.getLong("events_seen", 0L)
        set(v) = p.edit().putLong("events_seen", v).apply()

    /** Avisos de alertas nuevas (WorkManager) y hasta qué alerta (epoch ms) ya se avisó. */
    var notify: Boolean
        get() = p.getBoolean("notify", true)
        set(v) = p.edit().putBoolean("notify", v).apply()

    var notifiedUntil: Long
        get() = p.getLong("notified_until", 0L)
        set(v) = p.edit().putLong("notified_until", v).apply()

    /** Modo silencioso global: push de la nube apagado en todas las cámaras y sin avisos de la app. */
    var silent: Boolean
        get() = p.getBoolean("silent", false)
        set(v) = p.edit().putBoolean("silent", v).apply()

    var ai: Boolean
        get() = p.getBoolean("ai", true)
        set(v) = p.edit().putBoolean("ai", v).apply()

    /** 0 = según el sistema, 1 = oscuro, 2 = claro. */
    var theme: Int
        get() = p.getInt("theme", 0)
        set(v) = p.edit().putInt("theme", v).apply()

    /** Pantalla de inicio en mosaico (monitor de seguridad) en vez de lista. */
    var monitor: Boolean
        get() = p.getBoolean("monitor", true)
        set(v) = p.edit().putBoolean("monitor", v).apply()

    /** Login recordado (vacío = no recordar). En claro dentro de las preferencias privadas, igual que el token. */
    var savedAccount: Pair<String, String>?
        get() = p.getString("saved_account", null)?.let { it to p.getString("saved_password", "")!! }
        set(v) = p.edit().apply {
            if (v == null) remove("saved_account").remove("saved_password")
            else putString("saved_account", v.first).putString("saved_password", v.second)
        }.apply()

    // claves Wi-Fi usadas al emparejar, por SSID
    fun wifiPassword(ssid: String) = p.getString("wifi_$ssid", null)
    fun setWifiPassword(ssid: String, pwd: String) = p.edit().putString("wifi_$ssid", pwd).apply()

    /** PIN por cámara guardado como SHA-256 (con el uid como sal); null = sin PIN. */
    fun hasPin(uid: String) = p.contains("pin_$uid")
    fun checkPin(uid: String, pin: String) = p.getString("pin_$uid", null) == pinHash(uid, pin)
    fun setPin(uid: String, pin: String?) = p.edit().apply {
        if (pin == null) remove("pin_$uid") else putString("pin_$uid", pinHash(uid, pin))
    }.apply()
    private fun pinHash(uid: String, pin: String) =
        java.security.MessageDigest.getInstance("SHA-256").digest("$uid:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    // el giro vive en la cámara; esto solo recuerda el último valor que le mandamos
    fun flipped(uid: String) = p.getBoolean("flip_$uid", false)
    fun setFlipped(uid: String, v: Boolean) = p.edit().putBoolean("flip_$uid", v).apply()

    var cameras: List<CameraDevice>
        get() = JSONArray(p.getString("cameras", "[]")).let { a -> (0 until a.length()).map { CameraDevice.fromJson(a.getJSONObject(it)) } }
        set(v) = p.edit().putString("cameras", JSONArray(v.map { it.toJson() }).toString()).apply()
}
