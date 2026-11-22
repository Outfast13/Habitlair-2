package com.habitlair.app.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.habitlair.app.HabitLairApp
import com.habitlair.app.MainActivity
import com.habitlair.app.R
import com.habitlair.app.data.AppDatabase
import com.habitlair.app.data.AppSettings
import com.habitlair.app.data.Dates
import com.habitlair.app.data.Habit
import com.habitlair.app.data.Owner
import com.habitlair.app.data.SettingsStore
import com.habitlair.app.widget.HabitWidget
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

const val EXTRA_TYPE = "type"
const val EXTRA_ID = "id"
const val TYPE_POMODORO = "pomodoro"
const val TYPE_TEST = "test"
const val ACTION_REMINDER = "com.habitlair.REMINDER"
const val ACTION_DONE = "com.habitlair.DONE"
const val ACTION_SNOOZE = "com.habitlair.SNOOZE"

object Notifier {
    fun channelId(sound: Boolean, vibration: Boolean): String {
        val s = if (sound) "s" else "x"
        val v = if (vibration) "v" else "x"
        return "reminders_$s$v"
    }

    /** Channel sound/vibration can't change after creation, so one channel exists per combination. */
    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        for (sound in listOf(true, false)) {
            for (vib in listOf(true, false)) {
                val importance = when {
                    sound -> NotificationManager.IMPORTANCE_HIGH
                    vib -> NotificationManager.IMPORTANCE_DEFAULT
                    else -> NotificationManager.IMPORTANCE_LOW
                }
                val name = "Reminders (" + (if (sound) "sound" else "silent") + (if (vib) ", vibration" else "") + ")"
                val ch = NotificationChannel(channelId(sound, vib), name, importance)
                ch.enableVibration(vib)
                if (sound) {
                    val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
                    ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), attrs)
                } else {
                    ch.setSound(null, null)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun show(ctx: Context, s: AppSettings, notifId: Int, title: String, text: String, type: String? = null, id: Long = 0L) {
        if (!canPost(ctx)) return
        ensureChannels(ctx)
        val openIntent = Intent(ctx, MainActivity::class.java)
        val open = PendingIntent.getActivity(ctx, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(ctx, channelId(s.sound, s.vibration))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
        if (type == Owner.HABIT || type == Owner.TASK) {
            b.addAction(0, "Done", actionIntent(ctx, ACTION_DONE, type, id, notifId))
            b.addAction(0, "Snooze 10 min", actionIntent(ctx, ACTION_SNOOZE, type, id, notifId))
        }
        NotificationManagerCompat.from(ctx).notify(notifId, b.build())
    }

    private fun actionIntent(ctx: Context, action: String, type: String, id: Long, notifId: Int): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java)
        i.action = action
        i.putExtra(EXTRA_TYPE, type)
        i.putExtra(EXTRA_ID, id)
        i.putExtra("notif", notifId)
        val code = notifId * 3 + (if (action == ACTION_DONE) 1 else 2)
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun showTest(ctx: Context, s: AppSettings): Boolean {
        if (!canPost(ctx)) return false
        show(ctx, s, 9_999, "HabitLair test", "Notifications are working.")
        return true
    }
}

class ReminderScheduler(private val ctx: Context, private val db: AppDatabase, private val settings: SettingsStore) {
    private val am: AlarmManager = ctx.getSystemService(AlarmManager::class.java)

    fun requestCode(type: String, id: Long): Int = when (type) {
        Owner.HABIT -> id.toInt()
        Owner.TASK -> 0x40000000 + id.toInt()
        TYPE_POMODORO -> 0x7FFFFFF0
        else -> 0x7FFFFFF1
    }

    fun notifId(type: String, id: Long): Int = requestCode(type, id)

