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
        listOf("PPPP_API", "IOTCAPIs", "sCHL", "AVAPIs").forEach(System::loadLibrary)
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

    /** Hablar: modo 0 = AAC sin cancelación de eco (no usa MobileAEC). Necesita libVoAACEncoder.so. */
    fun startTalk(c: AntsCamera) { System.loadLibrary("VoAACEncoder"); c.startSpeaking(0) }

    fun stopTalk(c: AntsCamera) = c.stopSpeaking()

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
        init()
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
