package com.metalsyntax.yihomelocal.ui.components

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
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

/** Lo que la pantalla de vivo necesita del video: captura (bitmap del TextureView) y grabación MP4 del stream. */
class LiveTools {
    var view: TextureView? = null
    @Volatile var recorder: Mp4Recorder? = null
        private set

    /** Captura el cuadro completo (sin el zoom de la vista). */
    fun capture(ok: (Bitmap?) -> Unit) = ok(view?.takeIf { it.isAvailable }?.bitmap)

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
    /** Hora (epoch s) de cada cuadro: sirve para mostrar por dónde va la reproducción de la microSD. */
    onFrameTime: ((Long) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val state by rememberUpdatedState(onState)
    val audioOn by rememberUpdatedState(audio)
    var view by remember { mutableStateOf<TextureView?>(null) }
    // TextureView (no SurfaceView) para que el zoom con graphicsLayer escale el video
    var surface by remember { mutableStateOf<Surface?>(null) }
    var live by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { c ->
            TextureView(c).apply {
                view = this
                tools?.view = this
                keepScreenOn = true // no apagar la pantalla mientras se mira el vivo
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { surface = Surface(st) }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { surface?.release(); surface = null; return true }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
    )

    fun snapshot() {
        val v = view ?: return
        if (!live || !v.isAvailable) return
        val bmp = v.bitmap ?: return
        Thread { Snapshots.save(ctx, camera.uid, bmp) }.start()
    }

    LaunchedEffect(live) {
        if (!live) return@LaunchedEffect
        delay(2_000) // dar tiempo al primer I-frame
        while (true) { snapshot(); delay(10_000) }
    }

    val sf = surface ?: return
    DisposableEffect(sf, camera.uid, session) {
        val video = VideoDecoder(sf)
        val sound = AudioDecoder()
        live = false
        val cam = runCatching { YiP2p.open(camera, quality, audio, object : YiP2p.Listener() {
            override fun onState(text: String, connected: Boolean) {
                if (connected) live = true
                state(text)
            }
            override fun onFrame(f: AVFrame) {
                onFrameTime?.invoke(f.timeStamp.toLong())
                video.feed(f)
                tools?.recorder?.write(f)
            }
            override fun onAudio(f: AVFrame) {
                tools?.recorder?.writeAudio(f)
                if (audioOn) sound.feed(f)
            }
        }) }.onFailure { state(it.message ?: "No se pudo abrir el P2P") }.getOrNull()
        onCamera(cam)
        onDispose {
            tools?.stopRecording(ctx)
            snapshot() // leer el último cuadro antes de que se destruya el decodificador
            onCamera(null)
            cam?.let(YiP2p::close)
            video.release()
            sound.release()
        }
    }
}
