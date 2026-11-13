package io.nekohasekai.sfa.compose.screen.home

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.component.UptimeText
import io.nekohasekai.sfa.compose.screen.dashboard.DashboardUiState
import io.nekohasekai.sfa.compose.screen.dashboard.IziServer
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.TypedProfile
import java.text.SimpleDateFormat
import java.util.Locale

// Палитра главного экрана: та же, что на сайте «Цифровая безопасность» (тёмный фон, зелёный акцент).
private val IziGround = Color(0xFF080C12)
private val IziSurface = Color(0xFF111720)
private val IziSurfaceHigh = Color(0xFF1B232E)
private val IziLine = Color(0xFF1F2731)
private val IziLineStrong = Color(0xFF2A3440)
private val IziText = Color(0xFFF2F5F8)
private val IziMuted = Color(0xFF9AA7B5)
private val IziGreen = Color(0xFF3EE08A)
private val IziOnGreen = Color(0xFF06210F)
private val IziSelected = Color(0xFF0F1D17)

// Главный экран всегда тёмный, какая бы тема ни стояла в телефоне; диалоги экрана берут те же цвета.
private val IziColorScheme = darkColorScheme(
    primary = IziGreen,
    onPrimary = IziOnGreen,
    primaryContainer = IziSelected,
    onPrimaryContainer = IziText,
    secondary = IziGreen,
    onSecondary = IziOnGreen,
    background = IziGround,
    onBackground = IziText,
    surface = IziSurface,
    onSurface = IziText,
    surfaceVariant = IziSurface,
    onSurfaceVariant = IziMuted,
    surfaceContainerLowest = IziGround,
    surfaceContainerLow = IziSurface,
    surfaceContainer = IziSurface,
    surfaceContainerHigh = IziSurface,
    surfaceContainerHighest = IziSurfaceHigh,
    outline = IziLineStrong,
    outlineVariant = IziLine,
    error = Color(0xFFFF8A80),
    onError = Color(0xFF2B0A07),
)

/**
 * Изи Защитник: простой главный экран.
 * Одна кнопка «Включить», одна кнопка «Добавить», список подключений для переключения.
 * Всё остальное живёт в разделе «Для мастера».
 */
@Composable
fun IziHomeScreen(
    serviceStatus: Status,
    uiState: DashboardUiState,
    isAdding: Boolean,
    onToggleService: () -> Unit,
    onAdd: (QuickProfileAdder.Link) -> Unit,
    onSelectProfile: (Long) -> Unit,
    onUpdateProfile: (Profile) -> Unit,
    onRenameProfile: (Profile, String) -> Unit,
    onRemoveProfile: (Profile) -> Unit,
    onSelectServer: (String) -> Unit,
    onOpenMaster: () -> Unit,
) {
    // Экран тёмный, поэтому значки в строке состояния телефона делаем светлыми; при уходе с экрана возвращаем как было.
    val view = LocalView.current
    val systemDark = isSystemInDarkTheme()
    if (!view.isInEditMode) {
        // После каждой перерисовки: общая тема приложения может вернуть тёмные значки.
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        DisposableEffect(view, systemDark) {
            onDispose {
                val window = (view.context as? Activity)?.window
                if (window != null) {
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !systemDark
                        isAppearanceLightNavigationBars = !systemDark
                    }
                }
            }
        }
    }

    MaterialTheme(colorScheme = IziColorScheme) {
        IziHomeContent(
            serviceStatus = serviceStatus,
            uiState = uiState,
            isAdding = isAdding,
            onToggleService = onToggleService,
            onAdd = onAdd,
            onSelectProfile = onSelectProfile,
            onUpdateProfile = onUpdateProfile,
            onRenameProfile = onRenameProfile,
            onRemoveProfile = onRemoveProfile,
            onSelectServer = onSelectServer,
            onOpenMaster = onOpenMaster,
        )
    }
}

