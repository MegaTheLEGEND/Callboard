package com.rvcs25.app

import android.content.Context
import android.media.AudioManager
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
    /** Why the helper mic could not be used (and the app mic took over), for the UI. */
    @Volatile var micNote = ""; private set
    /** Peak (0..32767) of the mic going in and of the audio going into the call since the UI last reset them. */
    @Volatile var inPeak = 0
    @Volatile var outPeak = 0
    @Volatile private var fx: ClassicFx? = null
    @Volatile private var dec: Decimator? = null
    @Volatile private var ring: ShortRing? = null
    private var appMic: AppMic? = null
    /** Loudest mic sample seen this session (the UI resets [inPeak], this one is only for the silence check). */
    @Volatile private var everPeak = 0
    /** True while the mic is captured by the Shizuku helper, false while by the app itself. */
    @Volatile private var micOnHelper = false
    /** True while the watchdog swaps the mic path, so the old path closing is not treated as a failure. */
    @Volatile private var swapping = false
    @Volatile private var session = 0

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
        micNote = ""; inPeak = 0; outPeak = 0
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
            // Auto-mute needs the helper to leave the platform mute flag alone.
            svc.setKeepUnmuted(if (Settings.muteOnLive) false else keepUnmuted)

            // Uplink: engine -> pipe -> helper Injector -> call. The helper paces and drops/pads as needed.
            val up = ParcelFileDescriptor.createPipe()
            try { svc.playStream(up[0], rate) } finally { up[0].close() }
            upOut = ParcelFileDescriptor.AutoCloseOutputStream(up[1])

            // Microphone. Android silences the phone's own mics while a call is up, so the chosen device (a Bluetooth
            // headset / watch) is what to record from. The setting picks which process records it first: the app
            // itself, or the Shizuku helper (which can also be pointed at the device). If that path delivers only
            // digital silence, the watchdog below tries the other one.
            val dev = AudioInputs.selected(ctx)
            // The mic reader threads check [active], so it must already be true when they start (else they quit at once).
            active = true
            everPeak = 0; swapping = false
            val mySession = ++session
            var micOk = false
            if (shellMic) {
                micOk = startShellMic(svc, unprocessed, dev)
                if (!micOk) micNote = (micNote.ifEmpty { "helper mic unavailable" }) + "; using the app mic"
            }
            if (!micOk) {
                err = startAppMic(ctx, classic, inputDevice, unprocessed)
                if (err.isNotEmpty()) {
                    // Last resort: the helper, recording from the chosen device.
                    if (shellMic || !startShellMic(svc, unprocessed, dev)) throw IllegalStateException("app mic: $err")
                    micNote = "app mic: $err; using the helper mic"
                }
            }
            startWatchdog(ctx.applicationContext, svc, classic, unprocessed, dev, mySession)

            active = true
            pump = Thread({ pumpLoop(rate) }, "uplink-pump").apply { priority = Thread.MAX_PRIORITY; start() }
            // Live voice changer = dialer muted (the far end hears the changed voice, not the raw mic).
            if (Settings.muteOnLive) runCatching { svc.setMicMuted(true) }
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
        var pk = 0
        for (i in 0 until n) { val v = kotlin.math.abs(buf[i].toInt()); if (v > pk) pk = v }
        if (pk > inPeak) inPeak = pk
        if (pk > everPeak) everPeak = pk
        if (classicMode) {
            val f = fx; val d = dec; val r = ring
            if (f != null && d != null && r != null) { f.process(buf, n); d.process(buf, n, r) }
        } else {
            NativeBridge.nativePushMic(buf, n)
        }
    }

    /** Starts the helper mic, on [dev] when one is chosen. True if it is running. */
    private fun startShellMic(svc: IShell, unprocessed: Boolean, dev: AudioInputs.Dev?): Boolean {
        val bt = dev?.viaComm == true
        val candidates = when {
            micSource != 0 -> listOf(micSource)
            bt -> listOf(6, 1)                                   // voice recognition, like RVC's input (7 is privacy-sensitive: muted in a call)
            else -> listOf(if (unprocessed) 9 else 1, 1).distinct()   // 9 = UNPROCESSED, 1 = MIC
        }
        for (src in candidates) {
            val pipe = ParcelFileDescriptor.createPipe()
            try {
                try {
                    if (dev != null) {
                        val note = svc.startMicOn(pipe[1], MIC_RATE, src, dev.type, dev.address)
                        if (note.isNotEmpty()) micNote = "helper mic: $note"
                    } else svc.startMic(pipe[1], MIC_RATE, src)
                } finally { pipe[1].close() }
            } catch (t: Throwable) {
                micNote = "helper mic (source $src): ${t.message}"
                runCatching { pipe[0].close() }
                continue
            }
            val input = ParcelFileDescriptor.AutoCloseInputStream(pipe[0])
            micIn = input
            micOnHelper = true
            micMode = "shell mic (source $src)" + if (dev != null) " on ${dev.label}" else ""
            micThread = Thread({ micLoop(input) }, "shell-mic-read").apply { priority = Thread.MAX_PRIORITY; start() }
            return true
        }
        return false
    }

    /** Starts the app's own mic (Oboe for RVC, AudioRecord for the classic changer). "" = started, otherwise why not. */
    private fun startAppMic(ctx: Context, classic: Boolean, inputDevice: Int, unprocessed: Boolean): String {
        val err: String
        if (classic) {
            val m = AppMic(ctx.applicationContext, inputDevice, unprocessed,
                { b, n -> onMic(b, n) }, { msg -> if (active && !swapping) fail(msg) })
            err = m.start()
            if (err.isEmpty()) appMic = m
        } else {
            err = NativeBridge.nativeStartAppMic(inputDevice, unprocessed)
        }
        if (err.isEmpty()) {
            micOnHelper = false
            micMode = if (AudioInputs.active.isNotEmpty()) "app mic: ${AudioInputs.active}" else if (classic) "app mic" else "app mic (Oboe)"
        }
        return err
    }

    /**
     * Digital silence (every sample exactly 0) for ~3.5 s means Android, or the call, is muting this capture path, not that
     * you are quiet. The classic changer then tries the other path once (helper <-> app); RVC's native mic can't be swapped
     * while running, so it only reports.
     */
    private fun startWatchdog(app: Context, svc: IShell, classic: Boolean, unprocessed: Boolean, dev: AudioInputs.Dev?, id: Int) {
        Thread({
            SystemClock.sleep(3500)
            if (!active || session != id || everPeak > 0) return@Thread
            val why = silenceReason(app)
            if (!classic) { micNote = "No microphone audio from the ${if (micOnHelper) "helper" else "app"} mic.$why"; return@Thread }
            val toHelper = !micOnHelper
            micNote = "No audio from the ${if (micOnHelper) "helper" else "app"} mic; trying the ${if (toHelper) "helper" else "app"} mic.$why"
            swapping = true
            try {
                if (micOnHelper) {
                    runCatching { svc.stopMic() }
                    runCatching { micIn?.close() }; micIn = null
                    micThread?.let { if (it !== Thread.currentThread()) runCatching { it.join(500) } }; micThread = null
                } else {
                    runCatching { appMic?.stop() }; appMic = null
                }
                val ok = if (toHelper) startShellMic(svc, unprocessed, dev) else startAppMic(app, true, 0, unprocessed).isEmpty()
                if (!ok) { micNote += " Couldn't start it."; return@Thread }
                if (!active || session != id) {            // the session ended while the new path was opening
                    runCatching { svc.stopMic() }; runCatching { appMic?.stop() }; appMic = null
                    return@Thread
                }
            } finally { swapping = false }
            SystemClock.sleep(3000)
            if (active && session == id && everPeak == 0) micNote = "No microphone audio from either path.$why"
        }, "mic-watchdog").apply { isDaemon = true; start() }
    }

    private fun silenceReason(app: Context): String {
        val am = app.getSystemService(AudioManager::class.java)
        return buildString {
            if (runCatching { am.isMicrophoneMute }.getOrDefault(false))
                append(" The call is muted, and some phones then silence the mic too: try turning off “Mute the dialer when a voice changer goes live” in Settings.")
            if (runCatching { am.activeRecordingConfigurations.any { it.isClientSilenced } }.getOrDefault(false))
                append(" Android reports a recording as silenced.")
        }
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
            if (active && !swapping) fail("mic stream ended: ${t.message}")
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
                var opk = 0
                for (i in 0 until n) {
                    val s = shorts[i].toInt()
                    val a = if (s < 0) -s else s
                    if (a > opk) opk = a
                    bytes[2 * i] = s.toByte()
                    bytes[2 * i + 1] = (s shr 8).toByte()
                }
                if (opk > outPeak) outPeak = opk
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
