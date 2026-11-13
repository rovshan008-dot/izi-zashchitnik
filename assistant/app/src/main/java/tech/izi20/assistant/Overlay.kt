package tech.izi20.assistant

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val IZI_COLORS = intArrayOf(0xFF8B6CFF.toInt(), 0xFFFF5FA2.toInt(), 0xFF3FD0FF.toInt(), 0xFF7CE0A8.toInt())

private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

/** Переливающийся шар как у Siri: цветные облака кружатся, растут от голоса, вихрем крутятся, пока Изи думает. */
class SiriOrbView(ctx: Context) : View(ctx) {
    enum class Mode { LISTEN, THINK, SPEAK }

    var mode = Mode.LISTEN
    var level = 0f
    private var lv = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t0 = SystemClock.uptimeMillis()

    override fun onDraw(c: Canvas) {
        val t = (SystemClock.uptimeMillis() - t0) / 1000f
        lv += (level - lv) * 0.25f
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) / 2f * 0.52f
        val speed = when (mode) { Mode.THINK -> 2.6f; Mode.SPEAK -> 1.4f; Mode.LISTEN -> 1f }
        val speak = if (mode == Mode.SPEAK) 0.10f * (1f + sin(t * 8f)) else 0f

        paint.shader = RadialGradient(cx, cy, base * 2f, intArrayOf(withAlpha(IZI_COLORS[0], 0x70), 0), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, base * 2f, paint)

        for (i in IZI_COLORS.indices) {
            val ang = t * (0.9f + i * 0.37f) * speed + i * 1.57f
            val wob = base * (0.16f + 0.32f * lv + speak)
            val x = cx + cos(ang) * wob
            val y = cy + sin(ang * 1.3f) * wob
            val r = base * (0.82f + 0.30f * lv + 0.07f * sin(t * 2.1f + i) + speak)
            paint.shader = RadialGradient(x, y, r, intArrayOf(withAlpha(IZI_COLORS[i], 0xD0), withAlpha(IZI_COLORS[i], 0)), null, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, paint)
        }
        paint.shader = RadialGradient(cx - base * 0.25f, cy - base * 0.3f, base * 0.9f,
            intArrayOf(0x88FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, base * 0.9f, paint)
        postInvalidateOnAnimation()
    }
}

/** Подсветка по краю экрана, как у новой Siri: цвета бегут по кругу и вспыхивают от голоса. */
class EdgeGlowView(ctx: Context) : View(ctx) {
    var level = 0f
    var alphaK = 0f
    private var lv = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val m = Matrix()
    private val rect = RectF()
    private val t0 = SystemClock.uptimeMillis()
    private val dp = ctx.resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val t = (SystemClock.uptimeMillis() - t0) / 1000f
        lv += (level - lv) * 0.2f
        val cols = intArrayOf(IZI_COLORS[0], IZI_COLORS[1], IZI_COLORS[2], IZI_COLORS[3], IZI_COLORS[0])
        val sg = SweepGradient(width / 2f, height / 2f, cols, null)
        m.setRotate(t * 90f, width / 2f, height / 2f)
        sg.setLocalMatrix(m)
        paint.shader = sg
        val radius = 38 * dp
        val layers = floatArrayOf(22f, 12f, 5f)
        val alphas = intArrayOf(45, 90, 230)
        for (k in layers.indices) {
            val w = layers[k] * dp * (1f + lv * 0.8f)
            paint.strokeWidth = w
            paint.alpha = (alphas[k] * alphaK).toInt().coerceIn(0, 255)
            rect.set(w / 2f, w / 2f, width - w / 2f, height - w / 2f)
            c.drawRoundRect(rect, radius, radius, paint)
        }
        postInvalidateOnAnimation()
    }
}

/**
 * Окошко «Привет, Изи» поверх любого приложения: слушает команду, отвечает голосом,
 * при вопросе «что ты об этом думаешь» смотрит на экран. Всё сказанное попадает в чат Изи.
 */
