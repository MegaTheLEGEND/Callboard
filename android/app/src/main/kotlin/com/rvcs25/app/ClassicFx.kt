package com.rvcs25.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The "classic" voice changer: plain modulation effects on the mic signal, no AI model involved.
 * It never runs together with RVC (see [VoiceService.blocker]).
 */

/** One complete effect setting. */
data class FxParams(
    /** Pitch shift in semitones (-12..+12). 0 = off. */
    val pitch: Float = 0f,
    /** Ring modulation amount 0..100 (0 = off). Gives the metallic "robot" sound. */
    val ringMix: Int = 0,
    /** Ring modulator frequency in Hz. Low (30-80) = robot/dalek, higher = alien/bell-like. */
    val ringHz: Int = 50,
    /** Overdrive 0..100 (0 = off). */
    val distortion: Int = 0,
    /** Echo amount 0..100 (0 = off). */
    val echo: Int = 0,
    /** Band-limits the voice to a thin phone-line sound. */
    val phone: Boolean = false,
)

object ClassicPresets {
    class Preset(val name: String, val blurb: String, val p: FxParams)

    val ALL = listOf(
        Preset("Deep", "Lower, bigger voice", FxParams(pitch = -5f)),
        Preset("Chipmunk", "High and squeaky", FxParams(pitch = 8f)),
        Preset("Robot", "Metallic ring-modulated voice", FxParams(ringMix = 100, ringHz = 55)),
        Preset("Alien", "Warbly, a little higher, with a trailing echo", FxParams(pitch = 3f, ringMix = 45, ringHz = 140, echo = 15)),
        Preset("Demon", "Low, growly and distorted", FxParams(pitch = -8f, distortion = 40, echo = 20)),
        Preset("Telephone", "Thin, band-limited line", FxParams(distortion = 8, phone = true)),
    )

    /** Index of the "Custom" entry (just after the last preset). */
    val CUSTOM: Int get() = ALL.size

    /** What the running effect reads on every audio frame. Call [refresh] after changing anything in [Settings]. */
    @Volatile var active: FxParams = ALL[0].p
        private set

    fun custom() = FxParams(
        pitch = Settings.cPitch.toFloat(), ringMix = Settings.cRingMix, ringHz = Settings.cRingHz,
        distortion = Settings.cDist, echo = Settings.cEcho, phone = Settings.cPhone,
    )

    fun selected(): Int = Settings.classicPreset.coerceIn(0, CUSTOM)

    fun label(): String = ALL.getOrNull(selected())?.name ?: "Custom"

    fun refresh() {
        val i = selected()
        active = if (i >= ALL.size) custom() else ALL[i].p
    }
}

/** 2nd-order RBJ low-pass / high-pass. */
internal class Biquad(highPass: Boolean, fs: Float, f0: Float, q: Float = 0.7071f) {
    private val b0: Float
    private val b1: Float
    private val b2: Float
    private val a1: Float
    private val a2: Float
    private var z1 = 0f
    private var z2 = 0f

    init {
        val w = 2.0 * PI * f0 / fs
        val cw = cos(w)
        val alpha = sin(w) / (2.0 * q)
        val a0 = 1.0 + alpha
        val nb0: Double
        val nb1: Double
        val nb2: Double
        if (highPass) { nb0 = (1.0 + cw) / 2.0; nb1 = -(1.0 + cw); nb2 = (1.0 + cw) / 2.0 }
        else { nb0 = (1.0 - cw) / 2.0; nb1 = 1.0 - cw; nb2 = (1.0 - cw) / 2.0 }
        b0 = (nb0 / a0).toFloat(); b1 = (nb1 / a0).toFloat(); b2 = (nb2 / a0).toFloat()
        a1 = (-2.0 * cw / a0).toFloat(); a2 = ((1.0 - alpha) / a0).toFloat()
    }

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}

/**
 * The effect chain, 48 kHz mono PCM16 in place:
 *   mic gain -> pitch shift -> ring mod -> distortion -> telephone filter -> echo -> limiter
 * Each stage is skipped when its amount is 0, so a plain preset adds almost no delay (the pitch shifter adds ~16 ms).
 */
