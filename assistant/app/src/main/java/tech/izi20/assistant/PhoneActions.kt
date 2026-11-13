package tech.izi20.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Команды, которые Изи делает прямо на телефоне — без нейросети, бесплатно и мгновенно.
 * Если команда не наша — возвращаем null, и фраза уходит в мозг Изи на сервере.
 */
object PhoneActions {

    /** Вопрос Изи, на который ждём ответ («Отправить?», «Куда — в Телеграм или Ватсап?»). */
    class Ask(val handler: (Context, String) -> Local?)

    data class Local(
        val say: String,
        val needPermission: String? = null,
        val photos: List<android.net.Uri> = emptyList(),
        val ask: Ask? = null,
    )

    fun normalize(raw: String): String {
        var t = raw.lowercase(Locale("ru")).replace('ё', 'е')
        t = t.replace(Regex("(\\d)[:.](\\d)"), "$1:$2")
        t = t.replace(Regex("[^а-яa-z0-9: ]"), " ").replace(Regex("\\s+"), " ").trim()
        t = t.replace(Regex("^(привет|окей|ок|эй|слушай|алло)?\\s*(изи|изя|иззи|easy|izi)\\b\\s*"), "")
        t = t.replace(Regex("\\b(пожалуйста|плиз)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        return t
    }

    fun isStop(t: String) = Regex("^(стоп|хватит|замолчи|молчи|тихо|отмена|отбой|все|спасибо)$").matches(t)

    fun handle(ctx: Context, raw: String): Local? {
        val t = normalize(raw)
        if (t.isEmpty()) return null
        return timeAndDate(t)
            ?: vpn(ctx, t)
            ?: Photos.handle(ctx, t)
            ?: Messaging.handle(ctx, t)
            ?: flashlight(ctx, t)
            ?: alarm(ctx, t)
            ?: timer(ctx, t)
            ?: reminder(ctx, t)
            ?: call(ctx, t)
            ?: openApp(ctx, t)
    }

    // ---------- числа, длительности, время ----------

    private val WORDS = mapOf(
        "ноль" to 0, "один" to 1, "одну" to 1, "одна" to 1, "две" to 2, "два" to 2, "три" to 3, "четыре" to 4,
        "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9, "десять" to 10,
        "одиннадцать" to 11, "двенадцать" to 12, "пятнадцать" to 15, "двадцать" to 20,
        "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50,
    )

    private fun num(s: String?): Int? = when {
        s.isNullOrBlank() -> null
        s.all { it.isDigit() } -> s.toIntOrNull()
        else -> WORDS[s]
    }

    private val NUMWORD = "(\\d+|" + WORDS.keys.sortedByDescending { it.length }.joinToString("|") + ")(?![а-я0-9])"

    /** «5 минут», «час 30 минут», «полчаса», «полтора часа», «минуту» → секунды. */
    private fun durationSec(t: String): Int? {
        if (Regex("\\bполчаса\\b").containsMatchIn(t)) return 1800
        if (Regex("\\bполтора час").containsMatchIn(t)) return 5400
        var total = 0
        var found = false
        Regex("(?:$NUMWORD\\s+)?(?<![а-я])(секунд[а-я]*|сек\\b|минут[а-я]*|мин\\b|час[а-я]*)").findAll(t).forEach { m ->
            val n = num(m.groupValues[1]) ?: 1
            val unit = m.groupValues[2]
            total += when {
                unit.startsWith("сек") -> n
                unit.startsWith("мин") -> n * 60
                else -> n * 3600
            }
            found = true
        }
        return if (found && total > 0) total else null
    }

    private data class Clock(val hour: Int, val minute: Int)

    /** «в 7», «на 7:30», «в 8 вечера», «в 19 00». */
    private fun clockTime(t: String): Clock? {
        val m = Regex("(?:^|\\s)(?:в|на|к)\\s+$NUMWORD(?:(?::|\\s)(\\d{2}))?(?:\\s+(утра|вечера|дня|ночи))?").find(t)
            ?: return null
        var h = num(m.groupValues[1]) ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: 0
        when (m.groupValues[3]) {
            "вечера", "дня" -> if (h < 12) h += 12
            "ночи" -> if (h == 12) h = 0
        }
        if (h !in 0..23 || min !in 0..59) return null
        return Clock(h, min)
    }

    private fun nextAt(c: Clock, tomorrow: Boolean): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, c.hour)
        cal.set(Calendar.MINUTE, c.minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (tomorrow || cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun hm(c: Clock) = String.format(Locale.US, "%d:%02d", c.hour, c.minute)

    private fun humanDuration(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts += "$h ч"
        if (m > 0) parts += "$m мин"
        if (s > 0) parts += "$s сек"
        return parts.joinToString(" ")
    }

    internal fun start(ctx: Context, i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    } catch (e: Exception) {
        false
    }

    // ---------- время и дата ----------

    private fun timeAndDate(t: String): Local? {
        if (t.split(" ").size > 6) return null
        if (Regex("(который час|сколько времени|сколько сейчас времени|сколько время)").containsMatchIn(t)) {
            val now = SimpleDateFormat("H:mm", Locale("ru")).format(System.currentTimeMillis())
            return Local("Сейчас $now")
        }
        if (Regex("(какое сегодня число|какое число|какой сегодня день|какая сегодня дата|какой день недели)").containsMatchIn(t)) {
            val d = SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(System.currentTimeMillis())
            return Local("Сегодня $d")
        }
        return null
    }

    // ---------- Изи Защитник (VPN) ----------

    private const val ZASH = "tech.izi20.zashchitnik"
    private val VPN_WORD = Regex("(впн|vpn|в пн|вэпээн|ви пи эн|защитник|защиту|прокси)")

    private fun vpnOn(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }
    } catch (_: Exception) { false }

    private fun vpn(ctx: Context, t: String): Local? {
        if (!VPN_WORD.containsMatchIn(t)) return null
        val status = Regex("(включен|работает|подключен|горит|статус|вкл ли|включен ли)").containsMatchIn(t) && !Regex("^(включи|выключи|отключи)").containsMatchIn(t)
        if (status) return Local(if (vpnOn(ctx)) "Да, Защитник сейчас включён" else "Нет, Защитник сейчас выключен")
        val off = Regex("^(выключи|отключи|выруби|останови|вырубай|убери)").containsMatchIn(t)
        val on = Regex("^(включи|подключи|вруби|запусти|врубай|активируй)").containsMatchIn(t)
        if (!on && !off) return null
        if (on && vpnOn(ctx)) return Local("Защитник уже включён")
        if (off && !vpnOn(ctx)) return Local("Защитник и так выключен")
        val launch = ctx.packageManager.getLaunchIntentForPackage(ZASH) ?: return Local("Изи Защитник не установлен")
        val cmd = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("izi-zashchitnik://vpn?on=" + if (on) "1" else "0")).setPackage(ZASH)
        if (start(ctx, cmd)) return Local(if (on) "Включаю Защитник" else "Выключаю Защитник")
        // старая версия Защитника не понимает команду — открываем его, нажать кнопку придётся самому
        start(ctx, launch)
        return Local("Открыл Защитник — нажми большую кнопку. Обнови Защитник до новой версии, и я смогу сам")
    }

    // ---------- фонарик ----------

    private fun flashlight(ctx: Context, t: String): Local? {
        if (!t.contains("фонар")) return null
        val on = !Regex("(выключ|выруби|погаси|убери)").containsMatchIn(t)
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return Local("На этом телефоне нет фонарика")
            cm.setTorchMode(id, on)
            Local(if (on) "Фонарик включён" else "Фонарик выключен")
        } catch (e: Exception) {
            Local("Не получилось включить фонарик")
        }
    }

