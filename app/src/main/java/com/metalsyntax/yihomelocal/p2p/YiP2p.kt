package com.metalsyntax.yihomelocal.p2p

import com.metalsyntax.yihomelocal.data.CameraDevice
import com.p2p.pppp_api.PPPP_APIs
import com.tutk.IOTC.AVFrame
import com.tutk.IOTC.TutkCamera
import com.xiaoyi.camera.sdk.AntsCamera
import com.xiaoyi.camera.sdk.AntsCameraListener
import com.xiaoyi.camera.sdk.AntsCameraManage
import com.xiaoyi.camera.sdk.CameraCommandHelper
import com.xiaoyi.camera.sdk.P2PDevice
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

typealias DeviceInfo = com.tutk.IOTC.AVIOCTRLDEFs.SMsgAVIoctrlDeviceInfoResp

/** Puente mínimo con el SDK P2P original de Yi Home (yisdk.jar + .so). */
object YiP2p {
    private var ready = false

    /** Calidades de CameraPlayerFragment: 0 auto, 1 alta (2K en y623), 2 normal. */
    const val QUALITY_AUTO = 0
    const val QUALITY_HIGH = 1
    const val QUALITY_SD = 2

    /** Equivale a lo que hace Yi Home al arrancar (AntsApplication + DeviceManager). */
    @Synchronized
    fun init() {
        if (ready) return
        // en Yi Home las carga código fuera del SDK; IOTCAPIs antes que AVAPIs (dependencia)
        try {
            listOf("PPPP_API", "IOTCAPIs", "sCHL", "AVAPIs").forEach(System::loadLibrary)
        } catch (e: UnsatisfiedLinkError) {
            // p. ej. tablet de 32 bits con un APK que solo trae las .so de Yi en arm64
            throw IllegalStateException("Este equipo (${android.os.Build.SUPPORTED_ABIS.joinToString()}) no tiene las librerías P2P de Yi", e)
        }
        com.xiaoyi.log.AntsLog.setDebug(com.metalsyntax.yihomelocal.BuildConfig.DEBUG)
        PPPP_APIs.PPPP_Initialize(byteArrayOf(0), 12)
        TutkCamera.init()
        ready = true
    }

    fun open(cam: CameraDevice, quality: Int, audio: Boolean, l: Listener): AntsCamera {
        init()
        val model = CameraDevice.modelName(cam.model)
        // último parámetro = !isSupportIOTAbility() de Yi Home: 0 solo para modelos "common"/"phone"
        val dev = P2PDevice(
            cam.uid, cam.p2pId, "", cam.password, P2PDevice.getDeviceType(cam.p2pId),
            cam.tnpServer, cam.tnpKey, model, cam.encrypted, 0, 0.toByte(), false, false,
            if (model == "common" || model == "phone") 0 else 1,
        )
        return AntsCameraManage.getAntsCamera(dev).apply {
            setAntsCameraListener(l)
            setEnableListening(audio)
            connect()
            // el hilo de comandos del SDK espera un primer comando en cola antes de autenticar ("wait for first command")
            startPlay()
            commandHelper.setResolution(quality, null)
        }
    }

    fun setQuality(c: AntsCamera, quality: Int) = c.commandHelper.setResolution(quality, null)

    /** Gira 180° la imagen en la propia cámara (queda guardado en ella, como "Rotar imagen" de Yi Home). */
    fun setFlipped(c: AntsCamera, flipped: Boolean) = c.commandHelper.setReverse(flipped, null)

