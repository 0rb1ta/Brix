package app.brix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import app.brix.core.ButtonAction
import app.brix.core.QuickButtonConfig
import app.brix.core.QuickButtonSlot
import app.brix.core.QuickButtonWidth
import app.brix.core.QuickButtonColumn
import app.brix.ui.theme.liveColor

private val SMALL_W = 50.dp
private val SMALL_H = 50.dp
private val LARGE_W = 104.dp // exactly two SMALL cells + spacing, so the Stop/large buttons align with the grid columns
private val GRID_SPACING = 4.dp
private const val GRID_ACTION_ROWS = 5
private val STOP_HEIGHT = SMALL_H
private const val LONG_PRESS_MS = 1500L
private const val STOP_HOLD_MS = 700L

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapWithLongPress(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onPressedChange: (Boolean) -> Unit = {},
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onPressedChange(true)
        val downPos = down.position
        var up: androidx.compose.ui.input.pointer.PointerInputChange? = null
        var swiped = false
        // A long hold almost always accumulates a few px of finger drift; the
        // stock touchSlop (~8px) would misclassify it as a swipe. Allow 3x.
        val slop = viewConfiguration.touchSlop * 3f
        // TOTAL deadline, not per-event: a steady stream of MOVE events
        // (finger micro-tremor) would otherwise reset a per-event timeout
        // forever and the long-press would never fire.
        val deadline = android.os.SystemClock.elapsedRealtime() + LONG_PRESS_MS
        while (true) {
            val event = withTimeoutOrNull((deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)) {
                awaitPointerEvent()
            }
            if (event == null) break // total hold time elapsed
            val change = event.changes.firstOrNull { it.id == down.id }
            when {
                change == null -> break
                !change.pressed -> { up = change; break }
                (change.position - downPos).getDistance() > slop -> {
                    swiped = true
                    break
                }
            }
        }
        onPressedChange(false)
        when {
            up != null -> onTap()
            swiped -> Unit
            else -> {
                onLongPress()
                waitForUpOrCancellation()
            }
        }
    }
}

fun ButtonAction.icon(active: Boolean = false): ImageVector = when (this) {
    ButtonAction.MUTE -> if (active) Icons.Filled.MicOff else Icons.Filled.Mic
    ButtonAction.TORCH -> if (active) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff
    ButtonAction.BLACK_SCREEN -> if (active) Icons.Filled.VisibilityOff else Icons.Filled.Visibility
    ButtonAction.FLIP -> Icons.Filled.FlipCameraAndroid
    ButtonAction.INFO -> Icons.Filled.Info
    ButtonAction.LOCK -> Icons.Filled.Lock
    ButtonAction.RECONNECT -> Icons.Filled.Refresh
    ButtonAction.SETTINGS -> Icons.Filled.Settings
    ButtonAction.ADAPTIVE_BITRATE -> Icons.Filled.Speed
    ButtonAction.OVERLAY -> if (active) Icons.Filled.Layers else Icons.Filled.Layers
    ButtonAction.VIDEO_EFFECT -> Icons.Filled.Palette
    ButtonAction.MASCOT -> Icons.Filled.Face
    ButtonAction.POWER_SAVE -> Icons.Filled.BatterySaver
    // Активна, когда микрофон выбран вручную, а не отдан системе.
    ButtonAction.MIC -> if (active) Icons.Filled.SettingsVoice else Icons.Filled.KeyboardVoice
    ButtonAction.SCENE -> Icons.Filled.Layers
    ButtonAction.SNAPSHOT -> Icons.Filled.PhotoCamera
}

