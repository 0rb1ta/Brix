package app.brix.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private val IntroBg = Color(0xFF0D0D0F)
private val IntroAccent = Color(0xFFFFC257)
private val IntroText = Color(0xFFF4F1EC)

private val MatrixChars = listOf("^", "_", "•", "o", "x", ">", "<", "$", "?", "-", "*", "#")

private class RainColumn(
    val speedFactor: Float,
    val headFactor: Float,
    val tail: Int,
    val glyphs: IntArray,
)

private const val MAX_RAIN_COLUMNS = 60

/** Самый длинный шаг времени за кадр, секунды: ~два кадра при 30 fps. */
private const val MAX_FRAME_STEP = 1f / 15f

@Composable
fun BrixIntro(onFinished: () -> Unit) {
    val wordmark = "BRIX"
    var typed by remember { mutableIntStateOf(0) }
    var cursorOn by remember { mutableStateOf(true) }

    val textAlpha = remember { Animatable(0f) }
    val rainAlpha = remember { Animatable(1f) }
    val fade = remember { Animatable(1f) }
    var time by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        // 1. Счетчик времени кадров. Копим шаг, а не берём «сейчас минус старт»:
        // первые кадры совпадают с холодным запуском (загрузка классов, чтение
        // настроек), и один кадр в 200 мс при абсолютном времени переносил
        // колонки скачком. С потолком шага дождь на таком кадре просто
        // замедляется, а не прыгает.
        launch {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                time += ((now - last) / 1_000_000_000f).coerceAtMost(MAX_FRAME_STEP)
                last = now
            }
        }

        // 2. Мигание курсора
        launch {
            while (true) {
                delay(400)
                cursorOn = !cursorOn
            }
        }

        // 3. Сценарий анимации
        delay(2200) // Даем матрице пролиться в соло

        // Запускаем приглушение матрицы И проявление `#` ОДНОВРЕМЕННО
        launch {
            rainAlpha.animateTo(0.20f, tween(500, easing = LinearEasing))
        }
        textAlpha.animateTo(1f, tween(250, easing = EaseOutCubic))

        // Сразу печатаем букву за буквой без задержек
        wordmark.indices.forEach { i ->
            typed = i + 1
            delay(90)
        }

        delay(700)
        fade.animateTo(0f, tween(300, easing = LinearEasing))
        onFinished()
    }

    val measurer = rememberTextMeasurer()
    val glyphStyle = remember {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = IntroAccent,
        )
    }

    val layouts = remember(measurer, glyphStyle) {
        MatrixChars.map { measurer.measure(it, glyphStyle) }
    }
    // Знаки растрируются один раз, белыми, а в кадре только копируются картинкой.
    // drawText раскладывает абзац на каждом вызове, а вызовов в кадре сотни
    // (колонки × хвост). Цвет хвоста — тонировкой той же картинки, голова —
    // без неё, белая как есть.
    val density = LocalDensity.current
    val glyphImages = remember(layouts, density) {
        layouts.map { layout ->
            val w = layout.size.width.coerceAtLeast(1)
            val h = layout.size.height.coerceAtLeast(1)
            ImageBitmap(w, h).also { image ->
                CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(w.toFloat(), h.toFloat())) {
                    drawText(layout, color = Color.White)
                }
            }
        }
    }
    val tailTint = remember { ColorFilter.tint(IntroAccent) }

    val columns = remember {
        val rnd = Random(4242)
        List(MAX_RAIN_COLUMNS) {
            RainColumn(
                speedFactor = 7f + rnd.nextFloat() * 8f,
                headFactor = rnd.nextFloat(),
                tail = 10 + rnd.nextInt(12),
                glyphs = IntArray(32) { rnd.nextInt(MatrixChars.size) },
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IntroBg)
            // ModulateAlpha: прозрачность применяется к каждой операции рисования,
            // без внеэкранного буфера на весь экран. Он выделялся ровно на
            // последние 300 мс — там, где заметен рывок в конце. Наложений,
            // которым нужна честная групповая прозрачность, здесь нет.
            .graphicsLayer {
                alpha = fade.value
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (rainAlpha.value <= 0f) return@Canvas

            val charW = glyphImages.first().width.toFloat()
            val charH = glyphImages.first().height.toFloat()
            val step = charW * 1.5f
            val cols = (size.width / step).toInt().coerceIn(10, MAX_RAIN_COLUMNS)
            val maxY = size.height + charH * 20

            val glitchStep = (time * 8f).toInt()

            for (i in 0 until cols) {
                val col = columns[i]
                val x = i * step
                val headY = (col.headFactor * maxY + time * col.speedFactor * charH) % maxY

                for (k in 0 until col.tail) {
                    val y = headY - k * charH
                    if (y < -charH || y > size.height) continue

                    val fadeK = 1f - (k / col.tail.toFloat())
                    val rawIndex = col.glyphs[(k + (headY / charH).toInt()) % col.glyphs.size]
                    val mutatingIndex = (rawIndex + glitchStep + k * 3 + i * 7).mod(MatrixChars.size)

                    drawImage(
                        image = glyphImages[mutatingIndex],
                        topLeft = Offset(x, y),
                        alpha = (fadeK * fadeK * rainAlpha.value).coerceIn(0f, 1f),
                        colorFilter = if (k == 0) null else tailTint,
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.graphicsLayer { alpha = textAlpha.value },
        ) {
            Text(
                text = "#",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 36.sp,
                color = IntroAccent,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = wordmark.take(typed),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 36.sp,
                letterSpacing = 4.sp,
                color = IntroText,
            )
            Text(
                text = "_",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 36.sp,
                color = if (cursorOn) IntroAccent else Color.Transparent,
            )
        }
    }
}
