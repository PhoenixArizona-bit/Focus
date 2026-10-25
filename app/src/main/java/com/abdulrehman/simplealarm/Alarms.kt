package com.abdulrehman.simplealarm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import java.util.Calendar

data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val label: String,
    val enabled: Boolean = true
)

object Store {
    private const val PREFS = "alarms"
    private const val KEY = "list"

    // Placeholder times only - edit them to your local prayer times.
    private val presets = listOf(
        Alarm(1, 5, 0, "Fajr"),
        Alarm(2, 13, 0, "Dhuhr"),
        Alarm(3, 16, 30, "Asr"),
        Alarm(4, 18, 30, "Maghrib"),
        Alarm(5, 20, 0, "Isha")
    )

    fun ring(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ring", true)

    fun setRing(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ring", on).apply()
    }

    fun load(ctx: Context): List<Alarm> {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!sp.contains(KEY)) save(ctx, presets)
        val raw = sp.getStringSet(KEY, emptySet()) ?: emptySet()
        return raw.mapNotNull {
            val p = it.split("|")
            if (p.size < 5) null
            else Alarm(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3], p[4] == "1")
        }.sortedWith(compareBy({ it.hour }, { it.minute }))
    }

    fun save(ctx: Context, list: List<Alarm>) {
        val set = list.map {
            "${it.id}|${it.hour}|${it.minute}|${it.label.replace("|", " ")}|${if (it.enabled) 1 else 0}"
        }.toSet()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY, set).apply()
    }
}

object Scheduler {
    private fun pending(ctx: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id,
            Intent(ctx, AlarmReceiver::class.java).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun schedule(ctx: Context, a: Alarm) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val needed = Store.ring(ctx) || (a.id in 1..5 && Cfg.prayerFocus(ctx) > 0)
        if (!a.enabled || !needed) { am.cancel(pending(ctx, a.id)); return }
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, a.hour)
            set(Calendar.MINUTE, a.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(cal.timeInMillis, show), pending(ctx, a.id))
    }

    fun cancel(ctx: Context, id: Int) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(ctx, id))
    }

    fun scheduleAll(ctx: Context) = Store.load(ctx).forEach { schedule(ctx, it) }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        val alarm = Store.load(ctx).firstOrNull { it.id == id } ?: return
        if (!alarm.enabled) return

        val pf = Cfg.prayerFocus(ctx)
        if (id in 1..5 && pf > 0) {
            Cfg.setFocusUntil(ctx, System.currentTimeMillis() + pf * 60000L)
        }

        if (Store.ring(ctx)) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val channel = NotificationChannel("alarm", "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(
                    sound,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                )
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(ctx, "alarm")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(alarm.label.ifBlank { "Alarm" })
                .setContentText("%02d:%02d".format(alarm.hour, alarm.minute))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(id, n)
        }

        Scheduler.schedule(ctx, alarm) // repeat daily
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.scheduleAll(ctx)
        if (Cfg.guard(ctx) && Perms.usage(ctx) && Perms.overlay(ctx)) {
            try { Guard.start(ctx) } catch (e: Exception) { }
        }
    }
}