@Composable
private fun IziHomeContent(
    serviceStatus: Status,
    uiState: DashboardUiState,
    isAdding: Boolean,
    onToggleService: () -> Unit,
    onAdd: (QuickProfileAdder.Link) -> Unit,
    onSelectProfile: (Long) -> Unit,
    onUpdateProfile: (Profile) -> Unit,
    onRenameProfile: (Profile, String) -> Unit,
    onRemoveProfile: (Profile) -> Unit,
    onSelectServer: (String) -> Unit,
    onOpenMaster: () -> Unit,
) {
    val context = LocalContext.current
    val profiles = uiState.profiles
    val selectedProfileId = uiState.selectedProfileId
    val hasProfiles = profiles.isNotEmpty()

    var showPasteDialog by remember { mutableStateOf(false) }
    var showServerDialog by remember { mutableStateOf(false) }
    var profileToRename by remember { mutableStateOf<Profile?>(null) }
    var profileToRemove by remember { mutableStateOf<Profile?>(null) }

    // Если выбранное подключение пропало (удалили), берём первое из списка.
    LaunchedEffect(profiles, selectedProfileId) {
        if (profiles.isNotEmpty() && profiles.none { it.id == selectedProfileId }) {
            onSelectProfile(profiles.first().id)
        }
    }

    if (showPasteDialog) {
        PasteLinkDialog(
            onDismiss = { showPasteDialog = false },
            onAdd = { link ->
                showPasteDialog = false
                onAdd(link)
            },
        )
    }

    if (showServerDialog) {
        ServerDialog(
            servers = uiState.iziServers,
            selected = uiState.iziSelectedServer,
            autoServer = uiState.iziAutoServer,
            onDismiss = { showServerDialog = false },
            onSelect = { tag ->
                showServerDialog = false
                onSelectServer(tag)
            },
        )
    }

    profileToRename?.let { profile ->
        RenameDialog(
            currentName = profile.name,
            onDismiss = { profileToRename = null },
            onSave = { name ->
                profileToRename = null
                onRenameProfile(profile, name)
            },
        )
    }

    profileToRemove?.let { profile ->
        AlertDialog(
            onDismissRequest = { profileToRemove = null },
            title = { Text(stringResource(R.string.izi_profile_remove_title)) },
            text = { Text(stringResource(R.string.izi_profile_remove_message, profile.name)) },
            confirmButton = {
                TextButton(onClick = {
                    profileToRemove = null
                    onRemoveProfile(profile)
                }) {
                    Text(stringResource(R.string.izi_profile_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { profileToRemove = null }) {
                    Text(stringResource(R.string.izi_cancel))
                }
            },
        )
    }

    val onAddClick: () -> Unit = {
        val link = QuickProfileAdder.parseLink(readClipboard(context))
        if (link != null) {
            onAdd(link)
        } else {
            showPasteDialog = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IziGround)
            .padding(LocalScaffoldPadding.current),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(IziGreen, RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = IziOnGreen,
                        modifier = Modifier.size(17.dp),
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = IziText,
                )
            }

            Spacer(modifier = Modifier.height(40.dp))

            PowerButton(
                serviceStatus = serviceStatus,
                enabled = hasProfiles,
                onClick = onToggleService,
            )

            Spacer(modifier = Modifier.height(24.dp))

            StatusLine(
                serviceStatus = serviceStatus,
                hasProfiles = hasProfiles,
                startTime = uiState.serviceStartTime,
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Ручной выбор сервера: показываем, когда в подключении больше одного сервера.
            if (hasProfiles && uiState.iziServers.size > 1) {
                ServerRow(
                    selected = uiState.iziSelectedServer,
                    autoServer = uiState.iziAutoServer,
                    onClick = { showServerDialog = true },
                )
                Spacer(modifier = Modifier.height(20.dp))
            }

            if (hasProfiles) {
                Text(
                    text = stringResource(R.string.izi_profiles_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = IziMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, bottom = 8.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    profiles.forEach { profile ->
                        ProfileRow(
                            profile = profile,
                            selected = profile.id == selectedProfileId,
                            updating = uiState.updatingProfileId == profile.id,
                            onSelect = {
                                if (profile.id != selectedProfileId) {
                                    onSelectProfile(profile.id)
                                }
                            },
                            onUpdate = { onUpdateProfile(profile) },
                            onRename = { profileToRename = profile },
                            onRemove = { profileToRemove = profile },
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.izi_profiles_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = IziMuted,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            AddButton(
                primary = !hasProfiles,
                isAdding = isAdding,
                onClick = onAddClick,
            )

            Spacer(modifier = Modifier.height(32.dp))

            TextButton(onClick = onOpenMaster) {
                Text(
                    text = stringResource(R.string.izi_master),
                    style = MaterialTheme.typography.labelLarge,
                    color = IziMuted,
                )
            }
            Text(
                text = stringResource(R.string.izi_version, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelSmall,
                color = IziMuted.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun PowerButton(serviceStatus: Status, enabled: Boolean, onClick: () -> Unit) {
    val isOn = serviceStatus == Status.Started
    val isBusy = serviceStatus == Status.Starting || serviceStatus == Status.Stopping
    // Выключено: тёмная кнопка с зелёным контуром. Включено: кнопка целиком зелёная и светится.
    val container by animateColorAsState(if (isOn) IziGreen else IziSurface, label = "powerContainer")
    val content by animateColorAsState(
        when {
            !enabled -> IziMuted
            isOn -> IziOnGreen
            else -> IziText
        },
        label = "powerContent",
    )
    val accent = when {
        !enabled -> IziLineStrong
        else -> IziGreen
    }
    val glow by animateColorAsState(
        if (isOn) IziGreen.copy(alpha = 0.26f) else Color.Transparent,
        label = "powerGlow",
    )
    val label = when (serviceStatus) {
        Status.Stopped -> stringResource(R.string.izi_button_turn_on)
        Status.Started -> stringResource(R.string.izi_button_turn_off)
        Status.Starting, Status.Stopping -> stringResource(R.string.izi_button_wait)
    }

    Box(
        modifier = Modifier
            .size(196.dp)
            .drawBehind {
                // свечение вокруг включённой кнопки и мягкое кольцо у самой кнопки
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glow, Color.Transparent),
                        center = center,
                        radius = size.minDimension * 1.05f,
                    ),
                    radius = size.minDimension * 1.05f,
                )
                drawCircle(
                    color = accent.copy(alpha = if (isOn) 0.14f else 0.06f),
                    radius = size.minDimension / 2f + 10.dp.toPx(),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = onClick,
            // Пока защита выключается, нажимать нечего; пока включается, нажатие отменяет запуск.
            enabled = enabled && serviceStatus != Status.Stopping,
            shape = CircleShape,
            color = container,
            contentColor = content,
            border = if (isOn) null else BorderStroke(2.dp, accent),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        color = if (isOn) IziOnGreen else IziGreen,
                        strokeWidth = 4.dp,
                        modifier = Modifier.size(52.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = null,
                        tint = if (isOn) IziOnGreen else accent,
                        modifier = Modifier.size(60.dp),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(serviceStatus: Status, hasProfiles: Boolean, startTime: Long?) {
    val text = when {
        !hasProfiles && serviceStatus == Status.Stopped -> stringResource(R.string.izi_status_no_profile)
        serviceStatus == Status.Stopped -> stringResource(R.string.izi_status_off)
        serviceStatus == Status.Starting -> stringResource(R.string.izi_status_starting)
        serviceStatus == Status.Started -> stringResource(R.string.izi_status_on)
        else -> stringResource(R.string.izi_status_stopping)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (serviceStatus == Status.Started) IziGreen else IziText,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (serviceStatus == Status.Started && startTime != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.izi_status_uptime),
                    style = MaterialTheme.typography.bodyMedium,
                    color = IziMuted,
                )
                Spacer(modifier = Modifier.width(6.dp))
                UptimeText(startTime = startTime)
            }
        } else if (hasProfiles && serviceStatus == Status.Stopped) {
            Text(
                text = stringResource(R.string.izi_status_off_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = IziMuted,
            )
        }
    }
}

/** Название сервера для человека: служебное «auto» показываем как «Авто». */
@Composable
private fun serverTitle(tag: String?, autoServer: String?): String = when {
    tag == null -> ""
    !tag.equals("auto", ignoreCase = true) -> tag
    autoServer != null -> stringResource(R.string.izi_server_auto_now, autoServer)
    else -> stringResource(R.string.izi_server_auto)
}

@Composable
private fun ServerRow(selected: String?, autoServer: String?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = IziSurface,
        contentColor = IziText,
        border = BorderStroke(1.dp, IziLine),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.izi_server_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = IziMuted,
                )
                Text(
                    text = serverTitle(selected, autoServer),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(imageVector = Icons.Default.ExpandMore, contentDescription = null, tint = IziMuted)
        }
    }
}

/** Список серверов выезжает снизу. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerDialog(
    servers: List<IziServer>,
    selected: String?,
    autoServer: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = IziSurface,
        contentColor = IziText,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.izi_server_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.izi_server_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = IziMuted,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                servers.forEach { server ->
                    val isSelected = server.tag == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(server.tag) }
                            .heightIn(min = 52.dp)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = IziGreen, unselectedColor = IziMuted),
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = serverTitle(server.tag, if (isSelected) autoServer else null),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        if (server.delayMs > 0) {
                            Text(
                                text = stringResource(R.string.izi_server_delay, server.delayMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = IziMuted,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(IziLine),
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = onDismiss,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = IziSurfaceHigh,
                    contentColor = IziText,
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(
                    text = stringResource(R.string.izi_close),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun ProfileRow(
    profile: Profile,
    selected: Boolean,
    updating: Boolean,
    onSelect: () -> Unit,
    onUpdate: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val isRemote = profile.typed.type == TypedProfile.Type.Remote
    val updatedAt = remember(profile.typed.lastUpdated.time) {
        if (isRemote && profile.typed.lastUpdated.time > 0L) {
            SimpleDateFormat("d MMMM, HH:mm", Locale.forLanguageTag("ru")).format(profile.typed.lastUpdated)
        } else {
            null
        }
    }

    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) IziSelected else IziSurface,
        contentColor = IziText,
        border = BorderStroke(1.dp, if (selected) IziGreen else IziLine),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = IziGreen, unselectedColor = IziMuted),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (updatedAt != null) {
                    Text(
                        text = stringResource(R.string.izi_profile_updated_at, updatedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = IziMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (updating) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                } else {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.izi_profile_menu),
                            tint = IziMuted,
                        )
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        if (isRemote) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.izi_profile_refresh)) },
                                onClick = {
                                    showMenu = false
                                    onUpdate()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.izi_profile_rename)) },
                            onClick = {
                                showMenu = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.izi_profile_remove)) },
                            onClick = {
                                showMenu = false
                                onRemove()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddButton(primary: Boolean, isAdding: Boolean, onClick: () -> Unit) {
    // Пока подключений нет, это главная кнопка экрана (зелёная); потом — спокойная, с контуром.
    val contentColor = if (primary) IziOnGreen else IziText
    val content: @Composable () -> Unit = {
        if (isAdding) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = if (primary) IziOnGreen else IziGreen,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = if (primary) IziOnGreen else IziGreen,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(if (isAdding) R.string.izi_adding else R.string.izi_add),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
        )
    }
    val modifier = Modifier
        .fillMaxWidth()
        .height(54.dp)
    if (primary) {
        Button(
            onClick = onClick,
            enabled = !isAdding,
            shape = RoundedCornerShape(16.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = IziGreen,
                contentColor = IziOnGreen,
                disabledContainerColor = IziGreen.copy(alpha = 0.6f),
                disabledContentColor = IziOnGreen,
            ),
            modifier = modifier,
        ) { content() }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = !isAdding,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, IziLineStrong),
            modifier = modifier,
        ) { content() }
    }
}

@Composable
private fun PasteLinkDialog(onDismiss: () -> Unit, onAdd: (QuickProfileAdder.Link) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    val link = QuickProfileAdder.parseLink(text)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.izi_add_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.izi_add_dialog_message))
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.izi_add_dialog_label)) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { readClipboard(context)?.let { text = it } }) {
                    Text(stringResource(R.string.izi_add_dialog_paste))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { link?.let(onAdd) }, enabled = link != null) {
                Text(stringResource(R.string.izi_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.izi_cancel))
            }
        },
    )
}

@Composable
private fun RenameDialog(currentName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.izi_profile_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.izi_profile_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.izi_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.izi_cancel))
            }
        },
    )
}

/** Текст из буфера обмена. Null, если буфер пуст или система не дала его прочитать. */
private fun readClipboard(context: Context): String? = runCatching {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = manager?.primaryClip
    if (clip != null && clip.itemCount > 0) {
        clip.getItemAt(0).coerceToText(context)?.toString()
    } else {
        null
    }
}.getOrNull()
