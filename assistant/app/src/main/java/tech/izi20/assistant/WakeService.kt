package tech.izi20.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.cancel

/**
 * «Привет, Изи»: слушает микрофон в фоне и ждёт фразу.
 * Распознаватель (Vosk, русская модель, без интернета) включается только когда рядом звучит голос —
 * в тишине работает лишь дешёвый замер громкости, чтобы беречь батарею.
 */
class WakeService : Service() {

    companion object {
        private const val TAG = "IziWake"
        const val ACTION_START = "izi.wake.start"
        const val ACTION_PAUSE = "izi.wake.pause"
        const val ACTION_RESUME = "izi.wake.resume"
        const val ACTION_STOP = "izi.wake.stop"
        private const val CHANNEL = "izi_wake_v1"
        private const val CHANNEL_HIT = "izi_wake_hit_v1"
        private const val NID = 4242
        private const val NID_HIT = 4243

        @Volatile var running = false
            private set
        @Volatile private var model: Model? = null

        private val WAKE = Regex("(привет|приветик|эй|окей|слушай|хей)\\s*(изи|из|иззи|изя|изе|эзи|и зи|из и|изь|easy|izi)(\\s|$)")

        fun enabled(ctx: Context) = ctx.getSharedPreferences("izi", Context.MODE_PRIVATE).getBoolean("wake", false)

        fun setEnabled(ctx: Context, on: Boolean) =
            ctx.getSharedPreferences("izi", Context.MODE_PRIVATE).edit().putBoolean("wake", on).apply()

        /** Запуск — только когда приложение на экране (так требует Android 14). */
        fun startFromForeground(ctx: Context, action: String = ACTION_START) {
            if (!enabled(ctx)) return
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, WakeService::class.java).setAction(action))
            } catch (e: Exception) {
                Log.w(TAG, "start failed", e)
            }
        }

        /** Команда уже запущенной службе (можно и из фона). */
        fun send(ctx: Context, action: String) {
            if (!running) return
            try { ctx.startService(Intent(ctx, WakeService::class.java).setAction(action)) } catch (e: Exception) { Log.w(TAG, "send failed", e) }
        }

        fun stop(ctx: Context) {
            setEnabled(ctx, false)
            if (running) try { ctx.startService(Intent(ctx, WakeService::class.java).setAction(ACTION_STOP)) } catch (_: Exception) {}
        }

        fun matches(text: String): Boolean {
            val t = text.lowercase().replace('ё', 'е')
            return WAKE.containsMatchIn("$t ")
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var loop: Loop? = null
    private var paused = false
    private var foreground = false
    private var loading = false
    private val scope = kotlinx.coroutines.MainScope()
    private var overlay: VoiceOverlay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foreground && !goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopLoop()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> { paused = true; stopLoop(); overlay?.close() }
            ACTION_RESUME -> { if (overlay == null) { paused = false; ensureListening() } }
            else -> ensureListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        overlay?.close()
        scope.cancel()
        stopLoop()
        running = false
        super.onDestroy()
    }

    // ---------- уведомление службы ----------

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "«Привет, Изи»", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Изи ждёт, когда его позовут"
                setShowBadge(false)
            })
        }
        if (nm.getNotificationChannel(CHANNEL_HIT) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_HIT, "Изи зовут", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Когда Изи не может сам открыться поверх других приложений"
            })
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val off = PendingIntent.getService(
            this, 2, Intent(this, WakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_izi)
            .setContentTitle("Изи слушает «Привет, Изи»")
            .setContentText("Микрофон работает только на этом телефоне, запись никуда не уходит")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "Выключить", off)
            .build()
    }

    private fun goForeground(): Boolean {
        ensureChannels()
        return try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, NID, notification(), type)
            foreground = true
            true
        } catch (e: Exception) {
            // Android не разрешил (например, после перезагрузки, пока Изи не открывали)
            Log.w(TAG, "startForeground failed", e)
            false
        }
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
    }

    // ---------- модель ----------

    private fun ensureListening() {
        if (paused || loop != null) return
        val m = model
        if (m != null) { startLoop(m); return }
        if (loading) return
        loading = true
        StorageService.unpack(this, "model-ru", "model",
            { mm ->
                loading = false
                model = mm
                if (!paused && running) startLoop(mm)
            },
            { e ->
                loading = false
                Log.e(TAG, "model unpack failed", e)
            })
    }

    private fun startLoop(m: Model) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val l = Loop(m)
        loop = l
        l.start()
    }

    private fun stopLoop() {
        loop?.let { it.alive = false; try { it.join(800) } catch (_: Exception) {} }
        loop = null
    }

    // ---------- срабатывание ----------

    private fun onWake() {
        if (paused) return
        Log.i(TAG, "wake!")
        stopLoop()
        paused = true
        vibrate()
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_LISTEN, true)
            .putExtra(MainActivity.EXTRA_FROM_WAKE, true)
        // Как у Siri: окошко с шаром поверх того приложения, что сейчас открыто
        if (VoiceOverlay.canShow(this)) {
            try {
                overlay = VoiceOverlay(this, scope) {
                    overlay = null
                    if (!MainActivity.visible) { paused = false; ensureListening() }
                }.also { it.start() }
                return
            } catch (e: Exception) {
                Log.e(TAG, "overlay failed", e)
                overlay = null
            }
        }
        val canOverlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        var opened = false
        if (canOverlay) {
            opened = try { startActivity(i); true } catch (e: Exception) { false }
        }
        if (!opened) {
            showHitNotification(i)
            // Изи на экран не вышел — через пару секунд снова слушаем
            main.postDelayed({ paused = false; ensureListening() }, 2500)
        } else {
            // если экран так и не открылся (система не пустила) — не глохнем навсегда
            main.postDelayed({ if (paused && !MainActivity.visible) { paused = false; ensureListening() } }, 8000)
        }
    }

    @SuppressLint("MissingPermission")
    private fun showHitNotification(i: Intent) {
        val pi = PendingIntent.getActivity(this, 3, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL_HIT)
            .setSmallIcon(R.drawable.ic_stat_izi)
            .setContentTitle("Изи слушает")
            .setContentText("Нажми, чтобы сказать команду")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) NotificationManagerCompat.from(this).notify(NID_HIT, n)
    }

    private fun vibrate() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(40, 120))
            else @Suppress("DEPRECATION") v.vibrate(40)
        } catch (_: Exception) {}
    }

    // ---------- цикл микрофона ----------

    private inner class Loop(private val m: Model) : Thread("izi-wake") {
        @Volatile var alive = true

        @SuppressLint("MissingPermission")
        override fun run() {
            val rate = 16000
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val ar = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 6400) * 2)
            } catch (e: Exception) { Log.e(TAG, "AudioRecord", e); return }
            if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); retryLater(); return }
            val rec = try { Recognizer(m, rate.toFloat()) } catch (e: Exception) { ar.release(); Log.e(TAG, "Recognizer", e); return }
            try {
                ar.startRecording()
                val frame = ShortArray(1600) // 100 мс
                val pre = ArrayDeque<ShortArray>()
                var floor = 400.0
                var active = false
                var quiet = 0
                var activeFrames = 0
                while (alive) {
                    val n = ar.read(frame, 0, frame.size)
                    if (n <= 0) { if (n < 0) break else continue }
                    val rms = rms(frame, n)
                    val thr = max(700.0, floor * 2.8)
                    if (!active) {
                        floor = floor * 0.97 + rms * 0.03
                        pre.addLast(frame.copyOf(n))
                        if (pre.size > 6) pre.removeFirst()
                        if (rms > thr) {
                            active = true; quiet = 0; activeFrames = 0
                            for (p in pre) rec.acceptWaveForm(p, p.size)
                            pre.clear()
                        }
                        continue
                    }
                    activeFrames++
                    val end = rec.acceptWaveForm(frame, n)
                    val text = if (end) field(rec.result, "text") else field(rec.partialResult, "partial")
                    if (text.isNotEmpty() && matches(text)) {
                        rec.reset()
                        alive = false
                        main.post { onWake() }
                        break
                    }
                    quiet = if (rms < thr) quiet + 1 else 0
                    // тишина 1,2 с или фраза дольше 6 с — сбрасываем и снова ждём голос
                    if (quiet > 12 || activeFrames > 60 || end) {
                        if (!end) field(rec.finalResult, "text").let { if (matches(it)) { alive = false; main.post { onWake() } } }
                        rec.reset()
                        active = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loop", e)
            } finally {
                try { ar.stop() } catch (_: Exception) {}
                ar.release()
                rec.close()
            }
        }

        private fun retryLater() {
            // микрофон занят (звонок, другое приложение) — попробуем позже
            main.postDelayed({ if (loop === this) { loop = null; ensureListening() } }, 5000)
        }
    }

    private fun rms(b: ShortArray, n: Int): Double {
        var s = 0.0
        for (i in 0 until n) { val v = b[i].toDouble(); s += v * v }
        return sqrt(s / n)
    }

    private fun field(json: String, key: String): String =
        try { JSONObject(json).optString(key) } catch (_: Exception) { "" }
}
