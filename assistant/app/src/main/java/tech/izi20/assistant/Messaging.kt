package tech.izi20.assistant

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import tech.izi20.assistant.PhoneActions.Ask
import tech.izi20.assistant.PhoneActions.Local

/**
 * «Напиши маме, что я опоздаю» / «отправь Саше в Ватсап: буду в 7».
 * СМС Изи отправляет сам (после «да»). В Ватсапе и Телеграме чужие приложения нельзя нажимать за тебя,
 * поэтому Изи открывает нужный чат с готовым текстом, а «Отправить» нажимаешь ты.
 */
object Messaging {

    enum class Channel(val title: String) { SMS("СМС"), WHATSAPP("Ватсап"), TELEGRAM("Телеграм") }

    data class Contact(val name: String, val number: String)

    private val CHANNEL_RE = Regex("(?:^|\\s)(?:в|по|через)?\\s*(вотсап[а-я]*|ватсап[а-я]*|вацап[а-я]*|whatsapp|телеграм[а-я]*|телег[уеа]|смс[а-я]*|sms|эсэмэс[а-я]*)(?=\\s|$)")

    fun channelOf(t: String): Channel? {
        val m = CHANNEL_RE.find(t) ?: return null
        val w = m.groupValues[1]
        return when {
            w.startsWith("тел") -> Channel.TELEGRAM
            w.startsWith("смс") || w == "sms" || w.startsWith("эсэмэс") -> Channel.SMS
            else -> Channel.WHATSAPP
        }
    }

    private const val PREFS = "izi"

    private fun defaultChannel(ctx: Context): Channel? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("msg_channel", null)?.let {
            runCatching { Channel.valueOf(it) }.getOrNull()
        }

    private fun rememberChannel(ctx: Context, c: Channel) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("msg_channel", c.name).apply()

    fun handle(ctx: Context, t: String): Local? {
        val m = Regex("^(?:напиши|отправь|скинь|пошли|черкни|передай)\\s+(?:сообщение\\s+|смску\\s+|смс\\s+)?(.+)$").find(t) ?: return null
        var body = m.groupValues[1]
        // «напиши стих / письмо / текст …» — это работа для мозга Изи, а не сообщение человеку
        if (Regex("^(стих|письмо|текст|пост|сочинение|рассказ|песню|код|резюме|отзыв|программу|сценарий|историю|статью|план)").containsMatchIn(body)) return null
        val channel = channelOf(body)
        body = body.replace(CHANNEL_RE, " ").replace(Regex("\\s+"), " ").trim()

        val looksLikeMessage = body.contains(" что ") || body.contains(":") || channel != null
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return if (looksLikeMessage) Local("Разреши доступ к контактам, и я напишу", Manifest.permission.READ_CONTACTS) else null
        }

