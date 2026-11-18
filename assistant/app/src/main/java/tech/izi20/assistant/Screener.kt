package tech.izi20.assistant

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Изи Скринер на телефоне: живые данные Binance Futures через свой сервер.
 * Нейросеть не участвует, поэтому срез приходит за полсекунды и ничего не стоит.
 *
 * Эндпоинты (те же, что у izi20.tech/screener.html):
 *   GET /screener-api/                      — список монет
 *   GET /screener-api/stats?symbol=BTCUSDT  — открытый интерес, фандинг, перевес покупателей
 */
object Screener {
    private const val BASE = "https://izi20.tech/screener-api/"

    data class Row(
        val sym: String, val pair: String, val price: Double, val chg: Double,
        val vol: Double, val trades: Long, val funding: Double, val nextFunding: Long,
        val high: Double, val low: Double, val natr: Double,
    )

    private var cache: List<Row> = emptyList()
    private var cachedAt = 0L

    private fun get(path: String, timeoutMs: Int = 12_000): String? = try {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "IziAndroid/1")
        try {
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    @Synchronized
    private fun feed(): List<Row> {
        if (cache.isNotEmpty() && System.currentTimeMillis() - cachedAt < 5_000) return cache
        val body = get("") ?: return cache
        val out = mutableListOf<Row>()
        try {
            val arr = JSONObject(body).optJSONArray("data") ?: return cache
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Row(
                    o.optString("sym"), o.optString("pair"), o.optDouble("price", 0.0),
                    o.optDouble("chg", 0.0), o.optDouble("vol", 0.0), o.optLong("trades", 0),
                    o.optDouble("funding", 0.0), o.optLong("nextFunding", 0),
                    o.optDouble("high", 0.0), o.optDouble("low", 0.0), o.optDouble("natr", 0.0),
                )
            }
        } catch (e: Exception) {
            return cache
        }
        if (out.isNotEmpty()) { cache = out; cachedAt = System.currentTimeMillis() }
        return cache
    }

    // ---------- как человек называет монеты ----------

    private val ALIASES = mapOf(
        "биток" to "BTC", "битка" to "BTC", "битку" to "BTC", "биткоин" to "BTC",
        "биткоина" to "BTC", "биткоину" to "BTC", "бтс" to "BTC",
        "эфир" to "ETH", "эфира" to "ETH", "эфиру" to "ETH", "эфириум" to "ETH",
        "солана" to "SOL", "соланы" to "SOL", "сол" to "SOL", "солане" to "SOL",
        "бнб" to "BNB", "рипл" to "XRP", "рипла" to "XRP",
        "дог" to "DOGE", "доги" to "DOGE", "догикоин" to "DOGE", "додж" to "DOGE",
        "тон" to "TON", "тона" to "TON", "трон" to "TRX", "трона" to "TRX",
        "кардано" to "ADA", "ада" to "ADA", "авакс" to "AVAX", "аваланч" to "AVAX",
        "линк" to "LINK", "дот" to "DOT", "полкадот" to "DOT",
        "полигон" to "POL", "матик" to "POL", "лайт" to "LTC", "лайткоин" to "LTC",
        "шиба" to "SHIB", "пепе" to "PEPE", "суи" to "SUI", "аптос" to "APT",
        "арбитрум" to "ARB", "атом" to "ATOM", "космос" to "ATOM", "ниар" to "NEAR",
        "уни" to "UNI", "юнисвап" to "UNI", "аве" to "AAVE", "бонк" to "BONK",
    )

    private fun key(s: String) = s.lowercase(Locale("ru")).replace('ё', 'е').filter { it.isLetterOrDigit() }

    private fun find(word: String): Row? {
        val rows = feed()
        if (rows.isEmpty()) return null
        var q = key(word)
        if (q.isEmpty()) return null
        ALIASES[q]?.let { q = key(it) }
        q = q.removeSuffix("usdt")
        return rows.firstOrNull { key(it.sym) == q }
    }

    // ---------- числа по-русски ----------

    private fun ru(v: Double, digits: Int) = String.format(Locale.US, "%.${digits}f", v).replace('.', ',')

    private fun spaced(v: Double): String =
        String.format(Locale.US, "%.0f", v).reversed().chunked(3).joinToString(" ").reversed()

    private fun money(v: Double): String {
        val a = Math.abs(v)
        return when {
            a >= 1000 -> spaced(v)
            a >= 1 -> ru(v, 2)
            a >= 0.01 -> ru(v, 4)
            else -> ru(v, 8)
        }
    }

