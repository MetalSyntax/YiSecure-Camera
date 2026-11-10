package com.metalsyntax.yihomelocal.p2p

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.tutk.IOTC.AVFrame

/**
 * Decodifica el video de una cámara sin pantalla (vigilancia en segundo plano): [VideoDecoder] escribe en un
 * ImageReader YUV y [latest] devuelve el último cuadro como Bitmap. Solo se convierte cuando se pide (≈1 por segundo).
 */
class FrameGrabber {
    private val thread = HandlerThread("grab").apply { start() }
    private var reader: ImageReader? = null
    private var decoder: VideoDecoder? = null
    private var size = ""
    @Volatile private var want = false
    @Volatile private var last: Bitmap? = null
    @Volatile var lastFrameAt = 0L
        private set

    /** El lector se crea (o rehace) con el tamaño real del video, en el primer I-frame de cada tamaño. */
    @Synchronized
    fun feed(f: AVFrame) {
        lastFrameAt = System.currentTimeMillis()
        val sz = "${f.videoWidth}x${f.videoHeight}"
        if (sz != size) {
            if (!f.isIFrame || f.videoWidth <= 0 || f.videoHeight <= 0) return
            decoder?.release(); runCatching { reader?.close() }
            val r = ImageReader.newInstance(f.videoWidth, f.videoHeight, ImageFormat.YUV_420_888, 2)
            r.setOnImageAvailableListener({ rr ->
                val img = runCatching { rr.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try { if (want) { last = yuvToBitmap(img); want = false } } finally { img.close() }
            }, Handler(thread.looper))
            reader = r; decoder = VideoDecoder(r.surface); size = sz
        }
        decoder?.feed(f)
    }

    /** Pide el próximo cuadro y devuelve el último convertido (null hasta que llegue el primero). */
    fun latest(): Bitmap? { want = true; return last }

    @Synchronized
    fun release() {
        decoder?.release()
        runCatching { reader?.close() }
        thread.quitSafely()
    }

    companion object {
        /** YUV_420_888 → ARGB (BT.601), respetando los strides de cada plano. */
        fun yuvToBitmap(img: android.media.Image): Bitmap {
            val w = img.width; val h = img.height
            val (yP, uP, vP) = img.planes.let { Triple(it[0], it[1], it[2]) }
            val y = yP.buffer; val u = uP.buffer; val v = vP.buffer
            val out = IntArray(w * h)
            for (j in 0 until h) {
                val yRow = j * yP.rowStride; val cRow = (j / 2) * uP.rowStride
                for (i in 0 until w) {
                    val yy = (y.get(yRow + i * yP.pixelStride).toInt() and 0xFF) - 16
                    val c = cRow + (i / 2) * uP.pixelStride
                    val uu = (u.get(c).toInt() and 0xFF) - 128
                    val vv = (v.get((j / 2) * vP.rowStride + (i / 2) * vP.pixelStride).toInt() and 0xFF) - 128
                    val yc = 1192 * maxOf(yy, 0)
                    val r = ((yc + 1634 * vv) shr 10).coerceIn(0, 255)
                    val g = ((yc - 833 * vv - 400 * uu) shr 10).coerceIn(0, 255)
                    val b = ((yc + 2066 * uu) shr 10).coerceIn(0, 255)
                    out[j * w + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        }
    }
}
