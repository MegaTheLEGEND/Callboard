package dev.callboard

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import dev.callboard.shell.ShellService
import rikka.shizuku.Shizuku

/**
 * Keeps the Shizuku UserService (our [ShellService], running as uid 2000) bound for the app's lifetime.
 * Condensed from Voice SIP Agent's ShizukuConnector.
 */
class ShizukuLink(context: Context) {
    enum class State { SHIZUKU_NOT_RUNNING, PERMISSION_REQUIRED, PERMISSION_DENIED, BINDING, CONNECTED, ERROR }

    private val app = context.applicationContext
    @Volatile var state = State.SHIZUKU_NOT_RUNNING; private set
    @Volatile var service: IShell? = null; private set
    @Volatile private var detail = ""
    private var conn: ServiceConnection? = null

    private val args = Shizuku.UserServiceArgs(ComponentName(app.packageName, ShellService::class.java.name))
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(false)
        .tag("callboard-shell")
        // Restart the shell process whenever a new APK is installed so it never runs stale code.
        .version((app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime / 1000 % Int.MAX_VALUE).toInt())

    private val binderReceived = Shizuku.OnBinderReceivedListener { check() }
    private val binderDead = Shizuku.OnBinderDeadListener { set(State.SHIZUKU_NOT_RUNNING, null, "Shizuku binder died") }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) bind() else set(State.PERMISSION_DENIED, null, "denied in Shizuku")
    }

    fun start() {
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
    }

    /** Shows Shizuku's permission dialog (or binds right away if already granted). */
    fun requestPermission() {
        try {
            if (!Shizuku.pingBinder()) return set(State.SHIZUKU_NOT_RUNNING, null, "start Shizuku first")
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bind()
            else Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: RuntimeException) {
            set(State.ERROR, null, "permission request failed: ${e.message}")
        }
    }

    /** Human-readable summary for the UI; makes a Binder call, so keep it off the main thread. */
    fun describe(): String {
        val head = "Shizuku: ${state.name.lowercase().replace('_', ' ')}" + if (detail.isEmpty()) "" else " ($detail)"
        val svc = service ?: return head
        return head + "\n" + runCatching { svc.status() }.getOrElse { "helper error: ${it.message}" }
    }

    @Synchronized
    private fun check() {
        try {
            if (!Shizuku.pingBinder()) return set(State.SHIZUKU_NOT_RUNNING, null, "start Shizuku first")
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) return set(State.ERROR, null, "Shizuku v13+ required")
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bind()
            else set(State.PERMISSION_REQUIRED, null, "tap Grant Shizuku")
        } catch (e: RuntimeException) {
            set(State.ERROR, null, "Shizuku check failed: ${e.message}")
        }
    }

    @Synchronized
    private fun bind() {
        if (state == State.BINDING) return
        if (state == State.CONNECTED && service?.asBinder()?.pingBinder() == true) return
        set(State.BINDING, null, "")
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
                if (binder == null || !binder.pingBinder()) return set(State.ERROR, null, "UserService binder dead")
                val svc = IShell.Stub.asInterface(binder)
                val uid = try { svc.uid } catch (e: Exception) { -1 }
                if (uid != 2000 && uid != 0) return set(State.ERROR, null, "unexpected helper uid $uid")
                set(State.CONNECTED, svc, "uid $uid")
            }

            override fun onServiceDisconnected(name: ComponentName) {
                conn = null
                set(State.ERROR, null, "helper disconnected")
            }
        }
        conn = c
        try { Shizuku.bindUserService(args, c) } catch (e: RuntimeException) { set(State.ERROR, null, "bind failed: ${e.message}") }
    }

    private fun set(s: State, svc: IShell?, why: String) {
        state = s; service = svc; detail = why
    }

    companion object { private const val REQUEST_CODE = 4101 }
}
