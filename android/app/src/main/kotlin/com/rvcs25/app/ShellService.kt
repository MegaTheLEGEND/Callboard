package dev.callboard.shell

import android.content.Context
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import dev.callboard.IShell
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Shizuku UserService, instantiated by Shizuku inside an app_process running as shell (uid 2000).
 * Only the owning app may call in.
 */
class ShellService(context: Context) : IShell.Stub() {
    private val appUid = context.applicationInfo.uid
    private val ctx = ShellEnv.shellContext(context)
    private val injector = Injector(ctx)
    private val mic = MicCapture(ctx)
    private val seq = AtomicInteger()
    private val caps by lazy {
        runCatching { AudioCapabilities.inspect(ctx) }
            .map { "direct=${it.direct} redirect=${it.redirect}" + if (it.detail.isEmpty()) "" else " (${it.detail})" }
            .getOrElse { "capabilities unavailable: ${it.message}" }
    }

    private fun enforceCaller() {
        val caller = Binder.getCallingUid()
        if (caller != appUid && caller != Process.myUid()) throw SecurityException("caller uid $caller not allowed")
    }

    override fun destroy() {
        enforceCaller()
        mic.stop()
        injector.shutdown()
        exitProcess(0)
    }

    override fun getUid(): Int { enforceCaller(); return Process.myUid() }

    override fun play(pcm: ParcelFileDescriptor, rate: Int) {
        enforceCaller()
        require(rate == 8000 || rate == 16000) { "unsupported rate $rate" }
        val id = seq.incrementAndGet()
        // Read off the Binder thread; a newer play()/stop() invalidates this one.
        Thread({
            try {
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(pcm).use { it.readBytes() }
                if (id == seq.get()) {
                    val samples = ShortArray(bytes.size / 2) { ((bytes[2 * it + 1].toInt() shl 8) or (bytes[2 * it].toInt() and 0xff)).toShort() }
                    if (id == seq.get()) injector.play(samples, rate)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "play failed", t)
            }
        }, "pcm-read").start()
    }

    override fun stop() {
        enforceCaller()
        seq.incrementAndGet()
        injector.stop()
    }

    override fun playStream(pcm: ParcelFileDescriptor, rate: Int) {
        enforceCaller()
        require(rate == 8000 || rate == 16000) { "unsupported rate $rate" }
        seq.incrementAndGet() // cancels any clip still being read
        injector.playStream(ParcelFileDescriptor.AutoCloseInputStream(pcm), rate)
    }

    override fun endStream() {
        enforceCaller()
        injector.endStream()
    }

    override fun startMic(sink: ParcelFileDescriptor, rate: Int, source: Int) {
        enforceCaller()
        require(rate == 8000 || rate == 16000 || rate == 48000) { "unsupported mic rate $rate" }
        // Audio objects must not be built on a Binder thread (attribution would use the app's identity,
        // see Tap), so open the recorder on a plain thread and hand failures back as IllegalStateException.
        var failure: Throwable? = null
        val opener = Thread({
            try { mic.start(sink, rate, source) } catch (t: Throwable) { failure = t }
        }, "mic-open")
        opener.start()
        opener.join(5000)
        failure?.let { throw IllegalStateException(Tap.describe(it)) }
        check(!opener.isAlive) { "mic open timed out" }
    }

    override fun stopMic() {
        enforceCaller()
        mic.stop()
    }

    override fun setKeepUnmuted(on: Boolean) {
        enforceCaller()
        injector.keepUnmuted = on
    }

    override fun setMethod(method: Int) {
        enforceCaller()
        injector.method = method.coerceIn(0, 2)
    }

    override fun setWifiEnabled(on: Boolean): Boolean {
        enforceCaller()
        val token = Binder.clearCallingIdentity()
        try {
            val p = ProcessBuilder("svc", "wifi", if (on) "enable" else "disable").redirectErrorStream(true).start()
            if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroy(); return false }
            return p.exitValue() == 0
        } catch (t: Throwable) {
            Log.w(TAG, "setWifiEnabled($on) failed", t)
            return false
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    // Call controls. They run on a plain thread (like the mic), never on the Binder thread, so the audio and Telecom
    // services see the shell identity rather than the app's.
    private fun <T> onPlainThread(timeoutMs: Long = 4000, block: () -> T): T? {
        var result: T? = null
        val t = Thread({ result = runCatching(block).getOrNull() }, "call-control")
        t.start()
        t.join(timeoutMs)
        return result
    }

    override fun setMicMuted(on: Boolean): Boolean {
        enforceCaller()
        if (injector.userMute(on)) return true   // a clip / the voice is playing: apply the mute when it ends
        return onPlainThread { CallControls.setMuted(ctx, on) } ?: false
    }

    override fun endCall(): Boolean {
        enforceCaller()
        return onPlainThread(8000) { CallControls.endCall(ctx) } ?: false
    }

    override fun status(): String {
        enforceCaller()
        val token = Binder.clearCallingIdentity()
        try { return "${injector.status}\n$caps" } finally { Binder.restoreCallingIdentity(token) }
    }

    companion object { private const val TAG = "CallboardShell" }
}