    /** Envuelve un comando del SDK (CameraCommandHelper.OnCommandResponse) en una suspensión; error o sin respuesta → null. */
    suspend fun <T> cmd(c: AntsCamera, send: CameraCommandHelper.(CameraCommandHelper.OnCommandResponse<T>) -> Unit): T? =
        withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { k ->
                c.commandHelper.send(object : CameraCommandHelper.OnCommandResponse<T> {
                    override fun onResult(t: T) { if (k.isActive) k.resume(t) }
                    override fun onError(i: Int) { if (k.isActive) k.resume(null) }
                })
            }
        }

    /** Estado completo de la cámara (lo que lee la pantalla de ajustes de Yi Home). */
    suspend fun info(c: AntsCamera): DeviceInfo? = cmd(c) { getDeviceInfo(it) }

    suspend fun version(c: AntsCamera): String? = cmd(c) { doGetCameraVersion(it) }

    /** Visión nocturna (CameraInfraredSettingActivity): 1 auto, 3 encendida, 2 apagada. */
    suspend fun setNightVision(c: AntsCamera, mode: Int) = cmd(c) { setDayNight(mode, it) }

    /** LED de estado: true = encendido (doOpenOrCloseLight(true) manda close_light=0). */
    suspend fun setLed(c: AntsCamera, on: Boolean) = cmd(c) { doOpenOrCloseLight(on, it) }

    /** Cámara encendida/apagada (privacidad): close_camera=1 deja de transmitir. */
    suspend fun setCameraOn(c: AntsCamera, on: Boolean) = cmd(c) { doOpenOrCloseVideo(on, it) }

    /** Sensibilidad de movimiento: 0 alta, 1 media, 2 baja (CameraAlarmNotifyActivity.e4). */
    suspend fun setSensitivity(c: AntsCamera, v: Int) = cmd(c) { setAlarmSensitivity(v, it) }

    /** Detección de persona en la cámara (setAlarmMode 1/0, rama humanMovingDetectionSupport de Yi Home). */
    suspend fun setPersonDetection(c: AntsCamera, on: Boolean) = cmd(c) { setAlarmMode(if (on) 1 else 0, it) }

    /** Sonido anormal (llanto, golpes, alarmas): 2 encendido, 1 apagado. */
    suspend fun setSoundDetection(c: AntsCamera, on: Boolean) = cmd(c) { setAbnormalSound(if (on) 2 else 1, it) }

    /** Detección de llanto de bebé: 2 encendido, 1 apagado. */
    suspend fun setBabyCry(c: AntsCamera, on: Boolean) = cmd(c) { setBabyCryingMode(if (on) 2 else 1, it) }

    /**
     * "Modo ventana" de Kami (CameraSettingFragment): detrás de un vidrio el IR y el LED se reflejan y la imagen
     * queda blanca. Activar = LED apagado + IR apagado (setDayNight 2); desactivar = LED encendido + IR auto.
     */
    suspend fun setGlassMode(c: AntsCamera, on: Boolean): DeviceInfo? {
        setLed(c, !on) ?: return null
        return setNightVision(c, if (on) 2 else 1)
    }

    /** Ordena a la cámara bajar e instalar el firmware de [url] (IOCTRL 4866). El avance se lee en info().update_progress. */
    suspend fun upgrade(c: AntsCamera, url: String) = cmd(c) { doSendUpgradeCommand(url, it) }

    /** Tramo grabado en la microSD: inicio (epoch ms, hora local de la cámara = la del teléfono) y duración. */
    data class SdClip(val start: Long, val seconds: Int) { val end get() = start + seconds * 1000L }

    /**
     * Grabaciones de la microSD entre [from] y [to] (epoch ms). TNP = IOCTRL 9031, TUTK = 792 (getEvents del SDK).
     * La petición va en hora GMT y la respuesta en hora local: quien llame debe pedir con un día de margen.
     */
    suspend fun sdClips(c: AntsCamera, from: Long, to: Long): List<SdClip>? = withTimeoutOrNull(15_000) {
        suspendCancellableCoroutine { k ->
            c.commandHelper.getEvents(from, to, object : CameraCommandHelper.OnGetEventsCommandResponse {
                override fun onEvents(list: MutableList<com.tutk.IOTC.EventInfo>?, n: Int) {
                    if (k.isActive) k.resume(list.orEmpty().map { SdClip(it.sTimeDay?.timeInMillis ?: it.startTime, it.length) }.sortedBy { it.start })
                }
                override fun onError(i: Int) { if (k.isActive) k.resume(null) }
            })
        }
    }

    /** Evento relevante guardado por la propia cámara en la microSD (movimiento/persona…): inicio epoch ms, duración, tipo. */
    data class SdAlert(val start: Long, val seconds: Int, val category: Int) { val end get() = start + maxOf(seconds, 6) * 1000L }

    /**
     * Eventos de alerta de la microSD (TNP IOCTRL 23558, getAlertEvents del SDK) entre [from] y [to] (epoch ms).
     * ponytail: se asume starttime en epoch segundos (como AlertEvent de Kami); firmwares viejos no responden → null.
     */
    suspend fun sdAlerts(c: AntsCamera, from: Long, to: Long): List<SdAlert>? = withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine { k ->
            c.commandHelper.getAlertEvents((from / 1000).toInt(), (to / 1000).toInt(), object : CameraCommandHelper.OnGetAlertsCommandResponse {
                override fun onEvents(list: MutableList<com.tutk.IOTC.AlertEvent>?, n: Int) {
                    if (k.isActive) k.resume(list.orEmpty().map { SdAlert(it.starttime * 1000L, it.duration, it.category) }.sortedBy { it.start })
                }
                override fun onError(i: Int) { if (k.isActive) k.resume(null) }
            })
        }
    }

    /**
     * Modo de grabación en la microSD. El SDK manda `!soloMovimiento` (IOCTRL 4874), así que record_mode 1 = continua
     * 24 h y 0 = solo con movimiento (por eso en la línea de tiempo solo salían los eventos).
     */
    fun isContinuous(i: DeviceInfo) = i.record_mode.toInt() == 1

    suspend fun setContinuous(c: AntsCamera, on: Boolean) = cmd(c) { setMotionRecord(!on, it) }

    /** Reproduce la microSD desde [ms] (epoch ms) en la misma conexión; [goLive] vuelve al vivo. */
    fun playSd(c: AntsCamera, ms: Long) = c.seekTo(ms)

    fun goLive(c: AntsCamera) = c.goLive()

    /** Pausa / reanuda la reproducción de la microSD (el SDK no tiene cambio de velocidad, solo saltar con seekTo). */
    fun pauseSd(c: AntsCamera) = c.pausePlay()

    fun resumeSd(c: AntsCamera) = c.resumePlay()

    /** Zona de detección de movimiento que guarda la cámara (IOCTRL 4903/4901): open, resolution y el rectángulo. */
    suspend fun motionZone(c: AntsCamera) = cmd<com.tutk.IOTC.AVIOCTRLDEFs.SMsAVIoctrlMotionDetectCfg>(c) { getMotionDetect(it) }

    /**
     * Cambia la zona de movimiento de la cámara. ponytail: la escala de las coordenadas depende de [resolution] y no
     * está documentada; quien llama reusa la resolution que leyó y escala al tamaño que esa lectura sugiere.
     */
    suspend fun setMotionZone(c: AntsCamera, open: Boolean, resolution: Int, l: Int, t: Int, r: Int, b: Int) =
        cmd<com.tutk.IOTC.AVIOCTRLDEFs.SMsAVIoctrlMotionDetectCfg>(c) { setMotionDetect(if (open) 1 else 0, resolution, l, t, r, b, it) }

    /** Reinicia la cámara (se desconecta ~1 min). */
    suspend fun reboot(c: AntsCamera): Int? = cmd(c) { rebootDevice(it) }

    /** Formatea la microSD (borra TODAS las grabaciones). Devuelve el estado nuevo o null si no respondió. */
    suspend fun formatSd(c: AntsCamera) = cmd(c) { formatTfCard(it) }

    /** Hablar: modo 0 = AAC sin cancelación de eco (no usa MobileAEC). Necesita libVoAACEncoder.so. */
    fun startTalk(c: AntsCamera) { System.loadLibrary("VoAACEncoder"); c.startSpeaking(0) }

    fun stopTalk(c: AntsCamera) = c.stopSpeaking()

    /**
     * Intercomunicador manos libres (botón "llamada" de Kami): modo 1 = AAC con cancelación de eco MobileAEC
     * (libwebrtc_apm) y se escucha la cámara a la vez.
     */
    fun startIntercom(c: AntsCamera) {
        listOf("c++_shared", "webrtc_audio_preprocessing", "webrtc_apm", "VoAACEncoder").forEach(System::loadLibrary)
        setAudio(c, true)
        c.startSpeaking(1)
    }

    fun setAudio(c: AntsCamera, on: Boolean) {
        c.setEnableListening(on)
        if (on) c.startListening() else c.stopListening()
    }

    fun close(c: AntsCamera) {
        c.stopPlay()
        c.disconnect()
        AntsCameraManage.remove(c)
    }

    /**
     * Pregunta al servidor P2P si la cámara está registrada ahora mismo (PPPP_CheckDevOnline, lo mismo que ThreadOnlineStatus).
     * null = no se pudo saber (TUTK o error de red) → usar lo que dice la nube.
     */
    fun isOnline(cam: CameraDevice): Boolean? {
        if (cam.tnpServer.isEmpty()) return null
        runCatching { init() }.onFailure { return null }
        val last = IntArray(1)
        return when (runCatching { PPPP_APIs.PPPP_CheckDevOnline(cam.p2pId, cam.tnpServer, 2, last) }.getOrDefault(-1)) {
            1 -> true
            0 -> false
            else -> null
        }
    }

    /** Solo lo que usa la app; el resto de callbacks del SDK se ignoran. */
    abstract class Listener : AntsCameraListener {
        // el SDK entrega por receiveVideoFrameData o por ...New según el modo; usamos el primero que llegue
        private var source = 0
        private var size = ""

        abstract fun onState(text: String, connected: Boolean)
        abstract fun onFrame(f: AVFrame)
        abstract fun onAudio(f: AVFrame)

        override fun receiveConnectSuccess() = onState("Conectado", true)
        override fun receiveConnectingProgress(p: Int) = onState("Conectando… $p%", false)
        override fun receiveErrorState(s: String?, a: Int, b: Int) = onState("Error $a/$b ${s.orEmpty()}", false)
        override fun receivePasswordError(a: Int, b: Int) = onState("Contraseña P2P rechazada ($a)", false)
        override fun receiveVideoFrameData(f: AVFrame) = frame(1, f)
        override fun receiveVideoFrameDataNew(f: AVFrame) = frame(2, f)
        override fun receiveAudioFrameData(f: AVFrame) = onAudio(f)
        override fun receiveDeviceInfo(i: Int, o: Any?) {}
        override fun receiveP2pTypeInfo(s: String?, i: Int) {}
        override fun receiveSendIOError(a: Int, b: Int) {}
        override fun receiveSpeakEnableInfo(b: Boolean) {}
        override fun receiveStopSpeakingStatus(i: Int) {}
        override fun receiveVideoInfoChanged(f: AVFrame) {}

        private fun frame(s: Int, f: AVFrame) {
            if (source == 0) source = s
            if (source != s) return
            // con TNP el SDK no siempre avisa receiveConnectSuccess: el frame (y su tamaño) es la señal fiable
            val sz = "${f.videoWidth}×${f.videoHeight}"
            if (sz != size) { size = sz; onState("En vivo · $sz", true) }
            onFrame(f)
        }
    }
}
