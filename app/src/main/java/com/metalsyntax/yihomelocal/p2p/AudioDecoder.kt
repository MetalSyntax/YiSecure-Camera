package com.metalsyntax.yihomelocal.p2p

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.tutk.IOTC.AVFrame

/** Audio de la cámara: AAC (ADTS o crudo) con el decodificador de Android, o PCM directo. Reemplaza DecodeAAC/MobileAEC de Yi. */
class AudioDecoder {
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var key = ""

    @Synchronized
    fun feed(f: AVFrame) {
        val data = f.frmData ?: return
        val len = if (f.frmSize in 1..data.size) f.frmSize else data.size
        val rate = AVFrame.getSamplerate(f.flags).takeIf { it > 0 } ?: 16000
        when (f.codecId.toInt()) {
            AVFrame.MEDIA_CODEC_AUDIO_PCM -> play(data, 0, len, rate)
            AVFrame.MEDIA_CODEC_AUDIO_AAC -> aac(data, len, rate)
        }
    }

    private fun aac(data: ByteArray, len: Int, fallbackRate: Int) {
        val adts = len > 7 && data[0] == 0xFF.toByte() && (data[1].toInt() and 0xF0) == 0xF0
        val rate = if (adts) ADTS_RATES.getOrElse((data[2].toInt() shr 2) and 0xF) { fallbackRate } else fallbackRate
        val k = "aac $rate $adts"
        if (k != key) {
            release()
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1)
                if (adts) fmt.setInteger(MediaFormat.KEY_IS_ADTS, 1)
                else {
                    // AudioSpecificConfig de AAC-LC mono para la frecuencia indicada por el frame
                    val idx = ADTS_RATES.indexOf(rate).coerceAtLeast(0)
                    fmt.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(byteArrayOf(((2 shl 3) or (idx shr 1)).toByte(), (((idx and 1) shl 7) or (1 shl 3)).toByte())))
                    fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                }
                configure(fmt, null, null, 0)
                start()
            }
            key = k
        }
        val c = codec ?: return
        val i = c.dequeueInputBuffer(10_000)
        if (i >= 0) {
            c.getInputBuffer(i)!!.apply { clear(); put(data, 0, len) }
            c.queueInputBuffer(i, 0, len, System.nanoTime() / 1000, 0)
        }
        val info = MediaCodec.BufferInfo()
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (o < 0) break
            val buf = c.getOutputBuffer(o)!!
            val pcm = ByteArray(info.size).also { buf.position(info.offset); buf.get(it) }
            val outRate = c.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            c.releaseOutputBuffer(o, false)
            play(pcm, 0, pcm.size, outRate)
        }
    }

    private fun play(pcm: ByteArray, off: Int, len: Int, rate: Int) {
        val t = track?.takeIf { it.sampleRate == rate } ?: run {
            track?.release()
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
                .build().also { it.play(); track = it }
        }
        t.write(pcm, off, len, AudioTrack.WRITE_NON_BLOCKING)
    }

    @Synchronized
    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { track?.release() }
        codec = null; track = null; key = ""
    }

    private companion object {
        val ADTS_RATES = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    }
}
