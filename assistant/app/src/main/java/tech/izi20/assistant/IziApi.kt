package tech.izi20.assistant

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Связь с мозгом Изи на izi20.tech — те же адреса, что у веб-Изи (izi.html). */
object IziApi {
    private const val BASE = "https://izi20.tech"

    data class Reply(
        val text: String?,
        val imageUrl: String? = null,
        val recognized: String? = null,
        val authExpired: Boolean = false,
        val silent: Boolean = false,
        val error: String? = null,
    )

    private fun open(path: String, readTimeoutMs: Int): HttpURLConnection {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = readTimeoutMs
        c.doOutput = true
        c.requestMethod = "POST"
        c.setRequestProperty("User-Agent", "IziAndroid/1")
        return c
    }

    private fun readBody(c: HttpURLConnection, code: Int): String {
        val stream: InputStream? = if (code in 200..299) c.inputStream else c.errorStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }

    private fun postJson(path: String, body: JSONObject, readTimeoutMs: Int = 120_000): Pair<Int, String> {
        val c = open(path, readTimeoutMs)
        try {
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            return code to readBody(c, code)
        } finally {
            c.disconnect()
        }
    }

    /** Код из Telegram (/web у @IZI20bot) → постоянный ключ входа. */
    fun link(code: String): Result<String> = runCatching {
        val (_, s) = postJson("/chat/izi_link", JSONObject().put("code", code), 30_000)
        val j = JSONObject(s)
        if (j.optBoolean("ok") && j.optString("token").isNotEmpty()) j.getString("token")
        else throw IllegalStateException(j.optString("error").ifEmpty { "Не получилось войти" })
    }

    private fun parseReply(code: Int, s: String): Reply {
        if (code == 401) return Reply(null, authExpired = true)
        val j = try { JSONObject(s) } catch (e: Exception) {
            return Reply(null, error = "Сервер ответил непонятно (код $code)")
        }
        if (j.optString("auth") == "expired") return Reply(null, authExpired = true)
        return Reply(
            text = j.optString("reply").ifEmpty { null },
            imageUrl = j.optString("image_url").ifEmpty { null },
            recognized = j.optString("recognized").ifEmpty { null },
            silent = j.optBoolean("silent") || j.optBoolean("wake"),
        )
    }

    fun chat(token: String, text: String): Reply = try {
        val (code, s) = postJson("/chat/izi", JSONObject().put("token", token).put("text", text))
        parseReply(code, s)
    } catch (e: Exception) {
        Reply(null, error = "Нет связи с сервером")
    }

    /** Вопрос со снимком экрана. null — сервер не принял картинку, тогда спросим текстом. */
    fun see(token: String, text: String, imageDataUrl: String): Reply? = try {
        val (code, s) = postJson(
            "/chat/izi_see",
            JSONObject().put("token", token).put("text", text).put("image", imageDataUrl),
        )
        val r = parseReply(code, s)
        val t = r.text?.lowercase().orEmpty()
        // сервер принял снимок, но не разглядел его — тогда спросим по тексту с экрана
        val failed = Regex("(не получилось|не удалось|не могу|не вижу).{0,25}(разглядеть|рассмотреть|увидеть|изображени|картинк|фото|снимок)").containsMatchIn(t)
        if (r.authExpired || (code in 200..299 && r.text != null && !failed)) r else null
    } catch (e: Exception) {
        null
    }

    /** Запасной путь, если на телефоне нет распознавания речи: отправляем запись, сервер сам распознаёт. */
    fun voice(token: String, audio: File): Reply = try {
        val boundary = "----izi" + System.currentTimeMillis()
        val c = open("/chat/izi_voice", 120_000)
        try {
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.outputStream.use { out ->
                fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                w("--$boundary\r\nContent-Disposition: form-data; name=\"token\"\r\n\r\n$token\r\n")
                w("--$boundary\r\nContent-Disposition: form-data; name=\"voice\"; filename=\"voice.mp4\"\r\nContent-Type: audio/mp4\r\n\r\n")
                audio.inputStream().use { it.copyTo(out) }
                w("\r\n--$boundary--\r\n")
            }
            val code = c.responseCode
            parseReply(code, readBody(c, code))
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        Reply(null, error = "Нет связи с сервером")
    }

    /** Голос Изи с сервера (как в веб-версии). Возвращает false — тогда говорим голосом телефона. */
    fun tts(token: String, text: String, out: File): Boolean = try {
        val c = open("/chat/izi_tts", 60_000)
        try {
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = JSONObject().put("token", token).put("text", text).put("voice", "onyx")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val type = c.contentType ?: ""
            if (code in 200..299 && !type.contains("json") && !type.contains("text")) {
                c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                out.length() > 500
            } else false
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        false
    }
}