@Composable
fun ButtonAction.label(): String = stringResource(
    when (this) {
        ButtonAction.MUTE -> R.string.button_mute
        ButtonAction.TORCH -> R.string.button_torch
        ButtonAction.FLIP -> R.string.button_flip
        ButtonAction.INFO -> R.string.button_info
        ButtonAction.LOCK -> R.string.button_lock
        ButtonAction.BLACK_SCREEN -> R.string.button_black_screen
        ButtonAction.RECONNECT -> R.string.button_reconnect
        ButtonAction.SETTINGS -> R.string.button_settings
        ButtonAction.ADAPTIVE_BITRATE -> R.string.button_adaptive_bitrate
        ButtonAction.OVERLAY -> R.string.button_overlay
        ButtonAction.VIDEO_EFFECT -> R.string.button_video_effect
        ButtonAction.MASCOT -> R.string.button_mascot
        ButtonAction.POWER_SAVE -> R.string.button_power_save
        ButtonAction.MIC -> R.string.button_mic
        ButtonAction.SCENE -> R.string.button_scene
        ButtonAction.SNAPSHOT -> R.string.button_snapshot
    },
)

private sealed interface MenuTarget
private data class ActionCell(val row: Int, val col: QuickButtonColumn?, val slot: QuickButtonSlot?) : MenuTarget
private data object StopCell : MenuTarget

/**
 * Live, editable in-stream control grid. A fixed 6-row × 2-column layout:
 * 5 rows of action cells plus the Start/Stop button occupying the 6th (bottom)
 * row. Each action cell is either a filled button or a minimalist "+"
 * placeholder. Long-pressing any cell enters edit mode and opens a menu to
 * assign an action and pick the size. Edits are pushed back via [onConfigChange].
 */
