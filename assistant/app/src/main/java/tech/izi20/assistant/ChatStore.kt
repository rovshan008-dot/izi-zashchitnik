package tech.izi20.assistant

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Общая переписка с Изи: и в приложении, и из окошка «Привет, Изи» поверх других приложений. */
object ChatStore {
    private const val MAX = 150
    private var cache: MutableList<Msg>? = null

    @Volatile var version = 0
        private set

    private fun file(ctx: Context) = File(ctx.filesDir, "chat.json")

    @Synchronized
    fun all(ctx: Context): List<Msg> {
        cache?.let { return it.toList() }
        val list = mutableListOf<Msg>()
        try {
            val a = JSONArray(file(ctx).readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val ph = o.optJSONArray("photos")
                val photos = mutableListOf<Uri>()
                if (ph != null) for (j in 0 until ph.length()) photos += Uri.parse(ph.getString(j))
                list += Msg(o.optBoolean("me"), o.optString("text"), o.optString("image").ifEmpty { null }, photos)
            }
        } catch (_: Exception) {}
        cache = list
        return list.toList()
    }

    @Synchronized
    fun add(ctx: Context, m: Msg) {
        val list = cache ?: all(ctx).toMutableList().also { cache = it }
        list += m
        while (list.size > MAX) list.removeAt(0)
        version++
        save(ctx, list)
    }

    @Synchronized
    fun clear(ctx: Context) {
        cache = mutableListOf()
        version++
        save(ctx, emptyList())
    }

    private fun save(ctx: Context, list: List<Msg>) {
        try {
            val a = JSONArray()
            for (m in list) {
                a.put(JSONObject().put("me", m.me).put("text", m.text).put("image", m.image ?: "")
                    .put("photos", JSONArray(m.photos.map { it.toString() })))
            }
            file(ctx).writeText(a.toString())
        } catch (_: Exception) {}
    }

    /** Картинка из ответа (data:…) — в файл, чтобы не держать мегабайты текста в переписке. */
    fun storeImage(ctx: Context, image: String?): String? {
        if (image == null || !image.startsWith("data:")) return image
        return try {
            val ext = if (image.startsWith("data:image/png")) "png" else "jpg"
            val dir = File(ctx.filesDir, "images").apply { mkdirs() }
            val f = File(dir, "izi_${System.currentTimeMillis()}.$ext")
            f.writeBytes(Base64.decode(image.substringAfter(","), Base64.DEFAULT))
            Uri.fromFile(f).toString()
        } catch (_: Exception) { image }
    }
}
