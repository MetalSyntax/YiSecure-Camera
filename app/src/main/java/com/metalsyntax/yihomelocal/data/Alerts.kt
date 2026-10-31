package com.metalsyntax.yihomelocal.data

import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Alerta de la nube de Yi (/v2/alert/list, mismo parseo que com.ants360.yicamera.alert.Alert de Yi Home 6.9.9). */
data class Alert(
    val id: String,
    val uid: String,
    /** epoch ms */
    val time: Long,
    val type: Int,
    val subType: Int,
    val picUrl: String,
    val picPwd: String,
    val videoUrl: String,
    val videoPwd: String,
) {
    /** Categoría para mostrar/filtrar; las sub_type salen de Alert.H0() (type 0 = alertas de cámara). */
    val kind: String get() = if (type != 0) "Evento" else when (subType) {
        1, 4, 6 -> "Movimiento"
        2, 12 -> "Persona"
        3 -> "Llanto"
        5 -> "Sonido"
        7, 10 -> "Rostro"
        13 -> "Vehículo"
        14 -> "Mascota"
        15 -> "Caída"
        else -> "Evento"
    }

    companion object {
        /** Imagen/clip de alerta: [len int32 LE][AES-ECB sin padding, clave = pic_pwd[0..16]] (ae.c / AESNoPadding de Yi Home). */
        fun download(url: String, pwd: String): ByteArray {
            val raw = (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = 10000; readTimeout = 20000
                try { inputStream.readBytes() } finally { disconnect() }
            }
            if (pwd.isEmpty()) return raw
            return decrypt(raw, pwd)
        }

        fun decrypt(raw: ByteArray, pwd: String): ByteArray {
            val len = (raw[0].toInt() and 255) or ((raw[1].toInt() and 255) shl 8) or
                ((raw[2].toInt() and 255) shl 16) or ((raw[3].toInt() and 255) shl 24)
            val c = Cipher.getInstance("AES/ECB/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(pwd.take(16).toByteArray(Charsets.US_ASCII), "AES"))
            // ponytail: si el cuerpo no es múltiplo de 16 se descifra lo que cabe (Yi Home hace lo mismo: falla y devuelve null)
            val body = (raw.size - 4) / 16 * 16
            val out = c.doFinal(raw, 4, body)
            return if (len in 1..out.size) out.copyOf(len) else out
        }
    }
}
