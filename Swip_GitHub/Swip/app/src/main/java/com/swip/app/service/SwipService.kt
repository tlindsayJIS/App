package com.swip.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.os.Binder
import android.os.IBinder
import android.view.KeyEvent
import com.swip.app.ui.MainActivity

class SwipService : Service() {

    private val binder = LocalBinder()
    private lateinit var audioManager: AudioManager

    companion object {
        const val CHANNEL_ID = "swip_main"
        const val NOTIF_ID = 1
        const val ACTION_PLAY  = "swip.PLAY"
        const val ACTION_PAUSE = "swip.PAUSE"
        const val ACTION_SKIP  = "swip.SKIP"
        const val ACTION_STOP  = "swip.STOP"
    }

    inner class LocalBinder : Binder() {
        fun get(): SwipService = this@SwipService
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createChannel()
        startForeground(NOTIF_ID, buildNotification("Running in background"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY  -> sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            ACTION_PAUSE -> sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            ACTION_SKIP  -> {
                val skipped = SwipAccessibilityService.instance?.doSkip() ?: false
                if (!skipped) sendKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            }
            ACTION_STOP  -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun sendKey(keyCode: Int) {
        try {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        } catch (e: Exception) {}
    }

    private fun pi(requestCode: Int, action: String): PendingIntent {
        val i = Intent(this, SwipService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, requestCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Swip")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_play,  "Play",  pi(1, ACTION_PLAY))
            .addAction(android.R.drawable.ic_media_pause, "Pause", pi(2, ACTION_PAUSE))
            .addAction(android.R.drawable.ic_media_next,  "Skip",  pi(3, ACTION_SKIP))
            .addAction(android.R.drawable.ic_delete,      "Stop",  pi(4, ACTION_STOP))
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Swip Controls",
            NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            setSound(null, null)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
