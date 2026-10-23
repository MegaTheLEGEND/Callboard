package com.rvcs25.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import dev.callboard.IShell
import java.io.FileInputStream

/**
 * Plays a soundboard clip two ways at once:
 *  - into the call through the Shizuku helper's uplink injector (short clips are handed over whole with
 *    [IShell.play]; long clips are streamed in real time with [IShell.playStream], so any length works), or, while the
 *    voice changer is live, mixed into its uplink stream ([LiveVoice.mixClip]) so the two don't fight over the call;
 *  - on this phone, following the call's route: loudspeaker when speakerphone is on, otherwise the earpiece.
 */
object Player {
    @Volatile var playingId: String? = null; private set
    /** Short explanation of what the last tap did when it could not do everything (shown on the board). */
    @Volatile var note = ""; private set
    @Volatile var lastPlayAt = 0L; private set
    @Volatile var lastEndAt = 0L; private set
    /** When on, the clip you tap repeats until you press Stop or tap another pad. Not saved: it never starts the app looping. */
    @Volatile var loop = false

    private val lock = Any()
    @Volatile private var gen = 0
    @Volatile private var track: AudioTrack? = null

    /** Clips up to this long are sent to the helper in one piece; longer ones are streamed. */
    private const val WHOLE_CLIP_MAX_MS = 120_000

