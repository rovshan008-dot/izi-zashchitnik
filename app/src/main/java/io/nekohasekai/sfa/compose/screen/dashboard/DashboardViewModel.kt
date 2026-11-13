package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.lifecycle.viewModelScope
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.compose.base.BaseViewModel
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.model.Group
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import io.nekohasekai.sfa.utils.AppLifecycleObserver
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.HTTPClient
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.Date

enum class CardGroup(val pairGroup: CardPairGroup? = null) {
    ClashMode,
    UploadTraffic(CardPairGroup.Traffic),
    DownloadTraffic(CardPairGroup.Traffic),
    Debug(CardPairGroup.Statistics),
    Connections(CardPairGroup.Statistics),
    SystemProxy,
    Profiles,
}

enum class CardPairGroup {
    Traffic,
    Statistics,
}

/** Изи Защитник: сервер в списке выбора на главном экране. delayMs = 0, если задержка ещё не измерена. */
data class IziServer(val tag: String, val delayMs: Int = 0)

data class DashboardUiState(
    val serviceStatus: Status = Status.Stopped,
    // Изи Защитник: ручной выбор сервера на главном экране
    val iziServers: List<IziServer> = emptyList(),
    val iziSelectedServer: String? = null,
    // какой сервер сейчас выбрал режим «Авто» (известно только при включённой защите)
    val iziAutoServer: String? = null,
    val profiles: List<Profile> = emptyList(),
    val selectedProfileId: Long = -1L,
    val selectedProfileName: String? = null,
    val isLoading: Boolean = false,
    val hasGroups: Boolean = false,
    val groupsCount: Int = 0,
    val connectionsCount: Int = 0,
    val serviceStartTime: Long? = null,
    val deprecatedNotes: List<DeprecatedNote> = emptyList(),
    val showDeprecatedDialog: Boolean = false,
    val showAddProfileSheet: Boolean = false,
    val showProfilePickerSheet: Boolean = false,
    val updatingProfileId: Long? = null,
    val updatedProfileId: Long? = null,
    // Status
    val memory: String = "",
    val goroutines: String = "",
    val isStatusVisible: Boolean = false,
    // Traffic
    val trafficVisible: Boolean = false,
    val connectionsIn: String = "0",
    val connectionsOut: String = "0",
    val uplink: String = "0 B/s",
    val downlink: String = "0 B/s",
    val uplinkTotal: String = "0 B",
    val downlinkTotal: String = "0 B",
    val uplinkHistory: List<Float> = List(30) { 0f },
    val downlinkHistory: List<Float> = List(30) { 0f },
    // Clash Mode
    val clashModeVisible: Boolean = false,
    val clashModes: List<String> = emptyList(),
    val selectedClashMode: String = "",
    // System Proxy
    val systemProxyVisible: Boolean = false,
    val systemProxyEnabled: Boolean = false,
    val systemProxySwitching: Boolean = false,
    // Card visibility settings
    val visibleCards: Set<CardGroup> =
        setOf(
            CardGroup.ClashMode,
            CardGroup.UploadTraffic,
            CardGroup.DownloadTraffic,
            CardGroup.Debug,
            CardGroup.Connections,
            CardGroup.SystemProxy,
            CardGroup.Profiles,
        ),
    val cardOrder: List<CardGroup> =
        listOf(
            CardGroup.UploadTraffic,
            CardGroup.DownloadTraffic,
            CardGroup.Debug,
            CardGroup.Connections,
            CardGroup.SystemProxy,
            CardGroup.ClashMode,
            CardGroup.Profiles,
        ),
    val showCardSettingsDialog: Boolean = false,
) {
    data class DeprecatedNote(val message: String, val migrationLink: String?)
}

// DashboardViewModel now only uses UiEvent for all events
// No need for DashboardEvent anymore as all events are handled globally

