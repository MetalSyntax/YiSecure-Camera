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
            "password" to hmacB64("HmacSHA256", com.metalsyntax.yihomelocal.YiSdk.loginKey, password),
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
            "password" to hmacB64("HmacSHA256", com.metalsyntax.yihomelocal.YiSdk.loginKey, password), "email_code" to emailCode), "PUT")
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
                shared = o.optBoolean("share"),
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

    // ------------------------------------------------------------------------------------------ compartir
    // Mismo flujo que "Compartir dispositivo" de Kami/Yi Home (com.ants360.yicamera.base.s → jc.i / jc.h): el dueño invita
    // a otra cuenta Yi/Kami, esa cuenta acepta y la cámara le aparece en /v5/devices/list con share = true.

    data class ShareUser(val userId: String, val nickname: String, val sharedTime: Long, val lastAccess: Long)

    /** Invitación (enviada o recibida). state: 1 pendiente, 2 aceptada, 3/4 rechazada, 5 vencida. Tiempos en epoch s. */
    data class ShareInvite(val id: String, val nickname: String, val state: Int, val created: Long, val shareToken: String, val uid: String) {
        val pending get() = state == 1
        val stateLabel get() = when (state) { 1 -> "Pendiente"; 2 -> "Aceptada"; 3, 4 -> "Rechazada"; 5 -> "Vencida"; else -> "Estado $state" }
    }

    private fun shareGet(s: Session, path: String, vararg extra: Pair<String, String>, method: String = "GET") =
        signedRequest(s, path, linkedMapOf("seq" to "1", "userid" to s.userId).apply { extra.forEach { put(it.first, it.second) } }, method)

    /** Busca una cuenta Yi/Kami por correo (GET /v5/users/search). null = no existe. */
    fun searchUser(s: Session, email: String): Pair<String, String>? = runCatching {
        shareGet(s, "/v5/users/search", "email" to email.trim()).optJSONObject("data")
    }.getOrElse { if (it is ApiException) null else throw it }?.let { d ->
        d.optString("userid").takeIf { it.isNotEmpty() }?.let { it to d.optString("nickname").ifEmpty { email } }
    }

    /** Invita a [toUserId] a ver la cámara (POST /v5/deviceshare/invite, type 2 = a una cuenta Yi). Devuelve el id de la invitación. */
    fun invite(s: Session, uid: String, toUserId: String): String =
        shareGet(s, "/v5/deviceshare/invite", "uid" to uid, "type" to "2", "share_to_id" to toUserId, method = "POST")
            .optJSONObject("data")?.optString("invitationid").orEmpty()

    /** Cuentas que ya ven la cámara (GET /v4/deviceshare/shareddetails). */
    fun sharedWith(s: Session, uid: String): List<ShareUser> = shareGet(s, "/v4/deviceshare/shareddetails", "uid" to uid).optJSONArray("data").objects()
        .map { ShareUser(it.optString("userid"), it.optString("nickname"), it.optLong("sharedtime"), it.optLong("lastaccesstime")) }

    /** Invitaciones enviadas para esta cámara (GET /v5/deviceshare/invitor/invitations). */
    fun sentInvites(s: Session, uid: String): List<ShareInvite> = shareGet(s, "/v5/deviceshare/invitor/invitations", "uid" to uid).optJSONArray("data").objects()
        .map { ShareInvite(it.optString("id"), it.optString("nickname"), it.optInt("state"), it.optLong("created_time"), it.optString("share_token"), uid) }

    /** Anula una invitación pendiente (DELETE /v5/deviceshare/invite). */
    fun cancelInvite(s: Session, invitationId: String) { shareGet(s, "/v5/deviceshare/invite", "invitationid" to invitationId, method = "DELETE") }

    /** Quita el acceso de [userId] a la cámara (DELETE /v4/deviceshare/inviterdevice). */
    fun unshare(s: Session, uid: String, userId: String) { shareGet(s, "/v4/deviceshare/inviterdevice", "uid" to uid, "inviteeid" to userId, method = "DELETE") }

    /** Invitaciones recibidas por esta cuenta (GET /v5/deviceshare/invitee/invitations). */
    fun receivedInvites(s: Session): List<ShareInvite> = shareGet(s, "/v5/deviceshare/invitee/invitations").optJSONArray("data").objects()
        .map { ShareInvite(it.optString("id"), it.optString("nickname"), it.optInt("state"), it.optLong("created_time"), it.optString("share_token"), it.optString("uid")) }

    /** Acepta o rechaza una invitación recibida (PUT /v5/deviceshare/accept). */
    fun answerInvite(s: Session, shareToken: String, accept: Boolean) {
        shareGet(s, "/v5/deviceshare/accept", "share_token" to shareToken, "accept" to accept.toString(), method = "PUT")
    }

    /** La cuenta invitada deja de ver una cámara compartida (DELETE /v4/deviceshare/inviteedevice). */
    fun leaveShared(s: Session, uid: String) { shareGet(s, "/v4/deviceshare/inviteedevice", "uid" to uid, method = "DELETE") }

    private fun org.json.JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    data class Firmware(val needUpdate: Boolean, val version: String, val notes: String, val url: String)

    /**
     * ¿Hay firmware nuevo? GET sin firma a api.*.xiaoyi.com/vmanager/upgrade (kc.d.a0 de Kami Home).
     * sname = rama de firmware del modelo (feature_config), protocol = servidor según región.
     */
    fun firmware(uid: String, model: String, current: String): Firmware {
        val host = when (region) { "us" -> "api.us.xiaoyi.com"; "eu" -> "api.eu.xiaoyi.com"; else -> "api.xiaoyi.com.tw" }
        val protocol = when (region) { "us" -> "mius"; "eu" -> "mieu"; else -> "aws" }
        val branch = "familymonitor-" + (FIRMWARE_BRANCH[model] ?: model)
        val q = linkedMapOf("version" to current, "uid" to uid, "sname" to branch, "protocol" to protocol)
            .entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val o = JSONObject(fetch("https://$host/vmanager/upgrade?$q").decodeToString())
        if (o.optInt("code") != 20000) throw ApiException(o.optInt("code"))
        val r = o.optJSONObject("result") ?: o
        return Firmware(r.optString("needUpdate") == "true", r.optString("version"), r.optString("message"), r.optString("downloadPath"))
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
        // POST/PUT con cuerpo vacío como la app oficial; DELETE sin cuerpo (HttpURLConnection no deja escribir en DELETE)
        if (method != "GET") { c.requestMethod = method; if (method != "DELETE") { c.doOutput = true; c.outputStream.close() } }
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

        // modelos cuya rama de firmware no es familymonitor-<modelo> (assets/feature_config de Kami Home 4.9.9)
        private val FIRMWARE_BRANCH = mapOf("y30ga" to "y30qa", "y501c" to "y501gc", "y502c" to "y502gc")

        private val PAIR_XOR get() = com.metalsyntax.yihomelocal.YiSdk.pairXor

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
