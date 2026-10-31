package com.metalsyntax.yihomelocal.data

import android.os.Build
import android.util.Base64
import com.metalsyntax.yihomelocal.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** API cloud de Yi, solo lo necesario para obtener las credenciales P2P (mismo protocolo que Yi Home 6.9.9). */
class YiCloud(private val region: String) {

    data class Session(val userId: String, val token: String, val tokenSecret: String)

    class ApiException(val code: Int) : Exception("Yi API code $code")

    private val base get() = "https://gw-$region.xiaoyi.com"

    /** GET /v4/users/login. El password viaja como HMAC-SHA256 con la clave de la app (yi.properties). */
    fun login(account: String, password: String): Session {
        val data = get("/v4/users/login", linkedMapOf(
            "seq" to "1",
            "account" to account,
            "password" to hmacB64("HmacSHA256", BuildConfig.YI_LOGIN_KEY, password),
            "dev_name" to Build.BRAND,
            "dev_type" to Build.MODEL,
            "dev_os_version" to "Android ${Build.VERSION.RELEASE}",
        ))
        return Session(data.getString("userid"), data.getString("token"), data.getString("token_secret"))
    }

    /** Captcha (id, JPEG) previo a pedir el código por correo. */
    fun captcha(): Pair<String, ByteArray> {
        val id = JSONObject(fetch("$base/v4/users/validation_code_id").decodeToString()).getString("validationCodeId")
        return id to fetch("$base/v4/users/validation_code?validationCodeId=$id")
    }

    /** Envía al correo un código para fijar contraseña; sirve también para cuentas creadas con Google. */
    fun requestReset(account: String, captchaId: String, captcha: String) {
        request("/v4/users/request_reset_pwd", linkedMapOf(
            "seq" to "1", "account" to account, "client_code" to captcha, "validationCodeId" to captchaId), "PUT")
    }

    fun resetPassword(account: String, password: String, emailCode: String) {
        request("/v4/users/reset_pwd", linkedMapOf(
            "seq" to "1", "account" to account,
            "password" to hmacB64("HmacSHA256", BuildConfig.YI_LOGIN_KEY, password), "email_code" to emailCode), "PUT")
    }

