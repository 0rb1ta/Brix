package app.brix.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Mirrors real stream state — see mascotStateFor() in StreamScreen.kt. */
enum class MascotState { OFFLINE, IDLE, LIVE, MUTED, RECONNECTING, OVERHEAT, DONATION }

private data class MascotLook(val face: String, val blinkFace: String, val color: Color)

private fun lookFor(state: MascotState): MascotLook = when (state) {
    MascotState.OFFLINE -> MascotLook("?_?", "?-?", Color(0xFF8A857E))
    MascotState.IDLE -> MascotLook("•_•", "-_-", Color(0xFFFFC257))
    // Второе лицо — это МОРГАНИЕ, то есть закрытые глаза. Было «^o^»: круглый
    // глаз посреди довольного лица читается совсем не как моргание (владелец,
    // 14.09 — «во время стрима у него очко на лице появляется»).
    MascotState.LIVE -> MascotLook("^_^", "-_-", Color(0xFFFFC257))
    MascotState.MUTED -> MascotLook("o_x", "-_x", Color(0xFFFFC257))
    MascotState.RECONNECTING -> MascotLook(">.<", "-.-", Color(0xFFFFC257))
    MascotState.OVERHEAT -> MascotLook("x_x", "x_x", Color(0xFFFF6B5B))
    // Тот же круглый глаз, что был у LIVE, и та же причина его убрать.
    MascotState.DONATION -> MascotLook("\$_\$", "\$_\$", Color(0xFFFFC257))
}

private val CardBg = Color(0xFF140F06)
private val CardBorder = Color(0xFF3A3A3D)
private val OverheatGlow = Color(0xFFFF6B5B)
private val EaseInOutSine = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

/** Около 2.5 с дрожи при переподключении (цикл 280 мс). */
private const val ALARM_WOBBLE_CYCLES = 9

/** Три пульса рамки при перегреве (цикл 1 с). */
private const val ALARM_GLOW_CYCLES = 3

/**
 * Live "kaomoji" status mascot — replaces a plain icon in the quick-button
 * grid. Не просто смена глифа: моргает по случайному таймеру, подпрыгивает
 * при смене состояния, на донат, коротко дрожит при переподключении и
 * пульсирует рамкой при перегреве. Постоянного движения нет — см. замер
 * в теле функции.
 * Purely decorative — no click behavior of its own.
 */
@Composable
fun BrixMascot(state: MascotState, modifier: Modifier = Modifier) {
    // Бесконечных анимаций здесь нет НАМЕРЕННО. Замер на S21 23.09, голый эфир
    // SRTLA: пока маскот «дышал» (бесконечный transition), интерфейс рисовал
    // 60 кадров/с весь эфир — главный поток 30% ядра, поток отрисовки 32%.
    // Без маскота — 8 кадров за 5 с, главный поток 7.5%, поток отрисовки из
    // топа пропал. То есть декоративное покачивание стоило больше половины
    // ядра, больше всего транспорта на двух каналах. Правило для этого файла:
    // анимация только как короткий отклик на событие, в покое кадр стоит.

    // Blinks on its own random rhythm, independent of state changes, so it
    // never looks like it blinks "because" something happened — that's what
    // actually reads as alive rather than reactive-only. В покое моргает тоже:
    // 14.09 я убрал это по жалобе «в афк меняется выражение», но жалоба была
    // про другое (см. blinkFace у LIVE), и моргание вернули. Два кадра раз в
    // 3–5 секунд — это почти бесплатно, в отличие от дыхания.
    var blinking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2600, 5200))
            blinking = true
            delay(110)
            blinking = false
        }
    }

    // One-shot punch on entering DONATION — on top of (not instead of) the
    // normal state-change transition below.
    val bounce = remember { Animatable(1f) }
    LaunchedEffect(state) {
        if (state == MascotState.DONATION) {
            bounce.snapTo(1.3f)
            bounce.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
            )
        } else {
            bounce.snapTo(1f)
        }
    }

    // Тревожные состояния привлекают внимание движением только первые пару
    // секунд, дальше стоят. Раньше дрожь при переподключении и пульс рамки
    // при перегреве крутились всё время, пока длилось состояние, — то есть
    // при перегреве мы добавляли нагрузку ровно тогда, когда телефону и так
    // хуже всего (владелец, 23.09).
    val wobble = remember { Animatable(0f) }
    LaunchedEffect(state) {
        if (state == MascotState.RECONNECTING) {
            repeat(ALARM_WOBBLE_CYCLES) {
                wobble.animateTo(1f, tween(140, easing = LinearEasing))
                wobble.animateTo(-1f, tween(140, easing = LinearEasing))
            }
            wobble.animateTo(0f, tween(140, easing = LinearEasing))
        } else {
            wobble.snapTo(0f)
        }
    }

    val glow = remember { Animatable(1f) }
    LaunchedEffect(state) {
        if (state == MascotState.OVERHEAT) {
            repeat(ALARM_GLOW_CYCLES) {
                glow.animateTo(0.25f, tween(500, easing = EaseInOutSine))
                glow.animateTo(0.9f, tween(500, easing = EaseInOutSine))
            }
            // Дальше статичная яркая рамка: перегрев виден, но ничего не движется.
            glow.animateTo(1f, tween(200))
        } else {
            glow.snapTo(1f)
        }
    }

    val borderColor = if (state == MascotState.OVERHEAT) OverheatGlow.copy(alpha = glow.value) else CardBorder

    Box(
        modifier = modifier
            .scale(bounce.value)
            .rotate(wobble.value * 5f)
            .clip(RoundedCornerShape(18.dp))
            .background(CardBg)
            .border(2.dp, borderColor, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                (
                    scaleIn(initialScale = 0.55f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)) +
                        fadeIn(tween(120))
                    ) togetherWith (scaleOut(targetScale = 0.55f, animationSpec = tween(120)) + fadeOut(tween(90)))
            },
            label = "mascot-face",
        ) { s ->
            val look = lookFor(s)
            Text(
                text = if (blinking) look.blinkFace else look.face,
                color = look.color,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
    }
}
