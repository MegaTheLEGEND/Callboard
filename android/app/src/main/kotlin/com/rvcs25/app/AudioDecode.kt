package com.rvcs25.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Turns any audio file Android can decode (mp3, m4a, wav, ogg, flac, ...) into mono PCM16 samples. */
internal object AudioDecode {
    private const val MAX_SECONDS = 300

    private class ShortSink {
        var a = ShortArray(1 shl 18)
        var size = 0
        fun add(v: Int) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = v.toShort()
        }
        fun toArray() = a.copyOf(size)
    }

    /** Decodes [uri] to mono 48 kHz. Throws [IOException] with a readable message. Call off the main thread. */
    fun decodeMono48k(ctx: Context, uri: Uri): ShortArray {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try { ex.setDataSource(ctx, uri, null) } catch (e: Exception) { throw IOException("can't open the file") }
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) throw IOException("no audio found in that file")
            ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val c = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) { throw IOException("this phone can't decode $mime") }
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()

            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = maxOf(1, fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            var float = false
            val sink = ShortSink()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (!inDone) {
                    val ii = c.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = maxOf(1, f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    val buf = c.getOutputBuffer(oi)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    buf.order(ByteOrder.nativeOrder())
                    if (float) {
                        val fb = buf.asFloatBuffer()
                        val frames = fb.remaining() / ch
                        for (k in 0 until frames) {
                            var s = 0f
                            for (j in 0 until ch) s += fb.get()
                            sink.add((s / ch * 32767f).toInt().coerceIn(-32768, 32767))
                        }
                    } else {
                        val sb = buf.asShortBuffer()
                        val frames = sb.remaining() / ch
                        for (k in 0 until frames) {
                            var s = 0
                            for (j in 0 until ch) s += sb.get()
                            sink.add(s / ch)
                        }
                    }
                    c.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    if (sink.size > rate.toLong() * MAX_SECONDS) throw IOException("clips are limited to 5 minutes")
                }
            }
            return resample(sink.toArray(), rate, Sounds.LOCAL_RATE)
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            ex.release()
        }
    }

    /** Linear-interpolation resampler; good enough for sound effects and speech. */
    fun resample(src: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || src.isEmpty()) return src
        val n = (src.size.toLong() * to / from).toInt()
        val out = ShortArray(n)
        val step = from.toDouble() / to
        var pos = 0.0
        for (i in 0 until n) {
            val i0 = pos.toInt()
            val f = (pos - i0).toFloat()
            val a = src[minOf(i0, src.size - 1)].toInt()
            val b = src[minOf(i0 + 1, src.size - 1)].toInt()
            out[i] = (a + (b - a) * f).toInt().toShort()
            pos += step
        }
        return out
    }

    /**
     * Low-pass (windowed sinc) then keep every [factor]-th sample. Used for 48 kHz -> 16 kHz (3) and
     * 16 kHz -> 8 kHz (2), so phone-line clips don't alias.
     */
    fun downsample(src: ShortArray, factor: Int): ShortArray {
        val half = factor * 4
        val taps = 2 * half + 1
        val fc = 0.45 / factor                    // cutoff, cycles per input sample
        val raw = DoubleArray(taps)
        var sum = 0.0
        for (k in 0 until taps) {
            val m = k - half
            val sinc = if (m == 0) 2 * fc else sin(2 * PI * fc * m) / (PI * m)
            val w = 0.5 * (1 + cos(PI * m / (half + 1)))
            raw[k] = sinc * w
            sum += raw[k]
        }
        val h = FloatArray(taps) { (raw[it] / sum).toFloat() }
        val n = src.size / factor
        val out = ShortArray(n)
        for (i in 0 until n) {
            val c = i * factor
            var acc = 0f
            for (k in 0 until taps) {
                val j = c + k - half
                if (j >= 0 && j < src.size) acc += h[k] * src[j]
            }
            out[i] = acc.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}