    fun devices(s: Session): List<CameraDevice> {
        val res = signedGet(s, "/v5/devices/list", linkedMapOf("seq" to "1", "userid" to s.userId))
        val arr = res.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val uid = o.getString("uid")
            val ipc = o.optString("ipcParam").takeIf { it.isNotEmpty() }?.let(::JSONObject)
            val tnp = runCatching { tnpInfo(s, uid) }.getOrNull()
            CameraDevice(
                uid = uid,
                name = o.optString("name", uid),
                model = CameraDevice.modelName(o.optString("model")),
                online = o.optBoolean("online"),
                password = decryptPassword(uid, o.optString("password")),
                encrypted = ipc?.optBoolean("p2p_encrypt", true) ?: true,
                localIp = ipc?.optString("ip").orEmpty(),
                p2pId = tnp?.optString("DID").orEmpty().ifEmpty { uid },
                tnpServer = tnp?.optString("InitString").orEmpty(),
                // la app guarda solo la parte antes de ":" de la licencia
                tnpKey = tnp?.optString("License").orEmpty().substringBefore(':'),
            )
        }
    }

    /** Renombrar: /v2/devices/edit (la app oficial manda también message vacío). */
    fun rename(s: Session, uid: String, name: String) {
        signedGet(s, "/v2/devices/edit", linkedMapOf(
            "seq" to "1", "userid" to s.userId, "token" to s.token, "uid" to uid, "name" to name, "message" to ""))
    }

    /** Quita la cámara de la cuenta (igual que "Eliminar dispositivo" en Yi Home). */
    fun delete(s: Session, uid: String) {
        signedGet(s, "/v2/devices/del", linkedMapOf("seq" to "1", "userid" to s.userId, "token" to s.token, "uid" to uid))
    }

    /** Clave de emparejamiento que va dentro del QR que lee la cámara. */
    fun bindKey(s: Session): String =
        signedGet(s, "/v2/qrcode/get_bindkey", linkedMapOf(
            "seq" to "1", "userid" to s.userId, "timestamp" to System.currentTimeMillis().toString()))
            .getJSONObject("data").getString("bindkey")

    /** ret: 0 = esperando, 1 = vinculada (uid), negativo = error del servidor (-2 no existe, -3 expiró, -5 ya tiene dueño). */
    fun checkBind(s: Session, bindKey: String): Pair<Int, String> {
        val d = signedGet(s, "/v2/qrcode/check_bindkey", linkedMapOf(
            "seq" to "1", "bindkey" to bindKey, "timestamp" to System.currentTimeMillis().toString()))
            .getJSONObject("data")
        return d.optInt("ret") to d.optString("uid")
    }

    /** Alertas de todas las cámaras entre [fromSec] y [toSec] (epoch s), más recientes primero. Igual que kc.d.H() de Yi Home. */
    fun alerts(s: Session, fromSec: Long, toSec: Long, limit: Int = 100): List<Alert> {
        val res = signedGet(s, "/v2/alert/list", linkedMapOf(
            "seq" to "0", "userid" to s.userId, "type" to "", "sub_type" to "",
            "from" to fromSec.toString(), "to" to toSec.toString(), "limit" to limit.toString(),
            "fromDB" to "true", "expires" to "10"))
        val arr = res.optJSONArray("data") ?: return emptyList()
        if (BuildConfig.DEBUG && arr.length() > 0) android.util.Log.i("YiCloud", "alerts=${arr.length()} [0]=${arr.getJSONObject(0)}")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Alert(
                o.optString("id"), o.optString("uid"), o.optLong("time"), o.optInt("type"), o.optInt("sub_type"),
                o.optString("pic_urls").substringBefore(','), o.optString("pic_pwd").substringBefore(','),
                o.optString("video_urls").substringBefore(','), o.optString("video_pwd").substringBefore(','),
            )
        }.sortedByDescending { it.time }
    }

    /** Aviso de alertas en la nube por cámara: pushflag "0" apagado, "1" siempre, "2" horario (GET /v2/alert/get_push). */
    fun alertPush(s: Session, uid: String): String =
        signedGet(s, "/v2/alert/get_push", linkedMapOf("seq" to "1", "userid" to s.userId, "uid" to uid, "inviterUserId" to ""))
            .getJSONObject("data").getString("pushflag")

    /** PUT /v5/alert/push_prop (t1 de Yi Home): activa/desactiva las alertas de esa cámara. */
    fun setAlertPush(s: Session, uid: String, on: Boolean) {
        signedRequest(s, "/v5/alert/push_prop", linkedMapOf(
            "seq" to "1", "userid" to s.userId, "uid" to uid, "pushflag" to if (on) "1" else "0", "uploadflag" to "1"), "PUT")
    }

    private fun tnpInfo(s: Session, uid: String): JSONObject =
        signedGet(s, "/v4/tnp/device_info", linkedMapOf("seq" to "1", "userid" to s.userId, "uid" to uid))
            .getJSONObject("data")

    private fun signedGet(s: Session, path: String, p: LinkedHashMap<String, String>) = signedRequest(s, path, p, "GET")

    private fun signedRequest(s: Session, path: String, p: LinkedHashMap<String, String>, method: String): JSONObject {
        // firma = HMAC-SHA1(token&token_secret, "k1=v1&k2=v2…") sobre los valores sin codificar, en orden
        val raw = p.entries.joinToString("&") { "${it.key}=${it.value}" }
        p["hmac"] = hmacB64("HmacSHA1", "${s.token}&${s.tokenSecret}", raw)
        return request(path, p, method)
    }

    private fun get(path: String, p: LinkedHashMap<String, String>): JSONObject = request(path, p).getJSONObject("data")

    private fun fetch(url: String): ByteArray = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 15000; readTimeout = 15000
        try { inputStream.readBytes() } finally { disconnect() }
    }

    // la app oficial manda PUT con los parámetros en la query y cuerpo vacío
    private fun request(path: String, p: Map<String, String>, method: String = "GET"): JSONObject {
        val q = p.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val c = URL("$base$path?$q").openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 15000
        if (method != "GET") { c.requestMethod = method; c.doOutput = true; c.outputStream.close() }
        try {
            val body = if (c.responseCode < 400) c.inputStream else c.errorStream ?: c.inputStream
            val json = JSONObject(body.bufferedReader().readText())
            val code = json.optInt("code")
            if (code != 20000) throw ApiException(code)
            return json
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private fun hmacB64(alg: String, key: String, data: String): String {
            val mac = Mac.getInstance(alg).apply { init(SecretKeySpec(key.toByteArray(), alg)) }
            return Base64.encodeToString(mac.doFinal(data.toByteArray()), Base64.NO_WRAP)
        }

        /** Texto del QR de emparejamiento (GenerateAndScanBarcodeActivity): b=bindkey&s=b64(ssid)&p=b64(clave XOR tabla). */
        fun pairingQr(bindKey: String, ssid: String, wifiPassword: String): String {
            val xor = String(CharArray(wifiPassword.length) { i ->
                val c = (wifiPassword[i].code xor PAIR_XOR[i % PAIR_XOR.length].code).toChar()
                if (c.code == 0) wifiPassword[i] else c
            })
            fun b64(v: String) = String(com.xiaoyi.camera.util.Base64.encode(v.toByteArray()))
            return "b=$bindKey&s=${b64(ssid)}&p=${b64(xor)}"
        }

        private val PAIR_XOR = com.metalsyntax.yihomelocal.BuildConfig.YI_PAIR_XOR

        /** password del device list: hex de AES-ECB con clave = primeros 16 chars del UID. Vacío → "888888" (fábrica). */
        fun decryptPassword(uid: String, hex: String): String {
            if (hex.isEmpty()) return "888888"
            val c = Cipher.getInstance("AES/ECB/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(uid.substring(0, 16).toByteArray(), "AES"))
            val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            return String(c.doFinal(bytes))
        }
    }
}
