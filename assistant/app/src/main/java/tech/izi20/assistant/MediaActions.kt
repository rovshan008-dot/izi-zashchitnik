package tech.izi20.assistant

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

/**
 * Громкость и музыка. Делается на самом телефоне: мгновенно и без расхода токенов.
 * Не наша фраза — возвращаем null, и она уходит в мозг Изи.
 */
object MediaActions {

    private fun audio(ctx: Context) = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Шаг громкости — три деления: на одно разницу почти не слышно. */
    private fun step(ctx: Context, direction: Int, times: Int = 3) {
        val am = audio(ctx)
        repeat(times) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }
    }

    private fun mediaKey(ctx: Context, code: Int) {
        val am = audio(ctx)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /** Сколько процентов громкости сейчас — чтобы Изи сказал, а не просто сделал. */
    private fun percent(ctx: Context): Int {
        val am = audio(ctx)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    private val UP = Regex("^(?:сделай |давай |ну )?(громче|погромче|прибавь (?:звук|громкость)|звук громче|увеличь громкость)$")
    private val DOWN = Regex("^(?:сделай |давай |ну )?(тише|потише|убавь (?:звук|громкость)|звук тише|уменьши громкость)$")
    private val MUTE = Regex("^(?:сделай )?(выключи звук|включи звук|без звука|заглуши|тишина|звук выключи)$")
    private val MAXV = Regex("^(?:сделай )?(на всю|на полную|максимальная громкость|громкость на максимум)$")
    private val PAUSE = Regex("^(пауза|поставь на паузу|останови музыку|продолжи(?: музыку)?|включи музыку|играй|стоп музыка)$")
    private val NEXT = Regex("^(следующий(?: трек)?|следующая песня|переключи(?: трек| песню)?|дальше трек|next)$")
    private val PREV = Regex("^(предыдущий(?: трек)?|предыдущая песня|верни трек|назад трек|прошлый трек)$")
    private val HOWLOUD = Regex("^(какая громкость|сколько громкость|громкость)$")

    fun handle(ctx: Context, t: String): PhoneActions.Local? = try {
        when {
            UP.matches(t) -> { step(ctx, AudioManager.ADJUST_RAISE); PhoneActions.Local("Громкость ${percent(ctx)}%") }
            DOWN.matches(t) -> { step(ctx, AudioManager.ADJUST_LOWER); PhoneActions.Local("Громкость ${percent(ctx)}%") }
            MUTE.matches(t) -> {
                audio(ctx).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                PhoneActions.Local("Переключил звук")
            }
            MAXV.matches(t) -> {
                val am = audio(ctx)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), AudioManager.FLAG_SHOW_UI)
                PhoneActions.Local("Громкость на максимум")
            }
            HOWLOUD.matches(t) -> PhoneActions.Local("Громкость ${percent(ctx)}%")
            PAUSE.matches(t) -> { mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); PhoneActions.Local("Готово") }
            NEXT.matches(t) -> { mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT); PhoneActions.Local("Включил следующий") }
            PREV.matches(t) -> { mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS); PhoneActions.Local("Вернул предыдущий") }
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}
