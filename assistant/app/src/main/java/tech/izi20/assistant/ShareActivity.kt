package tech.izi20.assistant

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Поделиться → Изи»: из галереи или любого приложения.
 * Спрашивает — отправить на компьютер или в чат Изи.
 */
class ShareActivity : Activity() {

    private val job = SupervisorJob()
    private val ui = CoroutineScope(Dispatchers.Main + job)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = getSharedPreferences("izi", MODE_PRIVATE).getString("token", null)
        if (token == null) {
            Toast.makeText(this, "Сначала войди в Изи", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish(); return
        }

        val uris: List<Uri> = buildList {
            when (intent?.action) {
                Intent.ACTION_SEND -> intent.parcelable<Uri>(Intent.EXTRA_STREAM)?.let { add(it) }
                Intent.ACTION_SEND_MULTIPLE -> intent.parcelableList<Uri>(Intent.EXTRA_STREAM)?.let { addAll(it) }
            }
        }
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val isLink = Regex("^https?://\\S+$").matches(text)

        val what = when {
            uris.size > 1 -> "${uris.size} файлов"
            uris.size == 1 -> DropApi.queryName(this, uris[0]) ?: "файл"
            text.isNotEmpty() -> if (isLink) "ссылку" else "текст"
            else -> { finish(); return }
        }

        AlertDialog.Builder(this)
            .setTitle("Отправить $what")
            .setItems(arrayOf("💻  На компьютер", "💬  В чат Изи")) { _, which ->
                if (which == 0) toPc(token, uris, text, isLink) else toChat(uris, text)
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun toPc(token: String, uris: List<Uri>, text: String, isLink: Boolean) {
        if (uris.isEmpty()) {
            Toast.makeText(this, "Отправляю на компьютер…", Toast.LENGTH_SHORT).show()
            ui.launch {
                val ok = withContext(Dispatchers.IO) { DropApi.sendText(token, text, isLink) }
                Toast.makeText(this@ShareActivity,
                    if (ok) "Готово — Изи на компьютере уже показал" else "Не вышло, попробуй ещё раз",
                    Toast.LENGTH_LONG).show()
                finish()
            }
            return
        }
        // файлы идут с полосой в шторке: видео на полсотни мегабайт уходит долго
        val label = if (uris.size > 1) "Отправляю ${uris.size} файлов" else "Отправляю на компьютер"
        val bar = SendProgress(applicationContext, label)
        bar.start()
        Toast.makeText(this, "Отправляю на компьютер…", Toast.LENGTH_SHORT).show()
        ui.launch {
            val ok = withContext(Dispatchers.IO) {
                val totalAll = uris.sumOf { DropApi.sizeOf(this@ShareActivity, it) }
                var sentBefore = 0L
                var all = true
                for (u in uris) {
                    val one = DropApi.sizeOf(this@ShareActivity, u)
                    val okOne = DropApi.sendFile(this@ShareActivity, token, u) { done, _ ->
                        bar.update(sentBefore + done, totalAll)
                    }
                    if (!okOne) all = false
                    sentBefore += one
                }
                all
            }
            bar.finish(ok)
            finish()
        }
    }

    private fun toChat(uris: List<Uri>, text: String) {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_SHARE_TEXT, text)
        if (uris.isNotEmpty()) {
            i.putExtra(MainActivity.EXTRA_SHARE_IMAGE, uris[0].toString())
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(i)
        finish()
    }

    override fun onDestroy() { job.cancel(); super.onDestroy() }
}

@Suppress("DEPRECATION")
inline fun <reified T : android.os.Parcelable> Intent.parcelable(key: String): T? =
    if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java)
    else getParcelableExtra(key) as? T

@Suppress("DEPRECATION")
inline fun <reified T : android.os.Parcelable> Intent.parcelableList(key: String): ArrayList<T>? =
    if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, T::class.java)
    else getParcelableArrayListExtra(key)
