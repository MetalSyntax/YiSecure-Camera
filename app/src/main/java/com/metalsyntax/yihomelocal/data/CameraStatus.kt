package com.metalsyntax.yihomelocal.data

import android.content.Context
import com.metalsyntax.yihomelocal.p2p.DeviceInfo
import com.metalsyntax.yihomelocal.p2p.YiP2p
import org.json.JSONObject

/**
 * Último estado leído de cada cámara por P2P (Wi-Fi, batería, IR, privacidad, sensibilidad, grabación), para pintar
 * las etiquetas de la pantalla Cámaras sin abrir una conexión por cada miniatura. Se actualiza al abrir el vivo o
 * al aplicar un perfil; [time] dice de cuándo es.
 */
data class CameraStatus(
    val time: Long, val signal: Int, val battery: Int, val night: Int, val privacy: Boolean,
    val sensitivity: Int, val continuous: Boolean, val sd: Boolean,
) {
    companion object {
        private fun prefs(ctx: Context) = ctx.getSharedPreferences("status", Context.MODE_PRIVATE)

        fun save(ctx: Context, uid: String, i: DeviceInfo) {
            val j = JSONObject().put("time", System.currentTimeMillis()).put("signal", i.signal_quality.toInt())
                .put("battery", i.battery_precent.toInt()).put("night", i.day_night_mode.toInt())
                .put("privacy", i.close_camera.toInt() != 0).put("sens", i.alarm_sensitivity.toInt())
                .put("cont", YiP2p.isContinuous(i)).put("sd", i.tfstat.toInt() != 5)
            prefs(ctx).edit().putString(uid, j.toString()).apply()
        }

        fun load(ctx: Context, uid: String): CameraStatus? = prefs(ctx).getString(uid, null)?.let {
            runCatching {
                val j = JSONObject(it)
                CameraStatus(j.getLong("time"), j.getInt("signal"), j.getInt("battery"), j.getInt("night"), j.getBoolean("privacy"),
                    j.getInt("sens"), j.getBoolean("cont"), j.getBoolean("sd"))
            }.getOrNull()
        }
    }
}
