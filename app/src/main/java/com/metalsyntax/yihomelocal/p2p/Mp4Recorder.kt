package com.metalsyntax.yihomelocal.p2p

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.tutk.IOTC.AVFrame
import java.io.File
import java.nio.ByteBuffer

/**
 * Graba el stream H.264/H.265 y el audio AAC tal como llegan (sin recodificar) a un MP4.
 * La pista de audio se crea si antes del primer I-frame ya llegó audio con cabecera ADTS (lo normal: llega varias veces por segundo).
 */
class Mp4Recorder(val file: File) {
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var audioTrack = -1
    private var audioCfg: AudioDecoder.Adts? = null
    private var startUs = -1L
    private var lastUs = -1L
    var frames = 0; private set
    private var closed = false

    @Synchronized
    fun write(f: AVFrame) {
        if (closed) return
        val hevc = when (f.codecId.toInt()) {
            AVFrame.MEDIA_CODEC_VIDEO_H264 -> false
            AVFrame.MEDIA_CODEC_VIDEO_H265 -> true
            else -> return
        }
        val data = f.frmData ?: return
        val len = if (f.frmSize in 1..data.size) f.frmSize else data.size
        val nals = splitNals(data, len)
        if (muxer == null) {
            if (!f.isIFrame) return // empezar en un I-frame con sus parámetros
            val params = nals.filter { isParam(it, data, hevc) }
            if (params.isEmpty()) return
            val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val fmt = MediaFormat.createVideoFormat(mime, f.videoWidth, f.videoHeight)
            if (hevc) fmt.setByteBuffer("csd-0", ByteBuffer.wrap(params.flatMap { withStart(data, it).toList() }.toByteArray()))
            else params.forEachIndexed { i, r -> if (i < 2) fmt.setByteBuffer("csd-$i", ByteBuffer.wrap(withStart(data, r))) }
            muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { m ->
                track = m.addTrack(fmt)
                audioCfg?.let { a ->
                    val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, a.rate, a.channels)
                    af.setByteBuffer("csd-0", ByteBuffer.wrap(a.csd()))
                    audioTrack = m.addTrack(af)
                }
                m.start()
            }
        }
        // MPEG4Writer espera una NAL por muestra en Annex-B: se quitan SPS/PPS/SEI y se escribe la porción de video
        val slices = nals.filterNot { isParam(it, data, hevc) || isSei(it, data, hevc) }
        if (slices.isEmpty()) return
        val sample = slices.flatMap { withStart(data, it).toList() }.toByteArray()
        var pts = ptsNow()
        if (pts <= lastUs) pts = lastUs + 1
        lastUs = pts
        val info = MediaCodec.BufferInfo().apply { set(0, sample.size, pts, if (f.isIFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0) }
        muxer!!.writeSampleData(track, ByteBuffer.wrap(sample), info)
        frames++
    }

    private var lastAudioUs = -1L

    /** Audio AAC con ADTS: antes de arrancar solo se aprende el formato; después se escriben las tramas sin la cabecera. */
    @Synchronized
    fun writeAudio(f: AVFrame) {
        if (closed) return
        val data = f.frmData ?: return
        val len = if (f.frmSize in 1..data.size) f.frmSize else data.size
        val first = AudioDecoder.adts(data, len) ?: return
        val m = muxer
        if (m == null) { audioCfg = first; return }
        if (audioTrack < 0 || startUs < 0) return
        var off = 0
        while (off < len) {
            val h = AudioDecoder.adts(data, len, off) ?: break
            val end = if (h.frameLen in (h.headerLen + 1)..(len - off)) off + h.frameLen else len
            var pts = System.nanoTime() / 1000 - startUs
            if (pts <= lastAudioUs) pts = lastAudioUs + 1
            lastAudioUs = pts
            val info = MediaCodec.BufferInfo().apply { set(0, end - off - h.headerLen, pts, 0) }
            m.writeSampleData(audioTrack, ByteBuffer.wrap(data, off + h.headerLen, end - off - h.headerLen).slice(), info)
            off = end
        }
    }

    private fun ptsNow(): Long {
        val now = System.nanoTime() / 1000
        if (startUs < 0) startUs = now
        return now - startUs
    }

    /** true si quedó un MP4 válido. */
    @Synchronized
    fun stop(): Boolean {
        closed = true
        val m = muxer ?: run { file.delete(); return false }
        val ok = runCatching { m.stop() }.isSuccess && frames > 0
        runCatching { m.release() }
        muxer = null
        if (!ok) file.delete()
        return ok
    }

    private fun type(r: IntRange, d: ByteArray, hevc: Boolean): Int {
        val b = d[r.first].toInt() and 0xFF
        return if (hevc) (b shr 1) and 0x3F else b and 0x1F
    }
    private fun isParam(r: IntRange, d: ByteArray, hevc: Boolean) = type(r, d, hevc).let { if (hevc) it in 32..34 else it == 7 || it == 8 }
    private fun isSei(r: IntRange, d: ByteArray, hevc: Boolean) = type(r, d, hevc).let { if (hevc) it == 39 || it == 40 else it == 6 }

    private fun withStart(d: ByteArray, r: IntRange) = byteArrayOf(0, 0, 0, 1) + d.copyOfRange(r.first, r.last + 1)

    companion object {
        /** Rangos de cada NAL (sin el código de inicio) dentro de un buffer Annex-B. */
        fun splitNals(d: ByteArray, len: Int): List<IntRange> {
            val starts = ArrayList<Pair<Int, Int>>() // (inicio del código, inicio de la NAL)
            var i = 0
            while (i + 3 <= len) {
                if (d[i].toInt() == 0 && d[i + 1].toInt() == 0) {
                    if (d[i + 2].toInt() == 1) { starts += i to i + 3; i += 3; continue }
                    if (i + 4 <= len && d[i + 2].toInt() == 0 && d[i + 3].toInt() == 1) { starts += i to i + 4; i += 4; continue }
                }
                i++
            }
            return starts.mapIndexed { k, (_, s) -> s until (if (k + 1 < starts.size) starts[k + 1].first else len) }
                .filter { !it.isEmpty() }
        }
    }
}