    private fun pending(type: String, id: Long): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java)
        i.action = ACTION_REMINDER
        i.putExtra(EXTRA_TYPE, type)
        i.putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(ctx, requestCode(type, id), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun canExact(): Boolean = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

    fun cancel(type: String, id: Long) {
        am.cancel(pending(type, id))
    }

    fun setAt(type: String, id: Long, at: Long) {
        val p = pending(type, id)
        try {
            if (canExact()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    fun snooze(type: String, id: Long, minutes: Int = 10) {
        setAt(type, id, System.currentTimeMillis() + minutes * 60_000L)
    }

    fun nextHabitTime(h: Habit, from: Long = System.currentTimeMillis()): Long? {
        val t = h.timeMinutes ?: return null
        if (h.daysMask == 0) return null
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone)
        for (i in 0..7) {
            val d = start.plusDays(i.toLong())
            if ((h.daysMask and (1 shl (d.dayOfWeek.value - 1))) == 0) continue
            val at = LocalDateTime.of(d, LocalTime.of(t / 60, t % 60)).atZone(zone).toInstant().toEpochMilli()
            if (at > from + 1000) return at
        }
        return null
    }

    suspend fun syncHabit(id: Long) {
        val s = settings.current()
        val h = db.habits().get(id)
        cancel(Owner.HABIT, id)
        if (h == null || !s.remindersEnabled || !h.reminder || h.archived) return
        val at = nextHabitTime(h) ?: return
        setAt(Owner.HABIT, id, at)
    }

    suspend fun syncTask(id: Long) {
        val s = settings.current()
        val t = db.tasks().get(id)
        cancel(Owner.TASK, id)
        if (t == null || t.done || !t.reminder || !s.remindersEnabled) return
        val day = t.dueEpochDay ?: return
        val m = t.dueMinutes ?: s.defaultReminderMinutes
        val at = LocalDateTime.of(LocalDate.ofEpochDay(day), LocalTime.of(m / 60, m % 60))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (at > System.currentTimeMillis()) setAt(Owner.TASK, id, at)
    }

    suspend fun rescheduleAll() {
        for (h in db.habits().all()) syncHabit(h.id)
        for (t in db.tasks().all()) syncTask(t.id)
    }
}

private fun runAsync(receiver: BroadcastReceiver, ctx: Context, block: suspend (HabitLairApp) -> Unit) {
    val app = ctx.applicationContext as HabitLairApp
    val result = receiver.goAsync()
    app.container.scope.launch {
        try {
            block(app)
        } finally {
            result.finish()
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra(EXTRA_TYPE) ?: return
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        runAsync(this, context) { app ->
            val c = app.container
            val s = c.settings.current()
            val nid = c.scheduler.notifId(type, id)
            if (type == Owner.HABIT) {
                val h = c.db.habits().get(id)
                if (h != null) {
                    val doneToday = c.db.habits().isDone(id, Dates.today()) > 0
                    if (s.remindersEnabled && !doneToday) {
                        Notifier.show(context, s, nid, "Time for " + h.name, h.notes.ifBlank { "Tap to open HabitLair" }, type, id)
                    }
                    c.scheduler.syncHabit(id)
                }
            } else if (type == Owner.TASK) {
                val t = c.db.tasks().get(id)
                if (t != null && !t.done) {
                    Notifier.show(context, s, nid, "Task due: " + t.title, t.notes.ifBlank { "Tap to open HabitLair" }, type, id)
                }
            } else if (type == TYPE_POMODORO) {
                Notifier.show(context, s, nid, "Pomodoro finished", "Open HabitLair to continue your session.")
            } else {
                Notifier.show(context, s, nid, "HabitLair test alarm", "Your alarm fired on time.")
            }
        }
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra(EXTRA_TYPE) ?: return
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        val notif = intent.getIntExtra("notif", 0)
        val action = intent.action
        runAsync(this, context) { app ->
            val c = app.container
            if (action == ACTION_DONE) {
                if (type == Owner.HABIT) c.repo.setHabitDone(id, Dates.today(), true)
                else if (type == Owner.TASK) c.repo.setTaskDone(id, true)
            } else if (action == ACTION_SNOOZE) {
                c.scheduler.snooze(type, id)
            }
            NotificationManagerCompat.from(context).cancel(notif)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runAsync(this, context) { app ->
            app.container.scheduler.rescheduleAll()
            try {
                HabitWidget().updateAll(context)
            } catch (e: Exception) {
                // widget may not be placed; ignore
            }
        }
    }
}

class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as HabitLairApp).container
        c.scheduler.rescheduleAll()
        try {
            HabitWidget().updateAll(applicationContext)
        } catch (e: Exception) {
            // ignore
        }
        return Result.success()
    }
}