    // ---------- будильник ----------

    private fun alarm(ctx: Context, t: String): Local? {
        if (!(t.contains("будильник") || t.startsWith("разбуди"))) return null
        if (Regex("(покажи|открой|какие|список)").containsMatchIn(t)) {
            start(ctx, Intent(AlarmClock.ACTION_SHOW_ALARMS))
            return Local("Открываю будильники")
        }
        val clock: Clock? = clockTime(t) ?: durationSec(t)?.takeIf { t.contains("через") }?.let {
            val cal = Calendar.getInstance()
            cal.add(Calendar.SECOND, it)
            Clock(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        }
        if (clock == null) return Local("На какое время поставить будильник? Скажи, например: будильник на 7 утра")
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, clock.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, clock.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Изи")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (start(ctx, i)) Local("Будильник на ${hm(clock)} поставлен")
        else Local("Не нашёл приложение «Часы», не могу поставить будильник")
    }

    // ---------- таймер ----------

    private fun timer(ctx: Context, t: String): Local? {
        if (!(t.contains("таймер") || t.startsWith("засеки"))) return null
        val sec = durationSec(t) ?: return Local("На сколько поставить таймер?")
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, sec)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Изи")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (start(ctx, i)) Local("Таймер на ${humanDuration(sec)} запущен")
        else Local("Не нашёл приложение «Часы», не могу поставить таймер")
    }

