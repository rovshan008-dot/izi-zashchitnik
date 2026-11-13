package tech.izi20.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

data class Msg(val me: Boolean, val text: String, val image: String? = null)

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_LISTEN = "izi_listen"
    }

    private val prefs by lazy { getSharedPreferences("izi", MODE_PRIVATE) }

    private var token by mutableStateOf<String?>(null)
    private var phase by mutableStateOf(Phase.Idle)
    private var level by mutableFloatStateOf(0f)
    private var heard by mutableStateOf("")
    private var serverVoice by mutableStateOf(true)
    private val messages = mutableStateListOf<Msg>()

    private var useServerStt = false
    private var pendingText: String? = null
    private var pendingListen = false
    private var autoListen = false

    private lateinit var listener: Listener
    private lateinit var recorder: Recorder
    private lateinit var speaker: Speaker

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val granted = res.values.all { it }
        val text = pendingText
        pendingText = null
        if (text != null && granted) handleText(text, echo = false)
        if (pendingListen) {
            pendingListen = false
            if (hasMic()) startListening()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = prefs.getString("token", null)
        serverVoice = prefs.getBoolean("server_voice", true)
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
        if (token != null) askBasicPermissions()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Violet, background = Bg, surface = Surface)) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.radialGradient(listOf(Color(0xFF1C1440), Bg), center = Offset(540f, 700f), radius = 1400f)
                    )
                ) {
                    if (token == null) LoginScreen() else MainScreen()
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

    override fun onResume() {
        super.onResume()
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

    // ---------- голос ----------

    private fun micTap() {
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
        if (echo) messages += Msg(true, t)
        if (PhoneActions.isStop(PhoneActions.normalize(t))) { speaker.stop(); phase = Phase.Idle; return }

        val local = try { PhoneActions.handle(this, t) } catch (e: Exception) { null }
        if (local != null) {
            if (local.needPermission != null) {
                pendingText = t
                askPerms.launch(arrayOf(local.needPermission))
            }
            say(local.say)
            return
        }
        askBrain(t)
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
            r.recognized?.let { messages += Msg(true, "🎤 $it") }
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

    private fun say(text: String, image: String? = null) {
        messages += Msg(false, text, image)
        phase = Phase.Speaking
        speaker.speak(text, token, serverVoice) { if (phase == Phase.Speaking) phase = Phase.Idle }
    }

    private fun logout() {
        speaker.stop()
        prefs.edit().remove("token").apply()
        token = null
        phase = Phase.Idle
        messages.clear()
    }

    // ---------- экраны ----------

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
                    askBasicPermissions()
                    say("Привет! Это я, Изи. Нажми на шар и говори.")
                }.onFailure { error = it.message ?: "Не получилось" }
            }
        }
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Orb(Phase.Idle, 0f, 150.dp) {}
            Spacer(Modifier.height(28.dp))
            Text("Привет! Я Изи", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "Чтобы я тебя узнал, напиши в Telegram боту @IZI20bot команду /web и введи сюда код из 6 цифр.",
                color = Dim, fontSize = 16.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/IZI20bot"))) } catch (_: Exception) {}
            }) { Text("Открыть @IZI20bot", color = VioletLight, fontSize = 16.sp) }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { v -> code = v.filter { it.isDigit() }.take(6) },
                singleLine = true,
                placeholder = { Text("000000", color = Dim) },
                textStyle = MaterialTheme.typography.headlineMedium.copy(color = Ink, textAlign = TextAlign.Center, letterSpacing = 8.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { go() }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Violet, unfocusedBorderColor = Color(0xFF3A3550)),
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(),
            )
            if (error.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(error, color = Color(0xFFFF7A8A), fontSize = 14.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { go() },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().height(54.dp),
            ) { Text(if (busy) "Проверяю…" else "Войти", fontSize = 18.sp) }
        }
    }

    @Composable
    private fun MainScreen() {
        val listState = rememberLazyListState()
        var input by remember { mutableStateOf("") }
        var menu by remember { mutableStateOf(false) }
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Изи", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { menu = true }) { Text("⋮", color = Dim, fontSize = 24.sp) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (serverVoice) "Голос: Изи ✓ (сменить на голос телефона)" else "Голос: телефона ✓ (сменить на голос Изи)") },
                            onClick = {
                                serverVoice = !serverVoice
                                prefs.edit().putBoolean("server_voice", serverVoice).apply()
                                menu = false
                            },
                        )
                        DropdownMenuItem(text = { Text("Очистить экран") }, onClick = { messages.clear(); menu = false })
                        DropdownMenuItem(text = { Text("Выйти") }, onClick = { menu = false; logout() })
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            "Скажи, например:\n«Поставь будильник на 7 утра»\n«Таймер на 10 минут»\n«Напомни через час позвонить маме»\n«Открой Ютуб»\n«Позвони Саше»\n«Включи фонарик»\nили спроси что угодно.",
                            color = Dim, fontSize = 15.sp, lineHeight = 22.sp,
                            modifier = Modifier.padding(top = 24.dp, start = 8.dp),
                        )
                    }
                }
                items(messages) { m -> Bubble(m) }
            }

            if (heard.isNotEmpty()) {
                Text(
                    "«$heard»", color = VioletLight, fontStyle = FontStyle.Italic, fontSize = 15.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                Orb(phase, level, 132.dp) { micTap() }
            }
            Text(
                when (phase) {
                    Phase.Idle -> "Нажми на шар и говори"
                    Phase.Listening -> "Слушаю…"
                    Phase.Thinking -> "Думаю…"
                    Phase.Speaking -> "Говорю · нажми, чтобы прервать"
                },
                color = Dim, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
            )

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Напиши Изи…", color = Dim) },
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        val t = input.trim(); if (t.isNotEmpty() && phase != Phase.Thinking) { input = ""; handleText(t) }
                    }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Violet, unfocusedBorderColor = Color(0xFF2E2A40)),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = { val t = input.trim(); if (t.isNotEmpty() && phase != Phase.Thinking) { input = ""; handleText(t) } },
                    colors = ButtonDefaults.buttonColors(containerColor = Violet, contentColor = Color.White),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.height(54.dp),
                ) { Text("➤", fontSize = 20.sp) }
            }
        }
    }

    @Composable
    private fun Bubble(m: Msg) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.me) Arrangement.End else Arrangement.Start) {
            Column(
                Modifier.widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (m.me) Color(0xFF3B2C86) else Surface)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (m.image != null) {
                    RemoteImage(m.image)
                    if (m.text.isNotBlank()) Spacer(Modifier.height(8.dp))
                }
                if (m.text.isNotBlank()) Text(m.text, color = Ink, fontSize = 16.sp, lineHeight = 22.sp)
            }
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
                    } else {
                        val c = URL(url).openConnection() as HttpURLConnection
                        c.connectTimeout = 15_000; c.readTimeout = 30_000
                        c.inputStream.use { BitmapFactory.decodeStream(it) }
                    }
                } catch (e: Exception) { null }
            }
        }
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                    if (!url.startsWith("data:")) try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                },
            )
        } else {
            Text("🖼 картинка загружается…", color = Dim, fontSize = 14.sp)
        }
    }
}

