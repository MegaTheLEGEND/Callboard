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
import java.io.FileInputStream

/**
 * Plays a soundboard clip two ways at once:
 *  - into the call: through the Shizuku helper's uplink injector ([dev.callboard.IShell.play]), or, while the voice
 *    changer is live, mixed into its uplink stream ([LiveVoice.mixClip]) so the two don't fight over the call;
 *  - on the phone's loudspeaker (Settings.sbSpeaker).
 */
object Player {
    @Volatile var playingId: String? = null; private set
    /** Short explanation of what the last tap did when it could not do everything (shown on the board). */
    @Volatile var note = ""; private set
    @Volatile var lastPlayAt = 0L; private set

    private val lock = Any()
    @Volatile private var gen = 0
    @Volatile private var track: AudioTrack? = null

    private val HEADSET_TYPES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
    )

    /** True while a cellular call is connected (same test the helper uses before it opens the uplink). */
    fun inCall(ctx: Context): Boolean {
        val m = (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode
        return m == AudioManager.MODE_IN_CALL || (Build.VERSION.SDK_INT >= 33 && m == 5)
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
            try { sendToCall(ctx, s) } catch (t: Throwable) { note = "Couldn't send to the call: ${t.message}" }
            if (gen == my) playLocal(ctx, s, my)
        } finally {
            synchronized(lock) { if (gen == my) playingId = null }
        }
    }

    private fun sendToCall(ctx: Context, s: Sound) {
        val level = Settings.sbLevel / 100f
        if (!inCall(ctx)) { note = "No call detected, so this played on the speaker only."; return }
        if (LiveVoice.active) {
            LiveVoice.mixClip(Sounds.uplink(s.id, LiveVoice.rate, level))
            return
        }
        val svc = RvcApp.link.service
        if (svc == null) { note = "Shizuku helper not connected, so this played on the speaker only."; return }
        val rate = if (Settings.hd16k) 16000 else 8000
        val pcm = Sounds.uplink(s.id, rate, level)
        svc.setMethod(Settings.method)
        svc.setKeepUnmuted(Settings.keepUnmuted)
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

    private fun playLocal(ctx: Context, s: Sound, my: Int) {
        val t = if (Settings.sbSpeaker) buildTrack(ctx) else null
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
        // During a call Android may route media to the earpiece; pin it to the loudspeaker unless headphones are in.
        val outs = (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (outs.none { it.type in HEADSET_TYPES })
            outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { t.setPreferredDevice(it) }
        t
    }.getOrNull()
}
