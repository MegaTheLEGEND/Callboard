package com.rvcs25.app

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import dev.callboard.IShell
import dev.callboard.rvc.NativeBridge
import java.io.InputStream
import java.io.OutputStream

/**
 * Live call path:
 *
 *   mic ──► (Shizuku helper mic pipe, or Oboe app mic) ──► nativePushMic ──► voice engine
 *   voice engine ──► nativePullUplink (every 20 ms) ──► pipe ──► helper (uid 2000) Injector ──► call uplink
 *
 * While the helper's tap is open the far end hears only the converted voice, not your raw mic.
 * The call must already be connected when [start] runs (the helper refuses to open the tap otherwise).
 */
object LiveVoice {
    @Volatile var active = false; private set
    @Volatile var micMode = ""; private set
    /** 0 auto, 1 redirection only, 2 direct only (see IShell.setMethod). */
    @Volatile var method = 0
    /** Stay audible when the call is muted in the dialer (the helper clears the platform mic mute). */
    @Volatile var keepUnmuted = false
    /** 0 = automatic (raw switch picks UNPROCESSED/MIC), otherwise a MediaRecorder.AudioSource id for the helper mic. */
    @Volatile var micSource = 0
    /** Uplink rate (8000 / 16000) of the running live session; soundboard clips are converted to this. */
    @Volatile var rate = 8000; private set
    /** True while the classic (modulation) voice changer feeds the call instead of the RVC engine. */
    @Volatile var classicMode = false; private set
    @Volatile private var fx: ClassicFx? = null
    @Volatile private var dec: Decimator? = null
    @Volatile private var ring: ShortRing? = null
    private var appMic: AppMic? = null

    private val mixLock = Any()
    private var mixSrc: PcmReader? = null
    private val mixBuf = ShortArray(960)

    /** Soundboard: adds the clip streamed from [src] (already at [rate], mono) on top of the live voice. Replaces a clip still playing. */
    fun mixClip(src: PcmReader) { synchronized(mixLock) { mixSrc?.close(); mixSrc = src } }
    fun clearMix() { synchronized(mixLock) { mixSrc?.close(); mixSrc = null } }

    private fun mixInto(buf: ShortArray, n: Int) {
        synchronized(mixLock) {
            val src = mixSrc ?: return
            val got = src.read(mixBuf, minOf(n, mixBuf.size))
            for (i in 0 until got) buf[i] = (buf[i] + mixBuf[i]).coerceIn(-32768, 32767).toShort()
            if (got < n) { src.close(); mixSrc = null }
        }
    }

    private var upOut: OutputStream? = null
    private var micIn: InputStream? = null
    private var pump: Thread? = null
    private var micThread: Thread? = null
    private var appCtx: Context? = null

    /** RVC live call. Returns "" on success, otherwise a message. */
    @Synchronized
    fun start(ctx: Context, slackMs: Int, rate: Int, shellMic: Boolean, unprocessed: Boolean, inputDevice: Int = 0): String =
        begin(false, ctx, slackMs, rate, shellMic, unprocessed, inputDevice)

    /** Classic (modulation) voice changer on the call: same mic and uplink path, effects run here instead of the RVC engine. */
    @Synchronized
    fun startClassic(ctx: Context, rate: Int, shellMic: Boolean, unprocessed: Boolean, inputDevice: Int = 0): String =
        begin(true, ctx, 0, rate, shellMic, unprocessed, inputDevice)

    private fun begin(classic: Boolean, ctx: Context, slackMs: Int, rate: Int, shellMic: Boolean, unprocessed: Boolean, inputDevice: Int): String {
        stop()
        this.rate = rate
        clearMix()
        appCtx = ctx.applicationContext
        val svc: IShell = RvcApp.link.service ?: return "Shizuku helper not connected (start Shizuku, tap Grant Shizuku)"
        var err = ""
        if (classic) {
            ClassicPresets.refresh()
            classicMode = true
            fx = ClassicFx()
            dec = Decimator(48000 / rate, rate)
            ring = ShortRing(rate / 5)
        } else {
            err = NativeBridge.nativeStartLive(slackMs, rate)
            if (err.isNotEmpty()) return err
        }
        try {
            svc.setMethod(method)
            svc.setKeepUnmuted(keepUnmuted)

            // Uplink: engine -> pipe -> helper Injector -> call. The helper paces and drops/pads as needed.
            val up = ParcelFileDescriptor.createPipe()
            try { svc.playStream(up[0], rate) } finally { up[0].close() }
            upOut = ParcelFileDescriptor.AutoCloseOutputStream(up[1])

            // Microphone: prefer the helper (a normal app's mic can be silenced during a cellular call).
            // A specific input device (Bluetooth, USB, ...) can only be captured by the app's own mic path: the
            // helper's recorder cannot be pointed at a device.
            var shellOk = false
            if (shellMic && inputDevice == 0) shellOk = startShellMic(svc, unprocessed)
            if (!shellOk) {
                if (classic) {
                    val m = AppMic(ctx.applicationContext, inputDevice, unprocessed,
                        { b, n -> onMic(b, n) }, { msg -> if (active) fail(msg) })
                    err = m.start()
                    if (err.isNotEmpty()) throw IllegalStateException("app mic: $err")
                    appMic = m
                } else {
                    err = NativeBridge.nativeStartAppMic(inputDevice, unprocessed)
                    if (err.isNotEmpty()) throw IllegalStateException("app mic: $err")
                }
                micMode = if (AudioInputs.active.isNotEmpty()) "app mic: ${AudioInputs.active}" else if (classic) "app mic" else "app mic (Oboe)"
            }

            active = true
            pump = Thread({ pumpLoop(rate) }, "uplink-pump").apply { priority = Thread.MAX_PRIORITY; start() }
            return ""
        } catch (t: Throwable) {
            val helper = runCatching { svc.status() }.getOrDefault("")
            teardown()
            return "live start failed: ${t.message}" + if (helper.isEmpty()) "" else "\n$helper"
        }
    }

