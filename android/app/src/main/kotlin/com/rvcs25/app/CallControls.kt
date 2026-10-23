package dev.callboard.shell

import android.content.Context
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

    fun endCall(ctx: Context): Boolean {
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (runCatching { tm?.endCall() }.getOrNull() == true) return true
        // Same thing the hardware End key does.
        return runCatching {
            val p = ProcessBuilder("input", "keyevent", "KEYCODE_ENDCALL").redirectErrorStream(true).start()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
    }

    private val PKG = Regex("""\bu\d+\s+([A-Za-z0-9_.]+)/""")

    private fun sh(cmd: String): String = runCatching {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()   // grep -m1 exits after the first hit, so this returns fast
        if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroy()
        out
    }.getOrDefault("")

    /** The package on screen right now, or "". Tries the resumed activity first, then the focused window. */
    fun foregroundPackage(): String {
        for (cmd in listOf(
            "dumpsys activity activities | grep -m1 topResumedActivity",
            "dumpsys window | grep -m1 mCurrentFocus",
        )) PKG.find(sh(cmd))?.let { return it.groupValues[1] }
        return ""
    }
}