        // кому и что
        var who: String
        var text: String
        val split = Regex("\\s?:\\s?|\\s(?:чтобы|что)\\s").find(body)
        if (split != null) {
            who = body.substring(0, split.range.first).trim()
            text = body.substring(split.range.last + 1).trim()
            if (split.value.contains("чтобы")) text = "Можешь $text"
        } else {
            val words = body.split(" ")
            who = ""; text = ""
            for (n in minOf(2, words.size - 1) downTo 1) {
                val cand = words.take(n).joinToString(" ")
                if (resolve(ctx, cand).isNotEmpty()) { who = cand; text = words.drop(n).joinToString(" "); break }
            }
        }
        if (who.isEmpty()) return if (looksLikeMessage) Local("Кому написать? Скажи, например: напиши маме, что я опоздаю") else null
        val found = resolve(ctx, who)
        if (found.isEmpty()) return if (looksLikeMessage) Local("Не нашёл в контактах «$who»") else null
        if (found.size > 1) return Local("Нашёл несколько: ${found.take(4).joinToString(", ") { it.name }}. Скажи точнее")
        val c = found[0]
        if (text.isBlank()) return Local("Что написать ${c.name}?", ask = Ask { cx, ans -> deliver(cx, c, ans.replaceFirstChar { it.uppercase() }, channel) })
        return deliver(ctx, c, text.replaceFirstChar { it.uppercase() }, channel)
    }

    private fun resolve(ctx: Context, who: String): List<Contact> =
        PhoneActions.findContacts(ctx, who).map { Contact(it.first, it.second) }

    /** Выбор канала → подтверждение (для СМС) → отправка. */
    fun deliver(ctx: Context, c: Contact, text: String, channel: Channel?): Local {
        val ch = channel ?: defaultChannel(ctx)
            ?: return Local(
                "Куда отправить ${c.name}: в Телеграм, Ватсап или СМС?",
                ask = Ask { cx, ans ->
                    val picked = channelOf(PhoneActions.normalize(ans))
                    if (picked == null) Local("Не понял, куда отправить. Скажи заново")
                    else { rememberChannel(cx, picked); deliver(cx, c, text, picked) }
                },
            )
        return when (ch) {
            Channel.SMS -> {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    return Local("Разреши Изи отправлять СМС и повтори команду", Manifest.permission.SEND_SMS)
                }
                Local("Отправлю ${c.name} СМС: «$text». Отправить?", ask = confirm(c, text, ch))
            }
            Channel.WHATSAPP -> openWhatsApp(ctx, c, text)
            Channel.TELEGRAM -> openTelegram(ctx, c, text)
        }
    }

    private fun confirm(c: Contact, text: String, ch: Channel) = Ask { cx, ans ->
        val a = PhoneActions.normalize(ans)
        val other = channelOf(a)
        when {
            other != null && other != ch -> deliver(cx, c, text, other)
            Regex("^(да|ага|угу|отправ[а-я]*|давай|конечно|ок|окей|верно|точно|шли|отсылай)").containsMatchIn(a) -> sendSms(cx, c, text)
            Regex("^(нет|не надо|отмена|стоп|не отправляй)").containsMatchIn(a) -> Local("Хорошо, не отправляю")
            else -> null
        }
    }

    private fun sendSms(ctx: Context, c: Contact, text: String): Local = try {
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = sms.divideMessage(text)
        if (parts.size > 1) sms.sendMultipartTextMessage(c.number, null, parts, null, null)
        else sms.sendTextMessage(c.number, null, text, null, null)
        Local("Отправил ${c.name}")
    } catch (e: Exception) {
        Local("Не получилось отправить СМС")
    }

    /** Номер в международном виде без плюса: 8 914… → 7914… */
    fun intl(number: String): String {
        var d = number.filter { it.isDigit() }
        if (d.length == 11 && d.startsWith("8")) d = "7" + d.substring(1)
        if (d.length == 10) d = "7$d"
        return d
    }

    private fun installed(ctx: Context, vararg pkgs: String): String? = pkgs.firstOrNull {
        try { ctx.packageManager.getPackageInfo(it, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
    }

    private fun openWhatsApp(ctx: Context, c: Contact, text: String): Local {
        val pkg = installed(ctx, "com.whatsapp", "com.whatsapp.w4b") ?: return Local("Ватсап не установлен")
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=${intl(c.number)}&text=${Uri.encode(text)}")
        return if (PhoneActions.start(ctx, Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)))
            Local("Открыл чат с ${c.name} в Ватсапе, текст уже там — нажми «Отправить»")
        else Local("Не получилось открыть Ватсап")
    }

    private fun openTelegram(ctx: Context, c: Contact, text: String): Local {
        val pkg = installed(ctx, "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram", "org.telegram.plus")
            ?: return Local("Телеграм не установлен")
        // Телеграм не даёт вставить текст в чужой чат по ссылке — кладём в буфер обмена
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Изи", text))
        val uri = Uri.parse("https://t.me/+${intl(c.number)}")
        return if (PhoneActions.start(ctx, Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)))
            Local("Открыл чат с ${c.name} в Телеграме. Текст скопирован — вставь и отправь")
        else Local("Не получилось открыть Телеграм")
    }

    /** Отправить фото: Ватсап — сразу в нужный чат, Телеграм и остальное — через выбор получателя. */
    fun sharePhotos(ctx: Context, uris: List<Uri>, c: Contact?, channel: Channel?): Local {
        val i = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).setType("image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.size > 1) i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) else i.putExtra(Intent.EXTRA_STREAM, uris[0])
        val ch = channel ?: if (c != null) defaultChannel(ctx) else null
        when (ch) {
            Channel.WHATSAPP -> {
                val pkg = installed(ctx, "com.whatsapp", "com.whatsapp.w4b") ?: return Local("Ватсап не установлен")
                i.setPackage(pkg)
                if (c != null) i.putExtra("jid", intl(c.number) + "@s.whatsapp.net")
            }
            Channel.TELEGRAM -> {
                val pkg = installed(ctx, "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram", "org.telegram.plus")
                    ?: return Local("Телеграм не установлен")
                i.setPackage(pkg)
            }
            else -> {}
        }
        val target = if (i.`package` == null) Intent.createChooser(i, "Отправить фото") else i
        val ok = PhoneActions.start(ctx, target)
        val who = if (c != null) " ${c.name}" else ""
        return if (!ok) Local("Не получилось открыть отправку")
        else if (ch == Channel.WHATSAPP && c != null) Local("Открыл Ватсап с фото для$who — нажми «Отправить»")
        else Local("Выбери, кому отправить$who")
    }

    fun resolveOne(ctx: Context, who: String): Contact? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return resolve(ctx, who).singleOrNull()
    }
}
