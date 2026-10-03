package app.brix.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.brix.core.Ids
import app.brix.core.ServerProfile
import app.brix.core.ServerType
import app.brix.core.SettingsDeepLink
import app.brix.core.SharedConfig
import app.brix.core.StreamPreset
import app.brix.core.defaultStreamPresets
import app.brix.core.normalizeLanguageTag

private enum class OnboardingStep { WELCOME, LANGUAGE, PERMISSIONS, SERVER, QUALITY, DONE }

// Свой список, а не общий из StreamScreen: там он приватный и разделён на
// «обязательные» и «уведомления» ради ворот эфира. Здесь просим всё разом —
// человек только что поставил приложение и ждёт этих вопросов.
private val ONBOARDING_PERMISSIONS: Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

// Пояснение к каждому пресету — по индексу в defaultStreamPresets. Список
// пресетов живёт в core и меняется редко; если он разойдётся с этим, у лишних
// пресетов просто не будет пояснения.
private val PRESET_NOTES = listOf(
    R.string.onboarding_preset_720p30,
    R.string.onboarding_preset_720p60,
    R.string.onboarding_preset_1080p30,
    R.string.onboarding_preset_1080p60,
    R.string.onboarding_preset_4k30,
)

/**
 * Мастер первого запуска (roadmap п. 9). Портретный — окно поворачивает
 * MainActivity, а экран эфира с камерой в это время не создан вовсе.
 *
 * Черновик держится в rememberSaveable и пишется в настройки одним вызовом в
 * конце ([SettingsViewModel.completeOnboarding]). Смена языка на втором шаге
 * пересоздаёт Activity — шаг и введённое переживают это как поворот.
 *
 * Маскота и названия здесь нет намеренно: владелец (16.09) счёл их лишними —
 * заставка только что показала и то и другое.
 */
