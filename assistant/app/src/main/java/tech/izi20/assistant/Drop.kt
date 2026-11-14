package tech.izi20.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * «Отправь на комп» и обратно: вещи между твоим телефоном и твоим компьютером
 * через твой же аккаунт Изи. Сервер хранит их в закрытой папке 24 часа.
 */
object DropApi {
    private const val BASE = "https://izi20.tech"

    data class Item(
        val id: String, val ts: Double, val kind: String,
        val text: String?, val name: String?, val mime: String?, val size: Long,
    )

    private fun open(path: String, read: Int): HttpURLConnection {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = read; c.doOutput = true; c.requestMethod = "POST"
        c.setRequestProperty("User-Agent", "IziAndroid/1")
        return c
    }

    /** Отправить текст/ссылку на компьютер. */
    fun sendText(token: String, text: String, link: Boolean): Boolean = try {
        val boundary = "----izi${System.currentTimeMillis()}"
        val c = open("/chat/drop/send", 30_000)
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.outputStream.use { out ->
            fun field(n: String, v: String) = out.write(
                "--$boundary\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n".toByteArray(Charsets.UTF_8))
            field("token", token); field("to", "pc"); field("kind", if (link) "link" else "text"); field("text", text)
            out.write("--$boundary--\r\n".toByteArray())
        }
        ok(c)
    } catch (e: Exception) { false }

    /** Отправить файл (фото, видео, документ) на компьютер. */
    fun sendFile(ctx: Context, token: String, uri: android.net.Uri): Boolean = try {
        val cr = ctx.contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val name = queryName(ctx, uri) ?: ("file_" + System.currentTimeMillis())
        val boundary = "----izi${System.currentTimeMillis()}"
        val c = open("/chat/drop/send", 120_000)
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.outputStream.use { out ->
            fun field(n: String, v: String) = out.write(
                "--$boundary\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n".toByteArray(Charsets.UTF_8))
            field("token", token); field("to", "pc"); field("kind", "file")
            out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\n" +
                "Content-Type: $mime\r\n\r\n").toByteArray(Charsets.UTF_8))
            cr.openInputStream(uri)?.use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        ok(c)
    } catch (e: Exception) { false }

    data class Poll(val items: List<Item>, val now: Double, val otherOnline: Boolean, val authExpired: Boolean = false)

    /** Проверить, что пришло с компьютера. since = время последнего ответа. */
    fun poll(token: String, since: Double, wait: Boolean): Poll {
        return try {
            val (code, body) = postJson("/chat/drop/poll",
                JSONObject().put("token", token).put("me", "phone").put("since", since).put("wait", wait),
                if (wait) 30_000 else 12_000)
            if (code == 401) {
                Poll(emptyList(), since, false, authExpired = true)
            } else {
                val j = JSONObject(body)
                val arr = j.optJSONArray("items") ?: JSONArray()
                val items = ArrayList<Item>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    items += Item(o.getString("id"), o.optDouble("ts"), o.optString("kind"),
                        o.optString("text").ifEmpty { null }, o.optString("name").ifEmpty { null },
                        o.optString("mime").ifEmpty { null }, o.optLong("size"))
                }
                Poll(items, j.optDouble("now", since), j.optBoolean("other_online"))
            }
        } catch (e: Exception) {
            Poll(emptyList(), since, false)
        }
    }

    /** Скачать файл по id во временный файл. */
    fun download(token: String, item: Item, out: File): Boolean = try {
        val c = open("/chat/drop/get", 120_000)
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.outputStream.use { it.write(JSONObject().put("token", token).put("id", item.id).toString().toByteArray()) }
        if (c.responseCode in 200..299) {
            c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            out.length() > 0
        } else false
    } catch (e: Exception) { false }

    private fun ok(c: HttpURLConnection): Boolean = try {
        val code = c.responseCode
        val s: InputStream? = if (code in 200..299) c.inputStream else c.errorStream
        val body = s?.bufferedReader()?.use { it.readText() } ?: ""
        code in 200..299 && JSONObject(body).optBoolean("ok")
    } catch (e: Exception) { false } finally { c.disconnect() }

    private fun postJson(path: String, body: JSONObject, read: Int): Pair<Int, String> {
        val c = open(path, read)
        return try {
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val s = if (code in 200..299) c.inputStream else c.errorStream
            code to (s?.bufferedReader()?.use { it.readText() } ?: "")
        } finally { c.disconnect() }
    }

    fun queryName(ctx: Context, uri: android.net.Uri): String? = try {
        if (uri.scheme == "file") File(uri.path!!).name
        else ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    } catch (e: Exception) { null }
}
