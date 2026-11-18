package tech.izi20.assistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Полоса «отправляю на компьютер» в шторке. Нужна потому, что видео на 50 МБ
 * по мобильному интернету уходит долго, и без полосы непонятно, идёт оно или зависло.
 */
class SendProgress(private val ctx: Context, private val title: String) {

    private val id = 7100 + (System.currentTimeMillis() % 500).toInt()
    private var allowed = true
    private var lastPercent = -1

    private fun can(): Boolean {
        if (!allowed) return false
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            allowed = false
            return false
        }
        return true
    }

    private fun channel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Отправка на компьютер", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
    }

    private fun show(text: String, percent: Int, done: Boolean) {
        if (!can()) return
        channel()
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_izi)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(!done)
            .setAutoCancel(done)
        if (!done) {
            if (percent >= 0) b.setProgress(100, percent, false) else b.setProgress(0, 0, true)
        }
        try {
            NotificationManagerCompat.from(ctx).notify(id, b.build())
        } catch (e: SecurityException) {
            allowed = false
        }
    }

    fun start() = show("Начинаю…", -1, false)

    fun update(doneBytes: Long, totalBytes: Long) {
        if (totalBytes <= 0) {
            show(size(doneBytes), -1, false)
            return
        }
        val p = ((doneBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
        if (p == lastPercent) return // шторку дёргать чаще нет смысла
        lastPercent = p
        show("${size(doneBytes)} из ${size(totalBytes)}", p, false)
    }

    fun finish(ok: Boolean) {
        if (!can()) return
        if (ok) show("Готово — Изи на компьютере уже показал", 100, true)
        else show("Не вышло. Проверь интернет и попробуй ещё раз", 0, true)
    }

    companion object {
        private const val CHANNEL = "izi_send_v1"

        /** «3,4 МБ» — покороче и по-русски. */
        fun size(b: Long): String {
            if (b <= 0) return "0 Б"
            val units = arrayOf("Б", "КБ", "МБ", "ГБ")
            var v = b.toDouble()
            var i = 0
            while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
            val s = if (v >= 100 || i == 0) String.format(java.util.Locale.US, "%.0f", v)
            else String.format(java.util.Locale.US, "%.1f", v).replace('.', ',')
            return "$s ${units[i]}"
        }
    }
}
