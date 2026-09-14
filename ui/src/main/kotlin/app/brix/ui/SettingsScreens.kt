package app.brix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import app.brix.core.diagnostics.CrashReporter
import app.brix.core.diagnostics.Diagnostics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.brix.core.CameraSide
import app.brix.core.BrowserWidgetConfig
import app.brix.core.normalizeLanguageTag
import app.brix.core.OverlayConfig
import app.brix.core.AudioProcessing
import app.brix.core.MicSource
import app.brix.streaming.MicDevices
import app.brix.core.ServerProfile
import app.brix.core.ThemeMode
import java.util.Locale

sealed interface SettingsRoute {
    data object Menu : SettingsRoute
    data object StreamProfiles : SettingsRoute
    data object ServerProfiles : SettingsRoute
    data object Scenes : SettingsRoute
    data class SceneEdit(val sceneId: String?) : SettingsRoute
    data object ChannelPriorities : SettingsRoute
    data object QuickButtons : SettingsRoute
    data object Camera : SettingsRoute
    data object Audio : SettingsRoute
    data object Appearance : SettingsRoute
    data object Hud : SettingsRoute
    data object Overlay : SettingsRoute
    data object BrowserWidgets : SettingsRoute
    data object Language : SettingsRoute
    data object Advanced : SettingsRoute
    data object About : SettingsRoute
    data object Diagnostics : SettingsRoute
    data object DevicePassport : SettingsRoute
    data class StreamProfileEdit(val profileId: String?) : SettingsRoute
    data class ServerProfileEdit(val profileId: String?) : SettingsRoute
    data class OverlayEdit(val overlayId: String?) : SettingsRoute
    data class BrowserWidgetEdit(val widgetId: String?) : SettingsRoute
}

enum class SettingsCategory {
    STREAM, CAMERA, AUDIO, APPEARANCE, ADVANCED, MOBLINK, CHAT, CONFIG, ABOUT
}

@Composable
fun CategoryRail(
    selected: SettingsCategory,
    onSelect: (SettingsCategory) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = listOf(
        SettingsCategory.STREAM to R.string.settings_cat_stream,
        SettingsCategory.CAMERA to R.string.settings_cat_camera,
        SettingsCategory.AUDIO to R.string.settings_cat_audio,
        SettingsCategory.APPEARANCE to R.string.settings_cat_appearance,
        SettingsCategory.ADVANCED to R.string.settings_cat_advanced,
        SettingsCategory.MOBLINK to R.string.settings_cat_moblink,
        SettingsCategory.CHAT to R.string.settings_cat_chat,
        SettingsCategory.CONFIG to R.string.settings_cat_config,
        SettingsCategory.ABOUT to R.string.settings_cat_about,
    )
    // Консольное меню, а не список карточек: моноширинный текст и тонкая
    // полоса-индикатор слева от выбранного пункта — тот же терминальный
    // характер, что уже есть у маскота и вордмарка (design/Brix/assets),
    // просто до сих пор нигде в самом интерфейсе не звучал. Полоса вместо
    // заливки всей строки — сдержаннее, ближе к тому, как выбор выглядит на
    // настоящих панелях управления, а не как подсвеченная ячейка таблицы.
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(184.dp)
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                fontFamily = FontFamily.Monospace,
                fontSize = MaterialTheme.typography.titleMedium.fontSize,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.btn_close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // Scrollable: 8 categories (since Moblink/Config were added) no longer
        // fit the fixed-height rail on a landscape phone screen — without this
        // the last entries (About) were silently clipped off the bottom with
        // no way to reach them.
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            items.forEachIndexed { index, (cat, labelRes) ->
                val active = cat == selected
                Row(
                    // IntrinsicSize.Min — иначе внутри прокручиваемого Column
                    // высота строки для измерения бесконечна, и Box.fillMaxHeight()
                    // ниже схлопывается в 0: "заполнить максимум" от infinity
                    // не вычисляется, полоса пропадает целиком.
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .clickable { onSelect(cat) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(5.dp)
                            .fillMaxHeight()
                            .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent),
                    )
                    Text(
                        text = stringResource(labelRes),
                        fontFamily = FontFamily.Monospace,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        letterSpacing = 0.3.sp,
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(start = 11.dp, top = 13.dp, bottom = 13.dp, end = 14.dp),
                    )
                }
                if (index != items.lastIndex) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

@Composable
fun CategoryHome(
    category: SettingsCategory,
    viewModel: SettingsViewModel,
    onRoute: (SettingsRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (category) {
        // Scaffold + SettingsTopBar — тот же каркас, что у остальных восьми
        // категорий: раньше «Стрим» и «О приложении» были единственными, у
        // кого заголовок жил внутри LazyColumn (SettingsSectionHeader), а не
        // в общей шапке — то самое разное место заголовка, на которое жаловались.
        SettingsCategory.STREAM -> Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = { SettingsTopBar(stringResource(R.string.settings_stream)) },
        ) { inner ->
            LazyColumn(
                modifier = Modifier.padding(inner.verticalOnly()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    BrixCard {
                        BrixNavRow(stringResource(R.string.settings_server_profiles), divider = true, onClick = { onRoute(SettingsRoute.ServerProfiles) })
                        BrixNavRow(stringResource(R.string.settings_stream_profiles), divider = true, onClick = { onRoute(SettingsRoute.StreamProfiles) })
                        BrixNavRow(stringResource(R.string.settings_scenes), divider = true, onClick = { onRoute(SettingsRoute.Scenes) })
                        BrixNavRow(stringResource(R.string.settings_channel_priorities), divider = true, onClick = { onRoute(SettingsRoute.ChannelPriorities) })
                        BrixNavRow(stringResource(R.string.settings_overlays), divider = true, onClick = { onRoute(SettingsRoute.Overlay) })
                        BrixNavRow(stringResource(R.string.settings_browser_widgets), divider = false, onClick = { onRoute(SettingsRoute.BrowserWidgets) })
                    }
                }
            }
        }
        SettingsCategory.CAMERA -> CameraSettingsScreen(viewModel = viewModel, modifier = modifier)
        SettingsCategory.AUDIO -> AudioSettingsScreen(viewModel = viewModel, modifier = modifier)
        SettingsCategory.APPEARANCE -> AppearanceSettingsScreen(viewModel = viewModel, onRoute = onRoute, modifier = modifier)
        SettingsCategory.ADVANCED -> AdvancedSettingsScreen(viewModel = viewModel, modifier = modifier)
        SettingsCategory.MOBLINK -> MoblinkSettingsScreen(viewModel = viewModel, modifier = modifier)
        SettingsCategory.CHAT -> ChatSettingsScreen(viewModel = viewModel, modifier = modifier)
        SettingsCategory.CONFIG -> ConfigTransferScreen(viewModel = viewModel, modifier = modifier)
        SettingsCategory.ABOUT -> Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = { SettingsTopBar(stringResource(R.string.settings_about)) },
        ) { inner ->
            LazyColumn(
                modifier = Modifier.padding(inner.verticalOnly()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    BrixCard {
                        BrixNavRow(stringResource(R.string.settings_about_version), divider = true, onClick = { onRoute(SettingsRoute.About) })
                        BrixNavRow(stringResource(R.string.settings_diagnostics), divider = true, onClick = { onRoute(SettingsRoute.Diagnostics) })
                        BrixNavRow(stringResource(R.string.settings_device_passport), divider = false, onClick = { onRoute(SettingsRoute.DevicePassport) })
                    }
                }
            }
        }
    }
}

