package tech.izi20.assistant

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

/** Дизайн «D»: Туманность + Стекло. Цвета, шрифт, шар и светящийся край. */
object D {
    val Ground = Color(0xFF07070B)
    val Deep = Color(0xFF0B0917)
    val Nebula = Color(0xFF1A1338)
    val Ink = Color(0xFFF2F1F7)
    val Dim = Color(0xFF8E89A6)
    val Soft = Color(0xFFE9E7F2)
    val Accent = Color(0xFFB9A8FF)
    val Green = Color(0xFF6EDDA2)
    val GreenText = Color(0xFF9FE3BF)
    val Glass = Color(0x0DFFFFFF)      // 5% белого
    val GlassLine = Color(0x17FFFFFF)  // 9% белого
    val Violet = Color(0xFF7C5CFF)
    val Pink = Color(0xFFFF4F9A)
    val Cyan = Color(0xFF2EC8FF)
    val Amber = Color(0xFFFFB36B)
    val Stop = Color(0xFFFF6B8B)

    val background = Brush.radialGradient(
        0f to Nebula, 0.55f to Deep, 1f to Ground,
        center = Offset(540f, 900f), radius = 1500f,
    )

    @OptIn(ExperimentalTextApi::class)
    private fun w(weight: Int) = Font(
        R.font.onest, FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

    @OptIn(ExperimentalTextApi::class)
    val Onest = FontFamily(w(300), w(400), w(500), w(600))

    fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Доброе утро"
        in 12..17 -> "Добрый день"
        in 18..22 -> "Добрый вечер"
        else -> "Доброй ночи"
    }
}

enum class OrbMode { Idle, Listen, Think, Speak }

/** Общие «часы» анимации: секунды с запуска экрана. */
@Composable
fun rememberSeconds(): Float {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { t = (it - start) / 1000f }
    }
    return t
}

/** Переливающийся шар: тёмное ядро, внутри кружат фиолетовое, розовое и голубое облака, сверху блик. */
@Composable
fun NebulaOrb(size: Dp, mode: OrbMode = OrbMode.Idle, level: Float = 0f, glow: Boolean = true, modifier: Modifier = Modifier) {
    val t = rememberSeconds()
    val lv by animateFloatAsState(level, tween(140), label = "orbLevel")
    val outer = if (glow) size * 1.45f else size
    Box(modifier.size(outer), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(outer).graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            val c = center
            val speed = when (mode) { OrbMode.Think -> 2.6f; OrbMode.Listen -> 1.5f; OrbMode.Speak -> 1.6f; OrbMode.Idle -> 0.8f }
            val pulse = when (mode) {
                OrbMode.Speak -> 0.035f * (1f + sin(t * 9f))
                OrbMode.Listen -> 0.18f * lv
                OrbMode.Think -> 0.02f * sin(t * 4f)
                OrbMode.Idle -> 0.02f * sin(t * 1.25f)
            }
            val r = this.size.minDimension / 2f / (if (glow) 1.45f else 1f) * (1f + pulse)
            if (glow) {
                drawCircle(
                    Brush.radialGradient(
                        0f to D.Violet.copy(alpha = 0.42f + 0.25f * lv), 0.45f to D.Violet.copy(alpha = 0.12f), 1f to Color.Transparent,
                        center = c, radius = r * 1.45f,
                    ),
                    radius = r * 1.45f, center = c,
                )
            }
            val clip = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
            clipPath(clip) {
                drawCircle(Color(0xFF100C24), radius = r, center = c)
                val cols = listOf(D.Violet, D.Pink, D.Cyan)
                val periods = floatArrayOf(7f, -9f, 6f)
                val shifts = floatArrayOf(0f, 2.27f, 4.36f)
                for (i in cols.indices) {
                    val ang = (t * speed * (6.2832f / periods[i])) + shifts[i]
                    val wob = r * (0.12f + 0.22f * lv + (if (mode == OrbMode.Think) 0.08f else 0f))
                    val p = Offset(c.x + cos(ang) * wob, c.y + sin(ang * 1.2f) * wob)
                    val br = r * (0.95f + 0.2f * lv)
                    drawCircle(
                        Brush.radialGradient(0f to cols[i].copy(alpha = 0.95f), 1f to cols[i].copy(alpha = 0f), center = p, radius = br),
                        radius = br, center = p, blendMode = BlendMode.Screen,
                    )
                }
                drawCircle(
                    Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.55f), 0.32f to Color.White.copy(alpha = 0f),
                        center = Offset(c.x - r * 0.32f, c.y - r * 0.48f), radius = r,
                    ),
                    radius = r, center = c,
                )
            }
            drawCircle(Color.White.copy(alpha = 0.14f), radius = r, center = c, style = Stroke(width = 1.dp.toPx()))
        }
    }
}

/** Цветной светящийся край экрана, как у новой Siri: цвета бегут по кругу, вспыхивают от голоса. */
@Composable
fun EdgeGlow(level: Float = 0f, modifier: Modifier = Modifier) {
    val t = rememberSeconds()
    val lv by animateFloatAsState(level, tween(160), label = "edgeLevel")
    Canvas(modifier) {
        val brush = Brush.sweepGradient(rotateList(listOf(D.Cyan, D.Violet, D.Pink, D.Amber, D.Cyan), t), center)
        val radius = 44.dp.toPx()
        val layers = floatArrayOf(26f, 13f, 5f)
        val alphas = floatArrayOf(0.18f, 0.38f, 0.95f)
        for (k in layers.indices) {
            val w = layers[k].dp.toPx() * (1f + lv * 0.8f)
            drawRoundRect(
                brush = brush,
                topLeft = Offset(w / 2f, w / 2f),
                size = Size(size.width - w, size.height - w),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = w),
                alpha = alphas[k],
            )
        }
    }
}

/** Сдвигаем цвета по кругу со временем — так край «бежит», а сама рамка стоит на месте. */
private fun rotateList(cols: List<Color>, t: Float): List<Color> {
    val n = cols.size - 1
    val shift = (t * 0.35f) % 1f * n
    val out = ArrayList<Color>(n + 1)
    for (i in 0..n) {
        val pos = (i + shift) % n
        val a = pos.toInt() % n
        val b = (a + 1) % n
        val f = pos - pos.toInt()
        out += lerpColor(cols[a], cols[b], f)
    }
    out[n] = out[0]
    return out
}

private fun lerpColor(a: Color, b: Color, f: Float) = Color(
    red = a.red + (b.red - a.red) * f,
    green = a.green + (b.green - a.green) * f,
    blue = a.blue + (b.blue - a.blue) * f,
    alpha = 1f,
)