class VoiceOverlay(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val onClosed: () -> Unit,
) {
    companion object {
        private val SCREEN = Regex(
            "(об этом|про это|насчет этого|насчёт этого|по этому поводу|на это|что на экране|на экран|посмотри|глянь|" +
                "что (тут|здесь) |что это|прочитай (это|экран|что)|оцени|объясни (это|что)|переведи (это|экран)|" +
                "этот (график|текст|пост|экран|сайт)|эту (статью|картинку|страницу)|что скажешь)"
        )

        fun canShow(ctx: Context): Boolean {
            val okOverlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)
            val locked = (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
            return okOverlay && !locked
        }
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dp = ctx.resources.displayMetrics.density
    private val prefs = ctx.getSharedPreferences("izi", Context.MODE_PRIVATE)
    private val speaker = Speaker(ctx, scope)
    private var listener: Listener? = null
    private var pendingAsk: PhoneActions.Ask? = null
    private var closeJob: Job? = null
    private var closed = false

    private lateinit var glow: EdgeGlowView
    private lateinit var card: FrameLayout
    private lateinit var orb: SiriOrbView
    private lateinit var heard: TextView
    private lateinit var answer: TextView
    private lateinit var picture: ImageView

    private fun px(v: Float) = (v * dp).toInt()

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    fun start() {
        show()
        listen()
    }

    // ---------- окна ----------

    @SuppressLint("SetTextI18n")
    private fun show() {
        glow = EdgeGlowView(ctx)
        val gp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= 28) gp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(glow, gp)

        card = FrameLayout(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(18f), px(10f), px(18f), px(14f))
            background = GradientDrawable().apply {
                cornerRadius = px(30f).toFloat()
                setColor(0xF0100E1A.toInt())
                setStroke(px(1f), 0x553A3260)
            }
        }
        orb = SiriOrbView(ctx)
        box.addView(orb, LinearLayout.LayoutParams(px(118f), px(118f)))
        heard = TextView(ctx).apply {
            setTextColor(0xFFC9B8FF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER; text = "Слушаю…"
        }
        box.addView(heard, LinearLayout.LayoutParams(-1, -2))
        picture = ImageView(ctx).apply { visibility = View.GONE; adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        box.addView(picture, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        answer = TextView(ctx).apply {
            setTextColor(0xFFECE9F5.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(0f, 1.15f); setTextIsSelectable(false)
            autoLinkMask = Linkify.WEB_URLS; movementMethod = LinkMovementMethod.getInstance()
            setLinkTextColor(0xFFC9B8FF.toInt())
        }
        // длинный ответ прокручивается внутри окошка, целиком он есть в чате Изи
        answer.maxHeight = px(230f)
        box.addView(answer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6f) })

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun chip(label: String, onClick: () -> Unit) = TextView(ctx).apply {
            text = label; setTextColor(0xFFC9B8FF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(px(14f), px(8f), px(14f), px(8f))
            background = GradientDrawable().apply { cornerRadius = px(14f).toFloat(); setColor(0xFF2A2440.toInt()) }
            setOnClickListener { onClick() }
        }
        row.addView(chip("Открыть Изи") { openApp() })
        row.addView(View(ctx), LinearLayout.LayoutParams(px(10f), 1))
        row.addView(chip("Ещё вопрос") { closeJob?.cancel(); speaker.stop(); listen() })
        row.addView(View(ctx), LinearLayout.LayoutParams(px(10f), 1))
        row.addView(chip("✕") { close() })
        box.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(10f) })

        card.addView(box, FrameLayout.LayoutParams(-1, -2).apply { setMargins(px(10f), 0, px(10f), px(14f)) })
        val cp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        wm.addView(card, cp)

        card.translationY = px(320f).toFloat()
        card.animate().translationY(0f).setDuration(320).setInterpolator(DecelerateInterpolator(2f)).start()
        scope.launch {
            for (i in 1..12) { glow.alphaK = i / 12f; delay(16) }
        }
    }

    private fun setVisible(v: Boolean) {
        val vis = if (v) View.VISIBLE else View.INVISIBLE
        glow.visibility = vis
        card.visibility = vis
    }

    fun close() {
        if (closed) return
        closed = true
        closeJob?.cancel()
        listener?.cancel()
        speaker.shutdown()
        card.animate().translationY(px(360f).toFloat()).setDuration(220).withEndAction {
            try { wm.removeView(card) } catch (_: Exception) {}
            try { wm.removeView(glow) } catch (_: Exception) {}
            onClosed()
        }.start()
    }

    private fun openApp() {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try { ctx.startActivity(i) } catch (_: Exception) {}
        close()
    }

    private fun closeLater(ms: Long) {
        closeJob?.cancel()
        closeJob = scope.launch { delay(ms); close() }
    }

    // ---------- голос ----------

    private fun listen() {
        if (closed) return
        orb.mode = SiriOrbView.Mode.LISTEN
        heard.text = "Слушаю…"
        val l = Listener(
            ctx,
            onPartial = { heard.text = "«$it»" },
            onLevel = { orb.level = it; glow.level = it },
            onFinal = { orb.level = 0f; glow.level = 0f; handle(it) },
            onFail = { code ->
                orb.level = 0f; glow.level = 0f
                if (code == null) { heard.text = "Не расслышал"; closeLater(1800) }
                else { heard.text = "Распознавание речи недоступно — открой Изи"; closeLater(3000) }
            },
        )
        listener = l
        if (l.available()) l.start() else { heard.text = "На телефоне нет распознавания речи — открой Изи"; closeLater(3000) }
    }

    private fun handle(text: String) {
        heard.text = "«$text»"
        ChatStore.add(ctx, Msg(true, text))
        val n = PhoneActions.normalize(text)
        if (PhoneActions.isStop(n)) { close(); return }

        val ask = pendingAsk
        pendingAsk = null
        if (ask != null) {
            val r = try { ask.handler(ctx, text) } catch (_: Exception) { null }
            if (r != null) { showLocal(r); return }
        }
        if (SCREEN.containsMatchIn("$n ")) { askAboutScreen(text); return }

        val local = try { PhoneActions.handle(ctx, text) } catch (_: Exception) { null }
        if (local != null) { showLocal(local); return }
        askBrain { tok -> IziApi.chat(tok, text) }
    }

    private fun showLocal(l: PhoneActions.Local) {
        if (l.needPermission != null) {
            reply("${l.say}. Открой Изи — там попрошу разрешение.")
            return
        }
        pendingAsk = l.ask
        reply(l.say)
    }

    private fun askAboutScreen(question: String) {
        val acc = IziAccessibility.instance
        if (acc == null) {
            reply("Чтобы я видел экран, включи «Изи» в Спец. возможностях. Открываю настройки.")
            try { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            return
        }
        orb.mode = SiriOrbView.Mode.THINK
        heard.text = "Смотрю на экран…"
        val (app, screenText) = acc.screenText()
        // прячем окошко на долю секунды, чтобы оно не попало в снимок
        setVisible(false)
        scope.launch {
            delay(180)
            acc.screenshot { shot ->
                setVisible(true)
                ChatStore.add(ctx, Msg(true, "📱 Экран: ${app.ifEmpty { "приложение" }}"))
                val prompt = buildString {
                    append("[Пользователь показывает тебе экран своего телефона")
                    if (app.isNotEmpty()) append(", открыто приложение «$app»")
                    append(". ")
                    if (shot != null) append("Снимок экрана приложен. ")
                    if (screenText.isNotBlank()) append("Текст с экрана:\n").append(screenText.take(3000)).append("\n")
                    append("Отвечай по тому, что на экране, коротко и по делу.]\n")
                    append(question)
                }
                askBrain { tok ->
                    (if (shot != null) IziApi.see(tok, prompt, shot) else null) ?: IziApi.chat(tok, prompt)
                }
            }
        }
    }

    private fun askBrain(call: (String) -> IziApi.Reply) {
        val tok = prefs.getString("token", null)
        if (tok == null) { reply("Сначала войди в Изи"); return }
        orb.mode = SiriOrbView.Mode.THINK
        if (heard.text.isNullOrEmpty()) heard.text = "Думаю…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { call(tok) }
            when {
                r.authExpired -> reply("Нужно войти в Изи заново")
                r.error != null -> reply(r.error)
                else -> reply(r.text ?: "Хм, пусто. Попробуй ещё раз.", r.imageUrl)
            }
        }
    }

    private fun reply(text: String, image: String? = null) {
        if (closed) return
        val stored = ChatStore.storeImage(ctx, image)
        ChatStore.add(ctx, Msg(false, text, stored))
        answer.text = text
        showPicture(stored)
        orb.mode = SiriOrbView.Mode.SPEAK
        speaker.speak(text, prefs.getString("token", null), prefs.getBoolean("server_voice", true)) {
            if (closed) return@speak
            if (pendingAsk != null) { listen(); return@speak }
            orb.mode = SiriOrbView.Mode.LISTEN
            orb.level = 0f
            heard.text = "Нажми «Ещё вопрос» или просто убери окно"
            closeLater(if (image != null || text.length > 160) 9000 else 4500)
        }
    }

    private fun showPicture(url: String?) {
        if (url == null) { picture.visibility = View.GONE; return }
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    when {
                        url.startsWith("file:") -> BitmapFactory.decodeFile(Uri.parse(url).path)
                        url.startsWith("data:") -> Base64.decode(url.substringAfter(","), Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        else -> java.net.URL(url).openStream().use { BitmapFactory.decodeStream(it) }
                    }
                } catch (_: Exception) { null }
            }
            if (bmp != null && !closed) {
                picture.setImageBitmap(bmp)
                picture.maxHeight = px(220f)
                picture.visibility = View.VISIBLE
                picture.setOnClickListener { openApp() }
            }
        }
    }
}