@Composable
fun QuickButtonGrid(
    config: QuickButtonConfig,
    streaming: Boolean,
    activeActions: Set<ButtonAction>,
    onAction: (ButtonAction) -> Unit,
    onStop: () -> Unit,
    onConfigChange: (QuickButtonConfig) -> Unit,
    editing: Boolean = false,
    onEditingChange: (Boolean) -> Unit = {},
    mascotState: MascotState = MascotState.IDLE,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf<MenuTarget?>(null) }
    var menuSize by remember { mutableStateOf(QuickButtonWidth.SMALL) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    // Recomputed only when config.slots actually changes, not on every
    // recomposition (audit 4.7) — cheap on its own, but this grid recomposes
    // often (streaming stats tick every second) and these were rebuilt every
    // time regardless.
    val largeByRow = remember(config.slots) {
        config.slots.filter { it.width == QuickButtonWidth.LARGE }.associateBy { it.rowIndex }
    }
    val smallByCell = remember(config.slots) {
        config.slots.filter { it.width == QuickButtonWidth.SMALL }
            .associateBy { it.rowIndex to it.columnPosition }
    }
    val usedActions = remember(config.slots) { config.slots.map { it.action }.toSet() }

    fun build(nextSlots: List<QuickButtonSlot>): QuickButtonConfig =
        config.copy(slots = nextSlots)

    fun placeSmall(row: Int, col: QuickButtonColumn, action: ButtonAction) {
        val without = config.slots.filterNot { it.rowIndex == row && it.columnPosition == col }
        onConfigChange(build(without + QuickButtonSlot(action, QuickButtonWidth.SMALL, row, col)))
    }
    fun placeLarge(row: Int, action: ButtonAction) {
        val without = config.slots.filterNot { it.rowIndex == row }
        onConfigChange(build(without + QuickButtonSlot(action, QuickButtonWidth.LARGE, row, QuickButtonColumn.NULL)))
    }
    fun changeAction(slot: QuickButtonSlot, action: ButtonAction) {
        onConfigChange(build(config.slots.map { if (it === slot) it.copy(action = action) else it }))
    }
    fun removeSlot(slot: QuickButtonSlot) {
        onConfigChange(build(config.slots - slot))
    }

    fun openActionMenu(row: Int, col: QuickButtonColumn?, slot: QuickButtonSlot?) {
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        onEditingChange(true)
        menuSize = slot?.width ?: QuickButtonWidth.SMALL
        menu = ActionCell(row, col, slot)
    }

    Column(
        modifier = modifier.fillMaxHeight().padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (editing) {
            androidx.compose.material3.FilledTonalButton(
                onClick = { onEditingChange(false); menu = null },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.btn_done), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(8.dp))
        }

        BoxWithConstraints(
            modifier = Modifier.weight(1f, fill = true),
        ) {
            val maxRowsByHeight = ((maxHeight - STOP_HEIGHT) / (SMALL_H + GRID_SPACING)).toInt().coerceIn(3, 7)
            val displayRows = maxRowsByHeight.coerceAtLeast(GRID_ACTION_ROWS)
            val needed = (SMALL_H + GRID_SPACING) * displayRows + STOP_HEIGHT
            val useScroll = maxHeight < needed + 16.dp
            if (useScroll) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    for (row in 0 until displayRows) {
                val large = largeByRow[row]
                if (large != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        QuickActionButton(
                            action = large.action,
                            wide = true,
                            mascotState = mascotState,
                            onClick = { if (editing) openActionMenu(row, null, large) else onAction(large.action) },
                            onLongPress = { openActionMenu(row, null, large) },
                        )
                        if (editing) {
                            DeleteBadge(
                                onClick = { removeSlot(large) },
                                modifier = Modifier.align(Alignment.TopEnd),
                            )
                        }
                        if (menu == ActionCell(row, null, large)) {
                            SlotMenu(
                                existing = large,
                                usedActions = usedActions,
                                size = menuSize,
                                onSize = { menuSize = it },
                                largeAllowed = false,
                                onPick = { changeAction(large, it); menu = null },
                                onRemove = { removeSlot(large); menu = null },
                                onClose = { menu = null },
                            )
                        }
                    }
                    Spacer(Modifier.height(GRID_SPACING))
                } else {
                    val leftSlot = smallByCell[row to QuickButtonColumn.LEFT]
                    val rightSlot = smallByCell[row to QuickButtonColumn.RIGHT]
                    Row(horizontalArrangement = Arrangement.spacedBy(GRID_SPACING)) {
                        ActionCellView(
                            row = row,
                            col = QuickButtonColumn.LEFT,
                            slot = leftSlot,
                            editing = editing,
                            active = leftSlot?.action in activeActions,
                            mascotState = mascotState,
                            onAction = onAction,
                            onRemove = leftSlot?.let { { removeSlot(it) } },
                            openMenu = { openActionMenu(row, QuickButtonColumn.LEFT, leftSlot) },
                            menuOpen = menu == ActionCell(row, QuickButtonColumn.LEFT, leftSlot),
                            menuContent = {
                                SlotMenu(
                                    existing = leftSlot,
                                    usedActions = usedActions,
                                    size = menuSize,
                                    onSize = { menuSize = it },
                                    largeAllowed = rightSlot == null,
                                    onPick = { action ->
                                        if (leftSlot != null) {
                                            changeAction(leftSlot, action)
                                        } else if (menuSize == QuickButtonWidth.LARGE) {
                                            placeLarge(row, action)
                                        } else {
                                            placeSmall(row, QuickButtonColumn.LEFT, action)
                                        }
                                        menu = null
                                    },
                                    onRemove = leftSlot?.let { { removeSlot(it) } },
                                    onClose = { menu = null },
                                )
                            },
                        )
                        ActionCellView(
                            row = row,
                            col = QuickButtonColumn.RIGHT,
                            slot = rightSlot,
                            editing = editing,
                            active = rightSlot?.action in activeActions,
                            mascotState = mascotState,
                            onAction = onAction,
                            onRemove = rightSlot?.let { { removeSlot(it) } },
                            openMenu = { openActionMenu(row, QuickButtonColumn.RIGHT, rightSlot) },
                            menuOpen = menu == ActionCell(row, QuickButtonColumn.RIGHT, rightSlot),
                            menuContent = {
                                SlotMenu(
                                    existing = rightSlot,
                                    usedActions = usedActions,
                                    size = menuSize,
                                    onSize = { menuSize = it },
                                    largeAllowed = leftSlot == null,
                                    onPick = { action ->
                                        if (rightSlot != null) {
                                            changeAction(rightSlot, action)
                                        } else if (menuSize == QuickButtonWidth.LARGE) {
                                            placeLarge(row, action)
                                        } else {
                                            placeSmall(row, QuickButtonColumn.RIGHT, action)
                                        }
                                        menu = null
                                    },
                                    onRemove = rightSlot?.let { { removeSlot(it) } },
                                    onClose = { menu = null },
                                )
                            },
                        )
                    }
                    Spacer(Modifier.height(GRID_SPACING))
                }
            }

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                StopButton(
                    wide = true,
                    streaming = streaming,
                    onClick = { if (editing) menu = StopCell else onStop() },
                )
            }

            if (menu == StopCell) {
                StopMenu(onClose = { menu = null })
            }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    for (row in 0 until displayRows) {
                        val large = largeByRow[row]
                        if (large != null) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                QuickActionButton(
                                    action = large.action,
                                    wide = true,
                                    active = large.action in activeActions,
                                    mascotState = mascotState,
                                    onClick = { if (editing) openActionMenu(row, null, large) else onAction(large.action) },
                                    onLongPress = { openActionMenu(row, null, large) },
                                )
                                if (editing) {
                                    DeleteBadge(onClick = { removeSlot(large) }, modifier = Modifier.align(Alignment.TopEnd))
                                }
                                if (menu == ActionCell(row, null, large)) {
                                    SlotMenu(existing = large, usedActions = usedActions, size = menuSize, onSize = { menuSize = it }, largeAllowed = false, onPick = { changeAction(large, it); menu = null }, onRemove = { removeSlot(large); menu = null }, onClose = { menu = null })
                                }
                            }
                        } else {
                            val leftSlot = smallByCell[row to QuickButtonColumn.LEFT]
                            val rightSlot = smallByCell[row to QuickButtonColumn.RIGHT]
                            Row(horizontalArrangement = Arrangement.spacedBy(GRID_SPACING)) {
                                ActionCellView(row = row, col = QuickButtonColumn.LEFT, slot = leftSlot, editing = editing, active = leftSlot?.action in activeActions, mascotState = mascotState, onAction = onAction, onRemove = leftSlot?.let { { removeSlot(it) } }, openMenu = { openActionMenu(row, QuickButtonColumn.LEFT, leftSlot) }, menuOpen = menu == ActionCell(row, QuickButtonColumn.LEFT, leftSlot), menuContent = { SlotMenu(existing = leftSlot, usedActions = usedActions, size = menuSize, onSize = { menuSize = it }, largeAllowed = rightSlot == null, onPick = { action -> if (leftSlot != null) changeAction(leftSlot, action) else if (menuSize == QuickButtonWidth.LARGE) placeLarge(row, action) else placeSmall(row, QuickButtonColumn.LEFT, action); menu = null }, onRemove = leftSlot?.let { { removeSlot(it) } }, onClose = { menu = null }) })
                                ActionCellView(row = row, col = QuickButtonColumn.RIGHT, slot = rightSlot, editing = editing, active = rightSlot?.action in activeActions, mascotState = mascotState, onAction = onAction, onRemove = rightSlot?.let { { removeSlot(it) } }, openMenu = { openActionMenu(row, QuickButtonColumn.RIGHT, rightSlot) }, menuOpen = menu == ActionCell(row, QuickButtonColumn.RIGHT, rightSlot), menuContent = { SlotMenu(existing = rightSlot, usedActions = usedActions, size = menuSize, onSize = { menuSize = it }, largeAllowed = leftSlot == null, onPick = { action -> if (rightSlot != null) changeAction(rightSlot, action) else if (menuSize == QuickButtonWidth.LARGE) placeLarge(row, action) else placeSmall(row, QuickButtonColumn.RIGHT, action); menu = null }, onRemove = rightSlot?.let { { removeSlot(it) } }, onClose = { menu = null }) })
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        StopButton(wide = true, streaming = streaming, onClick = { if (editing) menu = StopCell else onStop() })
                    }
                    if (menu == StopCell) { StopMenu(onClose = { menu = null }) }
                }
            }
        }
    }
}

