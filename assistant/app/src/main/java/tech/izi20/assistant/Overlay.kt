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

private val IZI_COLORS = intArrayOf(0xFF2EC8FF.toInt(), 0xFF7C5CFF.toInt(), 0xFFFF4F9A.toInt(), 0xFFFFB36B.toInt())

private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

/** Шар дизайна D: тёмное ядро, внутри кружат фиолетовое, розовое и голубое облака, блик и тонкий ободок. */
class SiriOrbView(ctx: Context) : View(ctx) {
    enum class Mode { LISTEN, THINK, SPEAK }

    var mode = Mode.LISTEN
    var level = 0f
    var glow = true
    private var lv = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x24FFFFFF }
    private val clip = android.graphics.Path()
    private val t0 = SystemClock.uptimeMillis()
    private val cols = intArrayOf(0xFF7C5CFF.toInt(), 0xFFFF4F9A.toInt(), 0xFF2EC8FF.toInt())
    private val periods = floatArrayOf(7f, -9f, 6f)

    override fun onDraw(c: Canvas) {
        val t = (SystemClock.uptimeMillis() - t0) / 1000f
        lv += (level - lv) * 0.25f
        val cx = width / 2f
        val cy = height / 2f
        val k = if (glow) 1.45f else 1f
        val speed = when (mode) { Mode.THINK -> 2.6f; Mode.SPEAK -> 1.6f; Mode.LISTEN -> 1.5f }
        val pulse = when (mode) { Mode.SPEAK -> 0.035f * (1f + sin(t * 9f)); Mode.LISTEN -> 0.18f * lv; Mode.THINK -> 0.02f * sin(t * 4f) }
        val r = min(width, height) / 2f / k * (1f + pulse)
        if (glow) {
            paint.shader = RadialGradient(cx, cy, r * 1.45f, intArrayOf(withAlpha(cols[0], (0x6B + 0x40 * lv).toInt().coerceAtMost(255)), withAlpha(cols[0], 0x1F), 0), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r * 1.45f, paint)
        }
        val save = c.saveLayer(cx - r, cy - r, cx + r, cy + r, null)
        clip.reset(); clip.addCircle(cx, cy, r, android.graphics.Path.Direction.CW)
        c.clipPath(clip)
        paint.shader = null; paint.color = 0xFF100C24.toInt()
        c.drawCircle(cx, cy, r, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
        for (i in cols.indices) {
            val ang = t * speed * (6.2832f / periods[i]) + i * 2.1f
            val wob = r * (0.12f + 0.22f * lv + if (mode == Mode.THINK) 0.08f else 0f)
            val x = cx + cos(ang) * wob
            val y = cy + sin(ang * 1.2f) * wob
            val br = r * (0.95f + 0.2f * lv)
            paint.shader = RadialGradient(x, y, br, intArrayOf(withAlpha(cols[i], 0xF2), withAlpha(cols[i], 0)), null, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, br, paint)
        }
        paint.xfermode = null
        paint.shader = RadialGradient(cx - r * 0.32f, cy - r * 0.48f, r, intArrayOf(0x8CFFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.32f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, paint)
        c.restoreToCount(save)
        paint.shader = null
        rim.strokeWidth = resources.displayMetrics.density
        c.drawCircle(cx, cy, r, rim)
        postInvalidateOnAnimation()
    }
}

/** Волна голоса, пока Изи говорит или слушает. */
class WavesView(ctx: Context) : View(ctx) {
    var active = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t0 = SystemClock.uptimeMillis()
    private val dp = ctx.resources.displayMetrics.density
    override fun onDraw(c: Canvas) {
        val t = (SystemClock.uptimeMillis() - t0) / 1000f
        val n = 10
        val w = 3 * dp
        val gap = (width - n * w) / (n - 1)
        paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, height.toFloat(), 0xFF2EC8FF.toInt(), 0xFF7C5CFF.toInt(), Shader.TileMode.CLAMP)
        for (i in 0 until n) {
            val amp = if (active) (0.3f + 0.7f * ((sin(t * 7f + i * 0.9f) + 1f) / 2f)) else 0.2f
            val h = height * amp
            val x = i * (w + gap)
            c.drawRoundRect(x, (height - h) / 2f, x + w, (height + h) / 2f, w / 2, w / 2, paint)
        }
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
    private lateinit var bubble: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var waves: WavesView

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
        val font = try { androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.onest) } catch (_: Exception) { null }
        fun styled(tv: TextView, sp: Float, color: Int, weight: Int) = tv.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
            if (font != null) typeface = font
            if (Build.VERSION.SDK_INT >= 26) fontVariationSettings = "'wght' $weight"
        }
        fun glassBg(radius: Float) = GradientDrawable().apply {
            cornerRadius = px(radius).toFloat()
            setColor(0xFF0E0C1A.toInt())
            setStroke(px(1f), 0x1AFFFFFF)
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }

        // карточка с вопросом и ответом
        bubble = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20f), px(16f), px(20f), px(18f))
            background = glassBg(28f)
            elevation = px(12f).toFloat()
        }
        heard = styled(TextView(ctx), 13f, 0xFF8E89A6.toInt(), 400).apply { text = "Слушаю…" }
        bubble.addView(heard, LinearLayout.LayoutParams(-1, -2))
        picture = ImageView(ctx).apply { visibility = View.GONE; adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        bubble.addView(picture, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10f) })
        answer = styled(TextView(ctx), 21f, 0xFFF2F1F7.toInt(), 300).apply {
            setLineSpacing(0f, 1.18f); visibility = View.GONE
            autoLinkMask = Linkify.WEB_URLS; movementMethod = LinkMovementMethod.getInstance()
            setLinkTextColor(0xFFB9A8FF.toInt())
            // длинный ответ прокручивается внутри окошка, целиком он есть в чате Изи
            maxHeight = px(250f)
        }
        bubble.addView(answer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8f) })
        chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        fun chip(label: String, onClick: () -> Unit) = styled(TextView(ctx), 13f, 0xFFE9E7F2.toInt(), 400).apply {
            text = label; gravity = Gravity.CENTER
            minHeight = px(40f); setPadding(px(14f), 0, px(14f), 0)
            background = GradientDrawable().apply { cornerRadius = px(20f).toFloat(); setColor(0x0DFFFFFF); setStroke(px(1f), 0x1AFFFFFF) }
            setOnClickListener { onClick() }
        }
        chips.addView(chip("Подробнее в Изи") { openApp() }, LinearLayout.LayoutParams(-2, px(40f)))
        chips.addView(View(ctx), LinearLayout.LayoutParams(px(8f), 1))
        chips.addView(chip("Ещё вопрос") { closeJob?.cancel(); speaker.stop(); listen() }, LinearLayout.LayoutParams(-2, px(40f)))
        bubble.addView(chips, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(14f) })
        col.addView(bubble, LinearLayout.LayoutParams(-1, -2))

        // полоса с шаром, волной и крестиком
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(7f), px(7f), px(8f), px(7f))
            background = glassBg(32f)
            elevation = px(12f).toFloat()
        }
        orb = SiriOrbView(ctx).apply { glow = false; setOnClickListener { closeJob?.cancel(); speaker.stop(); listen() } }
        pill.addView(orb, LinearLayout.LayoutParams(px(46f), px(46f)))
        waves = WavesView(ctx)
        pill.addView(waves, LinearLayout.LayoutParams(px(92f), px(26f)).apply { leftMargin = px(12f); rightMargin = px(12f) })
        val x = styled(TextView(ctx), 16f, 0xFFF2F1F7.toInt(), 500).apply {
            text = "✕"; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x14FFFFFF) }
            setOnClickListener { close() }
            contentDescription = "Закрыть"
        }
        pill.addView(x, LinearLayout.LayoutParams(px(44f), px(44f)))
        col.addView(pill, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(12f) })

        card.addView(col, FrameLayout.LayoutParams(-1, -2).apply { setMargins(px(14f), 0, px(14f), px(28f)) })
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
        answer.visibility = View.GONE
        chips.visibility = View.GONE
        picture.visibility = View.GONE
        waves.active = true
        val l = Listener(
            ctx,
            onPartial = {
                // пока говоришь — твои слова крупно, как на экране «Слушаю»
                answer.setTextColor(0xFFB9A8FF.toInt()); answer.text = it; answer.visibility = View.VISIBLE
            },
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
        heard.text = text
        answer.visibility = View.GONE
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
        status("Смотрю на экран…")
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

    /** Служебная строка («Думаю…») на месте ответа, приглушённо. Вопрос сверху остаётся. */
    private fun status(text: String) {
        answer.setTextColor(0xFF8E89A6.toInt())
        answer.text = text
        answer.visibility = View.VISIBLE
    }

    private fun askBrain(call: (String) -> IziApi.Reply) {
        val tok = prefs.getString("token", null)
        if (tok == null) { reply("Сначала войди в Изи"); return }
        orb.mode = SiriOrbView.Mode.THINK
        if (answer.visibility != View.VISIBLE) status("Думаю…")
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
        answer.setTextColor(0xFFF2F1F7.toInt())
        answer.text = text
        answer.visibility = View.VISIBLE
        chips.visibility = View.VISIBLE
        waves.active = true
        showPicture(stored)
        orb.mode = SiriOrbView.Mode.SPEAK
        speaker.speak(text, prefs.getString("token", null), prefs.getBoolean("server_voice", true)) {
            if (closed) return@speak
            if (pendingAsk != null) { listen(); return@speak }
            orb.mode = SiriOrbView.Mode.LISTEN
            orb.level = 0f
            waves.active = false
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
