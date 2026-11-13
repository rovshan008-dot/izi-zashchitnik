package tech.izi20.assistant

import android.Manifest
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
import org.json.JSONArray
import org.json.JSONObject

/** Напоминания Изи: хранятся на телефоне и срабатывают даже без интернета. */
object Reminders {
    private const val PREFS = "izi_reminders"
    private const val KEY = "list"
    const val CHANNEL = "izi_reminders_v1"

    private fun load(ctx: Context): JSONArray =
        try { JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")) } catch (e: Exception) { JSONArray() }

    private fun save(ctx: Context, a: JSONArray) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()

    fun add(ctx: Context, at: Long, text: String) {
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val a = load(ctx)
        a.put(JSONObject().put("id", id).put("at", at).put("text", text))
        save(ctx, a)
        schedule(ctx, id, at, text)
    }

    fun remove(ctx: Context, id: Int) {
        val a = load(ctx)
        val b = JSONArray()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optInt("id") != id) b.put(o)
        }
        save(ctx, b)
    }

    fun rescheduleAll(ctx: Context) {
        val a = load(ctx)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            // просроченные (телефон был выключен) — покажем сразу
            val at = maxOf(o.optLong("at"), System.currentTimeMillis() + 5_000)
            schedule(ctx, o.optInt("id"), at, o.optString("text"))
        }
    }

    private fun pending(ctx: Context, id: Int, text: String): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("id", id).putExtra("text", text)
        return PendingIntent.getBroadcast(ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun schedule(ctx: Context, id: Int, at: Long, text: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx, id, text)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (exactAllowed) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "Напоминания", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "Напоминания, которые ты просил Изи поставить"
        ch.enableVibration(true)
        ch.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build(),
        )
        nm.createNotificationChannel(ch)
    }

    fun show(ctx: Context, id: Int, text: String) {
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_izi)
            .setContentTitle("Изи напоминает")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(ctx).notify(id, n)
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val text = intent.getStringExtra("text") ?: "Напоминание"
        Reminders.show(ctx, id, text)
        Reminders.remove(ctx, id)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Reminders.rescheduleAll(ctx)
    }
}
