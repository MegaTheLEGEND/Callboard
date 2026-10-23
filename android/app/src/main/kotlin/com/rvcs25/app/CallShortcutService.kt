package com.rvcs25.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.telecom.TelecomManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import kotlin.math.hypot

/**
 * "Show app shortcut during calls": a draggable floating button with the app icon, shown while a call is active and
 * the dialer / in-call screen is what is on screen. Tapping it opens the app (the running voice changer's screen if
 * one is running).
 *
 * Call state comes from the audio mode (no extra permission). What is on screen is read through the Shizuku helper
 * once a second, and only while a call is active. Without the helper the button simply shows for the whole call.
 */
class CallShortcutService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var am: AudioManager
    private var bubble: View? = null
    private var lp: WindowManager.LayoutParams? = null
    @Volatile private var polling = false
    private var poller: Thread? = null

    private val modeListener = AudioManager.OnModeChangedListener { onMode(it) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        am = getSystemService(AudioManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Call shortcut", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Call shortcut is on")
            .setContentText("Shows a button over the dialer during calls")
            .setSmallIcon(R.drawable.ic_stat_callboard)
            .setContentIntent(open).setOngoing(true).build()
        startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        am.addOnModeChangedListener(mainExecutor, modeListener)
        onMode(am.mode)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.callShortcut || !android.provider.Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { am.removeOnModeChangedListener(modeListener) }
        polling = false
        poller = null
        hide()
        super.onDestroy()
    }

    private fun onMode(mode: Int) {
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) startPolling()
        else { polling = false; poller = null; main.post { hide() } }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        poller = Thread({
            val dialers = dialerPackages()
            while (polling) {
                val want = onDialer(dialers)
                main.post { if (polling) { if (!want) hide() else if (bubble == null) show() else applyLook() } }
                SystemClock.sleep(1000)
            }
        }, "call-shortcut").apply { isDaemon = true; start() }
    }

    private fun dialerPackages(): Set<String> {
        val s = mutableSetOf(
            "com.android.incallui", "com.samsung.android.incallui", "com.google.android.dialer",
            "com.android.dialer", "com.samsung.android.dialer",
        )
        val tm = getSystemService(TelecomManager::class.java)
        runCatching { tm.defaultDialerPackage?.let { s += it } }
        runCatching { tm.systemDialerPackage?.let { s += it } }
        return s
    }

    /** True when the dialer is on screen, or when that can't be told (no helper): then the button shows for the whole call. */
    private fun onDialer(dialers: Set<String>): Boolean {
        val svc = RvcApp.link.service ?: return true
        val top = runCatching { svc.foregroundPackage() }.getOrDefault("")
        return top.isEmpty() || top in dialers
    }

    // ------------------------------------------------------------------ the floating button
    private fun show() {
        if (bubble != null || !android.provider.Settings.canDrawOverlays(this)) return
        val size = dp(Settings.shortcutSize)
        val screen = wm.currentWindowMetrics.bounds
        val params = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (Settings.shortcutX >= 0) Settings.shortcutX else screen.width() - size - dp(8)
            y = if (Settings.shortcutY >= 0) Settings.shortcutY else screen.height() / 3
            x = x.coerceIn(0, maxOf(0, screen.width() - size)); y = y.coerceIn(0, maxOf(0, screen.height() - size))
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val v = ImageView(this).apply {
            setImageResource(R.drawable.ic_callboard_mark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Open ${getString(R.string.app_name)}"
            setOnClickListener { open() }
            setOnTouchListener(object : View.OnTouchListener {
                var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
                override fun onTouch(view: View, e: MotionEvent): Boolean {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY; moved = false }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = e.rawX - tx; val dy = e.rawY - ty
                            if (!moved && hypot(dx, dy) > slop) moved = true
                            if (moved && !Settings.shortcutLock) {
                                val b = wm.currentWindowMetrics.bounds
                                params.x = (sx + dx).toInt().coerceIn(0, maxOf(0, b.width() - params.width))
                                params.y = (sy + dy).toInt().coerceIn(0, maxOf(0, b.height() - params.height))
                                runCatching { wm.updateViewLayout(view, params) }
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            // a drag on a locked button does nothing (and is not a tap either)
                            if (moved) { if (!Settings.shortcutLock) { Settings.shortcutX = params.x; Settings.shortcutY = params.y } }
                            else view.performClick()
                        }
                    }
                    return true
                }
            })
        }
        runCatching { wm.addView(v, params) }.onSuccess { bubble = v; lp = params }
    }

    /** Applies a size change from Settings to the button that is already showing. */
    private fun applyLook() {
        val v = bubble ?: return
        val p = lp ?: return
        val size = dp(Settings.shortcutSize)
        if (p.width == size) return
        val b = wm.currentWindowMetrics.bounds
        p.width = size; p.height = size
        p.x = p.x.coerceIn(0, maxOf(0, b.width() - size)); p.y = p.y.coerceIn(0, maxOf(0, b.height() - size))
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun hide() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null; lp = null
    }

    /** Opens the screen of whatever voice changer is running, else the home screen. */
    private fun open() {
        val target = when {
            VoiceService.classicRunning -> ClassicActivity::class.java
            VoiceService.rvcRunning -> RvcActivity::class.java
            else -> MainActivity::class.java
        }
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    companion object {
        private const val CHANNEL = "shortcut"

        /** Starts or stops the service to match the setting and the "Display over other apps" permission. */
        fun sync(ctx: Context) {
            val app = ctx.applicationContext
            val i = Intent(app, CallShortcutService::class.java)
            if (Settings.callShortcut && android.provider.Settings.canDrawOverlays(app)) runCatching { app.startForegroundService(i) }
            else app.stopService(i)
        }
    }
}