    // ---------- напоминание ----------

    private fun reminder(ctx: Context, t: String): Local? {
        if (!t.contains("напомни")) return null
        var rest = t.substringAfter("напомни").replace(Regex("^\\s*(мне|нам)\\b"), "").trim()
        val tomorrow = rest.contains("завтра")
        val at: Long
        val whenText: String
        val inMatch = Regex("через\\s+((?:$NUMWORD\\s+)?(?:секунд[а-я]*|минут[а-я]*|час[а-я]*)(?:\\s+$NUMWORD\\s+минут[а-я]*)?|полчаса|полтора часа)").find(rest)
        val clock = clockTime(" $rest")
        if (inMatch != null) {
            val sec = durationSec(inMatch.value) ?: return Local("Через сколько напомнить?")
            at = System.currentTimeMillis() + sec * 1000L
            whenText = "через ${humanDuration(sec)}"
            rest = rest.replace(inMatch.value, " ")
        } else if (clock != null) {
            at = nextAt(clock, tomorrow)
            val isTomorrow = !sameDay(at)
            whenText = (if (isTomorrow) "завтра " else "") + "в ${hm(clock)}"
            rest = rest.replace(Regex("(?:^|\\s)(?:в|на|к)\\s+$NUMWORD(?:(?::|\\s)(\\d{2}))?(?:\\s+(утра|вечера|дня|ночи))?"), " ")
        } else {
            // без времени это скорее «напомни, что мы решили…» — пусть отвечает мозг Изи
            return null
        }
        rest = rest.replace("завтра", " ").replace("сегодня", " ")
            .replace(Regex("^\\s*(что|о том что|о том|про|чтобы|об|о)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val what = rest.ifEmpty { "Ты просил напомнить" }
        Reminders.add(ctx, at, what.replaceFirstChar { it.uppercase() })
        return Local("Напомню $whenText: $what")
    }

    private fun sameDay(ms: Long): Boolean {
        val a = Calendar.getInstance()
        val b = Calendar.getInstance().apply { timeInMillis = ms }
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }

    // ---------- звонок ----------

    private val ENDINGS = listOf("ому", "ему", "ой", "ей", "ом", "ем", "ою", "ею", "ую", "ю", "е", "у", "и", "ы", "а", "я")

    internal fun stem(w: String): String {
        if (w.length <= 3) return w
        for (e in ENDINGS) if (w.endsWith(e) && w.length - e.length >= 3) return w.dropLast(e.length)
        return w
    }

    private fun call(ctx: Context, t: String): Local? {
        val m = Regex("^(?:позвони|набери|звони|вызови|звонок)\\s+(?:мне\\s+)?(?:на номер\\s+)?(.+)$").find(t) ?: return null
        val who = m.groupValues[1].trim()
        val digits = who.filter { it.isDigit() || it == '+' }
        val number: String
        val name: String
        if (digits.count { it.isDigit() } >= 5) {
            number = digits
            name = digits
        } else {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                return Local("Разреши доступ к контактам, и я позвоню", Manifest.permission.READ_CONTACTS)
            }
            val found = findContacts(ctx, who)
            if (found.isEmpty()) return Local("Не нашёл в контактах «$who»")
            val names = found.map { it.first }.distinct()
            if (names.size > 1) return Local("Нашёл несколько: ${names.take(4).joinToString(", ")}. Скажи точнее")
            name = found[0].first
            number = found[0].second
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return Local("Разреши Изи звонить, и я наберу", Manifest.permission.CALL_PHONE)
        }
        return if (start(ctx, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))))) Local("Звоню: $name")
        else Local("Не получилось позвонить")
    }

    internal fun findContacts(ctx: Context, query: String): List<Pair<String, String>> {
        val qStems = query.split(" ").filter { it.isNotBlank() }.map { stem(it) }
        val out = mutableListOf<Pair<String, String>>()
        val cr = ctx.contentResolver
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val numberStr = c.getString(1) ?: continue
                val nWords = name.lowercase(Locale("ru")).replace('ё', 'е')
                    .split(Regex("[^а-яa-z0-9]+")).filter { it.isNotBlank() }.map { stem(it) }
                val ok = qStems.all { q -> nWords.any { n -> n == q || (q.length >= 3 && (n.startsWith(q) || q.startsWith(n) && n.length >= 3)) } }
                if (ok) out += name to numberStr
            }
        }
        // одно имя с разными номерами — берём первый
        return out.distinctBy { it.first }
    }

    // ---------- открыть приложение ----------

    private val ALIASES = mapOf(
        "ютуб" to listOf("youtube"), "ютюб" to listOf("youtube"),
        "телеграм" to listOf("telegram"), "телеграмм" to listOf("telegram"), "телегу" to listOf("telegram"), "телега" to listOf("telegram"),
        "ватсап" to listOf("whatsapp"), "вотсап" to listOf("whatsapp"), "вацап" to listOf("whatsapp"),
        "инстаграм" to listOf("instagram"), "инсту" to listOf("instagram"), "инста" to listOf("instagram"),
        "вк" to listOf("vk", "вконтакте"), "вконтакте" to listOf("vk", "вконтакте"),
        "тикток" to listOf("tiktok"), "хром" to listOf("chrome"), "браузер" to listOf("chrome", "браузер", "browser", "яндекс"),
        "камеру" to listOf("camera", "камера"), "камера" to listOf("camera", "камера"),
        "галерею" to listOf("gallery", "галерея", "photos", "фото"), "галерея" to listOf("gallery", "галерея", "photos", "фото"),
        "фото" to listOf("photos", "фото", "gallery", "галерея"),
        "настройки" to listOf("settings", "настройки"), "калькулятор" to listOf("calculator", "калькулятор"),
        "часы" to listOf("clock", "часы"), "календарь" to listOf("calendar", "календарь"),
        "музыку" to listOf("music", "музыка", "яндекс музыка"), "музыка" to listOf("music", "музыка", "яндекс музыка"),
        "карты" to listOf("maps", "карты", "яндекс карты"), "навигатор" to listOf("навигатор", "navigator"),
        "почту" to listOf("gmail", "mail", "почта"), "почта" to listOf("gmail", "mail", "почта"),
        "спотифай" to listOf("spotify"), "озон" to listOf("ozon"), "вайлдберриз" to listOf("wildberries"), "вб" to listOf("wildberries"),
        "сбер" to listOf("сбер", "sber"), "сбербанк" to listOf("сбер", "sber"), "тинькофф" to listOf("т банк", "t-bank", "тинькофф"),
        "госуслуги" to listOf("госуслуги"), "окко" to listOf("okko"), "кинопоиск" to listOf("кинопоиск"),
        "защитник" to listOf("изи защитник"), "плей маркет" to listOf("play маркет", "play store"), "маркет" to listOf("play маркет", "rustore"),
    )

    private fun openApp(ctx: Context, t: String): Local? {
        val m = Regex("^(открой|запусти|включи|покажи|зайди в)\\s+(?:приложение\\s+)?(.+)$").find(t) ?: return null
        val verb = m.groupValues[1]
        val q = m.groupValues[2].trim()
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { it.loadLabel(pm).toString().lowercase(Locale("ru")).replace('ё', 'е') to it.activityInfo.packageName }
        val wanted = (listOf(q) + (ALIASES[q] ?: emptyList()))
        val qs = stem(q)
        val strict = verb == "включи" || verb == "покажи"
        val hit = apps.firstOrNull { (label, _) -> wanted.any { it == label } }
            ?: apps.firstOrNull { (label, _) -> wanted.any { label.startsWith(it) } }
            ?: if (strict) null else (apps.firstOrNull { (label, _) -> wanted.any { it.length >= 3 && label.contains(it) } }
                ?: apps.firstOrNull { (label, _) -> qs.length >= 3 && label.split(" ").any { stem(it) == qs } })
        if (hit == null) {
            // «включи свет», «включи музыку на колонке» и т.п. — пусть решает мозг Изи
            return if (verb == "открой" || verb == "запусти" || verb == "зайди в") Local("Не нашёл приложение «$q» на телефоне") else null
        }
        val launch = pm.getLaunchIntentForPackage(hit.second) ?: return Local("Не получилось открыть «$q»")
        return if (start(ctx, launch)) Local("Открываю ${hit.first.replaceFirstChar { it.uppercase() }}")
        else Local("Не получилось открыть «$q»")
    }
}