@Composable
fun OnboardingScreen(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current

    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val step = OnboardingStep.entries[stepIndex]

    var serverType by rememberSaveable { mutableStateOf(ServerType.SRTLA) }
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var serverKey by rememberSaveable { mutableStateOf("") }
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    // По умолчанию — то, что уже стоит в профиле, чтобы «Далее» без выбора
    // ничего не меняло.
    val currentVideo = settings.selectedStreamProfile()?.video
    var presetIndex by rememberSaveable {
        mutableIntStateOf(
            defaultStreamPresets.indexOfFirst {
                it.width == currentVideo?.width && it.height == currentVideo.height && it.fps == currentVideo.fps
            }.coerceAtLeast(0),
        )
    }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    var permissionTick by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionTick++ }

    val serverFilled = serverUrl.isNotBlank()
    // Разобранная ссылка ждёт подтверждения — как в «Импорт/экспорт»: молча
    // чужие серверы не применяем.
    var pendingImport by remember { mutableStateOf<SharedConfig?>(null) }

    fun finish() {
        val server = if (serverFilled) {
            ServerProfile(
                id = Ids.newId(),
                name = serverUrl.substringAfter("://").substringBefore('/').substringBefore('?')
                    .substringBefore(':').ifBlank { "Server" },
                type = serverType,
                baseUrl = serverUrl.trim(),
                streamId = if (serverType == ServerType.WHIP) "" else serverKey.trim(),
            )
        } else {
            null
        }
        viewModel.completeOnboarding(server, defaultStreamPresets[presetIndex])
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        // Шапка: номер шага и «пропустить всё». Пропуск тоже ставит признак
        // «пройден» — иначе мастер встречал бы человека при каждом запуске.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
        ) {
            Text(
                stringResource(R.string.onboarding_step, stepIndex + 1, OnboardingStep.entries.size),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (step != OnboardingStep.DONE) {
                TextButton(onClick = { viewModel.completeOnboarding(null, null) }) {
                    Text(stringResource(R.string.onboarding_skip_all), maxLines = 1)
                }
            }
        }
        StepProgress(current = stepIndex, total = OnboardingStep.entries.size)
        Spacer(Modifier.height(20.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step) {
                OnboardingStep.WELCOME -> {
                    StepTitle(stringResource(R.string.onboarding_welcome_title))
                    StepText(stringResource(R.string.onboarding_welcome_p1))
                    StepText(stringResource(R.string.onboarding_welcome_p2))
                    StepText(stringResource(R.string.onboarding_welcome_p3))
                    StepText(stringResource(R.string.onboarding_welcome_p4))
                }

                OnboardingStep.LANGUAGE -> {
                    StepTitle(stringResource(R.string.appearance_language))
                    LanguageChoice(viewModel)
                }

                OnboardingStep.PERMISSIONS -> {
                    StepTitle(stringResource(R.string.onboarding_permissions_title))
                    StepText(stringResource(R.string.onboarding_permissions_text))
                    // Тик заставляет перечитать статусы после ответа системы.
                    val statuses = remember(permissionTick) { ONBOARDING_PERMISSIONS.associateWith(::granted) }
                    BrixCard {
                        statuses.forEach { (perm, ok) -> PermissionRow(perm, ok) }
                    }
                    if (!statuses.values.all { it }) {
                        Button(
                            onClick = { permissionLauncher.launch(ONBOARDING_PERMISSIONS) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.onboarding_permissions_grant)) }
                    }
                }

                OnboardingStep.SERVER -> {
                    StepTitle(stringResource(R.string.onboarding_server_title))
                    StepText(stringResource(R.string.onboarding_server_text))
                    BrixCard {
                        BrixSegmentRow(
                            title = stringResource(R.string.field_type),
                            options = listOf(
                                ServerType.SRTLA to "SRT(LA)",
                                ServerType.RTMP to "RTMP",
                                ServerType.WHIP to "WHIP",
                            ),
                            selected = serverType,
                            divider = false,
                        ) { serverType = it }
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            label = { Text(stringResource(R.string.field_url)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // Подсказка отдельным текстом, а не supportingText поля:
                        // у поля свои внутренние отступы, и подсказка выходила
                        // заметно уже остального текста на экране.
                        if (serverType == ServerType.SRTLA) {
                            FieldHint(stringResource(R.string.field_url_hint_srtla))
                        }
                        if (serverType != ServerType.WHIP) {
                            val isRtmp = serverType == ServerType.RTMP
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = serverKey,
                                onValueChange = { serverKey = it },
                                label = {
                                    Text(
                                        stringResource(
                                            if (isRtmp) R.string.field_stream_key else R.string.onboarding_stream_id,
                                        ),
                                    )
                                },
                                // Как в редакторе сервера: ключ — доступ к каналу.
                                visualTransformation = if (keyVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(onClick = { keyVisible = !keyVisible }) {
                                        Icon(
                                            if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = stringResource(R.string.field_stream_key_show),
                                        )
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            FieldHint(
                                stringResource(
                                    if (isRtmp) R.string.field_stream_key_hint else R.string.onboarding_stream_id_hint,
                                ),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    ImportCard(onImported = { pendingImport = it })
                }

                OnboardingStep.QUALITY -> {
                    StepTitle(stringResource(R.string.onboarding_quality_title))
                    StepText(stringResource(R.string.onboarding_quality_text))
                    val caps = rememberDeviceCapabilities()
                    defaultStreamPresets.forEachIndexed { i, preset ->
                        if (!caps.supports(app.brix.core.Codec.H264, app.brix.streaming.VideoMode(preset.width, preset.height, preset.fps))) {
                            return@forEachIndexed
                        }
                        PresetTile(
                            preset = preset,
                            note = PRESET_NOTES.getOrNull(i)?.let { stringResource(it) },
                            selected = i == presetIndex,
                            onClick = { presetIndex = i },
                        )
                    }
                }

                OnboardingStep.DONE -> {
                    StepTitle(stringResource(R.string.onboarding_done_title))
                    StepText(stringResource(R.string.onboarding_done_text))
                    StepText(
                        stringResource(
                            if (serverFilled) R.string.onboarding_done_server else R.string.onboarding_done_no_server,
                        ),
                    )
                    StepText(stringResource(R.string.onboarding_done_scenes))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        // Подписи в одну строку: «Пропустить шаг» в две строки делал кнопку
        // выше, и «Назад» рядом подпрыгивал.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            if (stepIndex > 0) {
                OutlinedButton(onClick = { stepIndex-- }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.btn_back), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Button(
                onClick = { if (step == OnboardingStep.DONE) finish() else stepIndex++ },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(
                        when {
                            step == OnboardingStep.DONE -> R.string.onboarding_start
                            step == OnboardingStep.SERVER && !serverFilled -> R.string.onboarding_skip_step
                            else -> R.string.btn_next
                        },
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    pendingImport?.let { config ->
        BrixDialog(
            title = stringResource(R.string.deep_link_import_title),
            onDismiss = { pendingImport = null },
            dismissLabel = stringResource(R.string.btn_cancel),
            confirmLabel = stringResource(R.string.btn_import),
            onConfirm = {
                pendingImport = null
                // Привезённое уже содержит и сервер, и качество — дальнейшие
                // шаги их только перетёрли бы, поэтому мастер на этом кончается.
                viewModel.completeOnboardingWithImport(config)
            },
        ) {
            StepText(
                stringResource(
                    R.string.deep_link_import_message,
                    config.streamProfiles.size,
                    config.serverProfiles.size,
                ),
            )
        }
    }
}

@Composable
private fun StepProgress(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        repeat(total) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (i <= current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
            )
        }
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun StepText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FieldHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * Плитка пресета качества. Своя, а не BrixNavRow: у той стрелка «перейти»,
 * а здесь выбор, и её подсветка нажатия обрезалась внутренним отступом
 * карточки. Плитка кликабельна целиком, подсветка доходит до краёв.
 */
@Composable
private fun PresetTile(preset: StreamPreset, note: String?, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) scheme.primary.copy(alpha = 0.12f) else scheme.surfaceContainer,
        border = BorderStroke(
            if (selected) 1.5.dp else 0.5.dp,
            if (selected) scheme.primary else scheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    presetTitle(preset),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) scheme.primary else scheme.onSurface,
                )
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            Text(
                stringResource(R.string.onboarding_bitrate, preset.videoBitrateKbps),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = if (selected) scheme.primary else scheme.onSurfaceVariant,
            )
        }
    }
}

/** «720p@30», «4K@30». Имя пресета из core («720p30 · 2.5M») для людей не
 *  годится: владелец прочитал «2.5M» как метры. */
private fun presetTitle(p: StreamPreset): String {
    val res = if (p.height >= 2160) "4K" else "${p.height}p"
    return "$res@${p.fps}"
}

@Composable
private fun LanguageChoice(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsState()
    val appearance = settings.appearance
    val selectedTag = normalizeLanguageTag(appearance.language)
    val context = LocalContext.current
    val options = remember(context) { listOf("") + supportedAppLanguages(context) }
    BrixCard {
        options.forEachIndexed { i, tag ->
            BrixNavRow(
                title = if (tag.isEmpty()) stringResource(R.string.language_auto) else displayNameForLanguageTag(tag),
                value = if (selectedTag == tag) "✓" else null,
                divider = i != options.lastIndex,
                onClick = { viewModel.updateAppearance(appearance.copy(language = tag)) },
            )
        }
    }
}

@Composable
private fun PermissionRow(permission: String, granted: Boolean) {
    val label = when (permission) {
        Manifest.permission.CAMERA -> R.string.onboarding_perm_camera
        Manifest.permission.RECORD_AUDIO -> R.string.onboarding_perm_mic
        else -> R.string.onboarding_perm_notifications
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
    ) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (granted) "✓" else "—",
            fontFamily = FontFamily.Monospace,
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImportCard(onImported: (SharedConfig) -> Unit) {
    val context = LocalContext.current
    var link by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    BrixCard {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.onboarding_import_title),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            StepText(stringResource(R.string.onboarding_import_text))
            OutlinedTextField(
                value = link,
                onValueChange = {
                    link = it
                    invalid = false
                },
                label = { Text(stringResource(R.string.config_field_link)) },
                placeholder = { Text("brix:// · moblin:// · larix://", maxLines = 1) },
                isError = invalid,
                // Вставка — иконкой в самом поле: отдельной кнопкой рядом с
                // «Импортировать» обе не помещались, и подписи ломались.
                trailingIcon = {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.let {
                            link = it.toString().trim()
                            invalid = false
                        }
                    }) {
                        Icon(
                            Icons.Filled.ContentPaste,
                            contentDescription = stringResource(R.string.btn_paste_clipboard),
                        )
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (invalid) {
                Text(
                    stringResource(R.string.deep_link_import_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            FilledTonalButton(
                onClick = {
                    val trimmed = link.trim()
                    val config = SettingsDeepLink.decodeAny(trimmed)
                    if (config == null) invalid = true else onImported(config)
                },
                enabled = link.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.btn_import), maxLines = 1) }
        }
    }
}
