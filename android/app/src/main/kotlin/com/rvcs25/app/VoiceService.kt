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
 * Foreground service that owns the audio session. Two modes:
 *  - "test": Oboe mic -> voice engine -> Oboe speaker (headphones)
 *  - "live": mic -> voice engine -> phone-call uplink through the Shizuku helper ([LiveVoice])
 * RvcActivity drives it through [start] / [startLive] / [stop] and reads [running] / [mode] / [lastError].
 */
class VoiceService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Thread {
                runCatching { LiveVoice.stop() }
                runCatching { NativeBridge.nativeStop() }
                AudioInputs.release(applicationContext)
                running = false
                mode = ""
            }.start()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val live = intent?.getStringExtra(EXTRA_MODE) == "live"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Voice", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, RvcActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle(if (live) "RVC live call active" else "RVC voice active")
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
                if (live) LiveVoice.start(app, slack, rate, shellMic, unprocessed, dev)
                else NativeBridge.nativeStart(dev, 0, slack, unprocessed)
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
        /** "test", "live" or "" */
        @Volatile var mode = ""
        @Volatile var lastError = ""

        fun start(ctx: Context, slackMs: Int, unprocessedMic: Boolean) {
            lastError = ""; running = true; mode = "test"
            ctx.startForegroundService(
                Intent(ctx, VoiceService::class.java)
                    .putExtra(EXTRA_MODE, "test").putExtra(EXTRA_SLACK, slackMs).putExtra(EXTRA_RAW, unprocessedMic)
            )
        }

        fun startLive(ctx: Context, slackMs: Int, uplinkRate: Int, shellMic: Boolean, unprocessedMic: Boolean) {
            lastError = ""; running = true; mode = "live"
            ctx.startForegroundService(
                Intent(ctx, VoiceService::class.java)
                    .putExtra(EXTRA_MODE, "live").putExtra(EXTRA_SLACK, slackMs).putExtra(EXTRA_RATE, uplinkRate)
                    .putExtra(EXTRA_SHELL_MIC, shellMic).putExtra(EXTRA_RAW, unprocessedMic)
            )
        }

        fun stop(ctx: Context) {
            running = false
            ctx.startService(Intent(ctx, VoiceService::class.java).setAction(ACTION_STOP))
        }
    }
}