    @Synchronized
    fun stop() {
        if (!active && upOut == null && micIn == null && appMic == null) {
            runCatching { NativeBridge.nativeStop() }
            return
        }
        teardown()
    }

    private fun teardown() {
        val wasClassic = classicMode
        active = false
        micMode = ""
        clearMix()
        val svc = RvcApp.link.service
        runCatching { svc?.stopMic() }
        runCatching { svc?.endStream() }
        runCatching { svc?.stop() }
        runCatching { upOut?.close() }
        runCatching { micIn?.close() }
        upOut = null
        micIn = null
        runCatching { appMic?.stop() }
        appMic = null
        for (t in listOf(pump, micThread)) if (t != null && t !== Thread.currentThread()) runCatching { t.join(300) }
        pump = null
        micThread = null
        if (wasClassic) {
            classicMode = false
            fx = null; dec = null; ring = null
        } else {
            runCatching { NativeBridge.nativeStop() }
        }
    }

    /** One 20 ms frame of 48 kHz mic audio, from either the helper mic pipe or the app mic. */
    private fun onMic(buf: ShortArray, n: Int) {
        if (classicMode) {
            val f = fx; val d = dec; val r = ring
            if (f != null && d != null && r != null) { f.process(buf, n); d.process(buf, n, r) }
        } else {
            NativeBridge.nativePushMic(buf, n)
        }
    }

    private fun startShellMic(svc: IShell, unprocessed: Boolean): Boolean {
        val candidates = if (micSource != 0) listOf(micSource) else listOf(if (unprocessed) 9 else 1, 1).distinct()
        for (src in candidates) { // 9 = UNPROCESSED, 1 = MIC
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                try { svc.startMic(pipe[1], MIC_RATE, src) } finally { pipe[1].close() }
            } catch (t: Throwable) {
                runCatching { pipe[0].close() }
                continue
            }
            val input = ParcelFileDescriptor.AutoCloseInputStream(pipe[0])
            micIn = input
            micMode = "shell mic (source $src)"
            micThread = Thread({ micLoop(input) }, "shell-mic-read").apply { priority = Thread.MAX_PRIORITY; start() }
            return true
        }
        return false
    }

    private fun micLoop(input: InputStream) {
        val frame = MIC_RATE / 50                // 20 ms, matches MicCapture's frames
        val bytes = ByteArray(frame * 2)
        val shorts = ShortArray(frame)
        try {
            while (active) {
                var off = 0
                while (off < bytes.size) {
                    val n = input.read(bytes, off, bytes.size - off)
                    if (n < 0) throw java.io.IOException("helper mic closed")
                    off += n
                }
                for (i in 0 until frame) shorts[i] = ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xff)).toShort()
                onMic(shorts, frame)
            }
        } catch (t: Throwable) {
            if (active) fail("mic stream ended: ${t.message}")
        }
    }

    private fun pumpLoop(rate: Int) {
        val n = rate / 50                        // 20 ms of uplink audio
        val shorts = ShortArray(n)
        val bytes = ByteArray(n * 2)
        val r = if (classicMode) ring else null
        if (r != null) {
            // Let the effect chain get ~40 ms ahead first so the uplink does not start with gaps.
            val t0 = SystemClock.elapsedRealtime()
            while (active && r.available() < n * 2 && SystemClock.elapsedRealtime() - t0 < 600) SystemClock.sleep(5)
        }
        var next = SystemClock.elapsedRealtimeNanos()
        try {
            while (active) {
                if (r != null) {
                    val got = r.read(shorts, n)
                    for (i in got until n) shorts[i] = 0
                } else {
                    NativeBridge.nativePullUplink(shorts, n)
                }
                mixInto(shorts, n)
                for (i in 0 until n) {
                    val s = shorts[i].toInt()
                    bytes[2 * i] = s.toByte()
                    bytes[2 * i + 1] = (s shr 8).toByte()
                }
                upOut?.write(bytes) ?: break
                next += 20_000_000L
                val wait = next - SystemClock.elapsedRealtimeNanos()
                if (wait > 0) SystemClock.sleep(wait / 1_000_000)
                else if (wait < -100_000_000L) next = SystemClock.elapsedRealtimeNanos()
            }
        } catch (t: Throwable) {
            if (active) {
                val helper = runCatching { RvcApp.link.service?.status() }.getOrNull().orEmpty()
                fail("uplink closed: ${t.message}" + if (helper.isEmpty()) "" else "\n$helper")
            }
        }
    }

    /** Called from the worker threads: remember why, then stop through the service so the UI follows. */
    private fun fail(msg: String) {
        active = false
        VoiceService.lastError = msg
        appCtx?.let { VoiceService.stop(it) }
    }

    private const val MIC_RATE = 48000
}
