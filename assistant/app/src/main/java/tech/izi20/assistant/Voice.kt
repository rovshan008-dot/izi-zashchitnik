package tech.izi20.assistant

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Распознавание речи на телефоне (бесплатно и быстро). */
class Listener(
    private val ctx: Context,
    private val onPartial: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onFinal: (String) -> Unit,
    /** null — просто ничего не сказали; иначе код ошибки распознавателя. */
    private val onFail: (Int?) -> Unit,
) {
    private var sr: SpeechRecognizer? = null

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun start() {
        cancel()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                cancel()
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) onFail(null)
                else onFail(error)
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                cancel()
                if (text.isBlank()) onFail(null) else onFinal(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let(onPartial)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        r.startListening(i)
    }

    fun stop() {
        sr?.stopListening()
    }

    fun cancel() {
        sr?.let {
            try { it.cancel() } catch (_: Exception) {}
            try { it.destroy() } catch (_: Exception) {}
        }
        sr = null
    }
}

/** Запасной путь: пишем голос в файл (сам останавливается на паузе) и отдаём серверу. */
class Recorder(private val ctx: Context, private val scope: CoroutineScope) {
    private var rec: MediaRecorder? = null
    private var job: Job? = null
    val file: File get() = File(ctx.cacheDir, "voice.mp4")

    private var done: ((File?) -> Unit)? = null

    fun start(onLevel: (Float) -> Unit, onDone: (File?) -> Unit) {
        cancel()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(48000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) {}
            onDone(null)
            return
        }
        rec = r
        done = onDone
        job = scope.launch {
            val started = System.currentTimeMillis()
            var heard = false
            var quietSince = 0L
            while (true) {
                delay(100)
                val amp = try { r.maxAmplitude } catch (_: Exception) { 0 }
                onLevel((amp / 12000f).coerceIn(0f, 1f))
                val now = System.currentTimeMillis()
                if (amp > 2500) { heard = true; quietSince = 0L }
                else if (heard) { if (quietSince == 0L) quietSince = now; if (now - quietSince > 1300) break }
                if (!heard && now - started > 7000) break
                if (now - started > 15000) break
            }
            job = null
            val ok = finish()
            val cb = done
            done = null
            cb?.invoke(if (ok && heard) file else null)
        }
    }

    /** Остановить вручную (тап по кнопке) — запись уходит на сервер. */
    fun stopNow() {
        job?.cancel()
        job = null
        val ok = finish()
        val cb = done
        done = null
        cb?.invoke(if (ok) file else null)
    }

    private fun finish(): Boolean {
        val r = rec ?: return false
        rec = null
        return try { r.stop(); true } catch (e: Exception) { false } finally { try { r.release() } catch (_: Exception) {} }
    }

    fun cancel() {
        job?.cancel()
        job = null
        done = null
        finish()
    }

    val active: Boolean get() = rec != null
}

/** Голос Изи: серверный (как в веб-версии) или голос телефона. */
class Speaker(private val ctx: Context, private val scope: CoroutineScope) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var player: MediaPlayer? = null
    private var gen = 0
    private val main = Handler(Looper.getMainLooper())

    init {
        tts = TextToSpeech(ctx) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                ttsReady = true
            }
        }
    }

    private fun forSpeech(text: String): String {
        var t = text.replace(Regex("https?://\\S+"), " ").replace("•", " ").replace("\n", ". ")
            .replace(Regex("[\\p{So}\\p{Sk}\\x{FE0F}\\x{200D}*_#`>|]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        // длинные ответы читаем не целиком — полностью они на экране
        if (t.length > 600) {
            val cut = t.lastIndexOfAny(charArrayOf('.', '!', '?'), 600)
            t = t.substring(0, if (cut > 200) cut + 1 else 600)
        }
        return t
    }

    fun speak(text: String, token: String?, serverVoice: Boolean, onDone: () -> Unit) {
        stop()
        val my = ++gen
        val say = forSpeech(text)
        if (say.isBlank()) { onDone(); return }
        if (serverVoice && token != null) {
            scope.launch {
                val f = File(ctx.cacheDir, "say.mp3")
                val ok = withContext(Dispatchers.IO) { IziApi.tts(token, say, f) }
                if (my != gen) return@launch
                if (ok && playFile(f, my, onDone)) return@launch
                speakPhone(say, my, onDone)
            }
        } else {
            speakPhone(say, my, onDone)
        }
    }

    private fun playFile(f: File, my: Int, onDone: () -> Unit): Boolean = try {
        val p = MediaPlayer()
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        p.setDataSource(f.absolutePath)
        p.setOnCompletionListener { if (my == gen) { release(); onDone() } }
        p.setOnErrorListener { _, _, _ -> if (my == gen) { release(); onDone() }; true }
        p.prepare()
        p.start()
        player = p
        true
    } catch (e: Exception) {
        release()
        false
    }

    private fun speakPhone(text: String, my: Int, onDone: () -> Unit) {
        val t = tts
        if (t == null || !ttsReady) { onDone(); return }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { if (my == gen) onDone() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { if (my == gen) onDone() } }
        })
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "izi$my")
    }

    private fun release() {
        player?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        player = null
    }

    fun stop() {
        gen++
        release()
        try { tts?.stop() } catch (_: Exception) {}
    }

    fun shutdown() {
        stop()
        try { tts?.shutdown() } catch (_: Exception) {}
    }
}
