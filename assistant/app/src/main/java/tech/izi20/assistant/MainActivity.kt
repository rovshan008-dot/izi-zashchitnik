package tech.izi20.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import kotlin.math.sin
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private val Bg = Color(0xFF0A0A12)
private val Surface = Color(0xFF16141F)
private val Violet = Color(0xFF8B6CFF)
private val VioletLight = Color(0xFFC9B8FF)
private val VioletDeep = Color(0xFF5A3BE0)
private val Ink = Color(0xFFECE9F5)
private val Dim = Color(0xFF8C88A0)

enum class Phase { Idle, Listening, Thinking, Speaking }

data class Msg(val me: Boolean, val text: String, val image: String? = null, val photos: List<Uri> = emptyList())

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_LISTEN = "izi_listen"
        const val EXTRA_FROM_WAKE = "izi_from_wake"
        @Volatile var visible = false
    }

    private val prefs by lazy { getSharedPreferences("izi", MODE_PRIVATE) }

    private var token by mutableStateOf<String?>(null)
    private var phase by mutableStateOf(Phase.Idle)
    private var level by mutableFloatStateOf(0f)
    private var heard by mutableStateOf("")
    private var serverVoice by mutableStateOf(true)
    private var wakeOn by mutableStateOf(false)
    private var pendingWake = false
    private var wakeSession = false
    private var lastAction = 0L
    private var pendingAsk: PhoneActions.Ask? = null
    private var lastAsked by mutableStateOf("")
    private val messages = mutableStateListOf<Msg>()

    private var useServerStt = false
    private var pendingText: String? = null
    private var pendingListen = false
    private var autoListen = false

    private lateinit var listener: Listener
    private lateinit var recorder: Recorder
    private lateinit var speaker: Speaker

    private val askSetupPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        setup.onPermissionResult(res)
    }
    private val setup by lazy {
        Setup(this, { askSetupPerms.launch(it) }) {
            WakeService.setEnabled(this, true)
            wakeOn = true
            WakeService.startFromForeground(this, WakeService.ACTION_PAUSE)
        }
    }
    private var showSetup by mutableStateOf(false)

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val granted = res.values.all { it }
        val text = pendingText
        pendingText = null
        if (text != null && granted) handleText(text, echo = false)
        if (pendingGallery) {
            pendingGallery = false
            if (Photos.hasFullAccess(this)) say("Готово, теперь я вижу все фото и видео.")
            else {
                say("Похоже, доступ только к части фото. Открываю настройки: Разрешения, Фото и видео, «Разрешить всё».")
                lifecycleScope.launch { delay(3500); openAppSettings() }
            }
        }
        if (pendingWake) {
            pendingWake = false
            if (hasMic()) enableWake()
        }
        if (pendingListen) {
            pendingListen = false
            if (hasMic()) startListening()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = prefs.getString("token", null)
        serverVoice = prefs.getBoolean("server_voice", true)
        wakeOn = WakeService.enabled(this)
        Reminders.ensureChannel(this)

        listener = Listener(
            this,
            onPartial = { heard = it },
            onLevel = { level = it },
            onFinal = { heard = ""; level = 0f; handleText(it) },
            onFail = { code -> onListenFail(code) },
        )
        recorder = Recorder(this, lifecycleScope)
        speaker = Speaker(this, lifecycleScope)

        readIntent(intent)
        if (token != null && !autoListen) lifecycleScope.launch {
            delay(700)
            introIfNeeded()
            if (!prefs.getBoolean("setup_shown", false) && setup.needsAttention()) showSetup = true
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = D.Violet, background = D.Deep, surface = Color(0xFF16131F))) {
                Box(Modifier.fillMaxSize().background(D.background)) {
                    if (token == null) LoginScreen()
                    else if (showSetup) SetupScreen(setup) { showSetup = false; prefs.edit().putBoolean("setup_shown", true).apply(); setup.refresh() }
                    else MainScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
        if (token != null && autoListen) {
            autoListen = false
            lifecycleScope.launch { delay(250); startListening() }
        }
    }

    override fun onPause() {
        visible = false
        // микрофон отдаём обратно «Привет, Изи»
        if (phase == Phase.Listening) { listener.cancel(); recorder.cancel(); phase = Phase.Idle; heard = ""; level = 0f }
        WakeService.send(this, WakeService.ACTION_RESUME)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        setup.onResume()
        syncChat()
        wakeOn = WakeService.enabled(this)
        if (wakeOn && token != null) {
            // пока Изи на экране, микрофон у него — фоновое ожидание на паузе
            WakeService.startFromForeground(this, WakeService.ACTION_PAUSE)
        }
        if (token != null && autoListen) {
            autoListen = false
            lifecycleScope.launch { delay(300); startListening() }
        }
    }

    override fun onDestroy() {
        listener.cancel()
        recorder.cancel()
        speaker.shutdown()
        super.onDestroy()
    }

    private fun readIntent(i: Intent?) {
        if (i == null) return
        val fromWake = i.getBooleanExtra(EXTRA_FROM_WAKE, false)
        wakeSession = fromWake
        if (Build.VERSION.SDK_INT >= 27) {
            // по «Привет, Изи» показываемся и на заблокированном экране; в остальных случаях — нет
            setShowWhenLocked(fromWake)
            setTurnScreenOn(fromWake)
        }
        if (i.action == Intent.ACTION_ASSIST || i.action == "android.intent.action.VOICE_COMMAND" ||
            i.getBooleanExtra(EXTRA_LISTEN, false)
        ) autoListen = true
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askBasicPermissions() {
        val need = mutableListOf<String>()
        if (!hasMic()) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty() && !prefs.getBoolean("asked_basic", false)) {
            prefs.edit().putBoolean("asked_basic", true).apply()
            askPerms.launch(need.toTypedArray())
        }
    }

    // ---------- «Привет, Изи» ----------

    private fun toggleWake() {
        if (wakeOn) {
            WakeService.stop(this)
            wakeOn = false
            say("Хорошо, больше не слушаю в фоне. Зови кнопкой.")
        } else {
            if (!hasMic()) {
                pendingWake = true
                askPerms.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                return
            }
            enableWake()
        }
    }

    private fun enableWake() {
        WakeService.setEnabled(this, true)
        wakeOn = true
        prefs.edit().putBoolean("asked_overlay", false).putBoolean("asked_battery", false).apply()
        WakeService.startFromForeground(this, WakeService.ACTION_PAUSE)
        say("Готово! Теперь скажи «Привет, Изи», когда я свёрнут. Сейчас попрошу пару разрешений, чтобы я мог открываться сам.")
        askWakeExtras()
    }

    /** Разрешения, без которых «Привет, Изи» работает плохо: открываться поверх приложений и не засыпать. */
    private fun askWakeExtras() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this) && !prefs.getBoolean("asked_overlay", false)) {
            prefs.edit().putBoolean("asked_overlay", true).apply()
            lifecycleScope.launch {
                delay(3500)
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (_: Exception) {}
            }
            return
        }
        val pm = getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= 23 && pm != null && !pm.isIgnoringBatteryOptimizations(packageName) &&
            !prefs.getBoolean("asked_battery", false)
        ) {
            prefs.edit().putBoolean("asked_battery", true).apply()
            try {
                @android.annotation.SuppressLint("BatteryLife")
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                startActivity(i)
            } catch (_: Exception) {}
        }
    }

    // ---------- голос ----------

    private fun micTap() {
        lastAction = System.currentTimeMillis()
        when (phase) {
            Phase.Listening -> if (recorder.active) recorder.stopNow() else listener.stop()
            Phase.Speaking -> { speaker.stop(); phase = Phase.Idle }
            Phase.Thinking -> {}
            Phase.Idle -> startListening()
        }
    }

    private fun startListening() {
        if (token == null || phase == Phase.Thinking) return
        if (!hasMic()) {
            pendingListen = true
            askPerms.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        speaker.stop()
        heard = ""
        phase = Phase.Listening
        if (!useServerStt && listener.available()) listener.start() else startRecorder()
    }

    private fun startRecorder() {
        phase = Phase.Listening
        recorder.start(onLevel = { level = it }) { f ->
            level = 0f
            if (f == null) phase = Phase.Idle else sendVoice(f)
        }
    }

    private fun onListenFail(code: Int?) {
        level = 0f
        heard = ""
        when (code) {
            null -> phase = Phase.Idle
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                phase = Phase.Idle
                say("Мне нужен доступ к микрофону")
            }
            else -> {
                // распознавание телефона не работает (нет Google или нет сети к нему) — слушаем через сервер
                useServerStt = true
                startRecorder()
            }
        }
    }

    // ---------- обработка фраз ----------

    private fun handleText(text: String, echo: Boolean = true) {
        val t = text.trim()
        if (t.isEmpty()) { phase = Phase.Idle; return }
        if (echo) addMsg(Msg(true, t))
        lastAsked = t
        if (PhoneActions.isStop(PhoneActions.normalize(t))) { pendingAsk = null; speaker.stop(); phase = Phase.Idle; return }

        // ответ на вопрос Изи («Отправить?» — «да»)
        val ask = pendingAsk
        pendingAsk = null
        if (ask != null) {
            val r = try { ask.handler(this, t) } catch (e: Exception) { null }
            if (r != null) { showLocal(r, t); return }
        }

        val local = try { PhoneActions.handle(this, t) } catch (e: Exception) { null }
        if (local != null) { showLocal(local, t); return }
        askBrain(t)
    }

    private fun showLocal(local: PhoneActions.Local, original: String) {
        if (local.needPermission != null) {
            pendingText = original
            val p = local.needPermission
            askPerms.launch(if (p == Photos.readPermission()) Photos.permsToAsk() else arrayOf(p))
        }
        pendingAsk = local.ask
        say(local.say, photos = local.photos)
    }

    private fun askBrain(t: String) {
        val tok = token ?: return
        phase = Phase.Thinking
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { IziApi.chat(tok, t) }
            onBrainReply(r)
        }
    }

    private fun sendVoice(f: java.io.File) {
        val tok = token ?: return
        phase = Phase.Thinking
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { IziApi.voice(tok, f) }
            r.recognized?.let { addMsg(Msg(true, "🎤 $it")) }
            onBrainReply(r)
        }
    }

    private fun onBrainReply(r: IziApi.Reply) {
        when {
            r.authExpired -> { logout(); return }
            r.error != null -> say(r.error)
            r.silent && r.text == null -> phase = Phase.Idle
            else -> say(r.text ?: "Хм, пусто. Попробуй ещё раз.", r.imageUrl)
        }
    }

    private fun say(text: String, image: String? = null, photos: List<Uri> = emptyList()) {
        addMsg(Msg(false, text, ChatStore.storeImage(this, image), photos))
        phase = Phase.Speaking
        lastAction = System.currentTimeMillis()
        speaker.speak(text, token, serverVoice) {
            if (phase == Phase.Speaking) phase = Phase.Idle
            // Изи задал вопрос — сразу слушаем ответ
            if (pendingAsk != null && visible && phase == Phase.Idle) { startListening(); return@speak }
            // позвали голосом — ответил и через несколько секунд тишины сам уходит в фон, как Siri
            if (wakeSession) {
                val mark = System.currentTimeMillis()
                lastAction = mark
                lifecycleScope.launch {
                    delay(6000)
                    if (wakeSession && visible && phase == Phase.Idle && lastAction == mark) {
                        wakeSession = false
                        moveTaskToBack(true)
                    }
                }
            }
        }
    }

    /** Первый раз Изи коротко рассказывает, что умеет. */
    private fun introIfNeeded() {
        if (prefs.getBoolean("intro_done", false)) return
        prefs.edit().putBoolean("intro_done", true).apply()
        say(PhoneActions.INTRO)
    }

    private var seenVersion = -1

    private fun addMsg(m: Msg) {
        messages += m
        ChatStore.add(this, m)
        seenVersion = ChatStore.version
    }

    /** Подтянуть то, что Изи говорил из окошка поверх других приложений. */
    private fun syncChat() {
        if (seenVersion == ChatStore.version && messages.isNotEmpty()) return
        messages.clear()
        messages.addAll(ChatStore.all(this))
        seenVersion = ChatStore.version
    }

    private fun logout() {
        speaker.stop()
        prefs.edit().remove("token").apply()
        token = null
        phase = Phase.Idle
        messages.clear()
        ChatStore.clear(this)
    }

    // ---------- экраны (дизайн D: Туманность + Стекло) ----------

    private fun txt(size: Int, weight: Int = 400, color: Color = D.Ink, lh: Int = 0) =
        TextStyle(fontFamily = D.Onest, fontSize = size.sp, fontWeight = FontWeight(weight), color = color,
            lineHeight = if (lh > 0) lh.sp else TextUnit.Unspecified)

    @Composable
    private fun LoginScreen() {
        var code by remember { mutableStateOf("") }
        var error by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        fun go() {
            val c = code.filter { it.isDigit() }
            if (c.length != 6) { error = "Нужно 6 цифр"; return }
            busy = true; error = ""
            scope.launch {
                val r = withContext(Dispatchers.IO) { IziApi.link(c) }
                busy = false
                r.onSuccess {
                    prefs.edit().putString("token", it).apply()
                    token = it
                    introIfNeeded()
                    if (setup.needsAttention()) showSetup = true
                }.onFailure { error = it.message ?: "Не получилось" }
            }
        }
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 24.dp)) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                NebulaOrb(150.dp)
                Spacer(Modifier.height(26.dp))
                Text("Привет. Я Изи.", style = txt(36, 300, lh = 42), textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Чтобы я тебя узнал, напиши /web боту @IZI20bot и введи код.",
                    style = txt(16, 400, D.Dim, 23), textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(30.dp))
                BasicTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter { it.isDigit() }.take(6); error = ""; if (code.length == 6) go() },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { go() }),
                    singleLine = true,
                    textStyle = txt(1, color = Color.Transparent),
                    cursorBrush = SolidColor(Color.Transparent),
                    decorationBox = { inner ->
                        Box {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (i in 0 until 6) {
                                    val ch = code.getOrNull(i)?.toString() ?: ""
                                    val active = i == code.length
                                    Box(
                                        Modifier.size(width = 46.dp, height = 58.dp).clip(RoundedCornerShape(14.dp))
                                            .background(if (ch.isNotEmpty()) D.Violet.copy(alpha = 0.14f) else D.Glass)
                                            .border(1.dp, when { ch.isNotEmpty() -> D.Violet; active -> D.Accent; else -> D.GlassLine }, RoundedCornerShape(14.dp)),
                                        contentAlignment = Alignment.Center,
                                    ) { Text(ch, style = txt(24, 500)) }
                                }
                            }
                            Box(Modifier.size(1.dp)) { inner() }
                        }
                    },
                )
                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(error, style = txt(14, 400, D.Stop), textAlign = TextAlign.Center)
                }
            }
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(D.Ink)
                        .clickable(enabled = !busy) { go() },
                    contentAlignment = Alignment.Center,
                ) { Text(if (busy) "Проверяю…" else "Войти", style = txt(16, 600, D.Deep)) }
                Box(
                    Modifier.fillMaxWidth().height(48.dp).clickable {
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/IZI20bot"))) } catch (_: Exception) {}
                    },
                    contentAlignment = Alignment.Center,
                ) { Text("Получить код в Telegram", style = txt(15, 400, D.Accent)) }
            }
        }
    }

    @Composable
    private fun Header(onMenu: () -> Unit, menuContent: @Composable () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("ИЗИ", style = txt(13, 600, D.Dim).copy(letterSpacing = 4.sp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (wakeOn) D.Green else D.Dim))
                    Text(if (wakeOn) "Слышу «Привет, Изи»" else "«Привет, Изи» выключено", style = txt(13, 400, if (wakeOn) D.GreenText else D.Dim))
                }
            }
            Box {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(D.Glass).border(1.dp, D.GlassLine, CircleShape).clickable(onClick = onMenu),
                    contentAlignment = Alignment.Center,
                ) { Text("⋯", style = txt(20, 500, D.Soft)) }
                menuContent()
            }
        }
    }

    @Composable
    private fun MainScreen() {
        var input by remember { mutableStateOf("") }
        var menu by remember { mutableStateOf(false) }
        val listState = rememberLazyListState()
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }
        fun send() { val t = input.trim(); if (t.isNotEmpty() && phase != Phase.Thinking) { input = ""; handleText(t) } }

        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Header(onMenu = { menu = true }) {
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Настройка и разрешения") },
                            onClick = { menu = false; showSetup = true },
                        )
                        DropdownMenuItem(
                            text = { Text(if (wakeOn) "«Привет, Изи»: включено ✓" else "Включить «Привет, Изи»") },
                            onClick = { menu = false; toggleWake() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (IziAccessibility.instance != null) "Изи видит экран ✓" else "Разрешить Изи видеть экран") },
                            onClick = { menu = false; openAccessibility() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (Photos.hasFullAccess(this@MainActivity)) "Доступ к галерее: полный ✓" else "Дать полный доступ к галерее") },
                            onClick = { menu = false; askFullGallery() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (serverVoice) "Голос: Изи ✓ (сменить на телефонный)" else "Голос: телефона ✓ (сменить на Изи)") },
                            onClick = {
                                serverVoice = !serverVoice
                                prefs.edit().putBoolean("server_voice", serverVoice).apply()
                                menu = false
                            },
                        )
                        DropdownMenuItem(text = { Text("Очистить экран") }, onClick = { messages.clear(); ChatStore.clear(this@MainActivity); menu = false })
                        DropdownMenuItem(text = { Text("Выйти") }, onClick = { menu = false; logout() })
                    }
                }

                if (messages.isEmpty()) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        NebulaOrb(208.dp, OrbMode.Idle, modifier = Modifier.clip(CircleShape).clickable { micTap() })
                        Spacer(Modifier.height(24.dp))
                        Text(D.greeting(), style = txt(34, 300, lh = 40))
                        Spacer(Modifier.height(8.dp))
                        Text("Чем помочь?", style = txt(16, 400, D.Dim))
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Chip("Что ты умеешь?") { handleText("Что ты умеешь?") }
                        Chip("Будильник на 7:00") { handleText("Поставь будильник на 7:00") }
                        Chip("Фото за вчера") { handleText("Покажи фото за вчера") }
                        Chip("Погода завтра") { handleText("Какая погода завтра?") }
                        Chip("Защитник включён?") { handleText("Защитник включён?") }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        val lastIziIndex = messages.indexOfLast { !it.me }
                        itemsIndexed(messages) { i, m -> Exchange(m, lastIzi = !m.me && i == lastIziIndex) }
                    }
                }

                // стеклянная полоса внизу
                Row(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(30.dp)).background(D.Glass).border(1.dp, D.GlassLine, RoundedCornerShape(30.dp))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NebulaOrb(
                        44.dp, if (phase == Phase.Speaking) OrbMode.Speak else OrbMode.Idle, glow = false,
                        modifier = Modifier.clip(CircleShape).clickable { micTap() },
                    )
                    if (phase == Phase.Speaking) {
                        Waves(Modifier.weight(1f).height(28.dp))
                        Text("Говорю", style = txt(13, 400, D.Dim), modifier = Modifier.clickable { micTap() })
                    } else {
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            textStyle = txt(15, 400),
                            cursorBrush = SolidColor(D.Accent),
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send() }),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (input.isEmpty()) Text("Спроси или скажи «Привет, Изи»", style = txt(15, 400, D.Dim), maxLines = 1)
                                    inner()
                                }
                            },
                        )
                    }
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(D.Ink)
                            .clickable { if (input.isNotBlank()) send() else micTap() },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (input.isNotBlank()) "↑" else "●", style = txt(if (input.isNotBlank()) 20 else 14, 600, D.Deep)) }
                }
            }

            // слой «Слушаю» поверх всего
            if (phase == Phase.Listening || phase == Phase.Thinking) {
                Box(Modifier.fillMaxSize().background(D.background).clickable(enabled = false) {}) {
                    EdgeGlow(level, Modifier.fillMaxSize())
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (phase == Phase.Thinking) "ДУМАЮ" else "СЛУШАЮ", style = txt(13, 600, D.Dim).copy(letterSpacing = 4.sp), modifier = Modifier.padding(top = 20.dp))
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            NebulaOrb(236.dp, if (phase == Phase.Thinking) OrbMode.Think else OrbMode.Listen, level,
                                modifier = Modifier.clip(CircleShape).clickable { micTap() })
                            Spacer(Modifier.height(40.dp))
                            val shown = if (phase == Phase.Thinking) lastAsked else heard
                            Text(
                                shown.ifEmpty { "Говори…" }, style = txt(28, 300, if (shown.isEmpty()) D.Dim else D.Ink, 36),
                                textAlign = TextAlign.Center, maxLines = 5,
                            )
                        }
                        Row(
                            Modifier.padding(bottom = 36.dp).clip(RoundedCornerShape(26.dp)).background(D.Glass)
                                .border(1.dp, D.GlassLine, RoundedCornerShape(26.dp)).clickable { micTap() }
                                .padding(horizontal = 26.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(D.Stop))
                            Text(if (phase == Phase.Thinking) "Подожди секунду" else "Готово", style = txt(15, 500))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Chip(label: String, onClick: () -> Unit) {
        Box(
            Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(D.Glass).border(1.dp, D.GlassLine, RoundedCornerShape(22.dp))
                .clickable(onClick = onClick).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) { Text(label, style = txt(14, 400, D.Soft), maxLines = 1) }
    }

    @Composable
    private fun Waves(modifier: Modifier) {
        val t = rememberSeconds()
        Canvas(modifier) {
            val n = 14
            val w = 3.dp.toPx()
            val gap = (size.width - n * w) / (n - 1)
            for (i in 0 until n) {
                val h = size.height * (0.3f + 0.7f * ((sin(t * 7f + i * 0.9f) + 1f) / 2f) * (0.5f + 0.5f * sin(i * 1.7f).let { it * it }))
                drawRoundRect(
                    Brush.verticalGradient(listOf(D.Cyan, D.Violet)),
                    topLeft = Offset(i * (w + gap), (size.height - h) / 2f),
                    size = androidx.compose.ui.geometry.Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f, w / 2f),
                )
            }
        }
    }

    /** Одна реплика. Вопрос — мелко и приглушённо, последний ответ Изи — крупно, старые — тише. */
    @Composable
    private fun Exchange(m: Msg, lastIzi: Boolean) {
        if (m.me) {
            Text(m.text, style = txt(15, 400, D.Dim, 21), modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            return
        }
        Column(Modifier.fillMaxWidth().alpha(if (lastIzi) 1f else 0.6f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (m.image != null) RemoteImage(m.image)
            if (m.text.isNotBlank()) {
                SelectionContainer {
                    Text(
                        linkify(m.text),
                        style = if (lastIzi && m.text.length < 260) txt(26, 300, D.Ink, 34).copy(letterSpacing = (-0.3).sp) else txt(17, 300, D.Ink, 25),
                    )
                }
            }
            if (m.photos.isNotEmpty()) PhotoGrid(m.photos)
        }
    }

    @Composable
    private fun RemoteImage(url: String) {
        val bmp by produceState<Bitmap?>(null, url) {
            value = withContext(Dispatchers.IO) {
                try {
                    if (url.startsWith("data:")) {
                        val bytes = Base64.decode(url.substringAfter(","), Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } else if (url.startsWith("file:")) {
                        BitmapFactory.decodeFile(Uri.parse(url).path)
                    } else {
                        val c = URL(url).openConnection() as HttpURLConnection
                        c.connectTimeout = 15_000; c.readTimeout = 30_000
                        c.inputStream.use { BitmapFactory.decodeStream(it) }
                    }
                } catch (e: Exception) { null }
            }
        }
        val b = bmp
        var saved by remember(url) { mutableStateOf<Uri?>(null) }
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                    val u = saved
                    if (u != null) openPhoto(u)
                    else if (url.startsWith("http")) try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                },
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton(if (saved != null) "✓ В галерее" else "⬇ Сохранить") {
                    if (saved == null) saveImage(url) { saved = it }
                    else openPhoto(saved!!)
                }
                SmallButton("↗ Поделиться") {
                    val u = saved
                    if (u != null) sharePhoto(u) else saveImage(url) { saved = it; if (it != null) sharePhoto(it) }
                }
            }
        } else {
            Text("🖼 картинка загружается…", color = Dim, fontSize = 14.sp)
        }
    }

    @Composable
    private fun SmallButton(label: String, onClick: () -> Unit) {
        Text(
            label, color = VioletLight, fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A2440))
                .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun PhotoGrid(photos: List<Uri>) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            photos.take(30).chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { uri ->
                        val th by produceState<Bitmap?>(null, uri) {
                            value = withContext(Dispatchers.IO) { Photos.thumbnail(this@MainActivity, uri) }
                        }
                        Box(
                            Modifier.size(88.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF221E30))
                                .combinedClickable(onClick = { openPhoto(uri) }, onLongClick = { sharePhoto(uri) })
                        ) {
                            th?.let {
                                Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
    }

    private var pendingGallery = false

    /** Полный доступ к галерее: фото и видео, все, а не «выбранные». */
    private fun askFullGallery() {
        if (Photos.hasFullAccess(this)) { say("У меня уже есть доступ ко всем фото и видео."); return }
        pendingGallery = true
        askPerms.launch(Photos.permsToAsk())
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {}
    }

    private fun openAccessibility() {
        say("Найди в списке «Изи» и включи. Если переключатель серый: Настройки, Приложения, Изи, три точки вверху, «Разрешить ограниченные настройки», и снова сюда.")
        lifecycleScope.launch {
            delay(4500)
            try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun openPhoto(uri: Uri) {
        try {
            val type = contentResolver.getType(uri) ?: "image/*"
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            Toast.makeText(this, "Нечем открыть фото", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sharePhoto(uri: Uri) {
        val i = Intent(Intent.ACTION_SEND).setType(contentResolver.getType(uri) ?: "image/*").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(i, "Отправить")) } catch (_: Exception) {}
    }

    private fun saveImage(url: String, done: (Uri?) -> Unit) {
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            askPerms.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            Toast.makeText(this, "Разреши доступ к памяти и нажми ещё раз", Toast.LENGTH_SHORT).show()
            done(null)
            return
        }
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                try {
                    val (bytes, mime) = if (url.startsWith("data:")) {
                        Base64.decode(url.substringAfter(","), Base64.DEFAULT) to url.substringAfter("data:").substringBefore(";").ifEmpty { "image/png" }
                    } else if (url.startsWith("file:")) {
                        java.io.File(Uri.parse(url).path!!).readBytes() to (if (url.endsWith(".png")) "image/png" else "image/jpeg")
                    } else {
                        val c = URL(url).openConnection() as HttpURLConnection
                        c.connectTimeout = 15_000; c.readTimeout = 60_000
                        val type = c.contentType ?: "image/jpeg"
                        c.inputStream.use { it.readBytes() } to type
                    }
                    Photos.saveToGallery(this@MainActivity, bytes, if (mime.startsWith("image/")) mime else "image/jpeg")
                } catch (e: Exception) { null }
            }
            Toast.makeText(this@MainActivity, if (uri != null) "Сохранено в Галерею → Izi" else "Не получилось сохранить", Toast.LENGTH_SHORT).show()
            done(uri)
        }
    }

    private val urlRe = Regex("(https?://[^\\s<>\"«»)\\]]+)")

    private fun linkify(text: String): AnnotatedString = buildAnnotatedString {
        var last = 0
        for (m in urlRe.findAll(text)) {
            var url = m.value.trimEnd('.', ',', ';', ':', '!', '?')
            append(text.substring(last, m.range.first))
            withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = VioletLight, textDecoration = TextDecoration.Underline)))) {
                append(url)
            }
            last = m.range.first + url.length
        }
        append(text.substring(last))
    }
}

