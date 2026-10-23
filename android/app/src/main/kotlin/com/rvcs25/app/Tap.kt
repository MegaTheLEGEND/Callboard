package dev.callboard.shell

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log

/**
 * Minimal cellular-call audio tap, distilled from Voice SIP Agent's CallAudioTap.
 *
 * - UPLINK  (what the far end hears): an AudioTrack that Android routes into the call instead of the mic.
 *   Android 13+: call-redirection (PSTN) track. Fallback: USAGE_VOICE_COMMUNICATION track pinned to TYPE_TELEPHONY.
 * - DOWNLINK (what the far end says): an AudioRecord that must be open for the route to work; we only drain it.
 *
 * Runs inside the Shizuku shell process, so the shell uid's CAPTURE_AUDIO_OUTPUT / MODIFY_PHONE_STATE /
 * CALL_AUDIO_INTERCEPTION grants are what make this possible. Never construct on a Binder thread.
 */
internal class Tap(private val ctx: Context, private val rate: Int, private val preferred: Int = 0) {
    private val frameBytes = rate / 50 * 2 // 20 ms mono PCM16
    private val audioManager by lazy { ShellEnv.audioManager(ctx) }

    @Volatile private lateinit var record: AudioRecord
    @Volatile private lateinit var track: AudioTrack
    @Volatile private var open = false
    @Volatile var method = "none"; private set

    fun inCall() = isCellularMode(audioManager.mode)

    @Volatile private var forcedUnmute = false
    /** What the mute flag should go back to when the tap closes (true = the user wants to be muted). */
    @Volatile private var wantMuted = false

    fun micMuted(): Boolean = runCatching { audioManager.isMicrophoneMute }.getOrDefault(false)

    /** Clears the platform mic-mute flag (the call's mute button sets it). Undone in [close]. */
    fun forceUnmute() {
        runCatching { audioManager.setMicrophoneMute(false); forcedUnmute = true; wantMuted = true }
    }

    /**
     * Mute / un-mute pressed in Callboard while audio is being injected: the flag has to stay clear so the injected audio
     * is heard, so just remember the wish and apply it when the tap closes.
     */
    fun setUserMute(on: Boolean) {
        wantMuted = on
        forcedUnmute = true
        runCatching { audioManager.setMicrophoneMute(false) }
    }

    fun start() {
        check(inCall()) { "not in a cellular call (audio mode=${audioManager.mode})" }
        val caps = AudioCapabilities.inspect(ctx)
        val failures = ArrayList<String>()
        var opened = false
        val plan = when (preferred) { 1 -> listOf(true); 2 -> listOf(false); else -> listOf(true, false) } // AUTO = original's order
        for (redirected in plan) {
            try {
                if (redirected) { check(caps.redirect) { caps.detail }; openRedirected() }
                else { check(caps.direct) { caps.detail }; openLegacy(); method = "direct" }
                opened = true
                break
            } catch (t: Throwable) {
                failures += "${if (redirected) "redirection" else "direct"}: ${Tap.describe(t)}"
                release()
            }
        }
        check(opened) { failures.joinToString("; ") }

        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "record did not start" }
        // Two frames of silence absorb scheduling jitter.
        check(track.write(ByteArray(frameBytes * 2), 0, frameBytes * 2, AudioTrack.WRITE_NON_BLOCKING) >= 0) { "prefill failed" }
        track.play()
        check(track.playState == AudioTrack.PLAYSTATE_PLAYING) { "track did not start" }
        open = true
        Log.i(TAG, "tap open method=$method rate=$rate")