    private fun big(v: Double): String {
        val a = Math.abs(v)
        return when {
            a >= 1e12 -> ru(v / 1e12, 2).trimEnd('0').trimEnd(',') + " трлн"
            a >= 1e9 -> ru(v / 1e9, 2).trimEnd('0').trimEnd(',') + " млрд"
            a >= 1e6 -> ru(v / 1e6, 1) + " млн"
            a >= 1e3 -> spaced(v)
            else -> String.format(Locale.US, "%.0f", v)
        }
    }

    private fun signed(v: Double, digits: Int) = (if (v >= 0) "+" else "") + ru(v, digits) + "%"

    private fun until(ms: Long): String {
        if (ms <= 0) return ""
        val left = ms - System.currentTimeMillis()
        if (left <= 0) return "вот-вот"
        val h = left / 3_600_000
        val m = (left / 60_000) % 60
        return if (h > 0) "через $h ч $m мин" else "через $m мин"
    }

    private fun now(): String =
        java.text.SimpleDateFormat("HH:mm:ss", Locale("ru")).format(java.util.Date())

    // ---------- то, что говорит Изи ----------

    /** Срез по одной монете. Пусто — такой монеты в скринере нет. */
    fun coin(word: String): String? {
        val r = find(word) ?: return null
        val sb = StringBuilder()
        sb.append("${r.sym} — ${money(r.price)} $ (${signed(r.chg, 2)} за сутки)\n")
        sb.append("Сутки: от ${money(r.low)} до ${money(r.high)} · волатильность ${ru(r.natr, 1)}%\n")
        sb.append("Объём 24ч: ${big(r.vol)} $ · сделок ${big(r.trades.toDouble())}\n")

        var funding = r.funding
        var next = r.nextFunding
        var oi = 0.0
        var buy = 0.0
        get("stats?symbol=${r.pair}")?.let {
            try {
                val o = JSONObject(it)
                if (o.optBoolean("ok")) {
                    funding = o.optDouble("funding", funding)
                    next = o.optLong("nextFunding", next)
                    oi = o.optDouble("oi_usd", 0.0)
                    buy = o.optDouble("buyPct", 0.0)
                }
            } catch (e: Exception) { /* без доп. цифр срез всё равно полезен */ }
        }
        sb.append("Фандинг: ${signed(funding, 4)}")
        until(next).let { if (it.isNotEmpty()) sb.append(" · выплата $it") }
        sb.append("\n")
        if (oi > 0) sb.append("Открытый интерес: ${big(oi)} $\n")
        if (buy > 0) sb.append("Перевес покупателей: ${ru(buy, 1)}% от объёма\n")
        sb.append("\nBinance Futures, данные на ${now()}")
        return sb.toString()
    }

    /** Кто сильнее всех вырос и упал. */
    fun summary(): String? {
        val rows = feed()
        if (rows.size < 4) return null
        val up = rows.sortedByDescending { it.chg }
        val n = minOf(5, up.size / 2)
        if (n == 0) return null
        val sb = StringBuilder("Растут сильнее всех:\n")
        up.take(n).forEach { sb.append("  ${it.sym}  ${money(it.price)} $  ${signed(it.chg, 1)}\n") }
        sb.append("\nПадают сильнее всех:\n")
        up.takeLast(n).reversed().forEach { sb.append("  ${it.sym}  ${money(it.price)} $  ${signed(it.chg, 1)}\n") }
        sb.append("\nBinance Futures, ${rows.size} монет, данные на ${now()}")
        return sb.toString()
    }

    // ---------- разбор фразы ----------

    private val SUMMARY_RE = Regex("^(что (на|по) скринер[еу]|скринер|что (на|с) рынк[еа]|как рынок|кто растет|кто падает|топ движени[йя])$")
    private val COIN_RE = Regex("^(?:что по|что с|срез по|срез|курс|цена|цену|сколько стоит|почем)\\s+(.+)$")

    /** Похоже ли это на вопрос к скринеру. Без сети — можно звать из главного потока. */
    fun matches(t: String): Boolean = SUMMARY_RE.matches(t) || COIN_RE.matches(t)

    /**
     * Ответ скринера на фразу вида «что по битку», «курс эфира», «что на скринере».
     * Ходит в сеть, поэтому звать только с фонового потока.
     * Пусто — такой монеты нет или скринер не ответил; тогда пусть отвечает мозг Изи.
     */
    fun answer(t: String): String? {
        if (SUMMARY_RE.matches(t)) return summary()
        val m = COIN_RE.find(t) ?: return null
        for (w in m.groupValues[1].split(" ", ",", ".")) {
            if (w.isBlank()) continue
            coin(w)?.let { return it }
        }
        return null
    }
}