/**
 * Только вертикальные отступы от Scaffold.
 *
 * В альбомной ориентации система добавляет содержимому горизонтальный отступ под
 * вырез экрана (на S21 это 80 px слева). Панель категорий слева его не платит и
 * идёт от края, а содержимое честно отодвигалось — между ними получался провал,
 * который выглядел как ошибка вёрстки. Левый край и так закрыт панелью, поэтому
 * горизонтальную часть отступа гасим.
 */
@Composable
internal fun PaddingValues.verticalOnly(): PaddingValues = PaddingValues(
    top = calculateTopPadding(),
    bottom = calculateBottomPadding(),
)

/**
 * Заголовок слева, а не по центру — иначе кнопка назад слева без пары
 * справа сдвигает видимый центр заголовка вправо (ровно то, на что жаловались
 * при первой версии). Место под стрелку зарезервировано фиксированной
 * ширины боксом ВСЕГДА, есть там кнопка или нет — поэтому заголовок стоит
 * в одном и том же месте что на экране категории (без стрелки), что на
 * провалившемся подэкране (со стрелкой): не прыгает при навигации.
 */
@Composable
internal fun SettingsTopBar(title: String, onBack: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Кнопка нужна только там, куда действительно провалились: редакторы
        // профилей, HUD, быстрые кнопки. На экране категории идти назад некуда —
        // список категорий и так всегда стоит слева, — и «Назад» там означал
        // ровно ничего. Остаток от времён, когда панели ещё не было.
        Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.btn_back),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Заголовок с Cancel/Save по бокам — для экранов-редакторов (профиль,
 * сцена, оверлей, виджет). Раньше каждый рисовал это сам через два
 * одинаковых `Spacer(weight=1f)`, что центрирует заголовок только если
 * кнопки СЛЕВА и СПРАВА одной ширины — а «Отмена» и «Сохранить» разной
 * длины, поэтому текст всё равно съезжал. Здесь заголовок сам получает
 * `weight(1f)` и `textAlign = Center` — центрируется в том, что осталось
 * между кнопками, независимо от того, насколько они разной ширины.
 */
@Composable
internal fun SettingsEditTopBar(
    title: String,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    saveLabel: String,
    saveEnabled: Boolean = true,
    cancelLabel: String = stringResource(R.string.btn_cancel),
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel) { Text(cancelLabel) }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        TextButton(onClick = onSave, enabled = saveEnabled) { Text(saveLabel) }
    }
}

/** "Категория · Экран" в заголовке подэкрана — видно не только куда
 *  попал, но и из какой категории сюда попал (список категорий слева не
 *  подсвечивает текущий подэкран, только категорию). */
@Composable
internal fun crumb(categoryRes: Int, screenRes: Int): String =
    "${stringResource(categoryRes)} · ${stringResource(screenRes)}"

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
fun CameraSettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val camera = settings.cameraDefaults
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(stringResource(R.string.settings_camera)) },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.padding(inner.verticalOnly()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                BrixCard {
                    BrixSegmentRow(
                        title = stringResource(R.string.camera_tap_focus),
                        subtitle = stringResource(R.string.camera_tap_focus_hint),
                        options = listOf(
                            false to stringResource(R.string.camera_tap_focus_auto),
                            true to stringResource(R.string.camera_tap_focus_manual),
                        ),
                        selected = camera.tapToFocus,
                        divider = true,
                    ) { viewModel.updateCameraDefaults(camera.copy(tapToFocus = it)) }
                    BrixToggleRow(
                        stringResource(R.string.camera_stabilization),
                        subtitle = stringResource(R.string.camera_stabilization_hint),
                        checked = camera.stabilization,
                        divider = true,
                    ) { viewModel.updateCameraDefaults(camera.copy(stabilization = it)) }
                    BrixToggleRow(
                        stringResource(R.string.camera_ois),
                        subtitle = stringResource(R.string.camera_ois_hint),
                        checked = camera.opticalStabilization,
                        divider = true,
                    ) { viewModel.updateCameraDefaults(camera.copy(opticalStabilization = it)) }
                    BrixToggleRow(
                        stringResource(R.string.camera_mirror_front),
                        subtitle = stringResource(R.string.camera_mirror_front_hint),
                        checked = camera.mirrorFront,
                        divider = true,
                    ) { viewModel.updateCameraDefaults(camera.copy(mirrorFront = it)) }
                    BrixToggleRow(
                        stringResource(R.string.camera_mirror_stream),
                        subtitle = stringResource(R.string.camera_mirror_stream_hint),
                        checked = camera.mirrorFrontInStream,
                        divider = true,
                    ) { viewModel.updateCameraDefaults(camera.copy(mirrorFrontInStream = it)) }
                    BrixToggleRow(
                        stringResource(R.string.camera_torch_start),
                        subtitle = stringResource(R.string.camera_torch_start_hint),
                        checked = camera.torchOnStart,
                        divider = false,
                    ) { viewModel.updateCameraDefaults(camera.copy(torchOnStart = it)) }
                }
            }
            item {
                BrixCard {
                    BrixSegmentRow(
                        title = stringResource(R.string.camera_default),
                        subtitle = stringResource(R.string.camera_default_hint),
                        options = listOf(
                            CameraSide.BACK to stringResource(R.string.camera_back),
                            CameraSide.FRONT to stringResource(R.string.camera_front),
                        ),
                        selected = camera.defaultCamera,
                        divider = false,
                    ) { viewModel.updateCameraDefaults(camera.copy(defaultCamera = it)) }
                }
            }
        }
    }
}