    private val HEADSET_TYPES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
    )

    /** True while a cellular call is connected (same test the helper uses before it opens the uplink). */
    fun inCall(ctx: Context): Boolean {
        val m = (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode
        return m == AudioManager.MODE_IN_CALL || (Build.VERSION.SDK_INT >= 33 && m == 5)
    }

    /** The call's microphone-mute flag (what the dialer's mute button sets). */
    fun micMuted(ctx: Context): Boolean = (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMicrophoneMute

    /** True while the call is on the loudspeaker (what the dialer's speaker button shows). */
    @Suppress("DEPRECATION")
    fun speakerphoneOn(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.isSpeakerphoneOn || am.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }

    fun play(ctx: Context, s: Sound) {
        val app = ctx.applicationContext
        val my = synchronized(lock) {
            gen++
            track?.let { runCatching { it.pause(); it.flush() } }
            playingId = s.id
            lastPlayAt = System.currentTimeMillis()
            gen
        }
        Thread({ run(app, s, my) }, "sound-play").start()
    }

    fun stop() {
        synchronized(lock) {
            gen++
            playingId = null
            track?.let { runCatching { it.pause(); it.flush() } }
        }
        LiveVoice.clearMix()
        // While the voice changer is live, svc.stop() would also end its uplink stream, so leave the helper alone.
        if (!LiveVoice.active) Thread { runCatching { RvcApp.link.service?.stop() } }.start()
    }

    private fun run(ctx: Context, s: Sound, my: Int) {
        try {
            note = ""
            do {
                val t0 = SystemClock.elapsedRealtime()
                try { sendToCall(ctx, s, my) } catch (t: Throwable) { note = "Couldn't send to the call: ${t.message}" }
                if (gen == my) playLocal(ctx, s, my)
                // Switching Loop off lets the current play-through finish; a pass that ended instantly never spins.
            } while (loop && gen == my && SystemClock.elapsedRealtime() - t0 > 200)
        } finally {
            synchronized(lock) { if (gen == my) playingId = null }
            lastEndAt = System.currentTimeMillis()
        }
    }

    private fun sendToCall(ctx: Context, s: Sound, my: Int) {
        val level = Settings.sbLevel / 100f
        if (!inCall(ctx)) { note = "No call detected, so this only played on this phone."; return }
        if (LiveVoice.active) {
            LiveVoice.mixClip(Sounds.openUplink(s.id, LiveVoice.rate, level))
            return
        }
        val svc = RvcApp.link.service
        if (svc == null) { note = "Shizuku helper not connected, so this only played on this phone."; return }
        val rate = if (Settings.hd16k) 16000 else 8000
        svc.setMethod(Settings.method)
        // Follow the user's "keep un-muted" setting (off by default): injection normally works while the call is muted,
        // and leaving the mute flag alone keeps Mute and the dialer in step.
        svc.setKeepUnmuted(Settings.keepUnmuted)
        if (s.durationMs <= WHOLE_CLIP_MAX_MS) sendWhole(svc, s, rate, level) else sendStream(svc, s, rate, level, my)
    }

    private fun sendWhole(svc: IShell, s: Sound, rate: Int, level: Float) {
        val pcm = Sounds.uplink(s.id, rate, level)
        val pipe = ParcelFileDescriptor.createPipe()
        try { svc.play(pipe[0], rate) } finally { pipe[0].close() }
        val out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        Thread({
            try {
                val bytes = ByteArray(pcm.size * 2)
                for (i in pcm.indices) {
                    val v = pcm[i].toInt()
                    bytes[2 * i] = v.toByte()
                    bytes[2 * i + 1] = (v shr 8).toByte()
                }
                out.use { it.write(bytes) }
            } catch (_: Throwable) { /* the helper closed the pipe: a newer clip or Stop replaced this one */ }
        }, "sound-uplink").start()
    }

    /** Long clips: paced in real time into the helper's live-stream input, read from disk, nothing held in memory. */
    private fun sendStream(svc: IShell, s: Sound, rate: Int, level: Float, my: Int) {
        val reader = Sounds.openUplink(s.id, rate, level)
        val pipe = ParcelFileDescriptor.createPipe()
        try { svc.playStream(pipe[0], rate) } catch (t: Throwable) { reader.close(); pipe[1].close(); throw t } finally { pipe[0].close() }
        val out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        Thread({
            val frame = rate / 50 // 20 ms
            val shorts = ShortArray(frame)
            val bytes = ByteArray(frame * 2)
            var next = SystemClock.elapsedRealtimeNanos()
            var tail = 6          // a few frames of silence after the clip so the helper plays its last buffered audio
            try {
                while (gen == my) {
                    val n = reader.read(shorts, frame)
                    if (n <= 0) { if (tail-- <= 0) break }
                    if (n < frame) shorts.fill(0, maxOf(n, 0), frame)
                    for (i in 0 until frame) {
                        val v = shorts[i].toInt()
                        bytes[2 * i] = v.toByte()
                        bytes[2 * i + 1] = (v shr 8).toByte()
                    }
                    out.write(bytes)
                    next += 20_000_000L
                    val wait = next - SystemClock.elapsedRealtimeNanos()
                    if (wait > 0) SystemClock.sleep(wait / 1_000_000)
                    else if (wait < -100_000_000L) next = SystemClock.elapsedRealtimeNanos()
                }
            } catch (_: Throwable) { /* pipe closed by the helper: Stop or a newer clip */ }
            finally { runCatching { out.close() }; reader.close() }
        }, "sound-stream").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private fun playLocal(ctx: Context, s: Sound, my: Int) {
        val t = buildTrack(ctx)
        if (t == null) { waitMs(s.durationMs, my); return }
        synchronized(lock) {
            if (gen != my) { t.release(); return }
            track = t
        }
        try {
            t.play()
            var written = 0L
            FileInputStream(Sounds.localFile(s.id)).use { fin ->
                val buf = ByteArray(9600) // 100 ms
                while (gen == my) {
                    val n = fin.read(buf)
                    if (n <= 0) break
                    var off = 0
                    while (off < n && gen == my) {
                        val w = t.write(buf, off, n - off)
                        if (w <= 0) break
                        off += w
                    }
                    written += off / 2
                }
            }
            var waited = 0
            while (gen == my && t.playbackHeadPosition < written && waited < 3000) { SystemClock.sleep(20); waited += 20 }
        } finally {
            synchronized(lock) { if (track === t) track = null }
            runCatching { t.stop() }
            t.release()
        }
    }

    private fun waitMs(ms: Int, my: Int) {
        val end = SystemClock.elapsedRealtime() + ms
        while (gen == my && SystemClock.elapsedRealtime() < end) SystemClock.sleep(30)
    }

    private fun buildTrack(ctx: Context): AudioTrack? = runCatching {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(Sounds.LOCAL_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val min = AudioTrack.getMinBufferSize(Sounds.LOCAL_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(min * 2, 19200)).setTransferMode(AudioTrack.MODE_STREAM).build()
        // Follow the call's route: loudspeaker when speakerphone is on (or no call), earpiece otherwise.
        // Headphones / Bluetooth are left alone.
        val outs = (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (outs.none { it.type in HEADSET_TYPES }) {
            val want = if (!inCall(ctx) || speakerphoneOn(ctx)) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            outs.firstOrNull { it.type == want }?.let { t.setPreferredDevice(it) }
        }
        t
    }.getOrNull()
}
