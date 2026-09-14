package app.brix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward

private val BrixShape = RoundedCornerShape(16.dp)
private val BrixRowShape = RoundedCornerShape(8.dp)

@Composable
fun BrixCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = BrixShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) { content() }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
fun BrixNavRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    divider: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
    if (divider) RowDivider()
}

@Composable
fun BrixToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    divider: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        BrixToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
    if (divider) RowDivider()
}

@Composable
fun BrixToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 36.dp, height = 20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (checked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            )
            .clickable { onCheckedChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .padding(2.dp),
        )
    }
}

@Composable
fun <T> BrixSegmentRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    subtitle: String? = null,
    divider: Boolean = true,
    onSelected: (T) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(BrixRowShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEach { (value, label) ->
                val isSelected = selected == value
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Transparent
                            },
                        )
                        .clickable { onSelected(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
    if (divider) RowDivider()
}

/**
 * Компактный диалог вместо `AlertDialog`.
 *
 * У материального диалога поля 24 dp по кругу плюс свои промежутки между
 * заголовком, текстом и кнопками. На телефоне, который в эфире держат
 * горизонтально, это съедает почти всю доступную высоту: содержимого на две
 * строки, а окно во весь экран (владелец, 15.09 — «гигантские поля, непонятно
 * нахрена сделанные»). Здесь поля 16 dp, промежутки 8, ширина ограничена —
 * рамка обнимает содержимое, а не наоборот.
 */
@Composable
fun BrixDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String? = null,
    /** Опасное действие — красная кнопка подтверждения. */
    destructive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = BrixShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = modifier.widthIn(max = 360.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                content()
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    // У TextButton своя минимальная высота 40 dp и поля 24 dp
                    // по бокам — на два слова это половина диалога. Ужимаем до
                    // размера, который всё ещё уверенно попадается пальцем.
                    val tight = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    if (dismissLabel != null) {
                        androidx.compose.material3.TextButton(
                            onClick = onDismiss,
                            contentPadding = tight,
                            modifier = Modifier.heightIn(min = 34.dp),
                        ) { Text(dismissLabel) }
                        Spacer(Modifier.width(2.dp))
                    }
                    androidx.compose.material3.TextButton(
                        onClick = onConfirm,
                        contentPadding = tight,
                        modifier = Modifier.heightIn(min = 34.dp),
                    ) {
                        Text(
                            text = confirmLabel,
                            color = if (destructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
            }
        }
    }
}
