package com.metalsyntax.yihomelocal.ui.components

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.metalsyntax.yihomelocal.data.CameraDevice
import com.metalsyntax.yihomelocal.data.Gallery
import com.metalsyntax.yihomelocal.data.Snapshots
import com.metalsyntax.yihomelocal.p2p.Mp4Recorder
import com.metalsyntax.yihomelocal.p2p.AudioDecoder
import com.metalsyntax.yihomelocal.p2p.VideoDecoder
import com.metalsyntax.yihomelocal.p2p.YiP2p
import com.tutk.IOTC.AVFrame
import com.xiaoyi.camera.sdk.AntsCamera
import kotlinx.coroutines.delay

/** Lo que la pantalla de vivo necesita del video: captura (PixelCopy del Surface) y grabación MP4 del stream. */
class LiveTools {
    var view: SurfaceView? = null
    @Volatile var recorder: Mp4Recorder? = null
        private set

    fun capture(ok: (Bitmap?) -> Unit) {
        val v = view
        if (v == null || v.width == 0 || !v.holder.surface.isValid) return ok(null)
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(v, bmp, { r -> ok(if (r == PixelCopy.SUCCESS) bmp else null) }, Handler(Looper.getMainLooper()))
    }

    fun startRecording(file: java.io.File) { recorder = Mp4Recorder(file) }

    /** Cierra el MP4 y lo pasa a la galería; devuelve la ruta o null si no llegó ningún frame. */
    fun stopRecording(ctx: android.content.Context): String? {
        val r = recorder ?: return null
        recorder = null
        return if (r.stop()) Gallery.saveVideo(ctx, r.file) else null
    }
}

/**
 * Video en vivo por P2P: conecta al aparecer el Surface y libera todo al salir. [session] cambia → reconecta.
 * [onCamera] entrega la conexión para mandar comandos (calidad, giro, audio). Guarda la última imagen cada 10 s y al salir.
 */
@Composable
fun LiveView(
    camera: CameraDevice, session: Int, quality: Int, audio: Boolean,
    onState: (String) -> Unit, onCamera: (AntsCamera?) -> Unit, modifier: Modifier = Modifier, tools: LiveTools? = null,
) {
    val ctx = LocalContext.current
    val state by rememberUpdatedState(onState)
    val audioOn by rememberUpdatedState(audio)
    var view by remember { mutableStateOf<SurfaceView?>(null) }
    var holder by remember { mutableStateOf<SurfaceHolder?>(null) }
    var live by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { c ->
            SurfaceView(c).apply {
                view = this
                tools?.view = this
                keepScreenOn = true // no apagar la pantalla mientras se mira el vivo
                this.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) { holder = h }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                    override fun surfaceDestroyed(h: SurfaceHolder) { holder = null }
                })
            }
        },
    )

    fun snapshot() {
        val v = view ?: return
        if (!live || v.width == 0 || !v.holder.surface.isValid) return
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(v, bmp, { r ->
            if (r == PixelCopy.SUCCESS) Thread { Snapshots.save(ctx, camera.uid, bmp) }.start()
        }, Handler(Looper.getMainLooper()))
    }

    LaunchedEffect(live) {
        if (!live) return@LaunchedEffect
        delay(2_000) // dar tiempo al primer I-frame
        while (true) { snapshot(); delay(10_000) }
    }

    val h = holder ?: return
    DisposableEffect(h, camera.uid, session) {
        val video = VideoDecoder(h.surface)
        val sound = AudioDecoder()
        live = false
        val cam = YiP2p.open(camera, quality, audio, object : YiP2p.Listener() {
            override fun onState(text: String, connected: Boolean) {
                if (connected) live = true
                state(text)
            }
            override fun onFrame(f: AVFrame) {
                video.feed(f)
                tools?.recorder?.write(f)
            }
            override fun onAudio(f: AVFrame) { if (audioOn) sound.feed(f) }
        })
        onCamera(cam)
        onDispose {
            tools?.stopRecording(ctx)
            snapshot() // PixelCopy lee el surface antes de que se destruya el decodificador
            onCamera(null)
            YiP2p.close(cam)
            video.release()
            sound.release()
        }
    }
}