class DashboardViewModel :
    BaseViewModel<DashboardUiState, UiEvent>(),
    CommandClient.Handler {
    // Изи Защитник: поля выбора сервера объявлены до блока init, потому что он сразу запускает загрузку профилей.
    private val iziPrefs by lazy {
        Application.application.getSharedPreferences("izi_servers", android.content.Context.MODE_PRIVATE)
    }

    // группа выбора сервера из работающего ядра; null, пока защита выключена
    @Volatile
    private var iziLiveGroup: Group? = null

    // для какого профиля и какого списка серверов запомненный выбор уже применён
    private var iziRestoredFor: Pair<Long, List<String>>? = null

    private val _serviceStatus = MutableStateFlow(Status.Stopped)
    val serviceStatus: StateFlow<Status> = _serviceStatus.asStateFlow()

    internal val commandClient =
        CommandClient(
            viewModelScope,
            listOf(
                CommandClient.ConnectionType.Status,
                CommandClient.ConnectionType.ClashMode,
                CommandClient.ConnectionType.Groups,
            ),
            this,
        )

    override fun createInitialState(): DashboardUiState {
        val savedOrder = loadItemOrder()
        val disabledItems = loadDisabledItems()

        // Calculate visible items (all items minus disabled)
        val allItems = CardGroup.values().toSet()
        val visibleCards = allItems - disabledItems

        return DashboardUiState(
            cardOrder = savedOrder,
            visibleCards = visibleCards,
        )
    }

    init {
        loadProfiles()
        ProfileManager.registerCallback(::onProfilesChanged)

        viewModelScope.launch {
            combine(
                AppLifecycleObserver.isForeground,
                RemoteControlManager.remoteServer,
                RemoteControlManager.isConnected,
                _serviceStatus,
            ) { foreground, remoteServer, remoteConnected, status ->
                SessionTarget(
                    connect = foreground &&
                        if (remoteServer != null) remoteConnected else status == Status.Started,
                    remoteServerId = remoteServer?.id,
                )
            }.distinctUntilChanged().collect { target ->
                if (target.connect) {
                    commandClient.connect()
                } else {
                    commandClient.disconnect()
                }
            }
        }
    }

    private data class SessionTarget(val connect: Boolean, val remoteServerId: Long?)

    override fun onCleared() {
        super.onCleared()
        ProfileManager.unregisterCallback(::onProfilesChanged)
        commandClient.disconnect()
    }

    private fun onProfilesChanged() {
        loadProfiles()
    }

    private fun loadProfiles() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val profiles = ProfileManager.list()
                val selectedId = Settings.selectedProfile

                withContext(Dispatchers.Main) {
                    updateState {
                        copy(
                            profiles = profiles,
                            selectedProfileId = selectedId,
                            selectedProfileName = profiles.find { it.id == selectedId }?.name,
                        )
                    }
                }
                if (iziLiveGroup == null) {
                    publishOfflineServers(profiles.find { it.id == selectedId })
                }
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    // ---- Изи Защитник: ручной выбор сервера ----
    // Пока защита включена, список и текущий выбор приходят от работающего ядра.
    // Пока выключена, список читается из файла профиля, а выбор запоминается и применяется при включении.

    private fun iziChoiceKey(profileId: Long) = "server_$profileId"

    private fun savedServer(profileId: Long): String? = iziPrefs.getString(iziChoiceKey(profileId), null)

    /** Список серверов из файла профиля: выходы группы-переключателя (обычно «proxy») и её выбор по умолчанию. */
    private fun readServersFromFile(profile: Profile?): Pair<List<String>, String?> {
        if (profile == null) return emptyList<String>() to null
        return runCatching {
            val outbounds = JSONObject(File(profile.typed.path).readText()).optJSONArray("outbounds")
                ?: return@runCatching emptyList<String>() to null
            var fallback: JSONObject? = null
            var chosen: JSONObject? = null
            for (i in 0 until outbounds.length()) {
                val item = outbounds.optJSONObject(i) ?: continue
                if (item.optString("type") != "selector") continue
                if (fallback == null) fallback = item
                if (item.optString("tag").equals("proxy", ignoreCase = true)) chosen = item
            }
            val selector = chosen ?: fallback ?: return@runCatching emptyList<String>() to null
            val tags = selector.optJSONArray("outbounds") ?: return@runCatching emptyList<String>() to null
            val list = (0 until tags.length()).map { tags.optString(it) }.filter { it.isNotEmpty() }
            list to selector.optString("default").ifEmpty { list.firstOrNull() }
        }.getOrDefault(emptyList<String>() to null)
    }

    private suspend fun publishOfflineServers(profile: Profile?) {
        val (tags, default) = readServersFromFile(profile)
        val saved = profile?.let { savedServer(it.id) }?.takeIf { it in tags }
        withContext(Dispatchers.Main) {
            if (iziLiveGroup != null) return@withContext
            updateState {
                copy(
                    iziServers = tags.map { IziServer(it) },
                    iziSelectedServer = saved ?: default,
                    iziAutoServer = null,
                )
            }
        }
    }

    /** Выбор сервера с главного экрана. Работает и при выключенной защите: выбор применится при включении. */
    fun selectServer(tag: String) {
        val profileId = Settings.selectedProfile
        if (profileId != -1L) {
            iziPrefs.edit().putString(iziChoiceKey(profileId), tag).apply()
        }
        val group = iziLiveGroup
        if (group == null) {
            updateState { copy(iziSelectedServer = tag) }
            return
        }
        applyServer(group, tag)
    }

    private fun applyServer(group: Group, tag: String) {
        if (group.selected == tag) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                CommandTarget.standaloneClient().selectOutbound(group.tag, tag)
                // Старые соединения закрываем, чтобы всё сразу пошло через новый сервер.
                runCatching { CommandTarget.standaloneClient().closeConnections() }
                withContext(Dispatchers.Main) {
                    iziLiveGroup = iziLiveGroup?.copy(selected = tag)
                    updateState { copy(iziSelectedServer = tag) }
                }
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    private fun onLiveGroups(groups: List<Group>) {
        val group = groups.firstOrNull { it.selectable && it.tag.equals("proxy", ignoreCase = true) }
            ?: groups.firstOrNull { it.selectable }
        iziLiveGroup = group
        if (group == null) return
        // «Авто» — это вложенная группа, которая сама выбирает живой сервер; показываем, какой именно.
        val auto = groups.firstOrNull { it.tag != group.tag && it.tag == group.selected }
        updateState {
            copy(
                iziServers = group.items.map { IziServer(it.tag, it.urlTestDelay) },
                iziSelectedServer = group.selected,
                iziAutoServer = auto?.selected?.takeIf { it.isNotEmpty() },
            )
        }
        // Запомненный выбор применяем один раз на каждый запуск и на каждый новый список серверов.
        val restoreKey = Settings.selectedProfile to group.items.map { it.tag }
        if (iziRestoredFor != restoreKey) {
            iziRestoredFor = restoreKey
            val saved = savedServer(Settings.selectedProfile)
            if (saved != null && saved != group.selected && group.items.any { it.tag == saved }) {
                applyServer(group, saved)
            }
        }
    }

    private fun onLiveGroupsGone() {
        iziLiveGroup = null
        iziRestoredFor = null
        viewModelScope.launch(Dispatchers.IO) {
            val profile = runCatching { ProfileManager.get(Settings.selectedProfile) }.getOrNull()
            publishOfflineServers(profile)
        }
    }

    private fun checkDeprecatedNotes() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // Check if deprecated warnings are disabled
                if (Settings.disableDeprecatedWarnings) {
                    return@launch
                }

                val notes = Libbox.newStandaloneCommandClient().deprecatedNotes
                if (notes.hasNext()) {
                    val notesList = mutableListOf<DashboardUiState.DeprecatedNote>()
                    while (notes.hasNext()) {
                        val note = notes.next()
                        notesList.add(
                            DashboardUiState.DeprecatedNote(
                                message = note.message(),
                                migrationLink = note.migrationLink,
                            ),
                        )
                    }
                    withContext(Dispatchers.Main) {
                        updateState {
                            copy(
                                deprecatedNotes = notesList,
                                showDeprecatedDialog = notesList.isNotEmpty(),
                            )
                        }
                    }
                }
            }
        }
    }

    fun toggleService() {
        when (currentState.serviceStatus) {
            Status.Starting, Status.Started -> stopService()
            Status.Stopped -> sendGlobalEvent(UiEvent.RequestStartService)
            else -> { /* Ignore while transitioning */ }
        }
    }

    private fun stopService() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                BoxService.stop()
                // Status will be updated via updateServiceStatus callback
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    fun dismissDeprecatedNote() {
        val notes = currentState.deprecatedNotes
        if (notes.isNotEmpty()) {
            updateState {
                copy(
                    deprecatedNotes = notes.drop(1),
                    showDeprecatedDialog = notes.size > 1,
                )
            }
        }
    }

    fun selectProfile(profileId: Long) {
        if (currentState.isLoading) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                updateState { copy(isLoading = true) }
                val profile = ProfileManager.get(profileId) ?: return@launch

                Settings.selectedProfile = profileId
                applySelectedProfile()

                withContext(Dispatchers.Main) {
                    loadProfiles()
                }
            } catch (e: Exception) {
                sendError(e)
            } finally {
                updateState { copy(isLoading = false) }
            }
        }
    }

    private suspend fun applySelectedProfile() {
        if (_serviceStatus.value != Status.Started) return
        if (Settings.detectServiceMode() == Settings.serviceMode) {
            Libbox.newStandaloneCommandClient().serviceReload()
            return
        }
        BoxService.stop()
        for (i in 0 until 30) {
            if (_serviceStatus.value == Status.Stopped) {
                break
            }
            delay(100L)
        }
        sendGlobalEvent(UiEvent.RequestStartService)
    }

    fun editProfile(profile: Profile) {
        updateState { copy(showProfilePickerSheet = false) }
        sendGlobalEvent(UiEvent.EditProfile(profile.id))
    }

    fun deleteProfile(profile: Profile) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Update UI immediately for responsiveness
                withContext(Dispatchers.Main) {
                    updateState {
                        copy(
                            profiles = profiles.filter { p -> p.id != profile.id },
                        )
                    }
                }
                // Then delete from database
                ProfileManager.delete(profile)
            } catch (e: Exception) {
                // Reload profiles if deletion fails
                loadProfiles()
                sendError(e)
            }
        }
    }

    /**
     * Изи Защитник: удаление с главного экрана. Если удалён выбранный профиль,
     * выбирается первый из оставшихся, а если профилей не осталось, защита выключается.
     */
    fun removeProfile(profile: Profile) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val wasSelected = Settings.selectedProfile == profile.id
                if (wasSelected) {
                    // Новый выбор ставим до удаления, чтобы экран сразу показал правильное подключение.
                    val next = ProfileManager.list().firstOrNull { it.id != profile.id }
                    Settings.selectedProfile = next?.id ?: -1L
                }
                ProfileManager.delete(profile)
                runCatching { File(profile.typed.path).delete() }
                if (wasSelected) {
                    if (Settings.selectedProfile != -1L) {
                        applySelectedProfile()
                    } else if (_serviceStatus.value == Status.Started || _serviceStatus.value == Status.Starting) {
                        BoxService.stop()
                    }
                }
            } catch (e: Exception) {
                sendError(e)
            } finally {
                loadProfiles()
            }
        }
    }

    /** Изи Защитник: переименование профиля с главного экрана. */
    fun renameProfile(profile: Profile, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val stored = ProfileManager.get(profile.id) ?: return@launch
                stored.name = name
                ProfileManager.update(stored)
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    fun shareProfile(profile: Profile) {
        // Handled directly in ProfilesCard
    }

    fun shareProfileURL(profile: Profile) {
        // Handled directly in ProfilesCard
    }

    fun updateProfile(profile: Profile) {
        if (profile.typed.type != TypedProfile.Type.Remote) return

        viewModelScope.launch(Dispatchers.IO) {
            // Set updating state
            withContext(Dispatchers.Main) {
                updateState { copy(updatingProfileId = profile.id) }
            }

            try {
                // Fetch remote config
                val content = HTTPClient().use { it.getString(profile.typed.remoteURL) }
                Libbox.checkConfig(content)

                // Check if content changed
                val file = File(profile.typed.path)
                var contentChanged = false
                if (!file.exists() || file.readText() != content) {
                    file.writeText(content)
                    contentChanged = true
                }

                // Update last updated time
                profile.typed.lastUpdated = Date()
                ProfileManager.update(profile)

                if (contentChanged && profile.id == Settings.selectedProfile) {
                    try {
                        applySelectedProfile()
                    } catch (e: Exception) {
                        sendError(e)
                    }
                }

                // Reload profiles
                loadProfiles()

                // Show success state
                withContext(Dispatchers.Main) {
                    updateState { copy(updatingProfileId = null, updatedProfileId = profile.id) }
                }

                // Clear success state after delay
                withContext(Dispatchers.Main) {
                    delay(1500)
                    updateState { copy(updatedProfileId = null) }
                }
            } catch (e: Exception) {
                sendErrorMessage("Failed to update profile: ${e.message}")
                // Clear updating state on error
                withContext(Dispatchers.Main) {
                    updateState { copy(updatingProfileId = null) }
                }
            }
        }
    }

    fun moveProfile(from: Int, to: Int) {
        val currentProfiles = currentState.profiles.toMutableList()

        if (from < to) {
            for (i in from until to) {
                Collections.swap(currentProfiles, i, i + 1)
            }
        } else {
            for (i in from downTo to + 1) {
                Collections.swap(currentProfiles, i, i - 1)
            }
        }

        // Update UI immediately
        updateState { copy(profiles = currentProfiles) }

        // Update user order in database
        viewModelScope.launch(Dispatchers.IO) {
            currentProfiles.forEachIndexed { index, profile ->
                profile.userOrder = index.toLong()
            }
            ProfileManager.update(currentProfiles)
        }
    }

    fun showAddProfileSheet() {
        updateState { copy(showAddProfileSheet = true) }
    }

    fun hideAddProfileSheet() {
        updateState { copy(showAddProfileSheet = false) }
    }

    fun showProfilePickerSheet() {
        updateState { copy(showProfilePickerSheet = true) }
    }

    fun hideProfilePickerSheet() {
        updateState { copy(showProfilePickerSheet = false) }
    }

    fun updateServiceStatus(status: Status) {
        viewModelScope.launch {
            _serviceStatus.emit(status)
            updateState {
                copy(
                    serviceStatus = status,
                    isStatusVisible =
                    if (RemoteControlManager.remoteServer.value != null) {
                        isStatusVisible
                    } else {
                        status == Status.Starting || status == Status.Started
                    },
                )
            }
            handleServiceStatusChange(status)
        }
    }

    private fun handleServiceStatusChange(status: Status) {
        val isRemote = RemoteControlManager.remoteServer.value != null
        when (status) {
            Status.Started -> {
                checkDeprecatedNotes()
                if (isRemote) {
                    return
                }
                reloadSystemProxyStatus()
                reloadStartedAt()
            }

            Status.Stopped -> {
                if (isRemote) {
                    return
                }
                updateState {
                    copy(
                        hasGroups = false,
                        groupsCount = 0,
                        connectionsCount = 0,
                        serviceStartTime = null,
                        clashModeVisible = false,
                        systemProxyVisible = false,
                        trafficVisible = false,
                        memory = "",
                        goroutines = "",
                        connectionsIn = "0",
                        connectionsOut = "0",
                        uplink = "0 B/s",
                        downlink = "0 B/s",
                        uplinkTotal = "0 B",
                        downlinkTotal = "0 B",
                        uplinkHistory = List(30) { 0f },
                        downlinkHistory = List(30) { 0f },
                    )
                }
                onLiveGroupsGone()
            }

            else -> {}
        }
    }

    private fun reloadStartedAt() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val startedAt = Libbox.newStandaloneCommandClient().startedAt
                withContext(Dispatchers.Main) {
                    updateState {
                        copy(serviceStartTime = startedAt)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun reloadSystemProxyStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val status = Libbox.newStandaloneCommandClient().systemProxyStatus
                withContext(Dispatchers.Main) {
                    updateState {
                        copy(
                            systemProxyVisible = status.available,
                            systemProxyEnabled = status.enabled,
                        )
                    }
                }
            } catch (e: Exception) {
                // Ignore errors
            }
        }
    }

    fun toggleSystemProxy(enabled: Boolean) {
        if (currentState.systemProxySwitching) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                updateState { copy(systemProxySwitching = true) }
                Settings.systemProxyEnabled = enabled
                Libbox.newStandaloneCommandClient().setSystemProxyEnabled(enabled)
                delay(1000L)
                withContext(Dispatchers.Main) {
                    updateState {
                        copy(
                            systemProxyEnabled = enabled,
                            systemProxySwitching = false,
                        )
                    }
                }
            } catch (e: Exception) {
                sendError(e)
                updateState { copy(systemProxySwitching = false) }
            }
        }
    }

    fun selectClashMode(mode: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                CommandTarget.standaloneClient().setClashMode(mode)
                // Update UI state directly without reconnecting
                withContext(Dispatchers.Main) {
                    updateState {
                        copy(selectedClashMode = mode)
                    }
                }
            } catch (e: Exception) {
                sendError(e)
            }
        }
    }

    // CommandClient.Handler implementation
    override fun onConnected() {
        viewModelScope.launch(Dispatchers.Main) {
            updateState { copy(isStatusVisible = true) }
            // Returning from remote control skipped the local reloads that
            // normally run when the service starts.
            if (RemoteControlManager.remoteServer.value == null && _serviceStatus.value == Status.Started) {
                reloadSystemProxyStatus()
                reloadStartedAt()
            }
        }
    }

    override fun onDisconnected() {
        viewModelScope.launch(Dispatchers.Main) {
            updateState {
                copy(
                    memory = "",
                    goroutines = "",
                    isStatusVisible = false,
                )
            }
        }
    }

    override fun updateStatus(status: StatusMessage) {
        viewModelScope.launch(Dispatchers.Main) {
            updateState {
                // Update history by adding new values and removing old ones
                val newUplinkHistory = (uplinkHistory.drop(1) + status.uplink.toFloat())
                val newDownlinkHistory = (downlinkHistory.drop(1) + status.downlink.toFloat())

                // Format the total values
                val newUplinkTotal = Libbox.formatBytes(status.uplinkTotal)
                val newDownlinkTotal = Libbox.formatBytes(status.downlinkTotal)

                copy(
                    memory = Libbox.formatBytes(status.memory),
                    goroutines = status.goroutines.toString(),
                    // Only set trafficVisible to true, never back to false from status updates
                    trafficVisible = if (status.trafficAvailable) true else trafficVisible,
                    connectionsCount = status.connectionsIn,
                    connectionsIn = status.connectionsIn.toString(),
                    connectionsOut = status.connectionsOut.toString(),
                    uplink = "${Libbox.formatBytes(status.uplink)}/s",
                    downlink = "${Libbox.formatBytes(status.downlink)}/s",
                    // Only update total values if they've actually changed
                    uplinkTotal = if (newUplinkTotal != uplinkTotal) newUplinkTotal else uplinkTotal,
                    downlinkTotal = if (newDownlinkTotal != downlinkTotal) newDownlinkTotal else downlinkTotal,
                    uplinkHistory = newUplinkHistory,
                    downlinkHistory = newDownlinkHistory,
                )
            }
        }
    }

    override fun initializeClashMode(modeList: List<String>, currentMode: String) {
        viewModelScope.launch(Dispatchers.Main) {
            updateState {
                copy(
                    clashModeVisible = modeList.size > 1,
                    clashModes = modeList,
                    selectedClashMode = currentMode,
                )
            }
        }
    }

    override fun updateClashMode(newMode: String) {
        viewModelScope.launch(Dispatchers.Main) {
            updateState {
                copy(selectedClashMode = newMode)
            }
        }
    }

    override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
        // Группы переводим в свои объекты сразу, пока данные ядра точно действительны.
        val groups = runCatching { newGroups.map { Group(it) } }.getOrDefault(emptyList())
        val count = newGroups.size
        viewModelScope.launch(Dispatchers.Main) {
            updateState {
                copy(hasGroups = count > 0, groupsCount = count)
            }
            if (_serviceStatus.value == Status.Started) {
                onLiveGroups(groups)
            }
        }
    }

    fun toggleCardSettingsDialog() {
        updateState {
            copy(showCardSettingsDialog = !showCardSettingsDialog)
        }
    }

    fun toggleCardVisibility(cardGroup: CardGroup) {
        // Profiles card cannot be disabled
        if (cardGroup == CardGroup.Profiles) {
            return
        }

        updateState {
            val newVisibleCards =
                if (visibleCards.contains(cardGroup)) {
                    visibleCards - cardGroup
                } else {
                    visibleCards + cardGroup
                }
            // Save disabled items to settings
            saveDisabledItems(newVisibleCards)
            // Also save the current order if not already saved (indicates user has configured dashboard)
            if (Settings.dashboardItemOrder.isBlank()) {
                saveItemOrder(cardOrder)
            }
            copy(visibleCards = newVisibleCards)
        }
    }

    fun closeCardSettingsDialog() {
        updateState {
            copy(showCardSettingsDialog = false)
        }
    }

    fun reorderCards(newOrder: List<CardGroup>) {
        updateState {
            saveItemOrder(newOrder)
            copy(cardOrder = newOrder)
        }
    }

    fun resetCardOrder() {
        // Clear saved settings to restore defaults
        Settings.dashboardItemOrder = ""
        Settings.dashboardDisabledItems = emptySet()

        updateState {
            copy(
                cardOrder = getDefaultItemOrder(),
                visibleCards = CardGroup.values().toSet(),
            )
        }
    }

    // Helper functions for serialization
    private fun getDefaultItemOrder() = listOf(
        CardGroup.UploadTraffic,
        CardGroup.DownloadTraffic,
        CardGroup.Debug,
        CardGroup.Connections,
        CardGroup.SystemProxy,
        CardGroup.ClashMode,
        CardGroup.Profiles,
    )

    private fun loadItemOrder(): List<CardGroup> {
        val savedOrder = Settings.dashboardItemOrder
        if (savedOrder.isBlank()) {
            return getDefaultItemOrder()
        }

        return try {
            val jsonArray = JSONArray(savedOrder)
            val order = mutableListOf<CardGroup>()

            for (i in 0 until jsonArray.length()) {
                val itemName = jsonArray.getString(i)
                stringToCardGroup(itemName)?.let { order.add(it) }
            }

            // Add any new items that aren't in the saved order
            val allItems = CardGroup.values().toSet()
            val savedItems = order.toSet()
            val newItems = allItems - savedItems

            order.addAll(newItems)
            order
        } catch (e: JSONException) {
            getDefaultItemOrder()
        }
    }

    private fun saveItemOrder(order: List<CardGroup>) {
        val jsonArray = JSONArray()
        order.forEach { item ->
            jsonArray.put(cardGroupToString(item))
        }
        Settings.dashboardItemOrder = jsonArray.toString()
    }

    private fun loadDisabledItems(): Set<CardGroup> {
        val savedDisabled = Settings.dashboardDisabledItems
        // Filter out Profiles from disabled items (it cannot be disabled)
        return savedDisabled.mapNotNull { stringToCardGroup(it) }
            .filter { it != CardGroup.Profiles }
            .toSet()
    }

    private fun saveDisabledItems(visibleCards: Set<CardGroup>) {
        val allItems = CardGroup.values().toSet()
        // Always ensure Profiles is in visibleCards (cannot be disabled)
        val actualVisibleCards = visibleCards + CardGroup.Profiles
        val disabledItems = allItems - actualVisibleCards
        Settings.dashboardDisabledItems = disabledItems.map { cardGroupToString(it) }.toSet()
    }

    private fun cardGroupToString(card: CardGroup): String = card.name

    private fun stringToCardGroup(name: String): CardGroup? = try {
        CardGroup.valueOf(name)
    } catch (e: IllegalArgumentException) {
        null
    }
}
