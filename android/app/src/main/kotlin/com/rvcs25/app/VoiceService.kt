package com.rvcs25.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.callboard.rvc.NativeBridge

/**
 * Foreground service that owns the audio session. Four modes, in two families that never run together:
 *  RVC:     "test": Oboe mic -> voice engine -> Oboe speaker (headphones)
 *           "live": mic -> voice engine -> phone-call uplink through the Shizuku helper ([LiveVoice])
 *  Classic: "classic_test": mic -> modulation effects -> speaker (headphones)
 *           "classic": mic -> modulation effects -> phone-call uplink ([LiveVoice.startClassic])
 * RvcActivity / ClassicActivity drive it through the start functions and [stop], and read [running] / [mode] / [lastError].
 * Every start function returns "" when it started, or the reason it did not (see [blocker]).
 */
class VoiceService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Thread {
                try {
                    runCatching { LiveVoice.stop() }
                    runCatching { ClassicLocal.stop() }
                    runCatching { NativeBridge.nativeStop() }
                    AudioInputs.release(applicationContext)
                    running = false
                    mode = ""
                } finally { stopping = false }
            }.start()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val m = intent?.getStringExtra(EXTRA_MODE).orEmpty()
        val classic = m.startsWith("classic")
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Voice", NotificationManager.IMPORTANCE_LOW))
        val target = if (classic) ClassicActivity::class.java else RvcActivity::class.java
        val open = PendingIntent.getActivity(this, 0, Intent(this, target), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle(when (m) {
                "live" -> "RVC live call active"
                "classic" -> "Classic voice changer live call active"
                "classic_test" -> "Classic voice changer active"
                else -> "RVC voice active"
            })
            .setContentText("Tap to open")
            .setSmallIcon(R.drawable.ic_stat_callboard)
            .setContentIntent(open).setOngoing(true).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        val slack = intent?.getIntExtra(EXTRA_SLACK, 40) ?: 40
        val raw = intent?.getBooleanExtra(EXTRA_RAW, true) ?: true
        val rate = intent?.getIntExtra(EXTRA_RATE, 8000) ?: 8000
        val shellMic = intent?.getBooleanExtra(EXTRA_SHELL_MIC, true) ?: true
        val app = applicationContext
        Thread({
            val err = try {
                val dev = AudioInputs.activate(app)                 // 0 = system default microphone
                val unprocessed = raw && !AudioInputs.viaComm       // a Bluetooth mic has no "unprocessed" mode
                when (m) {
                    "live" -> LiveVoice.start(app, slack, rate, shellMic, unprocessed, dev)
                    "classic" -> LiveVoice.startClassic(app, rate, shellMic, unprocessed, dev)
                    "classic_test" -> ClassicLocal.start(app, dev, unprocessed)
                    else -> NativeBridge.nativeStart(dev, 0, slack, unprocessed)
                }
            } catch (t: Throwable) { "native start failed: ${t.message}" }
            if (err.isNotEmpty()) AudioInputs.release(app)
            if (err.isEmpty()) {
                running = true
            } else {
                lastError = err
                running = false
                mode = ""
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, "voice-start").start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { LiveVoice.stop() }
        runCatching { ClassicLocal.stop() }
        runCatching { NativeBridge.nativeStop() }
        AudioInputs.release(applicationContext)
        running = false
        mode = ""
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "voice"
        private const val ACTION_STOP = "com.rvcs25.app.STOP_VOICE"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_SLACK = "slack"
        private const val EXTRA_RAW = "raw"
        private const val EXTRA_RATE = "rate"
        private const val EXTRA_SHELL_MIC = "shellmic"

        /** true from the moment Start is pressed until audio stops or fails to start. */
        @Volatile var running = false
        /** "test", "live", "classic_test", "classic" or "" */
        @Volatile var mode = ""
        @Volatile var lastError = ""
        /** true from Stop being pressed until the audio and the microphone are really released. */
        @Volatile var stopping = false

        /** The classic (modulation) voice changer is running. */
        val classicRunning: Boolean get() = running && mode.startsWith("classic")
        /** RVC is running (test or live). */
        val rvcRunning: Boolean get() = running && (mode == "test" || mode == "live")

        /**
         * RVC and the classic voice changer must never run at the same time (they share the microphone and the call
         * uplink). "" = [wantClassic] may start now, otherwise the reason it may not.
         */
        fun blocker(wantClassic: Boolean): String = when {
            stopping -> "Still stopping the previous voice changer. Try again in a second."
            wantClassic && rvcRunning -> "RVC is running. Stop it first: RVC and the classic voice changer can't run together."
            !wantClassic && classicRunning -> "The classic voice changer is running. Stop it first: RVC and the classic voice changer can't run together."
            else -> ""
        }

        private fun launch(ctx: Context, newMode: String, wantClassic: Boolean, fill: Intent.() -> Unit): String {
            val why = blocker(wantClassic)
            if (why.isNotEmpty()) return why
            lastError = ""; running = true; mode = newMode
            ctx.startForegroundService(Intent(ctx, VoiceService::class.java).putExtra(EXTRA_MODE, newMode).apply(fill))
            return ""
        }

        fun start(ctx: Context, slackMs: Int, unprocessedMic: Boolean): String =
            launch(ctx, "test", false) { putExtra(EXTRA_SLACK, slackMs); putExtra(EXTRA_RAW, unprocessedMic) }

        fun startLive(ctx: Context, slackMs: Int, uplinkRate: Int, shellMic: Boolean, unprocessedMic: Boolean): String =
            launch(ctx, "live", false) {
                putExtra(EXTRA_SLACK, slackMs); putExtra(EXTRA_RATE, uplinkRate)
                putExtra(EXTRA_SHELL_MIC, shellMic); putExtra(EXTRA_RAW, unprocessedMic)
            }

        /** Classic voice changer into the phone call. */
        fun startClassicLive(ctx: Context, uplinkRate: Int, shellMic: Boolean, unprocessedMic: Boolean): String =
            launch(ctx, "classic", true) {
                putExtra(EXTRA_RATE, uplinkRate); putExtra(EXTRA_SHELL_MIC, shellMic); putExtra(EXTRA_RAW, unprocessedMic)
            }

        /** Classic voice changer to this phone's speaker / headphones, to try a preset. */
        fun startClassicTest(ctx: Context, unprocessedMic: Boolean): String =
            launch(ctx, "classic_test", true) { putExtra(EXTRA_RAW, unprocessedMic) }

        fun stop(ctx: Context) {
            stopping = true
            running = false
            ctx.startService(Intent(ctx, VoiceService::class.java).setAction(ACTION_STOP))
        }
    }
}
