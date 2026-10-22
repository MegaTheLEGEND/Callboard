package dev.callboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keep-alive foreground service while the voice engine is running (live call or headphone test), so the
 * inference and audio pump threads keep full CPU priority when the app is not on screen. It does no audio work.
 */
class VoiceService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Voice", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Callboard voice active")
            .setContentText("Tap to open")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open).setOngoing(true).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "voice"
        private const val ACTION_STOP = "dev.callboard.STOP_VOICE"
        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, VoiceService::class.java)) }
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, VoiceService::class.java).setAction(ACTION_STOP)) }
    }
}