        // Downlink is not used, but the record has to keep running for the call route to stay up.
        Thread({
            val b = ByteArray(frameBytes)
            while (open) {
                val n = try { record.read(b, 0, b.size, AudioRecord.READ_BLOCKING) } catch (_: Throwable) { -1 }
                if (n <= 0) break
            }
        }, "tap-drain").apply { isDaemon = true; start() }
    }

    fun diag(): String = if (!::track.isInitialized) "no track" else
        "micMute=${micMuted()} routed=${runCatching { track.routedDevice?.type }.getOrNull()} underruns=${runCatching { track.underrunCount }.getOrNull()} playState=${track.playState}"

    /** One 20 ms frame (blocking; paces the caller loosely). */
    fun write(bytes: ByteArray) {
        var off = 0
        while (off < bytes.size) {
            val w = track.write(bytes, off, bytes.size - off, AudioTrack.WRITE_BLOCKING)
            check(w > 0) { "track write $w" }
            off += w
        }
    }

    fun close() {
        open = false
        // Put the dialer's mute back so the real mic is not live while the UI still says "muted".
        if (forcedUnmute) {
            forcedUnmute = false
            if (wantMuted && runCatching { inCall() }.getOrDefault(false)) runCatching { audioManager.setMicrophoneMute(true) }
            wantMuted = false
        }
        if (::record.isInitialized) { runCatching { record.stop() } }
        if (::track.isInitialized) { runCatching { track.pause(); track.flush(); track.stop() } }
        release()
        Log.i(TAG, "tap closed")
    }

    private fun release() {
        if (::record.isInitialized) runCatching { record.release() }
        if (::track.isInitialized) runCatching { track.release() }
    }

    // ---- backends (ported from CallAudioTap) ----

    @SuppressLint("MissingPermission")
    private fun openRedirected() {
        check(ShellEnv.isPstnCallAudioInterceptable() != false) { "PSTN audio interception unavailable" }
        // Prefer the framework helpers when the process attribution matches the helper identity.
        val helperFailure = runCatching {
            check(ShellEnv.hasMatchingDefaultAudioAttribution(ctx)) { "default audio attribution mismatch" }
            record = AudioManager::class.java.getMethod("getCallDownlinkExtractionAudioRecord", AudioFormat::class.java)
                .invoke(audioManager, format(AudioFormat.CHANNEL_IN_MONO)) as AudioRecord
            track = AudioManager::class.java.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat::class.java)
                .invoke(audioManager, format(AudioFormat.CHANNEL_OUT_MONO)) as AudioTrack
            check(record.state == AudioRecord.STATE_INITIALIZED && track.state == AudioTrack.STATE_INITIALIZED)
        }.exceptionOrNull()
        if (helperFailure == null) { method = "redirection-helper"; return }
        release()

        // Otherwise build equivalent objects by hand. The attributes must match the helpers exactly:
        // without them the record falls back to the mic and the track to local media playback.
        val pstn = runCatching { AudioManager::class.java.getField("CALL_REDIRECT_PSTN").getInt(null) }.getOrDefault(1)
        val int = Int::class.javaPrimitiveType
        val recAttr = AudioAttributes.Builder().also {
            AudioAttributes.Builder::class.java.getMethod("setInternalCapturePreset", int)
                .invoke(it, MediaRecorder.AudioSource.VOICE_DOWNLINK)
        }.build()
        val rb = AudioRecord.Builder().setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO))
        setContext(rb)
        AudioRecord.Builder::class.java.getMethod("setAudioAttributes", AudioAttributes::class.java).invoke(rb, recAttr)
        AudioRecord.Builder::class.java.getMethod("setCallRedirectionMode", int).invoke(rb, pstn)
        record = rb.build()
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "PSTN record not initialized" }
            val trackAttr = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).also {
                AudioAttributes.Builder::class.java.getMethod("setSystemUsage", int).invoke(it, USAGE_CALL_ASSISTANT)
            }.build()
            val tb = AudioTrack.Builder().setAudioAttributes(trackAttr).setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO))
            setContext(tb)
            AudioTrack.Builder::class.java.getMethod("setCallRedirectionMode", int).invoke(tb, pstn)
            track = tb.build()
            check(track.state == AudioTrack.STATE_INITIALIZED) { "PSTN track not initialized" }
            method = "redirection-manual"
        } catch (t: Throwable) {
            release()
            throw t
        }
    }

    /** AirSIM-style fallback for devices without the official interception API. */
    @SuppressLint("MissingPermission")
    private fun openLegacy() {
        val minRec = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minRec > 0) { "record buffer size $minRec" }
        record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_DOWNLINK)
            .setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO)).setBufferSizeInBytes(maxOf(minRec, frameBytes * 10))
            .also(::setContext).build()
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "VOICE_DOWNLINK record not initialized" }
            val minTrack = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minTrack > 0) { "track buffer size $minTrack" }
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                .setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO))
                .setBufferSizeInBytes(maxOf(minTrack, frameBytes * 10))
                .setTransferMode(AudioTrack.MODE_STREAM).also(::setContext).build()
            check(track.state == AudioTrack.STATE_INITIALIZED) { "telephony track not initialized" }
            val tx = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            check(tx != null) { "TYPE_TELEPHONY output unavailable" }
            check(track.setPreferredDevice(tx)) { "telephony preferred-device request rejected" }
        } catch (t: Throwable) {
            release()
            throw t
        }
    }

    private fun setContext(builder: Any) {
        AudioBuilderContext.apply(builder, ctx, Context::class.java) { ShellEnv.prepareAudioAttribution(ctx) }
    }

    private fun format(mask: Int) = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(rate).setChannelMask(mask).build()

    private fun isCellularMode(mode: Int) = mode == AudioManager.MODE_IN_CALL || (Build.VERSION.SDK_INT >= 33 && mode == 5)

    companion object {
        private const val TAG = "CallboardTap"
        /** Hidden AudioAttributes.USAGE_CALL_ASSISTANT, as used by getCallUplinkInjectionAudioTrack. */
        private const val USAGE_CALL_ASSISTANT = 17

        fun describe(t: Throwable): String {
            var c = t
            while (c is java.lang.reflect.InvocationTargetException && c.cause != null) c = c.cause!!
            return "${c.javaClass.simpleName}: ${c.message}"
        }
    }
}
