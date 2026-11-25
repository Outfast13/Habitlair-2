package com.habitlair.app.work

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.habitlair.app.R
import com.habitlair.app.data.Owner

/**
 * Plays a looping alarm sound with vibration and shows a full-screen alarm screen.
 * Runs as a foreground service so Android keeps it alive until the person dismisses or snoozes it.
 */
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val giveUp = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            // startForegroundService() requires startForeground(); satisfy it, then stop right away
            stopSelf()
            return START_NOT_STICKY
        }
        val type = intent?.getStringExtra(EXTRA_TYPE) ?: TYPE_TEST
        val id = intent?.getLongExtra(EXTRA_ID, 0L) ?: 0L
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Alarm"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: ""

        Notifier.ensureChannels(this)
        val ringIntent = Intent(this, AlarmRingActivity::class.java)
            .putExtra(EXTRA_TYPE, type).putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val fullScreen = PendingIntent.getActivity(this, 7001, ringIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nid = 7000

        val b = NotificationCompat.Builder(this, ALARM_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text.ifBlank { "Alarm" })
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .addAction(0, "Dismiss", dismissIntent())
        if (type == Owner.HABIT || type == Owner.TASK) {
            b.addAction(0, "Done", Notifier.actionIntent(this, ACTION_DONE, type, id, nid))
            b.addAction(0, "Snooze", Notifier.actionIntent(this, ACTION_SNOOZE, type, id, nid))
        }
        val notification: Notification = b.build()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, nid, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(nid, notification)
        }

        startSound()
        startVibration()
        handler.removeCallbacks(giveUp)
        handler.postDelayed(giveUp, 2 * 60_000L) // stops by itself after two minutes
        return START_NOT_STICKY
    }

    private fun dismissIntent(): PendingIntent {
        val i = Intent(this, AlarmService::class.java).setAction(ACTION_DISMISS)
        return PendingIntent.getService(this, 7002, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun startSound() {
        stopSound()
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            player = null
        }
    }

    private fun stopSound() {
        player?.let {
            try { it.stop() } catch (e: Exception) { /* not started */ }
            it.release()
        }
        player = null
    }

    private fun startVibration() {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        vibrator = v
        v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0))
    }

    override fun onDestroy() {
        handler.removeCallbacks(giveUp)
        stopSound()
        vibrator?.cancel()
        vibrator = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_DISMISS = "com.habitlair.ALARM_DISMISS"

        fun start(ctx: Context, type: String, id: Long, title: String, text: String) {
            val i = Intent(ctx, AlarmService::class.java)
                .putExtra(EXTRA_TYPE, type).putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AlarmService::class.java))
        }
    }
}