class ClassicFx(private val fs: Int = 48000) {
    private val win = 1536f                       // pitch-shifter grain length (32 ms)
    private val mask = 8191
    private val buf = FloatArray(mask + 1)
    private var wp = 0
    private var ph = 0f
    private var ringPh = 0.0
    private val echoLen = (fs * 0.18).toInt()
    private val echoBuf = FloatArray(echoLen)
    private var ep = 0
    private val hp = Biquad(true, fs.toFloat(), 320f)
    private val lp1 = Biquad(false, fs.toFloat(), 3300f)
    private val lp2 = Biquad(false, fs.toFloat(), 3300f)

    private fun tap(d: Float): Float {
        val pos = wp - d
        val i0 = floor(pos).toInt()
        val fr = pos - i0
        return buf[i0 and mask] * (1f - fr) + buf[(i0 + 1) and mask] * fr
    }

    fun process(pcm: ShortArray, n: Int) {
        val p = ClassicPresets.active
        val gain = 10f.pow(Settings.micGainDb / 20f)
        val shifting = abs(p.pitch) > 0.01f
        val ratio = 2f.pow(p.pitch / 12f)
        val step = (1f - ratio) / win
        val rm = p.ringMix / 100f
        val rInc = 2.0 * PI * p.ringHz / fs
        val drive = 1f + p.distortion / 100f * 14f
        val dNorm = 1f / tanh(drive)
        val ew = p.echo / 100f * 0.8f
        val twoPi = (2.0 * PI).toFloat()

        for (i in 0 until n) {
            var x = pcm[i] / 32768f * gain
            buf[wp] = x
            if (shifting) {
                // Two read taps sweeping through the delay line half a grain apart, cross-faded with a Hann window.
                val g1 = 0.5f - 0.5f * cos(twoPi * ph)
                val ph2 = if (ph < 0.5f) ph + 0.5f else ph - 0.5f
                x = tap(ph * win) * g1 + tap(ph2 * win) * (1f - g1)
                ph += step
                if (ph >= 1f) ph -= 1f else if (ph < 0f) ph += 1f
            }
            wp = (wp + 1) and mask

            if (rm > 0f) {
                ringPh += rInc
                if (ringPh > 2.0 * PI) ringPh -= 2.0 * PI
                x = x * (1f - rm) + x * sin(ringPh).toFloat() * rm
            }
            if (p.distortion > 0) x = tanh(x * drive) * dNorm
            if (p.phone) x = lp2.process(lp1.process(hp.process(x))) * 1.6f
            if (ew > 0f) {
                val e = echoBuf[ep]
                echoBuf[ep] = x + e * 0.38f
                ep++; if (ep >= echoLen) ep = 0
                x += e * ew
            }
            pcm[i] = (x.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
    }
}

/** 48 kHz -> 8 / 16 kHz (the call uplink rates): low-pass, then keep every k-th sample. */
internal class Decimator(private val k: Int, rate: Int) {
    private val a = Biquad(false, 48000f, rate * 0.42f)
    private val b = Biquad(false, 48000f, rate * 0.42f)
    private var c = 0
    private val tmp = ShortArray(1024)

    fun process(src: ShortArray, n: Int, out: ShortRing) {
        var m = 0
        for (i in 0 until n) {
            val y = b.process(a.process(src[i] / 32768f))
            if (++c >= k) {
                c = 0
                if (m < tmp.size) tmp[m++] = (y.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
        }
        out.write(tmp, m)
    }
}

/** Small thread-safe PCM16 FIFO. When full, the oldest samples are dropped so latency stays bounded. */
internal class ShortRing(private val cap: Int) {
    private val a = ShortArray(cap)
    private var r = 0
    private var w = 0
    private var size = 0

    @Synchronized fun write(src: ShortArray, n: Int) {
        for (i in 0 until n) {
            if (size == cap) { r = (r + 1) % cap; size-- }
            a[w] = src[i]; w = (w + 1) % cap; size++
        }
    }

    @Synchronized fun read(dst: ShortArray, n: Int): Int {
        val c = minOf(n, size)
        for (i in 0 until c) { dst[i] = a[r]; r = (r + 1) % cap }
        size -= c
        return c
    }

    @Synchronized fun available(): Int = size
    @Synchronized fun clear() { r = 0; w = 0; size = 0 }
}

/** The app's own microphone (AudioRecord, 48 kHz mono) delivering 20 ms frames to [onFrame] on a worker thread. */
internal class AppMic(
    private val ctx: Context,
    private val device: Int,
    private val unprocessed: Boolean,
    private val onFrame: (ShortArray, Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    @Volatile private var running = false
    private var rec: AudioRecord? = null
    private var thread: Thread? = null

    /** "" = started, otherwise why not. */
    @SuppressLint("MissingPermission")
    fun start(): String {
        val rate = 48000
        val frame = 960
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return "mic buffer size $min"
        // A Bluetooth mic only carries audio through the call link, so ask for the communication source first.
        val sources = when {
            AudioInputs.viaComm -> listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)
            unprocessed -> listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)
            else -> listOf(MediaRecorder.AudioSource.MIC)
        }
        var r: AudioRecord? = null
        var why = ""
        for (src in sources) {
            try {
                val x = AudioRecord.Builder().setAudioSource(src)
                    .setAudioFormat(
                        AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
                    )
                    .setBufferSizeInBytes(maxOf(min, frame * 2 * 6)).build()
                if (x.state == AudioRecord.STATE_INITIALIZED) { r = x; break } else x.release()
            } catch (t: Throwable) { why = t.message ?: "mic error" }
        }
        val rc = r ?: return "mic not available${if (why.isEmpty()) "" else ": $why"}"
        // The Bluetooth input can show up a moment after the link opens, so look it up again if activate() had no id.
        val devId = if (device != 0) device else if (AudioInputs.viaComm) AudioInputs.activeInputId(ctx) else 0
        if (devId != 0) {
            ctx.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.id == devId }?.let { rc.setPreferredDevice(it) }
        }
        try { rc.startRecording() } catch (t: Throwable) { rc.release(); return "mic start failed: ${t.message}" }
        if (rc.recordingState != AudioRecord.RECORDSTATE_RECORDING) { rc.release(); return "mic did not start" }
        rec = rc
        running = true
        thread = Thread({
            val shorts = ShortArray(frame)
            while (running) {
                val n = rc.read(shorts, 0, frame, AudioRecord.READ_BLOCKING)
                if (n <= 0) { if (running) onError("mic stream ended ($n)"); break }
                onFrame(shorts, n)
            }
        }, "classic-mic").apply { priority = Thread.MAX_PRIORITY; start() }
        return ""
    }

    fun stop() {
        running = false
        rec?.let { runCatching { it.stop() } }
        thread?.let { if (it !== Thread.currentThread()) runCatching { it.join(300) } }
        thread = null
        rec?.let { runCatching { it.release() } }
        rec = null
    }
}

/** Classic voice changer test: mic -> effects -> this phone's speaker / headphones (use headphones, or it will feed back). */
object ClassicLocal {
    private var mic: AppMic? = null
    private var track: AudioTrack? = null

    @Synchronized
    fun start(ctx: Context, device: Int, unprocessed: Boolean): String {
        stop()
        ClassicPresets.refresh()
        val app = ctx.applicationContext
        val fx = ClassicFx()
        val minOut = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(maxOf(minOut, 960 * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Throwable) { return "speaker output failed: ${e.message}" }
        val m = AppMic(app, device, unprocessed,
            { buf, n -> fx.process(buf, n); t.write(buf, 0, n) },
            { msg -> VoiceService.lastError = msg; VoiceService.stop(app) })
        val err = m.start()
        if (err.isNotEmpty()) { t.release(); return err }
        t.play()
        track = t; mic = m
        return ""
    }

    @Synchronized
    fun stop() {
        mic?.stop(); mic = null
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null
    }
}
