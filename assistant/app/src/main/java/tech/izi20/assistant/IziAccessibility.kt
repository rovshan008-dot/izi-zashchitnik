package tech.izi20.assistant

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream

/**
 * «Спец. возможности» Изи: только по команде («Привет, Изи, что ты об этом думаешь?»)
 * читает текст на экране и делает снимок экрана. Сам по себе ничего не собирает.
 */
class IziAccessibility : AccessibilityService() {

    companion object {
        @Volatile var instance: IziAccessibility? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Название приложения на экране и весь видимый текст. */
    fun screenText(maxChars: Int = 3500): Pair<String, String> {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return "" to ""
        val pkg = root.packageName?.toString().orEmpty()
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || sb.length > maxChars || depth > 45) return
            val t = n.text?.toString()?.trim()
            val d = n.contentDescription?.toString()?.trim()
            when {
                !t.isNullOrEmpty() -> sb.append(t).append('\n')
                !d.isNullOrEmpty() -> sb.append(d).append('\n')
            }
            for (i in 0 until n.childCount) walk(try { n.getChild(i) } catch (_: Exception) { null }, depth + 1)
        }
        walk(root, 0)
        return label to sb.toString().take(maxChars)
    }

    /** Снимок экрана (Android 11+). На старых — null, тогда Изи опирается только на текст. */
    fun screenshot(cb: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { cb(null); return }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    val url = try {
                        val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        bmp?.let { toDataUrl(it) }
                    } catch (_: Exception) { null } finally { hb.close() }
                    cb(url)
                }
                override fun onFailure(errorCode: Int) = cb(null)
            })
        } catch (_: Exception) { cb(null) }
    }

    private fun toDataUrl(src: Bitmap): String {
        // крупные подписи и цифры на графиках должны читаться — держим высокое разрешение
        val maxW = 1600
        val b = if (src.width > maxW) Bitmap.createScaledBitmap(src, maxW, src.height * maxW / src.width, true) else src
        val out = ByteArrayOutputStream()
        var q = 88
        b.compress(Bitmap.CompressFormat.JPEG, q, out)
        // сервер принимает data-url до ~6 МБ; если вдруг больше — ужимаем
        while (out.size() > 4_500_000 && q > 55) {
            out.reset(); q -= 12; b.compress(Bitmap.CompressFormat.JPEG, q, out)
        }
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
