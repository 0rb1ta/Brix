package app.brix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import app.brix.core.SceneAudio
import app.brix.core.SceneSource
import app.brix.core.CameraSide
import app.brix.core.Ids
import app.brix.core.Scene

/**
 * Сцены: наборы того, что видит зритель, с переключением одним нажатием.
 *
 * Модель `Scene` лежала в коде с самого начала и не читалась никем — сцены
 * существовали в файле настроек и нигде больше. Здесь они наконец получают
 * управление.
 *
 * Сцена держит сторону камеры и список оверлеев с виджетами. Пока ни одной
 * сцены нет, приложение работает как раньше: показывает всё включённое. Это
 * важно — заведение сцен не должно означать «теперь настраивай заново».
 */
@Composable
fun ScenesScreen(
    viewModel: SettingsViewModel,
    onEdit: (String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // Настоящий подэкран (открывается из Стрим), а не экран категории —
        // раньше вызывался вовсе без onBack, в отличие от соседей
        // (ServerProfiles/StreamProfiles рядом), поэтому кнопки назад тут не
        // было вообще. Хлебная крошка — тем же приёмом, что у соседей.
        topBar = {
            SettingsTopBar(
                "${stringResource(R.string.settings_stream)} · ${stringResource(R.string.settings_scenes)}",
                onBack,
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner.verticalOnly())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.scenes_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (settings.scenes.isEmpty()) {
                Text(
                    text = stringResource(R.string.scenes_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                BrixCard {
                    settings.scenes.forEachIndexed { index, scene ->
                        ProfileRow(
                            title = scene.name.ifBlank { stringResource(R.string.scenes_unnamed) },
                            subtitle = sceneSummary(scene),
                            selected = settings.selectedSceneId == scene.id,
                            onSelect = { viewModel.selectScene(scene.id) },
                            onClick = { onEdit(scene.id) },
                            onDelete = { pendingDelete = scene.id },
                        )
                        if (index != settings.scenes.lastIndex) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            FilledTonalButton(
                onClick = { onEdit(null) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.scenes_add))
            }
        }
    }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.scenes_delete_title)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete?.let { viewModel.deleteScene(it) }
                    pendingDelete = null
                }) { Text(stringResource(R.string.btn_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}

/**
 * Редактор сцены — ОТДЕЛЬНЫЙ ЭКРАН, как у профиля стрима.
 *
 * До этого он был диалогом, и это было ошибкой: настроек здесь столько же,
 * сколько в профиле, а диалог их не вмещает. Попытки растянуть его ломали
 * прокрутку, которую AlertDialog делает сам. Экран этой проблемы не имеет и
 * заодно выглядит как остальное приложение — та же шапка «Отмена / название /
 * Сохранить», те же карточки.
 */
@Composable
fun SceneEditScreen(
    viewModel: SettingsViewModel,
    sceneId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val existing = sceneId?.let { id -> settings.scenes.firstOrNull { it.id == id } }

    var name by rememberSaveable(sceneId) { mutableStateOf(existing?.name ?: "") }
    var camera by rememberSaveable(sceneId) { mutableStateOf(existing?.camera ?: CameraSide.BACK) }
    var source by rememberSaveable(sceneId) { mutableStateOf(existing?.source ?: SceneSource.CAMERA) }
    var sceneAudio by rememberSaveable(sceneId) { mutableStateOf(existing?.audio ?: SceneAudio.MIC) }
    var imageUri by rememberSaveable(sceneId) { mutableStateOf(existing?.imageUri ?: "") }
    var overlayIds by rememberSaveable(sceneId) { mutableStateOf(existing?.overlayIds?.toSet() ?: emptySet()) }
    var widgetIds by rememberSaveable(sceneId) {
        mutableStateOf(existing?.browserWidgetIds?.toSet() ?: emptySet())
    }

    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // Постоянное разрешение: без него ссылка протухнет после
            // перезапуска приложения, и заставка молча превратится в камеру.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            imageUri = uri.toString()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsEditTopBar(
                title = if (sceneId == null) stringResource(R.string.scenes_new_title) else name,
                onCancel = onBack,
                onSave = {
                    viewModel.saveScene(
                        (existing ?: Scene(id = Ids.newId(), name = name)).copy(
                            name = name.trim(),
                            source = source,
                            audio = sceneAudio,
                            imageUri = imageUri,
                            camera = camera,
                            overlayIds = overlayIds.toList(),
                            browserWidgetIds = widgetIds.toList(),
                        ),
                    )
                    onBack()
                },
                saveLabel = stringResource(R.string.btn_save),
                saveEnabled = name.isNotBlank(),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner.verticalOnly())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BrixCard {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name)) },
                    placeholder = { Text(stringResource(R.string.scenes_name_example)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                BrixSegmentRow(
                    title = stringResource(R.string.scenes_source),
                    options = listOf(
                        SceneSource.CAMERA to stringResource(R.string.scenes_source_camera),
                        SceneSource.IMAGE to stringResource(R.string.scenes_source_image),
                        SceneSource.SCREEN to stringResource(R.string.scenes_source_screen),
                    ),
                    selected = source,
                    divider = source != SceneSource.CAMERA,
                ) { source = it }
                if (source == SceneSource.CAMERA) {
                    Spacer(Modifier.height(12.dp))
                    BrixSegmentRow(
                        title = stringResource(R.string.scenes_camera),
                        options = listOf(
                            CameraSide.BACK to stringResource(R.string.btn_rear),
                            CameraSide.FRONT to stringResource(R.string.btn_front),
                        ),
                        selected = camera,
                        divider = false,
                    ) { camera = it }
                }
                if (source == SceneSource.IMAGE) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (imageUri.isBlank()) {
                            stringResource(R.string.scenes_image_none)
                        } else {
                            stringResource(R.string.scenes_image_chosen)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = { picker.launch(arrayOf("image/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.scenes_image_pick)) }
                    Spacer(Modifier.height(8.dp))
                }
                if (source == SceneSource.SCREEN) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.scenes_screen_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            BrixCard {
                BrixSegmentRow(
                    title = stringResource(R.string.scenes_audio),
                    options = listOf(
                        SceneAudio.MIC to stringResource(R.string.scenes_audio_mic),
                        SceneAudio.INTERNAL to stringResource(R.string.scenes_audio_internal),
                        SceneAudio.BOTH to stringResource(R.string.scenes_audio_both),
                    ),
                    selected = sceneAudio,
                    divider = false,
                ) { sceneAudio = it }
                if (sceneAudio != SceneAudio.MIC) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.scenes_audio_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            val hasLayers = settings.overlays.isNotEmpty() || settings.browserWidgets.isNotEmpty()
            BrixCard {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        if (hasLayers) R.string.scenes_layers_hint else R.string.scenes_no_layers,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                settings.overlays.forEach { overlay ->
                    BrixToggleRow(
                        title = shortUrl(overlay.url),
                        subtitle = stringResource(R.string.settings_overlay),
                        checked = overlay.id in overlayIds,
                        divider = true,
                    ) { on -> overlayIds = if (on) overlayIds + overlay.id else overlayIds - overlay.id }
                }
                settings.browserWidgets.forEach { widget ->
                    BrixToggleRow(
                        title = shortUrl(widget.url),
                        subtitle = stringResource(R.string.browser_widget_section),
                        checked = widget.id in widgetIds,
                        divider = true,
                    ) { on -> widgetIds = if (on) widgetIds + widget.id else widgetIds - widget.id }
                }
            }
        }
    }
}

/**
 * Хост вместо полного адреса.
 *
 * Ссылки на виджеты и оверлеи длинные, часто с ключом внутри. В списке галочек
 * от полного адреса пользы нет — он не помещается и рвёт строку, — а хост
 * отличает один слой от другого.
 */
private fun shortUrl(url: String): String = runCatching {
    java.net.URI(url).host ?: url
}.getOrNull() ?: url

/** Что в сцене: источник и сколько слоёв поверх. */
@Composable
private fun sceneSummary(scene: Scene): String {
    val what = stringResource(
        when (scene.source) {
            SceneSource.IMAGE -> R.string.scenes_source_image
            SceneSource.SCREEN -> R.string.scenes_source_screen
            SceneSource.CAMERA ->
                if (scene.camera == CameraSide.FRONT) R.string.btn_front else R.string.btn_rear
        },
    )
    val layers = scene.overlayIds.size + scene.browserWidgetIds.size
    return if (layers == 0) what else stringResource(R.string.scenes_summary, what, layers)
}
