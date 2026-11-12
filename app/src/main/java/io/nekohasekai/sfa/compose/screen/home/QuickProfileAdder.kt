package io.nekohasekai.sfa.compose.screen.home

import android.content.Context
import android.net.Uri
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.UpdateProfileWork
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.HTTPClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

/**
 * Изи Защитник: добавление профиля одной кнопкой.
 *
 * Принимает что угодно, в чём есть ссылка: обычную ссылку-подписку, ссылку с ".sb",
 * ссылку кнопки с сайта (izi-zashchitnik://add?url=...) или sing-box://import-remote-profile.
 * Если ссылка без ".sb", сначала пробуется та же ссылка с ".sb" (формат sing-box на сервере).
 */
object QuickProfileAdder {
    const val SCHEME = "izi-zashchitnik"
    const val HOST_ADD = "add"

    private const val SING_BOX_FORMAT_SUFFIX = ".sb"
    private const val UPDATE_INTERVAL_MINUTES = 60
    private val linkPattern = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    data class Link(val url: String, val name: String?) {
        /** Адрес сервера для показа человеку перед добавлением. */
        val host: String
            get() = runCatching { Uri.parse(url).host }.getOrNull().orEmpty().ifEmpty { url }
    }

    sealed class Result {
        data class Added(val profile: Profile) : Result()

        data class Refreshed(val profile: Profile) : Result()

        data class Failed(val message: String) : Result()
    }

    /** Достаёт ссылку на подписку из произвольного текста. Null, если ссылки нет. */
    fun parseLink(text: String?): Link? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null

        if (raw.startsWith("$SCHEME:", ignoreCase = true)) {
            return parseButtonLink(raw)
        }
        if (raw.startsWith("sing-box://import-remote-profile", ignoreCase = true)) {
            return try {
                val info = Libbox.parseRemoteProfileImportLink(raw)
                cleanUrl(info.url)?.let { Link(it, info.name?.takeIf { name -> name.isNotBlank() }) }
            } catch (_: Exception) {
                null
            }
        }
        val match = linkPattern.find(raw) ?: return null
        return cleanUrl(match.value)?.let { Link(it, null) }
    }

    /** Ссылка кнопки с сайта: izi-zashchitnik://add?url=<ссылка>&name=<название>. */
    private fun parseButtonLink(raw: String): Link? {
        val uri = try {
            Uri.parse(raw)
        } catch (_: Exception) {
            return null
        }
        var url: String? = null
        var name: String? = null
        if (uri.isHierarchical) {
            url = runCatching { uri.getQueryParameter("url") }.getOrNull()
            name = runCatching { uri.getQueryParameter("name") }.getOrNull()
            if (name.isNullOrBlank()) {
                name = uri.fragment
            }
        }
        if (url.isNullOrBlank()) {
            // Ссылка без кодирования: берём всё после "url=".
            val index = raw.indexOf("url=")
            if (index >= 0) {
                url = Uri.decode(raw.substring(index + 4))
            }
        }
        val found = url?.let { linkPattern.find(it)?.value } ?: return null
        return cleanUrl(found)?.let { Link(it, name?.trim()?.takeIf { value -> value.isNotEmpty() }) }
    }

    private fun cleanUrl(url: String?): String? {
        val cleaned = url?.trim()?.trimEnd('.', ',', ';', ')') ?: return null
        if (!cleaned.startsWith("http://", ignoreCase = true) && !cleaned.startsWith("https://", ignoreCase = true)) {
            return null
        }
        return cleaned
    }

    /** Адреса, которые стоит попробовать, по порядку. */
    private fun candidates(url: String): List<String> {
        val hasQuery = url.contains('?') || url.contains('#')
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        if (hasQuery || path.endsWith(SING_BOX_FORMAT_SUFFIX) || path.endsWith(".json")) {
            return listOf(url)
        }
        return listOf(url.trimEnd('/') + SING_BOX_FORMAT_SUFFIX, url)
    }

    suspend fun add(context: Context, link: Link): Result = withContext(Dispatchers.IO) {
        try {
            var workingUrl: String? = null
            var config: String? = null
            var lastError: String? = null
            for (candidate in candidates(link.url)) {
                try {
                    val content = HTTPClient().use { it.getString(candidate) }
                    Libbox.checkConfig(content)
                    workingUrl = candidate
                    config = content
                    break
                } catch (e: Exception) {
                    lastError = e.message ?: e.toString()
                }
            }
            if (workingUrl == null || config == null) {
                return@withContext Result.Failed(
                    context.getString(R.string.izi_add_failed, link.url, lastError ?: ""),
                )
            }

            val profiles = ProfileManager.list()
            val existing = profiles.firstOrNull {
                it.typed.type == TypedProfile.Type.Remote && it.typed.remoteURL == workingUrl
            }
            if (existing != null) {
                File(existing.typed.path).writeText(config)
                existing.typed.lastUpdated = Date()
                ProfileManager.update(existing)
                return@withContext Result.Refreshed(existing)
            }

            val typedProfile =
                TypedProfile().apply {
                    type = TypedProfile.Type.Remote
                    remoteURL = workingUrl
                    autoUpdate = true
                    autoUpdateInterval = UPDATE_INTERVAL_MINUTES
                    lastUpdated = Date()
                }
            val profile =
                Profile(name = uniqueName(context, link.name, profiles), typed = typedProfile).apply {
                    userOrder = ProfileManager.nextOrder()
                }
            val fileID = ProfileManager.nextFileID()
            val configDirectory = File(context.filesDir, "configs").also { it.mkdirs() }
            val configFile = File(configDirectory, "$fileID.json")
            configFile.writeText(config)
            typedProfile.path = configFile.path

            ProfileManager.create(profile, andSelect = false)
            UpdateProfileWork.reconfigureUpdater()
            Result.Added(profile)
        } catch (e: Exception) {
            Result.Failed(context.getString(R.string.izi_add_failed, link.url, e.message ?: e.toString()))
        }
    }

    private fun uniqueName(context: Context, wanted: String?, profiles: List<Profile>): String {
        val taken = profiles.map { it.name }.toSet()
        val base = wanted?.trim().orEmpty()
        if (base.isNotEmpty()) {
            if (base !in taken) return base
            var suffix = 2
            while ("$base $suffix" in taken) suffix++
            return "$base $suffix"
        }
        var number = profiles.size + 1
        while (context.getString(R.string.izi_profile_default_name, number) in taken) number++
        return context.getString(R.string.izi_profile_default_name, number)
    }
}
