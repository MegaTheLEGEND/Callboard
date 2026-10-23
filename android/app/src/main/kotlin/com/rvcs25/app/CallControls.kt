package dev.callboard.shell

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.SystemClock
import android.telecom.TelecomManager
import java.util.concurrent.TimeUnit

/** In-call controls run as the shell user (through [ShellService]), which has the permissions a normal app lacks. */
internal object CallControls {
    /** Polls [check] for up to ~400 ms: the platform applies audio route / mute changes asynchronously. */
    private inline fun settled(check: () -> Boolean): Boolean {
        repeat(8) { if (check()) return true; SystemClock.sleep(50) }
        return check()
    }

    fun setMuted(ctx: Context, on: Boolean): Boolean {
        val am = ShellEnv.audioManager(ctx)
        am.setMicrophoneMute(on)
        return settled { am.isMicrophoneMute == on }
    }

    @Suppress("DEPRECATION")
    fun setSpeaker(ctx: Context, on: Boolean): Boolean {
        val am = ShellEnv.audioManager(ctx)
        fun isOn() = am.isSpeakerphoneOn || am.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        if (isOn() == on) return true
        // 1) the classic switch
        runCatching { am.setSpeakerphoneOn(on) }
        if (settled { isOn() == on }) return true
        // 2) Android 12+ communication device
        runCatching {
            val type = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val d = am.availableCommunicationDevices.firstOrNull { it.type == type }
            if (d != null) am.setCommunicationDevice(d) else if (!on) am.clearCommunicationDevice()
        }
        return settled { isOn() == on }
    }

    fun endCall(ctx: Context): Boolean {
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (runCatching { tm?.endCall() }.getOrNull() == true) return true
        // Same thing the hardware End key does.
        return runCatching {
            val p = ProcessBuilder("input", "keyevent", "KEYCODE_ENDCALL").redirectErrorStream(true).start()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
    }
}
