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
        get() = p.getBoolean("audio", false)
        set(v) = p.edit().putBoolean("audio", v).apply()

    // el giro vive en la cámara; esto solo recuerda el último valor que le mandamos
    fun flipped(uid: String) = p.getBoolean("flip_$uid", false)
    fun setFlipped(uid: String, v: Boolean) = p.edit().putBoolean("flip_$uid", v).apply()

    var cameras: List<CameraDevice>
        get() = JSONArray(p.getString("cameras", "[]")).let { a -> (0 until a.length()).map { CameraDevice.fromJson(a.getJSONObject(it)) } }
        set(v) = p.edit().putString("cameras", JSONArray(v.map { it.toJson() }).toString()).apply()
}