@Composable
private fun ActionCellView(
    row: Int,
    col: QuickButtonColumn,
    slot: QuickButtonSlot?,
    editing: Boolean,
    active: Boolean,
    onAction: (ButtonAction) -> Unit,
    onRemove: (() -> Unit)?,
    openMenu: () -> Unit,
    menuOpen: Boolean,
    menuContent: @Composable () -> Unit,
    mascotState: MascotState = MascotState.IDLE,
) {
    Box(Modifier.size(SMALL_W, SMALL_H)) {
        if (slot != null) {
            QuickActionButton(
                action = slot.action,
                wide = false,
                active = active,
                mascotState = mascotState,
                onClick = { if (editing) openMenu() else onAction(slot.action) },
                onLongPress = openMenu,
            )
            if (editing && onRemove != null) {
                DeleteBadge(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd))
            }
        } else {
            Placeholder(
                onTap = { if (editing) openMenu() },
                onLongPress = openMenu,
            )
        }
        if (menuOpen) menuContent()
    }
}

@Composable
private fun DeleteBadge(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // rememberUpdatedState: the gesture block captures lambdas once; without
    // this a reorder/delete would act on the PRE-edit slot.
    val currentOnClick by rememberUpdatedState(onClick)
    Box(
        modifier = modifier
            .offset(x = 6.dp, y = (-6).dp)
            .size(20.dp)
            .background(MaterialTheme.colorScheme.error, RoundedCornerShape(10.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { currentOnClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Close,
            contentDescription = stringResource(R.string.btn_delete),
            tint = MaterialTheme.colorScheme.onError,
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
private fun Placeholder(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val currentTap by rememberUpdatedState(onTap)
    val currentLongPress by rememberUpdatedState(onLongPress)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .border(
                2.dp,
                if (pressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(10.dp),
            )
            .pointerInput(Unit) {
                detectTapWithLongPress(
                    onTap = { currentTap() },
                    onLongPress = { currentLongPress() },
                    onPressedChange = { pressed = it },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = stringResource(R.string.quick_button_add),
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun QuickActionButton(
    action: ButtonAction,
    wide: Boolean,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    mascotState: MascotState = MascotState.IDLE,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    val sizeMod = if (wide) Modifier.width(LARGE_W).height(SMALL_H) else Modifier.size(SMALL_W, SMALL_H)
    var pressed by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    if (action == ButtonAction.MASCOT) {
        // The mascot draws its own card (background/border) — still wrapped
        // in the same tap/long-press box so long-press-to-edit keeps working
        // like every other slot.
        Box(
            modifier = modifier
                .then(sizeMod)
                .pointerInput(Unit) {
                    detectTapWithLongPress(
                        onTap = { currentOnClick() },
                        onLongPress = { currentOnLongPress() },
                        onPressedChange = { pressed = it },
                    )
                },
        ) {
            BrixMascot(state = mascotState, modifier = Modifier.fillMaxSize())
        }
        return
    }

    val isMuteActive = active && action == ButtonAction.MUTE
    val bg = when {
        isMuteActive -> MaterialTheme.colorScheme.error
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val iconTint = when {
        isMuteActive -> MaterialTheme.colorScheme.onError
        active -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    val borderColor = when {
        pressed -> MaterialTheme.colorScheme.primary
        isMuteActive -> MaterialTheme.colorScheme.error
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        modifier = modifier
            .then(sizeMod)
            .background(bg, RoundedCornerShape(10.dp))
            .border(2.dp, borderColor, RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                detectTapWithLongPress(
                    onTap = { currentOnClick() },
                    onLongPress = { currentOnLongPress() },
                    onPressedChange = { pressed = it },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = action.icon(active),
            contentDescription = action.label(),
            tint = iconTint,
        )
    }
}

@Composable
private fun StopButton(
    wide: Boolean,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    fillMax: Boolean = false,
    onClick: () -> Unit,
) {
    val sizeMod = if (fillMax) {
        Modifier
    } else if (wide) {
        Modifier.width(LARGE_W).height(SMALL_H)
    } else {
        Modifier.size(SMALL_W, SMALL_H)
    }
    // brix-instream-buttons-v3: Stop = hold-to-confirm (700ms). A short tap
    // does nothing — the most expensive mistake in the app must be deliberate.
    var holding by remember { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    // Border progress: fills the button outline over the hold window so the
    // user sees exactly how much longer they must keep holding.
    val holdProgress = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(holding, streaming) {
        if (streaming && holding) {
            androidx.compose.animation.core.animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.tween(STOP_HOLD_MS.toInt()),
            ) { v, _ -> holdProgress.floatValue = v }
        } else {
            holdProgress.floatValue = 0f
        }
    }
    val confirming = streaming && holding
    val bg = when {
        confirming -> MaterialTheme.colorScheme.error
        streaming -> liveColor()
        else -> MaterialTheme.colorScheme.primary
    }
    val holdStroke = 3.dp
    Box(
        modifier = modifier
            .then(sizeMod)
            .background(bg, RoundedCornerShape(10.dp))
            .drawBehind {
                if (!streaming) return@drawBehind
                val strokePx = holdStroke.toPx()
                val inset = strokePx / 2
                val radius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx() - inset)
                val rect = androidx.compose.ui.geometry.RoundRect(
                    inset, inset,
                    size.width - inset, size.height - inset,
                    radius,
                )
                val path = androidx.compose.ui.graphics.Path().apply { addRoundRect(rect) }
                val len = androidx.compose.ui.graphics.PathMeasure()
                    .apply { setPath(path, false) }
                    .length
                // Dim base outline…
                drawPath(
                    path,
                    Color.White.copy(alpha = 0.35f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(strokePx),
                )
                // …then the progress segment running along the perimeter.
                val p = holdProgress.floatValue
                if (p > 0f) {
                    drawPath(
                        path,
                        Color.White,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            strokePx,
                            pathEffect = androidx.compose.ui.graphics.PathEffect
                                .dashPathEffect(floatArrayOf(len, len), len * (1f - p)),
                        ),
                    )
                }
            }
            .pointerInput(streaming) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPos = down.position
                    val startAt = android.os.SystemClock.elapsedRealtime()
                    if (streaming) holding = true
                    var swiped = false
                    var liftedAt = 0L
                    val holdMs = if (streaming) STOP_HOLD_MS else LONG_PRESS_MS
                    val slop = viewConfiguration.touchSlop * 3f
                    // Total deadline (see detectTapWithLongPress): per-event
                    // timeouts never fire while MOVE events keep streaming in.
                    val deadline = startAt + holdMs
                    while (true) {
                        val event = withTimeoutOrNull((deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)) {
                            awaitPointerEvent()
                        } ?: break // held long enough
                        val change = event.changes.firstOrNull { it.id == down.id }
                        when {
                            change == null -> break
                            !change.pressed -> { liftedAt = android.os.SystemClock.elapsedRealtime(); break }
                            (change.position - downPos).getDistance() > slop -> {
                                swiped = true
                                break
                            }
                        }
                    }
                    holding = false
                    val heldMs = (if (liftedAt > 0) liftedAt else android.os.SystemClock.elapsedRealtime()) - startAt
                    val held = !swiped && heldMs >= holdMs
                    when {
                        // Swipe must never start or stop the stream.
                        swiped -> Unit
                        !streaming -> if (liftedAt > 0) currentOnClick()
                        else -> if (held) currentOnClick()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = when {
                confirming -> stringResource(R.string.btn_hold_stop)
                streaming -> stringResource(R.string.btn_stop)
                else -> stringResource(R.string.btn_start)
            },
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun SlotMenu(
    existing: QuickButtonSlot?,
    usedActions: Set<ButtonAction>,
    size: QuickButtonWidth,
    onSize: (QuickButtonWidth) -> Unit,
    largeAllowed: Boolean,
    onPick: (ButtonAction) -> Unit,
    onRemove: (() -> Unit)?,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp).width(320.dp)) {
                Text(
                    if (existing == null) stringResource(R.string.quick_button_add) else stringResource(R.string.quick_button_edit),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.quick_button_size),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = size == QuickButtonWidth.SMALL,
                        onClick = { onSize(QuickButtonWidth.SMALL) },
                        label = { Text(stringResource(R.string.quick_button_size_small)) },
                    )
                    FilterChip(
                        selected = size == QuickButtonWidth.LARGE,
                        onClick = { if (largeAllowed) onSize(QuickButtonWidth.LARGE) },
                        enabled = largeAllowed,
                        label = { Text(stringResource(R.string.quick_button_size_large)) },
                    )
                }
                if (!largeAllowed && size == QuickButtonWidth.LARGE) {
                    Text(
                        stringResource(R.string.quick_button_no_room_large),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                val actions = ButtonAction.entries
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.height(280.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(actions) { action ->
                        val alreadyUsed = action in usedActions && existing?.action != action
                        val isSelected = existing?.action == action
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .alpha(if (alreadyUsed) 0.35f else 1f)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainer,
                                    RoundedCornerShape(12.dp),
                                )
                                .border(
                                    if (isSelected) 2.dp else 0.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable(enabled = !alreadyUsed) { onPick(action) }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                        ) {
                            Icon(
                                imageVector = action.icon(),
                                contentDescription = null,
                                tint = if (alreadyUsed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                action.label(),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.btn_cancel)) }
                    if (existing != null && onRemove != null) {
                        TextButton(onClick = onRemove) {
                            Text(stringResource(R.string.btn_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StopMenu(
    onClose: () -> Unit,
) {
    DropdownMenu(expanded = true, onDismissRequest = onClose) {
        Text(
            stringResource(R.string.quick_button_stop_locked),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
internal fun CornerSlotView(
    action: ButtonAction?,
    editing: Boolean,
    active: Boolean,
    usedActions: Set<ButtonAction>,
    onAction: (ButtonAction) -> Unit,
    onAssign: (ButtonAction) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    mascotState: MascotState = MascotState.IDLE,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        if (action != null) {
            QuickActionButton(
                action = action,
                wide = false,
                active = active,
                // The corner slot can hold MASCOT like any other slot; without
                // this it rendered with the IDLE default forever, so a mascot
                // parked here never reacted to LIVE/RECONNECTING/OVERHEAT.
                mascotState = mascotState,
                onClick = { if (editing) menuOpen = true else onAction(action) },
                onLongPress = { if (editing) menuOpen = true },
            )
            if (editing) {
                DeleteBadge(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd))
            }
        } else if (editing) {
            Placeholder(
                onTap = { menuOpen = true },
                onLongPress = { menuOpen = true },
            )
        }
        if (menuOpen) {
            SlotMenu(
                existing = action?.let { QuickButtonSlot(it, QuickButtonWidth.SMALL, -1, QuickButtonColumn.NULL) },
                usedActions = usedActions,
                size = QuickButtonWidth.SMALL,
                onSize = {},
                largeAllowed = false,
                onPick = { onAssign(it); menuOpen = false },
                onRemove = { onRemove(); menuOpen = false },
                onClose = { menuOpen = false },
            )
        }
    }
}

@Composable
fun QuickButtonsConfigScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    var slots by remember(settings.quickButtons.slots) {
        mutableStateOf(settings.quickButtons.slots)
    }
    var corner by remember(settings.quickButtons.cornerSlotAction) {
        mutableStateOf(settings.quickButtons.cornerSlotAction)
    }
    var showCornerMenu by remember { mutableStateOf(false) }

    fun commit(nextSlots: List<QuickButtonSlot>, nextCorner: ButtonAction?) {
        slots = nextSlots
        corner = nextCorner
        viewModel.updateQuickButtons(QuickButtonConfig(nextSlots, nextCorner))
    }

    val usedActions = slots.map { it.action }.toSet()
    val available = ButtonAction.entries.filter { it !in usedActions }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsTopBar(
                "${stringResource(R.string.settings_appearance)} · ${stringResource(R.string.settings_quick_buttons)}",
                onBack,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding.verticalOnly())
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(R.string.quick_button_edit_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.quick_button_add),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(8.dp))
            if (available.isEmpty()) {
                Text(stringResource(R.string.quick_button_all_added), style = MaterialTheme.typography.bodySmall)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(available) { action ->
                        SuggestionChip(
                            onClick = {
                                commit(
                                    slots + QuickButtonSlot(action, QuickButtonWidth.SMALL, slots.size, QuickButtonColumn.LEFT),
                                    corner,
                                )
                            },
                            label = { Text(action.label()) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.quick_button_grid_title),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(8.dp))
            slots.forEachIndexed { index, slot ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Icon(slot.action.icon(), contentDescription = null, modifier = Modifier.size(24.dp))
                    Text(slot.action.label(), modifier = Modifier.weight(1f).padding(start = 8.dp))
                    FilterChip(
                        selected = slot.width == QuickButtonWidth.SMALL,
                        onClick = {
                            commit(
                                slots.mapIndexed { i, s -> if (i == index) s.copy(width = QuickButtonWidth.SMALL) else s },
                                corner,
                            )
                        },
                        label = { Text(stringResource(R.string.quick_button_chip_small)) },
                    )
                    FilterChip(
                        selected = slot.width == QuickButtonWidth.LARGE,
                        onClick = {
                            commit(
                                slots.mapIndexed { i, s -> if (i == index) s.copy(width = QuickButtonWidth.LARGE) else s },
                                corner,
                            )
                        },
                        label = { Text(stringResource(R.string.quick_button_chip_large)) },
                    )
                    IconButton(
                        enabled = index > 0,
                        onClick = {
                            val next = slots.toMutableList()
                            next[index] = next[index - 1].also { next[index - 1] = next[index] }
                            commit(next, corner)
                        },
                    ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.quick_button_move_up)) }
                    IconButton(
                        enabled = index < slots.lastIndex,
                        onClick = {
                            val next = slots.toMutableList()
                            next[index] = next[index + 1].also { next[index + 1] = next[index] }
                            commit(next, corner)
                        },
                    ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.quick_button_move_down)) }
                    IconButton(
                        onClick = { commit(slots.filterIndexed { i, _ -> i != index }, corner) },
                    ) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.btn_delete)) }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.quick_button_corner_title),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(8.dp))
            Box {
                SuggestionChip(
                    onClick = { showCornerMenu = true },
                    label = { Text(corner?.label() ?: stringResource(R.string.button_none)) },
                )
                DropdownMenu(
                    expanded = showCornerMenu,
                    onDismissRequest = { showCornerMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.button_none)) },
                        onClick = { commit(slots, null); showCornerMenu = false },
                    )
                    ButtonAction.entries.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action.label()) },
                            onClick = { commit(slots, action); showCornerMenu = false },
                        )
                    }
                }
            }
        }
    }
}
