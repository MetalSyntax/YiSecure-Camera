package com.metalsyntax.yihomelocal.p2p

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.tutk.IOTC.AVFrame

/** Decodifica los frames H.264/H.265 del SDK directo a un Surface (sin los players GL de Yi Home). */
class VideoDecoder(private val surface: Surface) {
    private var codec: MediaCodec? = null
    private var key = ""

    @Synchronized
    fun feed(f: AVFrame) {
        val mime = when (f.codecId.toInt()) {
            AVFrame.MEDIA_CODEC_VIDEO_H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
            AVFrame.MEDIA_CODEC_VIDEO_H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            else -> return
        }
        val k = "$mime ${f.videoWidth}x${f.videoHeight}"
        if (k != key) {
            if (!f.isIFrame) return // esperar un I-frame para (re)arrancar
            release()
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(MediaFormat.createVideoFormat(mime, f.videoWidth, f.videoHeight), surface, null, 0)
                start()
            }
            key = k
        }
        val c = codec ?: return
        val data = f.frmData ?: return
        val len = if (f.frmSize in 1..data.size) f.frmSize else data.size
        val i = c.dequeueInputBuffer(20_000)
        if (i >= 0) {
            c.getInputBuffer(i)!!.apply { clear(); put(data, 0, len) }
            c.queueInputBuffer(i, 0, len, System.nanoTime() / 1000, 0)
        }
        val info = MediaCodec.BufferInfo()
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            if (o < 0) break
            c.releaseOutputBuffer(o, true)
        }
    }

    @Synchronized
    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        key = ""
    }
}
