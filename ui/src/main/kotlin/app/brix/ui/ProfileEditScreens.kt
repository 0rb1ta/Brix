package app.brix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.brix.core.AbrAlgorithm
import app.brix.core.Codec
import app.brix.core.Ids
import app.brix.core.ServerProfile
import app.brix.core.ServerType
import app.brix.core.StreamProfile
import app.brix.core.defaultStreamPresets
import app.brix.streaming.EncoderCapabilities

@Composable
fun StreamProfileEditScreen(
    viewModel: SettingsViewModel,
    profileId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val profile = profileId?.let { id -> settings.streamProfiles.firstOrNull { it.id == id } }

    var name by rememberSaveable { mutableStateOf(profile?.name ?: "") }
    var codec by rememberSaveable { mutableStateOf(profile?.video?.codec ?: Codec.H264) }
    var width by rememberSaveable { mutableStateOf((profile?.video?.width ?: 1280).toString()) }
    var height by rememberSaveable { mutableStateOf((profile?.video?.height ?: 720).toString()) }
    var fps by rememberSaveable { mutableStateOf((profile?.video?.fps ?: 30).toString()) }
    var bitrate by rememberSaveable { mutableStateOf((profile?.video?.bitrateKbps ?: 2500).toString()) }
    var abrEnabled by rememberSaveable { mutableStateOf(profile?.adaptiveBitrate?.enabled ?: false) }
    var abrTarget by rememberSaveable { mutableStateOf((profile?.adaptiveBitrate?.targetBitrateKbps ?: 6000).toString()) }
    var abrMin by rememberSaveable { mutableStateOf((profile?.adaptiveBitrate?.minimumBitrateKbps ?: 250).toString()) }
    var abrInit by rememberSaveable { mutableStateOf((profile?.adaptiveBitrate?.initialBitrateKbps ?: 2500).toString()) }
    var abrAlgorithm by rememberSaveable { mutableStateOf(profile?.adaptiveBitrate?.algorithm ?: AbrAlgorithm.BELABOX) }
    var keyframe by rememberSaveable { mutableStateOf((profile?.video?.keyframeIntervalSec ?: 2).toString()) }

    val base = profile ?: newStreamProfile(name)
    val draft = base.copy(
        name = name.ifBlank { "Unnamed" },
        // Пустое или кривое поле оставляет ПРЕЖНЕЕ значение профиля, а не
        // подставляет захардкоженную константу. Раньше стёртая ширина у профиля
        // 1080p молча превращалась в 1280: человек правил одно поле, а уезжало
        // соседнее, и заметить это можно было только по картинке в эфире.
        video = base.video.copy(
            codec = codec,
            width = width.toIntOrNull() ?: base.video.width,
            height = height.toIntOrNull() ?: base.video.height,
            fps = fps.toIntOrNull() ?: base.video.fps,
            bitrateKbps = bitrate.toIntOrNull() ?: base.video.bitrateKbps,
            keyframeIntervalSec = keyframe.toIntOrNull()?.coerceIn(1, 10) ?: base.video.keyframeIntervalSec,
        ),
        adaptiveBitrate = base.adaptiveBitrate.copy(
            enabled = abrEnabled,
            targetBitrateKbps = abrTarget.toIntOrNull() ?: base.adaptiveBitrate.targetBitrateKbps,
            minimumBitrateKbps = abrMin.toIntOrNull() ?: base.adaptiveBitrate.minimumBitrateKbps,
            initialBitrateKbps = abrInit.toIntOrNull() ?: base.adaptiveBitrate.initialBitrateKbps,
            algorithm = abrAlgorithm,
        ),
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsEditTopBar(
                title = if (profileId == null) stringResource(R.string.settings_new_stream) else name,
                onCancel = onBack,
                onSave = {
                    viewModel.saveStreamProfile(draft)
                    onBack()
                },
                saveLabel = stringResource(R.string.btn_save),
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
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                BrixSegmentRow(
                    title = stringResource(R.string.field_codec),
                    options = listOf(
                        Codec.H264 to "H.264",
                        Codec.HEVC to "H.265/HEVC",
                    ),
                    selected = codec,
                    divider = false,
                ) { codec = it }
            }

            BrixCard {
                Text(
                    text = stringResource(R.string.profile_presets),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    (defaultStreamPresets + settings.customPresets).forEach { preset ->
                        val isCustom = settings.customPresets.any { it.name == preset.name }
                        val selected = width == preset.width.toString() &&
                            height == preset.height.toString() &&
                            fps == preset.fps.toString() &&
                            bitrate == preset.videoBitrateKbps.toString()
                        FilterChip(
                            selected = selected,
                            onClick = {
                                width = preset.width.toString()
                                height = preset.height.toString()
                                fps = preset.fps.toString()
                                bitrate = preset.videoBitrateKbps.toString()
                                // Звук с 03.09 общий, а не в профиле: пресет
                                // пишет его битрейт в общие настройки.
                                viewModel.updateAudio(
                                    settings.audio.copy(bitrateKbps = preset.audioBitrateKbps),
                                )
                            },
                            label = { Text(preset.name) },
                            trailingIcon = if (isCustom) {
                                {
                                    IconButton(
                                        onClick = { viewModel.deleteCustomPreset(preset.name) },
                                        modifier = Modifier.size(18.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.btn_delete),
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                var showSavePresetDialog by remember { mutableStateOf(false) }
                TextButton(onClick = { showSavePresetDialog = true }) {
                    Text(stringResource(R.string.preset_save_as))
                }
                if (showSavePresetDialog) {
                    var presetName by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { showSavePresetDialog = false },
                        title = { Text(stringResource(R.string.preset_save_title)) },
                        text = {
                            OutlinedTextField(
                                value = presetName,
                                onValueChange = { presetName = it },
                                label = { Text(stringResource(R.string.field_name)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        confirmButton = {
                            TextButton(
                                enabled = presetName.isNotBlank(),
                                onClick = {
                                    viewModel.saveCustomPreset(
                                        app.brix.core.StreamPreset(
                                            name = presetName,
                                            width = width.toIntOrNull() ?: 1280,
                                            height = height.toIntOrNull() ?: 720,
                                            fps = fps.toIntOrNull() ?: 30,
                                            videoBitrateKbps = bitrate.toIntOrNull() ?: 2500,
                                            audioBitrateKbps = settings.audio.bitrateKbps,
                                        ),
                                    )
                                    showSavePresetDialog = false
                                },
                            ) {
                                Text(stringResource(R.string.btn_save))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showSavePresetDialog = false }) {
                                Text(stringResource(R.string.btn_cancel))
                            }
                        },
                    )
                }
            }

            BrixCard {
                Row {
                    OutlinedTextField(
                        value = width,
                        onValueChange = { width = it },
                        label = { Text(stringResource(R.string.field_width)) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = height,
                        onValueChange = { height = it },
                        label = { Text(stringResource(R.string.field_height)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    OutlinedTextField(
                        value = fps,
                        onValueChange = { fps = it },
                        label = { Text(stringResource(R.string.field_fps)) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = bitrate,
                        onValueChange = { bitrate = it },
                        label = { Text(stringResource(R.string.field_video_bitrate)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Правду про режим битрейта больше нигде не видно: RootEncoder
                // просит CBR, при отказе молча берёт VBR и пишет об этом только
                // в системный лог. Выбрать режим нельзя — сеттера у энкодера
                // нет, — поэтому здесь только честное сообщение.
                Spacer(Modifier.height(4.dp))
                val cbr = remember(codec) { EncoderCapabilities.isCbrSupported(codec) }
                Text(
                    text = stringResource(
                        if (cbr) R.string.field_bitrate_mode_cbr else R.string.field_bitrate_mode_vbr,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (cbr) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
                // Поле было в модели с самого начала и не читалось нигде.
                OutlinedTextField(
                    value = keyframe,
                    onValueChange = { keyframe = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.field_keyframe)) },
                    supportingText = { Text(stringResource(R.string.field_keyframe_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                BrixToggleRow(
                    title = stringResource(R.string.adaptive_bitrate),
                    checked = abrEnabled,
                    divider = false,
                ) { abrEnabled = it }
                if (abrEnabled) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = abrTarget,
                        onValueChange = { abrTarget = it },
                        label = { Text(stringResource(R.string.field_abr_target)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        OutlinedTextField(
                            value = abrMin,
                            onValueChange = { abrMin = it },
                            label = { Text(stringResource(R.string.field_abr_min)) },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        OutlinedTextField(
                            value = abrInit,
                            onValueChange = { abrInit = it },
                            label = { Text(stringResource(R.string.field_abr_init)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    // Алгоритм жил в модели и доходил до регулятора
                    // (SrtlaStream -> AdaptiveBitrate.setAlgorithm), но выбрать
                    // его было негде — ровно как с задержкой SRT.
                    BrixSegmentRow(
                        title = stringResource(R.string.field_abr_algorithm),
                        options = listOf(
                            AbrAlgorithm.BELABOX to "Belabox",
                            AbrAlgorithm.FAST_IRL to "Fast",
                            AbrAlgorithm.SLOW_IRL to "Slow",
                        ),
                        selected = abrAlgorithm,
                        divider = false,
                    ) { abrAlgorithm = it }
                }
            }
        }
    }
}

@Composable
fun ServerProfileEditScreen(
    viewModel: SettingsViewModel,
    profileId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val server = profileId?.let { id -> settings.serverProfiles.firstOrNull { it.id == id } }

    var name by rememberSaveable { mutableStateOf(server?.name ?: "") }
    var baseUrl by rememberSaveable { mutableStateOf(server?.baseUrl ?: "") }
    var streamKey by rememberSaveable { mutableStateOf(server?.streamId ?: "") }
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    var type by rememberSaveable { mutableStateOf(server?.type ?: ServerType.SRTLA) }
    var enabled by rememberSaveable { mutableStateOf(server?.enabled ?: true) }
    var latency by rememberSaveable { mutableStateOf((server?.latencyMs ?: 2000).toString()) }
    var preferIpv4 by rememberSaveable { mutableStateOf(server?.preferIpv4 ?: false) }
    // Удаление сервера необратимо и стоит рядом с кнопкой сохранения: спрашиваем,
    // как это уже делает список серверов.
    var confirmDelete by remember { mutableStateOf(false) }

    val base = server ?: ServerProfile(
        id = Ids.newId(),
        name = name,
        type = type,
        baseUrl = baseUrl,
    )
    val draft = base.copy(
        name = name.ifBlank { "Unnamed" },
        baseUrl = baseUrl,
        type = type,
        enabled = enabled,
        // Пустое или кривое поле оставляет прежнее значение, а не константу —
        // как и в редакторе стрим-профилей.
        latencyMs = latency.toIntOrNull() ?: base.latencyMs,
        preferIpv4 = preferIpv4,
        // Ключ хранится отдельно от адреса и приклеивается при подключении
        // (ServerProfile.connectUrl). Для не-RTMP поле не показывается, но
        // значение не стираем: смена типа туда-сюда не должна его терять.
        streamId = streamKey.trim(),
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsEditTopBar(
                title = if (profileId == null) stringResource(R.string.settings_new_server) else name,
                onCancel = onBack,
                onSave = {
                    viewModel.saveServerProfile(draft)
                    onBack()
                },
                saveLabel = stringResource(R.string.btn_save),
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
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                BrixSegmentRow(
                    title = stringResource(R.string.field_type),
                    options = listOf(
                        ServerType.SRTLA to "SRT(LA)",
                        ServerType.RTMP to "RTMP",
                        ServerType.WHIP to "WHIP",
                    ),
                    selected = type,
                    divider = true,
                ) { type = it }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.field_url)) },
                    // Схема адреса выбирает режим, а не отдельный тип сервера:
                    // так же устроено в Moblin, откуда приходит часть людей.
                    supportingText = if (type == ServerType.SRTLA) {
                        { Text(stringResource(R.string.field_url_hint_srtla)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // И у RTMP, и у SRTLA секрет доступа к каналу лежит в адресе:
                // у первого последним сегментом пути, у второго внутри
                // параметра streamid вместе с srtauth. Поле одно, подпись
                // разная — площадки называют это по-разному.
                if (type != ServerType.WHIP) {
                    val isRtmp = type == ServerType.RTMP
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = streamKey,
                        onValueChange = { streamKey = it },
                        label = {
                            Text(
                                stringResource(
                                    if (isRtmp) R.string.field_stream_key else R.string.field_stream_id,
                                ),
                            )
                        },
                        supportingText = {
                            Text(
                                stringResource(
                                    if (isRtmp) R.string.field_stream_key_hint else R.string.field_stream_id_hint,
                                ),
                            )
                        },
                        // Точки по умолчанию: ключ трансляции — это доступ к
                        // каналу, а настройки стример открывает и в эфире тоже.
                        visualTransformation = if (keyVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(
                                    imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = stringResource(R.string.field_stream_key_show),
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (type == ServerType.SRTLA) {
                    Spacer(Modifier.height(12.dp))
                    BrixToggleRow(
                        title = stringResource(R.string.field_prefer_ipv4),
                        subtitle = stringResource(R.string.field_prefer_ipv4_hint),
                        checked = preferIpv4,
                        divider = true,
                    ) { preferIpv4 = it }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = latency,
                        onValueChange = { latency = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.field_srt_latency)) },
                        supportingText = {
                            // Moblin предупреждает ровно так же при значении ниже
                            // 1000 для своей реализации SRT. У нас реализация тоже
                            // своя, и смысл тот же: при короткой задержке окно на
                            // переотправку потерянного пакета почти нулевое, то
                            // есть SRT перестаёт делать то, ради чего его берут.
                            val low = latency.toIntOrNull()?.let { it < 1000 } ?: false
                            Text(
                                text = stringResource(
                                    if (low) R.string.field_srt_latency_low else R.string.field_srt_latency_hint,
                                ),
                                color = if (low) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Тумблера «Включено» здесь больше нет: активный сервер один и
                // выбирается радиокнопкой в списке. Иначе через редактор можно
                // было включить второй и вернуть ту самую путаницу, где вещает
                // первый по порядку, а галочки стоят у двоих.
            }
            if (profileId != null) {
                Button(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.btn_delete))
                }
            }
        }
    }

    if (confirmDelete && profileId != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.dialog_delete_server_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteServerProfile(profileId)
                    onBack()
                }) {
                    Text(stringResource(R.string.btn_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}
