package tech.izi20.assistant

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import tech.izi20.assistant.PhoneActions.Local
import java.util.Calendar

/** Фото на телефоне: «покажи фото за вчера», «последние скриншоты», «отправь последнее фото маме». */
object Photos {

    fun readPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    /** Что просить для полного доступа: фото и видео. */
    fun permsToAsk(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun hasFullAccess(ctx: Context): Boolean = permsToAsk().all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    fun canRead(ctx: Context): Boolean {
        if (ContextCompat.checkSelfPermission(ctx, readPermission()) == PackageManager.PERMISSION_GRANTED) return true
        // Android 14: доступ «только к выбранным фото» — тоже считаем, увидим то, что разрешили
        return Build.VERSION.SDK_INT >= 34 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
    }

    private val PHOTO_WORD = Regex("(фото|фотк|фоточ|снимк|снимок|скрин|скриншот|картинк|изображен|видео|ролик)")

    fun handle(ctx: Context, t: String): Local? {
        if (!PHOTO_WORD.containsMatchIn(t)) return null
        val verb = Regex("^(покажи|найди|открой|где|отправь|скинь|пошли|перешли|поделись)").find(t)?.value ?: return null
        // «нарисуй картинку», «сгенерируй фото» — это мозг Изи
        if (Regex("(нарисуй|сгенерир|создай|сделай картин)").containsMatchIn(t)) return null
        if (!canRead(ctx)) return Local("Разреши Изи доступ к фото, и я покажу", readPermission())

        val screenshots = t.contains("скрин")
        val video = Regex("(видео|ролик)").containsMatchIn(t)
        val one = Regex("(последн(ее|юю|ий)|последний снимок|последнее фото)").containsMatchIn(t) &&
            !Regex("последни[ех]").containsMatchIn(t)
        val count = Regex("последни[ех]\\s+(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull()
        val (from, to, label) = period(t)
        val limit = when { one -> 1; count != null -> count.coerceIn(1, 60); else -> 30 }
        val list = query(ctx, from, to, screenshots, limit, video)
        val what = when { video -> "видео"; screenshots -> "скриншот"; else -> "фото" }

        if (list.isEmpty()) return Local("Не нашёл $what${if (label.isNotEmpty()) " $label" else ""}")

        if (verb in listOf("отправь", "скинь", "пошли", "перешли", "поделись")) {
            // кому: «отправь последнее фото маме в ватсап»
            val channel = Messaging.channelOf(t)
            val rest = t.replace(Regex("^(отправь|скинь|пошли|перешли|поделись)\\s+"), "")
                .replace(PHOTO_WORD, " ").replace(Regex("(последн[а-я]*|за|сегодня|вчера|позавчера|неделю|месяц|все|мои|с камеры|\\d+)"), " ")
                .replace(Regex("(?:^|\\s)(?:в|по|через)?\\s*(вотсап[а-я]*|ватсап[а-я]*|вацап[а-я]*|whatsapp|телеграм[а-я]*|телег[уеа]|смс[а-я]*)"), " ")
                .replace(Regex("\\s+"), " ").trim()
            val contact = if (rest.isNotEmpty()) Messaging.resolveOne(ctx, rest) else null
            return Messaging.sharePhotos(ctx, list, contact, channel).let { r -> r.copy(photos = list) }
        }

        val n = list.size
        val head = when {
            n == 1 -> "Вот последнее $what"
            label.isNotEmpty() -> "Вот $what $label: $n шт."
            else -> "Вот последние $what: $n шт."
        }
        return Local("$head. Нажми, чтобы открыть, или удерживай, чтобы отправить", photos = list)
    }

    private data class Period(val from: Long?, val to: Long?, val label: String)

    private fun dayStart(shiftDays: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, shiftDays)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun period(t: String): Period = when {
        t.contains("позавчера") -> Period(dayStart(-2), dayStart(-1), "за позавчера")
        t.contains("вчера") -> Period(dayStart(-1), dayStart(0), "за вчера")
        t.contains("сегодня") -> Period(dayStart(0), null, "за сегодня")
        t.contains("недел") -> Period(dayStart(-7), null, "за неделю")
        t.contains("месяц") -> Period(dayStart(-30), null, "за месяц")
        else -> Period(null, null, "")
    }

    private fun query(ctx: Context, from: Long?, to: Long?, screenshots: Boolean, limit: Int, video: Boolean = false): List<Uri> {
        val coll = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val sel = mutableListOf<String>()
        val args = mutableListOf<String>()
        // дата: когда снято, а если неизвестно — когда появилось на телефоне (в секундах)
        if (from != null) { sel += "${MediaStore.Images.Media.DATE_ADDED} >= ?"; args += (from / 1000).toString() }
        if (to != null) { sel += "${MediaStore.Images.Media.DATE_ADDED} < ?"; args += (to / 1000).toString() }
        if (screenshots) {
            sel += "(${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ? OR ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?)"
            args += "%creenshot%"; args += "%криншот%"
        }
        val out = mutableListOf<Uri>()
        try {
            ctx.contentResolver.query(
                coll, arrayOf(MediaStore.Images.Media._ID),
                if (sel.isEmpty()) null else sel.joinToString(" AND "),
                if (args.isEmpty()) null else args.toTypedArray(),
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) out += ContentUris.withAppendedId(coll, c.getLong(0))
            }
        } catch (_: Exception) {}
        return out
    }

    fun thumbnail(ctx: Context, uri: Uri, px: Int = 320): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(uri, Size(px, px), null)
        else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var s = 1
            while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
        }
    } catch (_: Exception) { null }

    /** Самое свежее фото или видео из галереи. */
    fun latest(ctx: Context, video: Boolean): Uri? {
        if (!canRead(ctx)) return null
        val coll = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val idCol = if (video) MediaStore.Video.Media._ID else MediaStore.Images.Media._ID
        val dateCol = if (video) MediaStore.Video.Media.DATE_ADDED else MediaStore.Images.Media.DATE_ADDED
        return try {
            ctx.contentResolver.query(coll, arrayOf(idCol), null, null, "$dateCol DESC")?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(coll, c.getLong(0)) else null
            }
        } catch (_: Exception) { null }
    }

    /** Сохранить картинку от Изи в Галерею → «Izi». Возвращает адрес файла в галерее. */
    fun saveToGallery(ctx: Context, bytes: ByteArray, mime: String): Uri? {
        val ext = if (mime.contains("png")) "png" else "jpg"
        val name = "izi_${System.currentTimeMillis()}.$ext"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Izi")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Izi")
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, java.io.File(dir, name).absolutePath)
            }
        }
        return try {
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            if (Build.VERSION.SDK_INT >= 29) {
                ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            uri
        } catch (_: Exception) { null }
    }

    /** Сохранить видео от Изи или с компьютера в Галерею → «Izi». */
    fun saveVideoToGallery(ctx: Context, bytes: ByteArray, mime: String, srcName: String? = null): Uri? {
        val ext = when {
            srcName?.contains('.') == true -> srcName.substringAfterLast('.').take(5)
            mime.contains("mp4") -> "mp4"; mime.contains("webm") -> "webm"; mime.contains("quicktime") -> "mov"
            mime.contains("3gpp") -> "3gp"; else -> "mp4"
        }
        val name = "izi_${System.currentTimeMillis()}.$ext"
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, mime.ifBlank { "video/mp4" })
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Izi")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Izi")
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.Video.Media.DATA, java.io.File(dir, name).absolutePath)
            }
        }
        return try {
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            if (Build.VERSION.SDK_INT >= 29) {
                ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }
            uri
        } catch (_: Exception) { null }
    }

    /** Сохранить в галерею любой медиафайл (фото или видео), выбрав по типу. */
    fun saveMediaToGallery(ctx: Context, bytes: ByteArray, mime: String, srcName: String? = null): Uri? =
        if (mime.startsWith("video/") || srcName?.substringAfterLast('.', "")?.lowercase() in setOf("mp4", "mov", "webm", "3gp", "mkv", "avi"))
            saveVideoToGallery(ctx, bytes, mime, srcName)
        else saveToGallery(ctx, bytes, if (mime.startsWith("image/")) mime else "image/jpeg")
}
