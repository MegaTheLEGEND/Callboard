package com.rvcs25.app

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns any audio file Android can decode (mp3, m4a, wav, ogg, flac, ...) into mono PCM16 files, streaming the
 * whole way (decoder -> resampler -> low-pass filters -> files), so memory use does not depend on the clip length.
 */
internal object AudioDecode {
    /** Longest clip accepted. Storage is about 8.6 MB per minute (see [Sounds]). */
    const val MAX_SECONDS = 3600

    private class LeWriter(f: File) : Closeable {
        private val out = BufferedOutputStream(FileOutputStream(f), 1 shl 16)
        fun write(s: Short) { out.write(s.toInt() and 0xff); out.write((s.toInt() shr 8) and 0xff) }
        override fun close() { out.close() }
    }

    /** Streaming linear-interpolation resampler (good enough for sound effects and speech). */
    private class Resampler(private val from: Int, to: Int, private val out: (Short) -> Unit) {
        private val same = from == to
        private val step = from.toDouble() / to
        private var p = 0.0       // input position of the next output sample
        private var n = 0L        // number of input samples seen
        private var prev = 0
        fun push(s: Int) {
            if (same) { out(s.toShort()); return }
            if (n == 0L) {
                prev = s; n = 1
                while (p <= 0.0) { out(s.toShort()); p += step }
                return
            }
            val idx = n
            while (p <= idx) {
                val f = (p - (idx - 1)).toFloat()
                out((prev + (s - prev) * f).toInt().toShort())
                p += step
            }
            prev = s; n++
        }
    }

    /** Windowed-sinc low-pass design shared by the streaming and the one-shot decimators. */
    private fun lowpass(factor: Int): FloatArray {
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
        return FloatArray(taps) { (raw[it] / sum).toFloat() }
    }

    /** Streaming low-pass + keep every [factor]-th sample (48k -> 16k is 3, 16k -> 8k is 2), so phone-line clips don't alias. */
    private class Decimator(private val factor: Int, private val out: (Short) -> Unit) {
        private val half = factor * 4
        private val taps = 2 * half + 1
        private val h = lowpass(factor)
        private val hist = ShortArray(taps)
        private var count = 0L
        private var next = 0L          // input index at the centre of the next output
        fun push(s: Short) {
            hist[(count % taps).toInt()] = s
            count++
            while (next + half < count) {
                var acc = 0f
                for (k in 0 until taps) {
                    val j = next + k - half
                    if (j >= 0) acc += h[k] * hist[(j % taps).toInt()]
                }
                out(acc.toInt().coerceIn(-32768, 32767).toShort())
                next += factor
            }
        }
        /** Flushes the filter tail (a few samples of silence). */
        fun finish() { repeat(half) { push(0) } }
    }

    /**
     * Decodes [uri] and writes three mono files: [local] at 48 kHz, [u16] at 16 kHz and [u8] at 8 kHz (the latter two
     * already low-passed, ready for the call). Returns the number of 48 kHz samples. Throws [IOException] with a readable
     * message. Call off the main thread.
     */
    fun decodeToFiles(ctx: Context, uri: Uri, local: File, u16: File, u8: File): Long {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        val w48 = LeWriter(local)
        val w16 = LeWriter(u16)
        val w8 = LeWriter(u8)
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

            val dec8 = Decimator(2) { w8.write(it) }
            val dec16 = Decimator(3) { w16.write(it); dec8.push(it) }
            var total = 0L
            val maxSamples = Sounds.LOCAL_RATE.toLong() * MAX_SECONDS
            val emit: (Short) -> Unit = {
                w48.write(it)
                dec16.push(it)
                if (++total > maxSamples) throw IOException("clips are limited to ${MAX_SECONDS / 60} minutes")
            }

            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = maxOf(1, fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            var float = false
            var rs = Resampler(rate, Sounds.LOCAL_RATE, emit)
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
                    val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = maxOf(1, f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    if (newRate != rate) { rate = newRate; rs = Resampler(rate, Sounds.LOCAL_RATE, emit) }
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
                            rs.push((s / ch * 32767f).toInt().coerceIn(-32768, 32767))
                        }
                    } else {
                        val sb = buf.asShortBuffer()
                        val frames = sb.remaining() / ch
                        for (k in 0 until frames) {
                            var s = 0
                            for (j in 0 until ch) s += sb.get()
                            rs.push(s / ch)
                        }
                    }
                    c.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            dec16.finish()
            dec8.finish()
            return total
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            ex.release()
            runCatching { w48.close() }
            runCatching { w16.close() }
            runCatching { w8.close() }
        }
    }

    /** One-shot version of [Decimator], kept to upgrade clips stored by an older version (no 8 kHz file yet). */
    fun downsample(src: ShortArray, factor: Int): ShortArray {
        val half = factor * 4
        val taps = 2 * half + 1
        val h = lowpass(factor)
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
