package app.brix.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.brix.streaming.chat.ChatMessage
import app.brix.streaming.chat.ChatPlatform
import kotlin.math.round

private const val FONT_SCALE_MIN = 0.7f
private const val FONT_SCALE_MAX = 1.8f
private const val FONT_SCALE_STEP = 0.1f

/**
 * Чат на экране стримера — не в HUD (там компактная строка статистики,
 * текст переменной длины дёргал бы её), и не в потоке: обычный Compose-элемент
 * поверх превью, событийный, GL-тракт не трогает.
 *
 * Две ловушки, которых здесь избегаем:
 * - `items(messages, key = { it.id })` — без ключа LazyColumn перерисовывает
 *   весь список на каждое новое сообщение;
 * - автопрокрутка — `scrollToItem`, а не `animateScrollToItem`: анимация
 *   гоняла бы кадры непрерывно, пока чат открыт, а не только на новое
 *   сообщение.
 *
 * Размер и положение панели меняются перетаскиванием (см. `FractionalBox`/
 * `OverlayTuner` в StreamScreen.kt, вход — иконка [onEnterPlacement] в шапке);
 * размер шрифта — кнопками A±, тут же рядом со статусом подключения.
 *
 * Мультичат: [messages] — уже слитая лента из всех включённых площадок
 * (см. `mergeChatMessages` в :streaming), сюда попадает готовый список, без
 * знания о конкретных клиентах.
 */
@Composable
fun ChatPanel(
    messages: List<ChatMessage>,
    platformStatuses: List<Pair<ChatPlatform, Boolean>>,
    fontScale: Float,
    onFontScaleChange: (Float) -> Unit,
    onEnterPlacement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.chat_panel_title),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
            Spacer(Modifier.width(6.dp))
            // По иконке+точке статуса на каждую ВКЛЮЧЁННУЮ площадку — не одна
            // общая точка на всех: в мультичате важно видеть, что именно Kick
            // отвалился, а не гадать, кто из трёх сейчас не в сети.
            platformStatuses.forEach { (platform, isConnected) ->
                PlatformStatusBadge(platform, isConnected)
                Spacer(Modifier.width(6.dp))
            }
            Spacer(Modifier.weight(1f))
            HeaderIconButton(
                icon = Icons.Filled.Remove,
                contentDescription = stringResource(R.string.chat_font_smaller),
                enabled = fontScale > FONT_SCALE_MIN,
                onClick = { onFontScaleChange(stepFontScale(fontScale, -FONT_SCALE_STEP)) },
            )
            HeaderIconButton(
                icon = Icons.Filled.Add,
                contentDescription = stringResource(R.string.chat_font_bigger),
                enabled = fontScale < FONT_SCALE_MAX,
                onClick = { onFontScaleChange(stepFontScale(fontScale, FONT_SCALE_STEP)) },
            )
            HeaderIconButton(
                icon = Icons.Filled.OpenWith,
                contentDescription = stringResource(R.string.chat_change_position),
                onClick = onEnterPlacement,
            )
            HeaderIconButton(
                icon = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(R.string.chat_panel_title),
                onClick = { expanded = !expanded },
            )
        }
        if (expanded) {
            if (messages.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_panel_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    // weight — не fillMaxHeight: Column не задаёт минимальную
                    // высоту (см. FractionalBox.heightIn(max=...)), и без
                    // взвешенного ребёнка сам подстроился бы под контент. Со
                    // взвешенным — использует весь потолок только когда
                    // список реально показан.
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = true).padding(top = 4.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        ChatMessageRow(message, fontScale)
                    }
                }
            }
        }
    }
}

private fun stepFontScale(current: Float, delta: Float): Float {
    // round() против плавающего накопления погрешности (0.1f не точен в float).
    val stepped = round((current + delta) / FONT_SCALE_STEP) * FONT_SCALE_STEP
    return stepped.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)
}

@Composable
private fun HeaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(16.dp)
            .clickable(enabled = enabled, onClick = onClick),
    )
}

/** Одна и та же площадка — один и тот же значок везде: в шапке (статус
 *  подключения) и перед каждым сообщением (кто откуда пишет). Реальные
 *  бренд-иконки (simpleicons.org, CC0), не самодельные метки. */
private fun platformIconRes(platform: ChatPlatform): Int = when (platform) {
    ChatPlatform.TWITCH -> R.drawable.ic_platform_twitch
    ChatPlatform.KICK -> R.drawable.ic_platform_kick
    ChatPlatform.VK -> R.drawable.ic_platform_vk
}

@Composable
private fun PlatformStatusBadge(platform: ChatPlatform, connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(6.dp)
                .background(
                    if (connected) Color(0xFF66E066) else Color(0xFFE06666),
                    CircleShape,
                ),
        )
        Spacer(Modifier.width(3.dp))
        Image(
            painter = painterResource(platformIconRes(platform)),
            contentDescription = null,
            modifier = Modifier.size(13.dp),
        )
    }
}

@Composable
private fun ChatMessageRow(message: ChatMessage, fontScale: Float) {
    val authorColor = message.colorHex?.let { hex ->
        runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
    } ?: Color(0xFFB39DDB)
    val baseStyle = MaterialTheme.typography.bodySmall
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(vertical = 1.dp),
    ) {
        Image(
            painter = painterResource(platformIconRes(message.platform)),
            contentDescription = null,
            modifier = Modifier
                .padding(top = 2.dp, end = 4.dp)
                .size(12.dp * fontScale),
        )
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = authorColor, fontWeight = FontWeight.Bold)) {
                    append(message.author)
                }
                append(": ")
                append(message.text)
            },
            // lineHeight у Material-стилей — фиксированный sp, не доля от
            // fontSize: масштабировать нужно оба, иначе перенесённая строка
            // рисуется с прежним (маленьким) межстрочным интервалом и наезжает
            // на соседнюю — сам шрифт растёт, а зазор под него нет.
            style = baseStyle.copy(
                fontSize = baseStyle.fontSize * fontScale,
                lineHeight = baseStyle.lineHeight * fontScale,
            ),
            color = Color.White,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