@Composable
fun Orb(phase: Phase, level: Float, size: androidx.compose.ui.unit.Dp, onTap: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "orb")
    val breathe by inf.animateFloat(
        0f, 1f, infiniteRepeatable(tween(if (phase == Phase.Speaking) 520 else if (phase == Phase.Thinking) 900 else 2600, easing = LinearEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "spin")
    val lv by animateFloatAsState(if (phase == Phase.Listening) level else 0f, tween(120), label = "lv")
    val scale = when (phase) {
        Phase.Listening -> 1f + lv * 0.22f
        Phase.Speaking -> 0.97f + breathe * 0.08f
        Phase.Thinking -> 0.96f + breathe * 0.04f
        Phase.Idle -> 0.98f + breathe * 0.03f
    }
    Box(
        Modifier.size(size * 1.35f).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 1.35f).scale(scale)) {
            val r = this.size.minDimension / 2f / 1.35f
            val c = center
            // свечение
            drawCircle(
                Brush.radialGradient(
                    listOf(Violet.copy(alpha = if (phase == Phase.Idle) 0.30f else 0.55f), Color.Transparent),
                    center = c, radius = r * 1.35f,
                ),
                radius = r * 1.35f, center = c,
            )
            // шар
            drawCircle(
                Brush.radialGradient(
                    listOf(VioletLight, Violet, VioletDeep),
                    center = Offset(c.x - r * 0.3f, c.y - r * 0.35f), radius = r * 1.25f,
                ),
                radius = r, center = c,
            )
            if (phase == Phase.Thinking) {
                drawArc(
                    color = Color.White.copy(alpha = 0.8f), startAngle = spin, sweepAngle = 80f, useCenter = false,
                    topLeft = Offset(c.x - r * 1.12f, c.y - r * 1.12f),
                    size = androidx.compose.ui.geometry.Size(r * 2.24f, r * 2.24f),
                    style = Stroke(width = r * 0.06f),
                )
            }
            // полоски голоса
            val bars = floatArrayOf(0.30f, 0.62f, 1f, 0.62f, 0.30f)
            val w = r * 0.11f
            val gap = r * 0.09f
            val total = bars.size * w + (bars.size - 1) * gap
            var x = c.x - total / 2f
            val amp = when (phase) {
                Phase.Listening -> 0.45f + lv * 0.7f
                Phase.Speaking -> 0.5f + breathe * 0.5f
                else -> 0.5f
            }
            for (b in bars) {
                val h = r * 0.9f * b * amp
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(x, c.y - h / 2f),
                    size = androidx.compose.ui.geometry.Size(w, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f, w / 2f),
                )
                x += w + gap
            }
        }
    }
}
