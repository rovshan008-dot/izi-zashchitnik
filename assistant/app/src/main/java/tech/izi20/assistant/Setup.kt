package tech.izi20.assistant

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * «Настройка Изи»: все разрешения одним списком и кнопка «Разрешить всё».
 * Изи сам по очереди открывает нужные окна и страницы настроек и подсказывает, что нажать.
 */
class Setup(
    private val act: ComponentActivity,
    private val request: (Array<String>) -> Unit,
    private val turnOnWake: () -> Unit,
) {
    /** done: true — готово, false — нужно включить, null — проверить нельзя (покажем как совет). */
    class Step(val id: String, val title: String, val desc: String, val done: () -> Boolean?, val run: () -> Unit)

    var tick by mutableIntStateOf(0)
        private set

    private var wizard = false
    private var waitingReturn = false
    private val attempted = mutableSetOf<String>()
    private var lastRequested: Array<String> = emptyArray()
    private val prefs = act.getSharedPreferences("izi", android.content.Context.MODE_PRIVATE)

    private fun has(p: String) = ContextCompat.checkSelfPermission(act, p) == PackageManager.PERMISSION_GRANTED

    private fun hint(text: String) = Toast.makeText(act, text, Toast.LENGTH_LONG).show()

    private fun open(i: Intent, tip: String?, fallback: Intent? = null) {
        waitingReturn = true
        try {
            act.startActivity(i)
            if (tip != null) hint(tip)
        } catch (e: Exception) {
            try { if (fallback != null) { act.startActivity(fallback); if (tip != null) hint(tip) } else waitingReturn = false }
            catch (_: Exception) { waitingReturn = false }
        }
    }

    private fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${act.packageName}"))

    private fun ask(perms: Array<String>) {
        lastRequested = perms
        request(perms)
    }

    private val vendor = Build.MANUFACTURER.lowercase()
    private val needsAutostart = listOf("realme", "oppo", "oneplus", "xiaomi", "redmi", "poco", "huawei", "honor", "vivo", "meizu").any { vendor.contains(it) }

    val steps: List<Step> = buildList {
        add(Step("mic", "Микрофон", "Чтобы слышать тебя", { has(Manifest.permission.RECORD_AUDIO) }) {
            ask(arrayOf(Manifest.permission.RECORD_AUDIO))
        })
        if (Build.VERSION.SDK_INT >= 33) add(Step("notif", "Уведомления", "Напоминания и «Изи слушает»", { has(Manifest.permission.POST_NOTIFICATIONS) }) {
            ask(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        })
        add(Step("people", "Контакты, звонки и СМС", "«Позвони маме», «Напиши Саше»", {
            has(Manifest.permission.READ_CONTACTS) && has(Manifest.permission.CALL_PHONE) && has(Manifest.permission.SEND_SMS)
        }) { ask(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS)) })
        add(Step("gallery", "Все фото и видео", "Искать, показывать и отправлять", { Photos.hasFullAccess(act) }) {
            ask(Photos.permsToAsk())
        })
        if (Build.VERSION.SDK_INT >= 23) add(Step("overlay", "Поверх других приложений", "Окошко «Привет, Изи» над любым приложением", { Settings.canDrawOverlays(act) }) {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${act.packageName}")), "Включи переключатель у «Изи» и вернись назад")
        })
        if (Build.VERSION.SDK_INT >= 23) add(Step("battery", "Работа в фоне", "Чтобы телефон не усыплял Изи", {
            act.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(act.packageName) == true
        }) {
            @android.annotation.SuppressLint("BatteryLife")
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${act.packageName}"))
            open(i, null, appDetails())
        })
        add(Step("wake", "«Привет, Изи»", "Зови голосом, даже когда Изи свёрнут", { WakeService.enabled(act) }) {
            if (!has(Manifest.permission.RECORD_AUDIO)) { ask(arrayOf(Manifest.permission.RECORD_AUDIO)); return@Step }
            turnOnWake()
            refresh()
            proceedSoon()
        })
        add(Step("screen", "Видеть экран", "«Что ты об этом думаешь?» про открытое приложение", { IziAccessibility.instance != null }) {
            val comp = ComponentName(act, IziAccessibility::class.java).flattenToString()
            val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, comp)
            val tip = if (Build.VERSION.SDK_INT >= 33)
                "Включи «Изи». Если переключатель серый — вернись и нажми «Переключатель серый?»"
            else "Найди «Изи» и включи"
            open(if (Build.VERSION.SDK_INT >= 30) details else Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), tip, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        if (needsAutostart) add(Step("autostart", "Автозапуск", "На ${Build.MANUFACTURER} без него Изи может засыпать", {
            if (prefs.getBoolean("autostart_seen", false)) null else false
        }) {
            prefs.edit().putBoolean("autostart_seen", true).apply()
            val list = listOf(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            )
            val i = list.map { Intent().setComponent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                .firstOrNull { it.resolveActivity(act.packageManager) != null }
            open(i ?: appDetails(), "Найди «Изи» и разреши автозапуск (или «Работа в фоне»)", appDetails())
        })
    }

    fun refresh() { tick++ }

    fun doneCount() = steps.count { it.done() != false }

    fun allDone() = steps.all { it.done() != false }

    /** Нужно ли показать экран настройки сам (что-то важное ещё не разрешено). */
    fun needsAttention() = steps.any { it.id in setOf("mic", "people", "gallery", "overlay", "wake") && it.done() == false }

    fun startWizard() {
        wizard = true
        attempted.clear()
        next()
    }

    fun runOne(s: Step) {
        wizard = false
        s.run()
    }

    private fun next() {
        refresh()
        if (!wizard) return
        val s = steps.firstOrNull { it.done() == false && it.id !in attempted }
        if (s == null) {
            wizard = false
            hint(if (allDone()) "Готово! Всё разрешено" else "Остальное можно включить позже в этом списке")
            return
        }
        attempted += s.id
        s.run()
    }

    private fun proceedSoon() {
        act.lifecycleScope.launch { delay(350); next() }
    }

    /** Вызывать из onResume: вернулись со страницы настроек — идём дальше. */
    fun onResume() {
        refresh()
        if (waitingReturn) {
            waitingReturn = false
            proceedSoon()
        }
    }

    /** Ответ системного окна разрешений. */
    fun onPermissionResult(res: Map<String, Boolean>) {
        refresh()
        val denied = res.filterValues { !it }.keys
        val forever = denied.any { !ActivityCompat.shouldShowRequestPermissionRationale(act, it) }
        val galleryPartial = lastRequested.contentEquals(Photos.permsToAsk()) && Photos.canRead(act) && !Photos.hasFullAccess(act)
        when {
            galleryPartial -> open(appDetails(), "Разрешения → Фото и видео → «Разрешить всё»")
            denied.isNotEmpty() && forever -> open(appDetails(), "Окно больше не появляется — разреши в «Разрешениях»")
            else -> proceedSoon()
        }
    }

    fun openRestrictedHelp() {
        open(appDetails(), "Нажми ⋮ вверху → «Разрешить ограниченные настройки», потом снова «Видеть экран»")
    }
}

/** Экран «Настройка Изи» в стиле D. */
@Composable
fun SetupScreen(setup: Setup, onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val t = setup.tick // перерисовка при изменениях
    fun st(size: Int, w: Int, c: Color) = TextStyle(fontFamily = D.Onest, fontSize = size.sp, fontWeight = FontWeight(w), color = c)
    val total = setup.steps.size
    val done = setup.doneCount()
    Box(Modifier.fillMaxSize().background(D.background)) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(20.dp))
                Text("НАСТРОЙКА", style = st(13, 600, D.Dim).copy(letterSpacing = 4.sp))
                Spacer(Modifier.height(10.dp))
                Text("Разреши Изи помогать тебе", style = st(30, 300, D.Ink).copy(lineHeight = 36.sp))
                Spacer(Modifier.height(8.dp))
                Text("Готово $done из $total. Нажми «Разрешить всё» — я сам открою нужные окна, тебе останется нажимать «Разрешить».",
                    style = st(15, 400, D.Dim).copy(lineHeight = 21.sp))
                Spacer(Modifier.height(18.dp))
                for (s in setup.steps) {
                    val d = s.done()
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(20.dp)).background(D.Glass)
                            .border(1.dp, D.GlassLine, RoundedCornerShape(20.dp))
                            .clickable(enabled = d != true) { setup.runOne(s) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(if (d == true) D.Green else Color.Transparent)
                                .border(1.5.dp, if (d == true) D.Green else D.Dim, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { if (d == true) Text("✓", style = st(15, 600, D.Deep)) }
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = st(16, 500, D.Ink))
                            Text(s.desc, style = st(13, 400, D.Dim).copy(lineHeight = 18.sp))
                            if (s.id == "screen" && d == false && android.os.Build.VERSION.SDK_INT >= 33) {
                                Text("Переключатель серый?", style = st(13, 500, D.Accent),
                                    modifier = Modifier.padding(top = 6.dp).clickable { setup.openRestrictedHelp() })
                            }
                        }
                        if (d != true) Text(if (d == null) "Проверить" else "Включить", style = st(14, 500, D.Accent))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Column(Modifier.fillMaxWidth().padding(bottom = 20.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!setup.allDone()) {
                    Box(
                        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(D.Ink).clickable { setup.startWizard() },
                        contentAlignment = Alignment.Center,
                    ) { Text("Разрешить всё", style = st(16, 600, D.Deep)) }
                }
                Box(
                    Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Text(if (setup.allDone()) "Готово" else "Позже", style = st(15, 400, D.Accent), textAlign = TextAlign.Center) }
            }
        }
    }
}