@Composable
fun AudioSettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(stringResource(R.string.settings_audio)) },
    ) { inner ->
        val audio = settings.audio
        LazyColumn(
            modifier = Modifier.padding(inner.verticalOnly()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Раздел делится по смыслу, как у Moblin: сначала УСТРОЙСТВО (откуда
            // берём звук), потом ПОТОК (в каком виде он уходит в эфир). Раньше всё
            // лежало вперемешку, и частота дискретизации соседствовала с выбором
            // капсюля, хотя это про разное.
            item { AudioGroupTitle(stringResource(R.string.audio_group_stream)) }
            item {
                BrixCard {
                    BrixSegmentRow(
                        title = stringResource(R.string.field_sample_rate),
                        options = listOf(44100 to "44.1 kHz", 48000 to "48 kHz"),
                        selected = audio.sampleRate,
                        divider = false,
                    ) { viewModel.updateAudio(audio.copy(sampleRate = it)) }
                }
            }
            item {
                BrixCard {
                    BrixSegmentRow(
                        title = stringResource(R.string.field_audio_bitrate),
                        options = listOf(64 to "64", 128 to "128", 192 to "192", 256 to "256"),
                        selected = audio.bitrateKbps,
                        divider = false,
                    ) { viewModel.updateAudio(audio.copy(bitrateKbps = it)) }
                }
            }
            item {
                BrixCard {
                    BrixToggleRow(
                        stringResource(R.string.field_stereo),
                        checked = audio.stereo,
                        divider = false,
                    ) { viewModel.updateAudio(audio.copy(stereo = it)) }
                }
            }
            item { AudioGroupTitle(stringResource(R.string.audio_group_input)) }
            item {
                BrixCard {
                    // Показываем ТОЛЬКО подключённое: пункт «Bluetooth» при
                    // отсутствующей гарнитуре — тот же обман, что тумблер без
                    // кода за ним. Список считается на каждую перерисовку,
                    // потому что гарнитуру втыкают посреди эфира.
                    val context = LocalContext.current
                    val mics = MicDevices.available(context)
                    BrixSegmentRow(
                        title = stringResource(R.string.field_audio_processing),
                        options = listOf(
                            AudioProcessing.CAMCORDER to stringResource(R.string.audio_proc_camcorder),
                            AudioProcessing.MIC to stringResource(R.string.audio_proc_mic),
                            AudioProcessing.UNPROCESSED to stringResource(R.string.audio_proc_raw),
                            AudioProcessing.VOICE_COMMUNICATION to stringResource(R.string.audio_proc_voice),
                        ),
                        selected = audio.processing,
                        divider = true,
                    ) { viewModel.updateAudio(audio.copy(processing = it)) }
                    Text(
                        text = stringResource(R.string.field_audio_processing_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                    // Тип сам по себе не различает два устройства: при двух
                    // Bluetooth-гарнитурах бралась первая попавшаяся. Поэтому
                    // при выборе типа, у которого устройств больше одного,
                    // спрашиваем какое именно. Одно устройство — не спрашиваем:
                    // диалог с единственным пунктом это работа без результата.
                    var pickFor by remember { mutableStateOf<MicSource?>(null) }
                    BrixSegmentRow(
                        title = stringResource(R.string.field_mic_source),
                        options = mics.map { it to micSourceLabel(it) },
                        selected = if (audio.micSource in mics) audio.micSource else MicSource.AUTO,
                        divider = false,
                    ) { picked ->
                        val names = MicDevices.deviceNamesFor(context, picked)
                        if (names.size > 1) {
                            pickFor = picked
                        } else {
                            viewModel.updateAudio(
                                audio.copy(micSource = picked, micDeviceName = names.firstOrNull().orEmpty()),
                            )
                        }
                    }
                    // Имя выбранного устройства — под строкой. Без него человек
                    // видит «Bluetooth» и не знает, какая из двух гарнитур.
                    if (audio.micDeviceName.isNotBlank()) {
                        Text(
                            text = audio.micDeviceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        )
                    }
                    pickFor?.let { source ->
                        MicDevicePickerDialog(
                            names = MicDevices.deviceNamesFor(context, source),
                            selected = audio.micDeviceName,
                            onDismiss = { pickFor = null },
                        ) { name ->
                            viewModel.updateAudio(audio.copy(micSource = source, micDeviceName = name))
                            pickFor = null
                        }
                    }
                }
            }
            item {
                BrixCard {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(
                                R.string.field_mic_gain,
                                (audio.micGain * 100).toInt(),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = audio.micGain,
                            // Пишем на диск только когда отпустили: каждый шаг
                            // перетаскивания иначе стоил бы полной сериализации
                            // файла с fsync — та же ошибка, что была у тумблера
                            // серверов.
                            onValueChange = {
                                viewModel.updateAudio(
                                    audio.copy(micGain = (it * 20).toInt() / 20f),
                                    commit = false,
                                )
                            },
                            onValueChangeFinished = { viewModel.updateAudio(audio) },
                            valueRange = 0.5f..3f,
                        )
                        Text(
                            text = stringResource(R.string.field_mic_gain_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppearanceSettingsScreen(
    viewModel: SettingsViewModel,
    onRoute: (SettingsRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val appearance = settings.appearance
    val normalizedLanguage = normalizeLanguageTag(appearance.language)
    val languageLabel = if (normalizedLanguage.isEmpty()) {
        stringResource(R.string.language_auto)
    } else {
        displayNameForLanguageTag(normalizedLanguage)
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(stringResource(R.string.settings_appearance)) },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.padding(inner.verticalOnly()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                BrixCard {
                    BrixSegmentRow(
                        title = stringResource(R.string.appearance_theme),
                        options = listOf(
                            ThemeMode.DARK to stringResource(R.string.appearance_dark),
                            ThemeMode.LIGHT to stringResource(R.string.appearance_light),
                            ThemeMode.SYSTEM to stringResource(R.string.appearance_system),
                        ),
                        selected = appearance.theme,
                        divider = false,
                    ) { viewModel.updateAppearance(appearance.copy(theme = it)) }
                }
            }
            item {
                BrixCard {
                    BrixToggleRow(
                        stringResource(R.string.appearance_dynamic),
                        subtitle = stringResource(R.string.appearance_dynamic_hint),
                        checked = appearance.dynamicColor,
                        divider = true,
                    ) { viewModel.updateAppearance(appearance.copy(dynamicColor = it)) }
                    BrixNavRow(
                        stringResource(R.string.appearance_language),
                        subtitle = stringResource(R.string.appearance_language_hint),
                        value = languageLabel,
                        divider = true,
                        onClick = { onRoute(SettingsRoute.Language) },
                    )
                    BrixToggleRow(
                        stringResource(R.string.appearance_auto_hide_hud),
                        subtitle = stringResource(R.string.appearance_auto_hide_hud_hint),
                        checked = appearance.autoHideHud,
                        divider = false,
                    ) { viewModel.updateAppearance(appearance.copy(autoHideHud = it)) }
                }
            }
            item {
                BrixCard {
                    // HUD переехал сюда из категории «Стрим» 03.09. Он про то,
                    // что видит стример на своём экране, а не про параметры
                    // вещания, и рядом с серверами сбивал. У Moblin его аналог
                    // (Local overlays) тоже лежит в разделе Display. Плюс
                    // тумблер автоскрытия HUD и так стоит на этом же экране —
                    // теперь настройка и её содержимое рядом.
                    BrixNavRow(
                        stringResource(R.string.settings_hud),
                        divider = true,
                        onClick = { onRoute(SettingsRoute.Hud) },
                    )
                    BrixNavRow(
                        stringResource(R.string.settings_quick_buttons),
                        divider = false,
                        onClick = { onRoute(SettingsRoute.QuickButtons) },
                    )
                }
            }
        }
    }
}

/** Display name for a locale tag, in that locale's own language (e.g. "en"
 *  -> "English", "ru" -> "Русский") — so a language is recognizable even to
 *  someone who can't currently read the UI. */
private fun displayNameForLanguageTag(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
}

@Composable
fun LanguageScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val appearance = settings.appearance
    val normalizedLanguage = normalizeLanguageTag(appearance.language)
    val context = LocalContext.current
    // No hardcoded list: any values-xx/ resource folder this APK ships makes
    // "xx" show up here automatically — a translator adding a new language
    // needs to touch zero Kotlin code, just drop in a translated strings.xml.
    val options = remember(context) {
        listOf("" to null) +
            context.assets.locales
                // assets.locales() includes "" (the base/unqualified
                // config) — collapsing to base language also merges region
                // variants (en-US, en-GB -> en), since we only ship
                // per-language, not per-region, translations.
                .map { it.lowercase().substringBefore("-") }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .map { it to it }
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(crumb(R.string.settings_appearance, R.string.appearance_language), onBack) },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.padding(inner.verticalOnly()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                BrixCard {
                    options.forEachIndexed { index, (tag, displayTag) ->
                        val label = if (displayTag == null) {
                            stringResource(R.string.language_auto)
                        } else {
                            displayNameForLanguageTag(displayTag)
                        }
                        val selected = normalizedLanguage == tag
                        BrixNavRow(
                            title = label,
                            value = if (selected) "✓" else null,
                            divider = index != options.lastIndex,
                            onClick = { viewModel.updateAppearance(appearance.copy(language = tag)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AdvancedSettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val advanced = settings.advanced
    val context = LocalContext.current
    // Пересчитывается при каждом входе на экран, а не на каждой рекомпозиции:
    // после выезда важно видеть, что запись вообще появилась.
    val sessionLogs = remember(advanced.debugLog) {
        app.brix.core.diagnostics.Diagnostics.listSessionLogs(context).size
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        // Без onBack: это и есть экран категории «Расширенные», деться
        // отсюда некуда, кроме как выбрать другую категорию слева — кнопка
        // «Назад» вела бы туда же, где уже находимся.
        // Заголовок совпадает с пунктом меню слева («Расширенные»), а не с
        // отдельной строкой settings_advanced_screen («SRTLA, запись и
        // отладка») — та не совпадала ни с чем в интерфейсе и только путала.
        topBar = { SettingsTopBar(stringResource(R.string.settings_cat_advanced)) },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.padding(inner.verticalOnly()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                BrixCard {
                    BrixToggleRow(
                        stringResource(R.string.advanced_record),
                        subtitle = stringResource(R.string.advanced_record_hint),
                        checked = advanced.recordStream,
                        divider = false,
                    ) { viewModel.updateAdvanced(advanced.copy(recordStream = it)) }
                }
            }
            item {
                BrixCard {
                    BrixToggleRow(
                        stringResource(R.string.advanced_debug_log),
                        subtitle = stringResource(R.string.advanced_debug_log_hint),
                        checked = advanced.debugLog,
                        divider = true,
                    ) { viewModel.updateAdvanced(advanced.copy(debugLog = it)) }
                    BrixNavRow(
                        stringResource(R.string.advanced_debug_log_share),
                        value = if (sessionLogs == 0) {
                            stringResource(R.string.advanced_debug_log_none)
                        } else {
                            sessionLogs.toString()
                        },
                        divider = false,
                        onClick = {
                            val uri = app.brix.core.diagnostics.Diagnostics
                                .exportLatestSessionLog(context) ?: return@BrixNavRow
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                android.content.Intent.createChooser(
                                    send,
                                    context.getString(R.string.advanced_debug_log_share),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val version = remember(context) {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(crumb(R.string.settings_about, R.string.settings_about_version), onBack) },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.padding(inner.verticalOnly()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.brix_icon),
                        contentDescription = null,
                        modifier = Modifier
                            .padding(vertical = 16.dp)
                            .size(96.dp),
                    )
                }
            }
            item {
                BrixCard {
                    BrixNavRow(
                        stringResource(R.string.app_name),
                        value = version,
                        divider = false,
                        onClick = {},
                    )
                }
            }
            item {
                BrixCard {
                    BrixNavRow(
                        stringResource(R.string.about_license),
                        divider = true,
                        onClick = {},
                    )
                    BrixNavRow(
                        stringResource(R.string.about_source),
                        divider = false,
                        onClick = {},
                    )
                }
            }
        }
    }
}

@Composable
fun StreamProfilesScreen(
    viewModel: SettingsViewModel,
    onEdit: (String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(crumb(R.string.settings_stream, R.string.settings_stream_profiles), onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding.verticalOnly())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            BrixCard {
                settings.streamProfiles.forEachIndexed { index, profile ->
                    val a = profile.audio
                    val audioMode = stringResource(
                        if (a.stereo) R.string.audio_stereo else R.string.audio_mono,
                    )
                    ProfileRow(
                        title = profile.name,
                        subtitle = "${profile.video.width}x${profile.video.height} · " +
                            "${profile.video.bitrateKbps} kbps · ${profile.video.fps} fps · " +
                            stringResource(
                                R.string.audio_info,
                                a.sampleRate,
                                a.bitrateKbps,
                                audioMode,
                            ),
                        selected = settings.selectedStreamProfileId == profile.id,
                        onSelect = { viewModel.selectStreamProfile(profile.id) },
                        onClick = { onEdit(profile.id) },
                        onDelete = {
                            pendingDelete = if (settings.streamProfiles.size == 1) {
                                "last"
                            } else {
                                profile.id
                            }
                        },
                    )
                    if (index != settings.streamProfiles.lastIndex) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = { onEdit(null) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_add))
            }
        }
    }
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.dialog_delete_profile_title)) },
            text = if (pendingDelete == "last") {
                { Text(stringResource(R.string.dialog_delete_profile_last)) }
            } else null,
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete?.let { if (it != "last") viewModel.deleteStreamProfile(it) }
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.btn_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}

@Composable
fun ServerProfilesScreen(
    viewModel: SettingsViewModel,
    onEdit: (String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    var pendingDelete by remember { mutableStateOf<Pair<ServerProfile, Boolean>?>(null) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(crumb(R.string.settings_stream, R.string.settings_server_profiles), onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding.verticalOnly())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            BrixCard {
                settings.serverProfiles.forEachIndexed { index, server ->
                    ProfileRow(
                        title = server.name,
                        subtitle = "${server.type} · ${
                            if (server.enabled) stringResource(R.string.settings_enabled)
                            else stringResource(R.string.settings_disabled)
                        }",
                        selected = server.enabled,
                        onSelect = { viewModel.setActiveServer(server.id) },
                        onClick = { onEdit(server.id) },
                        onDelete = {
                            val enabledCount = settings.serverProfiles.count { it.enabled }
                            pendingDelete = server to (server.enabled && enabledCount == 1)
                        },
                    )
                    if (index != settings.serverProfiles.lastIndex) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = { onEdit(null) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_add))
            }
        }
    }
    if (pendingDelete != null) {
        val (server, isLastEnabled) = pendingDelete!!
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.dialog_delete_server_title)) },
            text = if (isLastEnabled) {
                { Text(stringResource(R.string.dialog_delete_server_last_enabled)) }
            } else null,
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteServerProfile(server.id)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.btn_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}

@Composable
fun DiagnosticsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var crashCount by remember { mutableStateOf(0) }
    var exported by remember { mutableStateOf(false) }
    LaunchedEffect(settings) {
        crashCount = withContext(Dispatchers.IO) { CrashReporter.listReports(context).size }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(crumb(R.string.settings_about, R.string.settings_diagnostics), onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding.verticalOnly())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            BrixCard {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.diagnostics_crashes, crashCount),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.diagnostics_info),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = {
                    // Export does blocking subprocess + file I/O — never on the
                    // UI thread (H10).
                    scope.launch(Dispatchers.IO) {
                        val uri = Diagnostics.export(context, settings)
                        withContext(Dispatchers.Main) {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                            exported = true
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_export_diagnostics))
            }
            if (exported) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.btn_export_diagnostics) + " ✓",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
fun MoblinkSettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val moblink = settings.moblink
    var portText by rememberSaveable(moblink.port) { mutableStateOf(moblink.port.toString()) }
    var passwordText by rememberSaveable(moblink.password) { mutableStateOf(moblink.password) }
    var weightText by rememberSaveable(moblink.relayWeight) { mutableStateOf(moblink.relayWeight.toString()) }

    var relays by remember { mutableStateOf<List<app.brix.moblink.MoblinkRelayInfo>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            relays = app.brix.streaming.StreamController.current()?.state?.value?.moblinkRelays ?: emptyList()
            kotlinx.coroutines.delay(2000)
        }
    }

    // Port/password/weight below update in-memory state on every keystroke
    // (commit = false) so the screen and MoblinkServer see the live value
    // instantly, but only hit disk once — on focus loss, or on leaving this
    // screen entirely — instead of doing a full serialize + fsync + .bak copy
    // per keystroke (6.4.1).
    DisposableEffect(Unit) {
        onDispose { viewModel.flush() }
    }

    val portValue = portText.toIntOrNull()
    val portError = portValue == null || portValue !in 1..65535

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // Без onBack — экран категории «Moblink», уходить некуда, кроме
        // другой категории слева.
        topBar = { SettingsTopBar(stringResource(R.string.settings_cat_moblink)) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding.verticalOnly()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                BrixCard {
                    BrixToggleRow(
                        stringResource(R.string.moblink_enabled),
                        subtitle = stringResource(R.string.moblink_hint),
                        checked = moblink.enabled,
                        divider = false,
                    ) { viewModel.updateMoblink(moblink.copy(enabled = it)) }
                }
            }
            item {
                BrixCard {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { text ->
                                portText = text
                                val parsed = text.toIntOrNull()
                                // Out-of-range/unparseable values are shown as
                                // an error but never reach settings — MoblinkServer
                                // would otherwise throw on start and Moblink would
                                // silently fail to come up (6.4.2).
                                if (parsed != null && parsed in 1..65535) {
                                    viewModel.updateMoblink(moblink.copy(port = parsed), commit = false)
                                }
                            },
                            label = { Text(stringResource(R.string.moblink_port)) },
                            isError = portError,
                            supportingText = {
                                if (portError) {
                                    Text(stringResource(R.string.moblink_port_error))
                                }
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { if (!it.isFocused) viewModel.flush() },
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = passwordText,
                            onValueChange = {
                                passwordText = it
                                viewModel.updateMoblink(moblink.copy(password = it), commit = false)
                            },
                            label = { Text(stringResource(R.string.moblink_password)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { if (!it.isFocused) viewModel.flush() },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.moblink_password_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = weightText,
                            onValueChange = { text ->
                                weightText = text
                                text.toIntOrNull()?.let {
                                    viewModel.updateMoblink(moblink.copy(relayWeight = it), commit = false)
                                }
                            },
                            label = { Text(stringResource(R.string.moblink_relay_weight)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { if (!it.isFocused) viewModel.flush() },
                        )
                    }
                }
            }
            item { SettingsSectionHeader(stringResource(R.string.moblink_relays_title)) }
            if (relays.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.moblink_no_relays),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            } else {
                item {
                    BrixCard {
                        relays.forEachIndexed { index, relay ->
                            BrixNavRow(
                                title = relay.name,
                                subtitle = relay.batteryPercentage?.let { "$it%" },
                                divider = index != relays.lastIndex,
                                onClick = {},
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatSettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val chat = settings.chat
    var channelText by rememberSaveable(chat.twitchChannel) { mutableStateOf(chat.twitchChannel) }
    var kickChannelText by rememberSaveable(chat.kickChannel) { mutableStateOf(chat.kickChannel) }
    var vkChannelText by rememberSaveable(chat.vkChannelUrl) { mutableStateOf(chat.vkChannelUrl) }
    var vkClientIdText by rememberSaveable(chat.vkClientId) { mutableStateOf(chat.vkClientId) }
    var vkClientSecretText by rememberSaveable(chat.vkClientSecret) { mutableStateOf(chat.vkClientSecret) }

    // Тот же приём, что у Moblink: правки идут в память на каждое нажатие
    // клавиши (commit = false), на диск — по потере фокуса или уходу с экрана.
    DisposableEffect(Unit) {
        onDispose { viewModel.flush() }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // Без onBack — экран категории «Чат», уходить некуда, кроме
        // другой категории слева.
        topBar = { SettingsTopBar(stringResource(R.string.settings_cat_chat)) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding.verticalOnly()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SettingsSectionHeader("Twitch") }
            item {
                BrixCard {
                    BrixToggleRow(
                        stringResource(R.string.chat_enabled),
                        subtitle = stringResource(R.string.chat_hint),
                        checked = chat.enabled,
                        divider = false,
                    ) { viewModel.updateChat(chat.copy(enabled = it)) }
                }
            }
            item {
                BrixCard {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = channelText,
                            onValueChange = { text ->
                                channelText = text
                                viewModel.updateChat(chat.copy(twitchChannel = text), commit = false)
                            },
                            label = { Text(stringResource(R.string.chat_twitch_channel)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { if (!it.isFocused) viewModel.flush() },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.chat_twitch_channel_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { SettingsSectionHeader("Kick") }
            item {
                BrixCard {
                    BrixToggleRow(
                        stringResource(R.string.chat_kick_enabled),
                        subtitle = stringResource(R.string.chat_hint),
                        checked = chat.kickEnabled,
                        divider = false,
                    ) { viewModel.updateChat(chat.copy(kickEnabled = it)) }
                }
            }
            item {
                BrixCard {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = kickChannelText,
                            onValueChange = { text ->
                                kickChannelText = text
                                viewModel.updateChat(chat.copy(kickChannel = text), commit = false)
                            },
                            label = { Text(stringResource(R.string.chat_kick_channel)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { if (!it.isFocused) viewModel.flush() },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.chat_kick_channel_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { SettingsSectionHeader("VK Video Live") }
            item {
                BrixCard {
                    BrixToggleRow(
                        stringResource(R.string.chat_vk_enabled),
                        subtitle = stringResource(R.string.chat_vk_hint),
                        checked = chat.vkEnabled,
                        divider = false,
                    ) { viewModel.updateChat(chat.copy(vkEnabled = it)) }
                }
            }
            item {
                BrixCard {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = vkChannelText,
                            onValueChange = { text ->
                                vkChannelText = text
                                viewModel.updateChat(chat.copy(vkChannelUrl = text), commit = false)
                            },
                            label = { Text(stringResource(R.string.chat_vk_channel)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { if (!it.isFocused) viewModel.flush() },
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = vkClientIdText,
                            onValueChange = { text ->
                                vkClientIdText = text
                                viewModel.updateChat(chat.copy(vkClientId = text), commit = false)
                            },
                            label = { Text(stringResource(R.string.chat_vk_client_id)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { if (!it.isFocused) viewModel.flush() },
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = vkClientSecretText,
                            onValueChange = { text ->
                                vkClientSecretText = text
                                viewModel.updateChat(chat.copy(vkClientSecret = text), commit = false)
                            },
                            label = { Text(stringResource(R.string.chat_vk_client_secret)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { if (!it.isFocused) viewModel.flush() },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.chat_vk_hint_detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ConfigTransferScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    var linkText by rememberSaveable { mutableStateOf("") }
    var importError by remember { mutableStateOf(false) }
    var askIncludeSecrets by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<app.brix.core.SharedConfig?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // Без onBack — экран категории «Импорт/экспорт», уходить некуда,
        // кроме другой категории слева.
        topBar = { SettingsTopBar(stringResource(R.string.settings_config_title)) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding.verticalOnly())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            BrixCard {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.config_transfer_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = { askIncludeSecrets = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_share_config))
            }
            if (askIncludeSecrets) {
                // Спрашиваем ДО отправки, а не после: ссылку уже не отозвать из
                // чужой переписки и истории браузера.
                val share = { withSecrets: Boolean ->
                    askIncludeSecrets = false
                    val link = app.brix.core.SettingsDeepLink.encode(settings, includeSecrets = withSecrets)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, link)
                    }
                    context.startActivity(Intent.createChooser(intent, null))
                }
                AlertDialog(
                    onDismissRequest = { askIncludeSecrets = false },
                    title = { Text(stringResource(R.string.dialog_share_secrets_title)) },
                    text = { Text(stringResource(R.string.dialog_share_secrets_text)) },
                    confirmButton = {
                        TextButton(onClick = { share(false) }) {
                            Text(stringResource(R.string.dialog_share_without_keys))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { share(true) }) {
                            Text(stringResource(R.string.dialog_share_with_keys))
                        }
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
            BrixCard {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = linkText,
                        onValueChange = {
                            linkText = it
                            importError = false
                        },
                        label = { Text(stringResource(R.string.config_field_link)) },
                        placeholder = { Text("brix://...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (importError) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.deep_link_import_invalid),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(
                                    android.content.ClipboardManager::class.java,
                                )
                                val clip = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                                clip?.getItemAt(0)?.text?.let {
                                    linkText = it.toString()
                                    importError = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.btn_paste_clipboard))
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = {
                                val config = app.brix.core.SettingsDeepLink.decode(linkText)
                                    ?: app.brix.core.SettingsDeepLink.decodeMoblin(linkText)
                                if (config == null) {
                                    importError = true
                                } else {
                                    pendingImport = config
                                }
                            },
                            enabled = linkText.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.btn_import))
                        }
                    }
                }
            }
        }
    }
    val toImport = pendingImport
    if (toImport != null) {
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.deep_link_import_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.deep_link_import_message,
                        toImport.streamProfiles.size,
                        toImport.serverProfiles.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importSharedConfig(toImport)
                    pendingImport = null
                    linkText = ""
                }) {
                    Text(stringResource(R.string.btn_import))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}

@Composable
fun HudSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val hud = settings.hud
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(crumb(R.string.settings_appearance, R.string.settings_hud), onBack) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding.verticalOnly())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                BrixCard {
                    BrixToggleRow(
                        stringResource(R.string.hud_battery),
                        checked = hud.showBattery,
                        divider = true,
                    ) { viewModel.updateHud(hud.copy(showBattery = it)) }
                    BrixToggleRow(
                        stringResource(R.string.hud_thermal),
                        checked = hud.showThermal,
                        divider = true,
                    ) { viewModel.updateHud(hud.copy(showThermal = it)) }
                    BrixToggleRow(
                        stringResource(R.string.hud_networks),
                        checked = hud.showNetworks,
                        divider = true,
                    ) { viewModel.updateHud(hud.copy(showNetworks = it)) }
                    BrixToggleRow(
                        stringResource(R.string.hud_bitrate),
                        checked = hud.showBitrate,
                        divider = true,
                    ) { viewModel.updateHud(hud.copy(showBitrate = it)) }
                    BrixToggleRow(
                        stringResource(R.string.hud_uptime),
                        checked = hud.showUptime,
                        divider = true,
                    ) { viewModel.updateHud(hud.copy(showUptime = it)) }
                    BrixToggleRow(
                        stringResource(R.string.hud_scene),
                        checked = hud.showScene,
                        divider = true,
                    ) { viewModel.updateHud(hud.copy(showScene = it)) }
                    BrixToggleRow(
                        stringResource(R.string.hud_version),
                        checked = hud.showVersion,
                        divider = false,
                    ) { viewModel.updateHud(hud.copy(showVersion = it)) }
                }
            }
        }
    }
}

@Composable
internal fun ProfileRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.btn_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
fun OverlaySettingsScreen(
    viewModel: SettingsViewModel,
    onEdit: (String?) -> Unit,
    onPlaceOverlay: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(crumb(R.string.settings_stream, R.string.settings_overlays), onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding.verticalOnly())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (settings.overlays.isEmpty()) {
                Text(
                    text = stringResource(R.string.overlay_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            } else {
                BrixCard {
                    settings.overlays.forEachIndexed { index, overlay ->
                        OverlayRow(
                            overlay = overlay,
                            onToggle = { viewModel.saveOverlay(overlay.copy(enabled = it)) },
                            onEdit = { onEdit(overlay.id) },
                            onPlace = { onPlaceOverlay(overlay.id) },
                            onDelete = { pendingDelete = overlay.id },
                        )
                        if (index != settings.overlays.lastIndex) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            FilledTonalButton(
                onClick = { onEdit(null) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_add))
            }
        }
    }
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.dialog_delete_overlay_title)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete?.let { viewModel.deleteOverlay(it) }
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.btn_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}

@Composable
private fun OverlayRow(
    overlay: OverlayConfig,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onPlace: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrixToggle(checked = overlay.enabled, onCheckedChange = onToggle)
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEdit),
        ) {
            Text(
                text = overlay.url,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (overlay.enabled) {
                    stringResource(R.string.settings_enabled)
                } else {
                    stringResource(R.string.settings_disabled)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onPlace) {
            Icon(
                imageVector = Icons.Filled.OpenWith,
                contentDescription = stringResource(R.string.overlay_change_position),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.btn_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
fun OverlayEditScreen(
    viewModel: SettingsViewModel,
    overlayId: String?,
    onNext: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val overlay = overlayId?.let { id -> settings.overlays.firstOrNull { it.id == id } }

    var url by rememberSaveable { mutableStateOf(overlay?.url ?: "") }
    var audioOnDevice by rememberSaveable { mutableStateOf(overlay?.audioOnDevice ?: false) }
    var audioInStream by rememberSaveable { mutableStateOf(overlay?.audioInStream ?: false) }

    val base = overlay ?: newOverlay(url)
    val draft = base.copy(
        url = url,
        audioOnDevice = audioOnDevice,
        audioInStream = audioInStream,
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsEditTopBar(
                title = if (overlayId == null) {
                    stringResource(R.string.overlay_add_title)
                } else {
                    stringResource(R.string.settings_overlay)
                },
                onCancel = onBack,
                onSave = {
                    viewModel.saveOverlay(draft)
                    if (overlayId == null) onNext(draft.id) else onBack()
                },
                saveLabel = stringResource(if (overlayId == null) R.string.btn_next else R.string.btn_save),
                saveEnabled = url.isNotBlank(),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding.verticalOnly())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BrixCard {
                Text(
                    text = stringResource(R.string.overlay_section),
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.field_overlay_url)) },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.overlay_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BrixCard {
                Text(
                    text = stringResource(R.string.overlay_audio_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                BrixToggleRow(
                    title = stringResource(R.string.overlay_audio_device),
                    checked = audioOnDevice,
                    divider = true,
                ) {
                    audioOnDevice = it
                }
                BrixToggleRow(
                    title = stringResource(R.string.overlay_audio_stream),
                    checked = audioInStream,
                    divider = false,
                ) {
                    audioInStream = it
                }
            }
        }
    }
}

@Composable
fun BrowserWidgetSettingsScreen(
    viewModel: SettingsViewModel,
    onEdit: (String?) -> Unit,
    onPlaceWidget: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(crumb(R.string.settings_stream, R.string.settings_browser_widgets), onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding.verticalOnly())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (settings.browserWidgets.isEmpty()) {
                Text(
                    text = stringResource(R.string.browser_widget_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            } else {
                BrixCard {
                    settings.browserWidgets.forEachIndexed { index, widget ->
                        BrowserWidgetRow(
                            widget = widget,
                            onToggle = { viewModel.saveBrowserWidget(widget.copy(enabled = it)) },
                            onEdit = { onEdit(widget.id) },
                            onPlace = { onPlaceWidget(widget.id) },
                            onDelete = { pendingDelete = widget.id },
                        )
                        if (index != settings.browserWidgets.lastIndex) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            FilledTonalButton(
                onClick = { onEdit(null) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_add))
            }
        }
    }
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.dialog_delete_browser_widget_title)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete?.let { viewModel.deleteBrowserWidget(it) }
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.btn_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}

@Composable
private fun BrowserWidgetRow(
    widget: BrowserWidgetConfig,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onPlace: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrixToggle(checked = widget.enabled, onCheckedChange = onToggle)
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEdit),
        ) {
            Text(
                text = widget.url,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (widget.enabled) {
                    stringResource(R.string.settings_enabled)
                } else {
                    stringResource(R.string.settings_disabled)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onPlace) {
            Icon(
                imageVector = Icons.Filled.OpenWith,
                contentDescription = stringResource(R.string.overlay_change_position),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.btn_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
fun BrowserWidgetEditScreen(
    viewModel: SettingsViewModel,
    widgetId: String?,
    onNext: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val widget = widgetId?.let { id -> settings.browserWidgets.firstOrNull { it.id == id } }

    var url by rememberSaveable { mutableStateOf(widget?.url ?: "") }
    var refresh by rememberSaveable { mutableStateOf((widget?.refreshMs ?: 200L).toString()) }

    val base = widget ?: newBrowserWidget(url)
    val draft = base.copy(
        url = url,
        // Поле работало (StreamScreen.kt: delay(widget.refreshMs)), но задать
        // его было негде. Нижняя граница нужна, чтобы нельзя было случайно
        // попросить снимок WebView каждые пару миллисекунд: это покадровая
        // работа в GL-тракте, а не бесплатное число в настройке.
        refreshMs = refresh.toLongOrNull()?.coerceIn(50L, 5_000L) ?: base.refreshMs,
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsEditTopBar(
                title = if (widgetId == null) {
                    stringResource(R.string.browser_widget_add_title)
                } else {
                    stringResource(R.string.settings_browser_widgets)
                },
                onCancel = onBack,
                onSave = {
                    viewModel.saveBrowserWidget(draft)
                    if (widgetId == null) onNext(draft.id) else onBack()
                },
                saveLabel = stringResource(if (widgetId == null) R.string.btn_next else R.string.btn_save),
                saveEnabled = url.isNotBlank(),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding.verticalOnly())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BrixCard {
                Text(
                    text = stringResource(R.string.browser_widget_section),
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.field_browser_widget_url)) },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = refresh,
                    onValueChange = { refresh = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.field_widget_refresh)) },
                    supportingText = { Text(stringResource(R.string.field_widget_refresh_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.browser_widget_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun micSourceLabel(source: MicSource): String = stringResource(
    when (source) {
        MicSource.AUTO -> R.string.mic_auto
        MicSource.BUILTIN -> R.string.mic_builtin
        MicSource.BUILTIN_BOTTOM -> R.string.mic_bottom
        MicSource.BUILTIN_BACK -> R.string.mic_back
        MicSource.BUILTIN_TOP -> R.string.mic_top
        MicSource.WIRED -> R.string.mic_wired
        MicSource.BLUETOOTH -> R.string.mic_bluetooth
        MicSource.USB -> R.string.mic_usb
    },
)

/**
 * Какое именно устройство выбранного типа.
 *
 * Показывается только когда устройств этого типа больше одного — две
 * Bluetooth-гарнитуры, два USB-входа. Имя берётся из `productName`, потому что
 * `address` у Bluetooth это MAC: его нельзя ни показывать, ни хранить.
 */
@Composable
private fun MicDevicePickerDialog(
    names: List<String>,
    selected: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mic_device_pick_title)) },
        text = {
            Column {
                names.forEach { name ->
                    BrixNavRow(
                        title = name,
                        value = if (name == selected) "✓" else null,
                        divider = name != names.last(),
                        onClick = { onPick(name) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
    )
}

/** Заголовок смысловой группы внутри экрана настроек звука. */
@Composable
private fun AudioGroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}
