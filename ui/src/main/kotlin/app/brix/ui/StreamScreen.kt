package app.brix.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.util.Log
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Layers
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.brix.core.AppSettings
import app.brix.core.ButtonAction
import app.brix.core.MicSource
import app.brix.core.QuickButtonConfig
import app.brix.core.ServerProfile
import app.brix.core.ServerType
import app.brix.core.StreamProfile
import app.brix.streaming.LiveStreamer
import app.brix.streaming.MicDevices
import app.brix.streaming.overlay.OverlayController
import app.brix.streaming.overlay.OverlayJsBridge
import app.brix.streaming.RearLens
import app.brix.streaming.StreamController
import app.brix.streaming.StreamService
import app.brix.streaming.rearLenses
import app.brix.streaming.StreamStatus

/** Во сколько раз буфер превью меньше разрешения потока по стороне.
 *  2 — четверть площади заливки. Число одно и здесь, чтобы менять его при
 *  замерах в одном месте; величина выигрыша меряется прибором И4 (§6.7). */
private const val PREVIEW_DOWNSCALE = 2

// Required for streaming. These gate the preview/stream (C1).
private val REQUIRED_PERMISSIONS: Array<String> = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
)

// Optional foreground-service notification. A user may deny it freely without
// blocking streaming (the service still runs; it just won't show a
// notification). It must NOT be part of the `ready` gate (C1).
private val NOTIFICATION_PERMISSIONS: Array<String> =
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray()
    }

private fun hasRequiredPermissions(context: Context): Boolean =
    REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }



/** SettingsRoute is a sealed hierarchy — not Bundle-saveable by default.
 *  Encode as "name[:id]" strings. */
private fun routeSaver(): androidx.compose.runtime.saveable.Saver<SettingsRoute, String> =
    androidx.compose.runtime.saveable.Saver(
        save = { r ->
            when (r) {
                is SettingsRoute.StreamProfileEdit -> "StreamProfileEdit:" + (r.profileId ?: "")
                is SettingsRoute.SceneEdit -> "SceneEdit:" + (r.sceneId ?: "")
                is SettingsRoute.ServerProfileEdit -> "ServerProfileEdit:" + (r.profileId ?: "")
                is SettingsRoute.OverlayEdit -> "OverlayEdit:" + (r.overlayId ?: "")
                is SettingsRoute.BrowserWidgetEdit -> "BrowserWidgetEdit:" + (r.widgetId ?: "")
                else -> r::class.simpleName ?: "Menu"
            }
        },
        restore = { encoded ->
            val name = encoded.substringBefore(':')
            val id = encoded.substringAfter(':', "").ifEmpty { null }
            when (name) {
                "StreamProfiles" -> SettingsRoute.StreamProfiles
                "Scenes" -> SettingsRoute.Scenes
                "SceneEdit" -> SettingsRoute.SceneEdit(id)
                "ServerProfiles" -> SettingsRoute.ServerProfiles
                "ChannelPriorities" -> SettingsRoute.ChannelPriorities
                "QuickButtons" -> SettingsRoute.QuickButtons
                "Camera" -> SettingsRoute.Camera
                "Audio" -> SettingsRoute.Audio
                "Appearance" -> SettingsRoute.Appearance
                "Hud" -> SettingsRoute.Hud
                "Language" -> SettingsRoute.Language
                "Advanced" -> SettingsRoute.Advanced
                "About" -> SettingsRoute.About
                "Diagnostics" -> SettingsRoute.Diagnostics
                "BrowserWidgets" -> SettingsRoute.BrowserWidgets
                "StreamProfileEdit" -> SettingsRoute.StreamProfileEdit(id)
                "ServerProfileEdit" -> SettingsRoute.ServerProfileEdit(id)
                "OverlayEdit" -> SettingsRoute.OverlayEdit(id)
                "BrowserWidgetEdit" -> SettingsRoute.BrowserWidgetEdit(id)
                else -> SettingsRoute.Menu
            }
        },
    )

@Composable
fun StreamScreen(settings: AppSettings, settingsViewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val enabledServers = settings.enabledServers()
    var selectedServerId by rememberSaveable {
        mutableStateOf(enabledServers.firstOrNull()?.id ?: "")
    }
    val server = enabledServers.firstOrNull { it.id == selectedServerId } ?: enabledServers.firstOrNull()

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var settingsRoute by rememberSaveable(
        stateSaver = routeSaver(),
    ) { mutableStateOf<SettingsRoute>(SettingsRoute.Menu) }
    var settingsCategory by rememberSaveable { mutableStateOf(SettingsCategory.STREAM) }
    // Placement mode: settings panel is closed and the stream screen shows a
    // single draggable/resizable box for this overlay id, over the live
    // camera preview. Entered from the overlay create-wizard's "Next" step
    // and from "Change position" in the overlay list.
    var placementOverlayId by rememberSaveable { mutableStateOf<String?>(null) }
    // Same placement flow, for the separate Browser-widget list (round 7) —
    // kept as its own state rather than reusing placementOverlayId so "done"
    // returns to the correct list screen (Overlay vs BrowserWidgets).
    var placementWidgetId by rememberSaveable { mutableStateOf<String?>(null) }

    // Back closes placement mode (returning to the overlay list), then the
    // settings panel, before finishing the Activity (H14).
    BackHandler(enabled = placementOverlayId != null) {
        placementOverlayId = null
        showSettings = true
        settingsRoute = SettingsRoute.Overlay
    }
    BackHandler(enabled = placementWidgetId != null) {
        placementWidgetId = null
        showSettings = true
        settingsRoute = SettingsRoute.BrowserWidgets
    }
    BackHandler(enabled = showSettings) { showSettings = false }

    // Push ABR ceiling/floor to the running streamer when the profile's ABR
    // settings change — otherwise the live ABR keeps the values from stream
    // start and the user's min/max edits have no effect until a restart.
    val abr = settings.selectedStreamProfile()?.adaptiveBitrate
    LaunchedEffect(
        abr?.targetBitrateKbps,
        abr?.minimumBitrateKbps,
        abr?.initialBitrateKbps,
    ) {
        val streamer = StreamController.current()
        val active = streamer != null && run {
            val s = streamer!!.state.value.status
            s == StreamStatus.Connected || s == StreamStatus.Connecting
        }
        if (active) {
            abr?.let {
                streamer!!.updateAdaptiveBitrateLimits(
                    targetKbps = it.targetBitrateKbps,
                    minKbps = it.minimumBitrateKbps,
                    initialKbps = it.initialBitrateKbps,
                )
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (server == null) {
            NoServerPrompt(onOpenSettings = {
                settingsRoute = SettingsRoute.Menu
                showSettings = true
            })
        } else {
            key(server.id) {
                ImmersiveStream(
                    serverType = server.type,
                    server = server,
                    profile = settings.selectedStreamProfile(),
                    moblink = settings.moblink,
                    cameraDefaults = settings.cameraDefaults,
                    audio = settings.audio,
                    scene = settings.selectedScene(),
                    scenes = settings.scenes,
                    onSelectScene = { settingsViewModel.selectScene(it) },
                    recordStream = settings.advanced.recordStream,
                    debugLog = settings.advanced.debugLog,
                    quickButtons = settings.quickButtons,
                    autoHideHud = settings.appearance.autoHideHud,
                    hud = settings.hud,
                    chat = settings.chat,
                    // Сцена решает, что показывать. Пока сцен нет — прежнее
                    // поведение: показываем всё включённое. Так включение сцен
                    // не ломает настройку тем, кто ими не пользуется.
                    overlays = settings.activeOverlays(),
                    placementOverlayId = placementOverlayId,
                    browserWidgets = settings.activeBrowserWidgets(),
                    placementWidgetId = placementWidgetId,
                    onOpenSettings = {
                        settingsRoute = SettingsRoute.Menu
                        showSettings = true
                    },
                    onConfigChange = { settingsViewModel.updateQuickButtons(it) },
                    onOverlayChange = { id, px, py, w, h ->
                        settings.overlays.firstOrNull { it.id == id }?.let { overlay ->
                            settingsViewModel.saveOverlay(
                                overlay.copy(posX = px, posY = py, width = w, height = h),
                            )
                        }
                    },
                    onPlacementDone = {
                        placementOverlayId = null
                        showSettings = true
                        settingsRoute = SettingsRoute.Overlay
                    },
                    onWidgetChange = { id, px, py, w, h ->
                        settings.browserWidgets.firstOrNull { it.id == id }?.let { widget ->
                            settingsViewModel.saveBrowserWidget(
                                widget.copy(posX = px, posY = py, width = w, height = h),
                            )
                        }
                    },
                    onWidgetPlacementDone = {
                        placementWidgetId = null
                        showSettings = true
                        settingsRoute = SettingsRoute.BrowserWidgets
                    },
                    onChatChange = { settingsViewModel.updateChat(it) },
                )
            }
        }

        AnimatedVisibility(
            visible = showSettings,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                SettingsPanelContent(
                    viewModel = settingsViewModel,
                    route = settingsRoute,
                    category = settingsCategory,
                    onRoute = { settingsRoute = it },
                    onCategoryChange = { settingsCategory = it },
                    onClose = { showSettings = false },
                    onPlaceOverlay = { id ->
                        showSettings = false
                        placementOverlayId = id
                    },
                    onPlaceWidget = { id ->
                        showSettings = false
                        placementWidgetId = id
                    },
                )
            }
        }
    }
}

@Composable
private fun NoServerPrompt(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.stream_no_server),
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenSettings) {
            Text(stringResource(R.string.btn_settings))
        }
    }
}

@Composable
private fun ImmersiveStream(
    serverType: ServerType,
    server: ServerProfile,
    profile: StreamProfile?,
    moblink: app.brix.core.MoblinkSettings,
    cameraDefaults: app.brix.core.CameraDefaults,
    audio: app.brix.core.AudioSettings,
    scene: app.brix.core.Scene?,
    scenes: List<app.brix.core.Scene>,
    onSelectScene: (String) -> Unit,
    recordStream: Boolean,
    debugLog: Boolean,
    quickButtons: QuickButtonConfig,
    autoHideHud: Boolean,
    hud: app.brix.core.HudConfig,
    chat: app.brix.core.ChatSettings,
    overlays: List<app.brix.core.OverlayConfig>,
    placementOverlayId: String?,
    browserWidgets: List<app.brix.core.BrowserWidgetConfig>,
    placementWidgetId: String?,
    onOpenSettings: () -> Unit,
    onConfigChange: (QuickButtonConfig) -> Unit,
    onOverlayChange: (id: String, posX: Float, posY: Float, width: Float, height: Float) -> Unit,
    onPlacementDone: () -> Unit,
    onWidgetChange: (id: String, posX: Float, posY: Float, width: Float, height: Float) -> Unit,
    onWidgetPlacementDone: () -> Unit,
    onChatChange: (app.brix.core.ChatSettings) -> Unit,
) {
    val context = LocalContext.current
    val streamer: LiveStreamer = remember(context, serverType) {
        StreamController.getOrCreate(context, serverType)
    }
    // Preview via RootEncoder only (heavy, heats in idle) — helper disabled as workaround failed
    val state by streamer.state.collectAsState()
    // Session-active for the UI: Connecting or Connected. Derived from the
    // collected StateFlow so the Start/Stop button recomposes reliably
    // (streamer.isStreaming is a plain getter, not observable state).
    val uiStreaming = state.status == StreamStatus.Connected ||
        state.status == StreamStatus.Connecting
    var overlayShown by remember { mutableStateOf(true) }
    overlays.forEach { overlay ->
        key(overlay.id) {
            OverlayHost(overlay = overlay, context = context, streamer = streamer, overlayShown = overlayShown)
        }
    }
    browserWidgets.forEach { widget ->
        key(widget.id) {
            BrowserWidgetHost(widget = widget, context = context, streamer = streamer, shown = overlayShown)
        }
    }

    // Чат — только на экран стримера, в поток не идёт: здесь
    // это обычный Compose-элемент поверх SurfaceView, а не источник кадра, и
    // GL-тракта не касается вовсе, в отличие от оверлеев выше. Клиент живёт
    // своим CoroutineScope, отдельно от [streamer] — соединение с чатом не
    // зависит от того, идёт ли сейчас эфир.
    val chatScope = rememberCoroutineScope()
    val twitchChatClient = remember { app.brix.streaming.chat.TwitchChatClient(scope = chatScope) }
    val kickChatClient = remember { app.brix.streaming.chat.KickChatClient(scope = chatScope) }
    val vkChatClient = remember { app.brix.streaming.chat.VkChatClient(scope = chatScope) }
    DisposableEffect(Unit) {
        onDispose {
            twitchChatClient.stop()
            kickChatClient.stop()
            vkChatClient.stop()
        }
    }
    LaunchedEffect(chat.enabled, chat.twitchChannel) {
        if (chat.enabled && chat.twitchChannel.isNotBlank()) {
            twitchChatClient.start(chat.twitchChannel)
        } else {
            twitchChatClient.stop()
        }
    }
    LaunchedEffect(chat.kickEnabled, chat.kickChannel) {
        if (chat.kickEnabled && chat.kickChannel.isNotBlank()) {
            kickChatClient.start(chat.kickChannel)
        } else {
            kickChatClient.stop()
        }
    }
    LaunchedEffect(chat.vkEnabled, chat.vkChannelUrl, chat.vkClientId, chat.vkClientSecret) {
        if (chat.vkEnabled && chat.vkChannelUrl.isNotBlank() &&
            chat.vkClientId.isNotBlank() && chat.vkClientSecret.isNotBlank()
        ) {
            vkChatClient.start(chat.vkChannelUrl, chat.vkClientId, chat.vkClientSecret)
        } else {
            vkChatClient.stop()
        }
    }
    // Мультичат: три независимых клиента сливаются в одну ленту по времени —
    // панель ничего не знает о конкретных площадках (mergeChatMessages в
    // :streaming). Отключённый клиент просто вечно отдаёт пустой список,
    // поэтому слияние не нужно делать условным.
    val chatMessages by remember(twitchChatClient, kickChatClient, vkChatClient) {
        app.brix.streaming.chat.mergeChatMessages(
            listOf(twitchChatClient.messages, kickChatClient.messages, vkChatClient.messages),
            maxMessages = 250,
        )
    }.collectAsState(initial = emptyList())
    // По статусу на каждую ВКЛЮЧЁННУЮ площадку — не общий "хоть кто-то на
    // связи": иначе не видно, что именно Kick отвалился, пока Twitch и VK
    // работают.
    val twitchConnected by twitchChatClient.connected.collectAsState()
    val kickConnected by kickChatClient.connected.collectAsState()
    val vkConnected by vkChatClient.connected.collectAsState()
    val chatAnyEnabled = (chat.enabled && chat.twitchChannel.isNotBlank()) ||
        (chat.kickEnabled && chat.kickChannel.isNotBlank()) ||
        (
            chat.vkEnabled && chat.vkChannelUrl.isNotBlank() &&
                chat.vkClientId.isNotBlank() && chat.vkClientSecret.isNotBlank()
            )
    val chatPlatformStatuses = buildList {
        if (chat.enabled && chat.twitchChannel.isNotBlank()) {
            add(app.brix.streaming.chat.ChatPlatform.TWITCH to twitchConnected)
        }
        if (chat.kickEnabled && chat.kickChannel.isNotBlank()) {
            add(app.brix.streaming.chat.ChatPlatform.KICK to kickConnected)
        }
        if (chat.vkEnabled && chat.vkChannelUrl.isNotBlank() &&
            chat.vkClientId.isNotBlank() && chat.vkClientSecret.isNotBlank()
        ) {
            add(app.brix.streaming.chat.ChatPlatform.VK to vkConnected)
        }
    }
    var placementChat by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = placementChat) { placementChat = false }

    var ready by remember { mutableStateOf(false) }

    /**
     * Применить сцену целиком: сначала картинка, затем звук.
     *
     * Одним местом на оба вызывающих (эффект смены сцены и коллбэк согласия) —
     * иначе порядок и запасные пути пришлось бы держать в согласии вручную.
     * Везде, где что-то не вышло, откат один и тот же: камера и микрофон.
     * Показать зрителям чёрный кадр или тишину хуже, чем не выполнить
     * настройку.
     */
    fun applyScene(want: app.brix.core.Scene, projection: android.media.projection.MediaProjection?) {
        when (want.source) {
            // Запасной путь НЕ трогает сторону камеры. Поле [Scene.camera]
            // относится к сцене «камера»; у сцены с экраном оно остаётся от
            // прежней правки и человеку не видно. 05.09 на этом и обожглись:
            // захват не начинался, откат честно возвращал камеру — но
            // переворачивал её на фронтальную, потому что в сцене с экраном
            // лежало FRONT. Выглядело как «вместо экрана включилось селфи».
            app.brix.core.SceneSource.SCREEN ->
                if (projection == null || !streamer.showScreen(projection)) {
                    streamer.showCamera()
                }
            app.brix.core.SceneSource.IMAGE -> {
                val uri = want.imageUri.takeIf { it.isNotBlank() }
                if (uri == null || !streamer.showStillImage(android.net.Uri.parse(uri))) {
                    streamer.showCamera()
                }
            }
            app.brix.core.SceneSource.CAMERA -> {
                streamer.showCamera()
                streamer.setCameraSide(want.camera)
            }
        }
        // Звук телефона идёт через AudioPlaybackCapture, а не через
        // виртуальный дисплей, поэтому один и тот же токен обслуживает и
        // картинку, и звук — лишь бы дисплей по нему создавали однократно.
        if (want.audio == app.brix.core.SceneAudio.MIC || projection == null ||
            !streamer.setSceneAudio(want.audio, projection)
        ) {
            streamer.setSceneAudio(app.brix.core.SceneAudio.MIC, null)
        }
    }

    val screenConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        val want = scene
        if (result.resultCode != android.app.Activity.RESULT_OK || data == null || want == null) {
            // Отказались — остаёмся на камере и микрофоне, а не показываем
            // чёрный кадр и не уходим в тишину.
            app.brix.streaming.ScreenCapture.cancelAwait()
            streamer.showCamera()
            streamer.setSceneAudio(app.brix.core.SceneAudio.MIC, null)
        } else {
            // Всё, что ниже, ждёт службу: пока она не объявила тип
            // mediaProjection, система токен не отдаёт. Продолжение
            // регистрируется ДО отправки команды, иначе с ответом можно
            // разминуться. Раньше эта работа шла сразу, команда службе уходила
            // по кругу через ActivityManager и не успевала — первое включение
            // сцены с экраном проваливалось всегда, а со второго работало
            // (замер 05.09). Со стороны выглядело как «просит права дважды».
            app.brix.streaming.ScreenCapture.awaitAllowed {
                val manager = context.getSystemService(
                    android.media.projection.MediaProjectionManager::class.java,
                )
                val projection = runCatching {
                    manager?.getMediaProjection(result.resultCode, data)
                }.onFailure {
                    Log.e("BrixScreen", "токен захвата не выдан: ${it.message}")
                }.getOrNull()
                if (projection != null) {
                    // Коллбэк регистрируется ДО первого виртуального дисплея —
                    // на Android 14+ это требование, иначе создание дисплея
                    // падает. Заодно он единственный способ узнать, что захват
                    // остановили снаружи: без него мы держали бы мёртвый токен
                    // и показывали зрителям застывший кадр.
                    runCatching {
                        projection.registerCallback(
                            object : android.media.projection.MediaProjection.Callback() {
                                override fun onStop() {
                                    app.brix.streaming.ScreenCapture.set(null)
                                    streamer.showCamera()
                                    streamer.setSceneAudio(app.brix.core.SceneAudio.MIC, null)
                                    context.startService(
                                        android.content.Intent(context, StreamService::class.java)
                                            .setAction(StreamService.ACTION_REVOKE_SCREEN),
                                    )
                                }
                            },
                            android.os.Handler(android.os.Looper.getMainLooper()),
                        )
                    }
                    // Владелец захвата — не интерфейс, а ScreenCapture:
                    // остановить его надо уметь из уведомления, когда
                    // приложение свёрнуто. Токен при этом одноразовый (замер
                    // 05.09): повторный createVirtualDisplay на том же
                    // экземпляре система встречает отказом и гасит идущий
                    // захват, поэтому согласие спрашивается заново на каждый
                    // заход в сцену с экраном.
                    app.brix.streaming.ScreenCapture.set(projection)
                }
                applyScene(want, projection)
            }
            context.startService(
                android.content.Intent(context, StreamService::class.java)
                    .setAction(StreamService.ACTION_ALLOW_SCREEN),
            )
        }
    }

    // Ключ — сцена целиком, а не её id: правку выбранной сцены (сменили
    // источник, выбрали другую картинку) иначе пришлось бы «применять»
    // переключением на другую сцену и обратно.
    LaunchedEffect(scene, ready) {
        val want = scene
        if (!ready || want == null) return@LaunchedEffect
        // Согласие спрашивается ОДИН раз на сцену, даже когда токен нужен и
        // картинке, и звуку: раньше два блока подряд видели ещё не пришедший
        // ответ и открывали диалог дважды. Сочетание «экран плюс звук
        // телефона» — самое обычное, так что натыкались бы на это постоянно.
        val needsProjection = want.source == app.brix.core.SceneSource.SCREEN ||
            want.audio != app.brix.core.SceneAudio.MIC
        if (needsProjection) {
            val manager = context.getSystemService(
                android.media.projection.MediaProjectionManager::class.java,
            )
            val intent = manager?.createScreenCaptureIntent()
            if (intent != null) {
                screenConsent.launch(intent)
            } else {
                streamer.showCamera()
                streamer.setSceneAudio(app.brix.core.SceneAudio.MIC, null)
            }
            return@LaunchedEffect
        }
        applyScene(want, null)
    }

    var hudVisible by remember { mutableStateOf(true) }
    // Set only by an explicit INFO tap — distinct from hudVisible so the
    // initial/auto-hide-driven visibility at stream start doesn't count as
    // "pinned". While pinned, auto-hide is suppressed (mirrors MUTE: an
    // explicit toggle that stays until pressed again), and the quick button
    // highlights via activeActions below.
    var hudPinned by remember { mutableStateOf(false) }
    // Local-phone display power saving — NOT a stream property (unlike
    // MUTE/TORCH/BLACK_SCREEN it never touches LiveStreamer/StreamState):
    // dims the backlight and detaches the local GL preview render pass.
    // Safe while live — StreamBase.stopPreview() only tears down the
    // preview-only EGL surface when isStreaming is true; the camera source,
    // GL pipeline and encoder are untouched (verified against RootEncoder).
    var powerSaveOn by remember { mutableStateOf(false) }
    var previewSurfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    val activeActions = buildSet {
        if (state.micMuted) add(ButtonAction.MUTE)
        if (state.micSource != MicSource.AUTO) add(ButtonAction.MIC)
        if (state.adaptiveBitrateEnabled) add(ButtonAction.ADAPTIVE_BITRATE)
        if (state.torchOn) add(ButtonAction.TORCH)
        if (state.blackScreenOn) add(ButtonAction.BLACK_SCREEN)
        if (state.videoEffect != app.brix.streaming.VideoEffect.NONE) add(ButtonAction.VIDEO_EFFECT)
        if (hudPinned) add(ButtonAction.INFO)
        if (powerSaveOn) add(ButtonAction.POWER_SAVE)
    }
    var hudExpanded by remember { mutableStateOf(false) }
    var uiLocked by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    // Hoisted once: StatusOverlay and the mascot both need battery/thermal —
    // each calling rememberTelemetry() itself meant two BroadcastReceiver
    // registrations and two independent 5s thermal-poll loops running for
    // the whole session.
    val telemetry = rememberTelemetry()

    // Hoisted to the screen scope: the mascot can be placed in the corner slot
    // or in the quick-button grid (or both), and every placement must show the
    // same live stream state.
    val mascotState = mascotStateFor(state, telemetry.thermalLevel)

    // Detaches only the local GL preview render pass — camera/GL/encoder
    // keep running (verified: StreamBase.stopPreview() only tears down the
    // preview-only EGL surface while isStreaming is true), so this is a real
    // GPU/heat saving with zero effect on the actual broadcast.
    //
    // Deliberately does NOT touch screen brightness: android.view.Window's
    // screenBrightness override dims the WHOLE window, not just the preview
    // area — there is no way to spare just the toggle button itself. A first
    // version that set it near-zero made the entire UI unreadable outdoors,
    // trapping the user with no visible way to turn it back off (field-
    // reported 2026-08-31: "вешает экран... и всё, пока не отожмешь").
    val activity = LocalContext.current as? Activity
    LaunchedEffect(powerSaveOn) {
        if (powerSaveOn) {
            streamer.stopPreview()
        } else {
            previewSurfaceView?.let { streamer.startPreview(it) }
        }
    }

    // The panel has no reason to run above 60Hz while framing/streaming —
    // nothing on this screen needs it, and a free-running high refresh rate
    // (S21 defaults to 120Hz) is pure wasted power/heat driven by whatever
    // is still animating (HUD, mascot). preferredRefreshRate is API 30+;
    // minSdk is 29, so this is a no-op (not a crash) on Android 10.
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return@LaunchedEffect
        val window = activity?.window ?: return@LaunchedEffect
        window.attributes = window.attributes.apply {
            // Pinned always, not just while live. Gating this on uiStreaming
            // left the idle case — app open, no stream — free-running at the
            // panel's 120Hz, which is exactly the state measured at 47C on a
            // phone that was only lying on a table. Nothing on this screen
            // needs more than 60.
            preferredRefreshRate = 60f
        }
    }

    // System back should unlock the UI first, not finish the Activity (H14).
    BackHandler(enabled = uiLocked) { uiLocked = false }

    // Moblin-style lens pill bar: enumerate this device's rear lenses once.
    val rearLensList = remember { context.rearLenses() }
    var selectedLensId by remember {
        mutableStateOf(streamer.currentLensId() ?: rearLensList.firstOrNull()?.id)
    }

    // HUD auto-hide only when enabled in appearance settings; the INFO quick
    // action toggles HUD visibility manually either way.
    val autoHide = autoHideHud
    LaunchedEffect(hudVisible, autoHide, hudPinned) {
        if (hudVisible && autoHide && !hudPinned) {
            kotlinx.coroutines.delay(4000)
            hudVisible = false
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* best-effort; denial must not block streaming (C1) */ }

    val requestNotifications: () -> Unit = {
        if (NOTIFICATION_PERMISSIONS.isNotEmpty() &&
            NOTIFICATION_PERMISSIONS.any {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
        ) {
            notificationLauncher.launch(NOTIFICATION_PERMISSIONS)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (profile != null) streamer.configure(profile, server.latencyMs)
        streamer.configureMoblink(moblink)
        (streamer as? app.brix.streaming.SrtlaStreamer)?.setPreferIpv4(server.preferIpv4)
        streamer.configureCamera(cameraDefaults)
        streamer.configureAudio(audio)
        streamer.setRecordStream(recordStream)
        streamer.setDebugLog(debugLog)
        ready = result.values.all { it } && (streamer.isStreaming || streamer.prepare())
        requestNotifications()
    }

    LaunchedEffect(Unit) {
        if (profile != null) streamer.configure(profile, server.latencyMs)
        streamer.configureMoblink(moblink)
        (streamer as? app.brix.streaming.SrtlaStreamer)?.setPreferIpv4(server.preferIpv4)
        streamer.configureCamera(cameraDefaults)
        streamer.configureAudio(audio)
        streamer.setRecordStream(recordStream)
        streamer.setDebugLog(debugLog)
        requestNotifications()
        if (hasRequiredPermissions(context)) {
            // RootEncoder camera/GL init is thread-sensitive; prepare() must not
            // run on an arbitrary IO thread (it caused camera-HAL stalls / freeze).
            ready = streamer.isStreaming || streamer.prepare()
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    // Release the cached streamer only when the Activity is really going
    // away. A plain onDispose also fires on server switch (key(server.id))
    // and Activity recreation — killing a live stream in both cases (audit
    // P0-1). ON_DESTROY + isChangingConfigurations distinguishes them.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event != androidx.lifecycle.Lifecycle.Event.ON_DESTROY) return@LifecycleEventObserver
            val act = context as? android.app.Activity
            if (act?.isChangingConfigurations == true) return@LifecycleEventObserver
            val current = StreamController.current()
            // A running stream belongs to the foreground service — leave it.
            if (current?.isStreaming != true) {
                Thread { StreamController.releaseAll() }.start()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val scope = rememberCoroutineScope()
    val handleAction: (ButtonAction) -> Unit = { action ->
        when (action) {
            ButtonAction.MUTE -> streamer.setMuted(!state.micMuted)
            ButtonAction.FLIP -> {
                streamer.switchCamera()
                selectedLensId = if (selectedLensId == null) {
                    rearLensList.firstOrNull()?.id
                } else {
                    null
                }
            }
            ButtonAction.ADAPTIVE_BITRATE -> streamer.setAdaptiveBitrate(!state.adaptiveBitrateEnabled)
            ButtonAction.RECONNECT -> streamer.reconnect()
            ButtonAction.SETTINGS -> onOpenSettings()
            ButtonAction.TORCH -> streamer.setTorch(!state.torchOn)
            ButtonAction.LOCK -> { uiLocked = !uiLocked }
            ButtonAction.INFO -> {
                hudPinned = !hudPinned
                hudVisible = hudPinned
            }
            ButtonAction.BLACK_SCREEN -> streamer.setBlackScreen(!state.blackScreenOn)
            ButtonAction.OVERLAY -> { overlayShown = !overlayShown }
            ButtonAction.VIDEO_EFFECT -> {
                val effects = app.brix.streaming.VideoEffect.entries
                val next = effects[(state.videoEffect.ordinal + 1) % effects.size]
                streamer.setVideoEffect(next)
            }
            ButtonAction.MASCOT -> Unit // purely decorative, no tap action
            ButtonAction.POWER_SAVE -> { powerSaveOn = !powerSaveOn }
            // Перебираем то, что подключено сейчас, а не весь enum: гарнитуру
            // втыкают и вынимают посреди эфира, и предлагать отсутствующее
            // устройство значило бы оставить стримера без звука.
            // Перебор по кругу: сцен обычно две-три, и лезть за ними в
            // настройки посреди эфира — не вариант.
            ButtonAction.SCENE -> {
                if (scenes.isNotEmpty()) {
                    val i = scenes.indexOfFirst { it.id == scene?.id }
                    onSelectScene(scenes[(i + 1) % scenes.size].id)
                }
            }
            ButtonAction.MIC -> {
                val mics = MicDevices.available(context)
                val next = mics.getOrNull(mics.indexOf(state.micSource) + 1) ?: mics.first()
                streamer.setMicSource(next)
            }
        }
    }
    val startOrStop: () -> Unit = {
        // Runs on the composition's Main scope; only the genuinely blocking
        // transport calls are dispatched to IO. prepare() must NOT be one of
        // them — it drives RootEncoder's camera/GL init, which is
        // thread-sensitive (see the LaunchedEffect above, where it is
        // deliberately kept on main; running it on an arbitrary IO thread
        // caused camera-HAL stalls and a frozen preview). Normally prepare()
        // short-circuits on `prepared`, but after releaseAll() or a failed
        // session it really does re-init the camera here.
        scope.launch {
            val current = StreamController.getOrCreate(context.applicationContext, serverType)
            val st = current.state.value.status
            // Stop in any non-idle state (Connected/Connecting/Reconnecting/Failed/
            // Rejected/Disconnected). Previously only Connected/Connecting stopped,
            // so during a connection error (status=Failed) the same button routed to
            // START and the user could not stop/abort the session.
            if (st != StreamStatus.Idle) {
                withContext(Dispatchers.IO) { current.stop() }
                StreamService.stop(context)
            } else {
                if (profile != null) current.configure(profile, server.latencyMs)
                current.configureMoblink(moblink)
                (current as? app.brix.streaming.SrtlaStreamer)?.setPreferIpv4(server.preferIpv4)
                current.configureCamera(cameraDefaults)
                current.configureAudio(audio)
                current.setRecordStream(recordStream)
                current.setDebugLog(debugLog)
                if (!current.prepare()) return@launch
                // Foreground service first: it owns the wake lock that keeps
                // the CPU alive if the screen goes off mid-connect.
                StreamService.start(context)
                // connectUrl, а не baseUrl: у RTMP ключ трансляции хранится
                // отдельным полем и приклеивается к адресу здесь. Профили без
                // отдельного ключа отдают baseUrl как есть.
                withContext(Dispatchers.IO) { current.start(server.connectUrl()) }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
            if (ready) {
                // The factory lambda below runs once (Compose's AndroidView
                // contract) and the touch listener it installs closes over
                // whatever it captures — without this, toggling "Фокус" in
                // Settings while this screen stays composed (Settings renders
                // as an overlay on top, not a separate destination) would
                // never be picked up until the SurfaceView is recreated.
                val currentCameraDefaults by rememberUpdatedState(cameraDefaults)
                AndroidView(
                    factory = { ctx ->
                        val scaleDetector = ScaleGestureDetector(
                            ctx,
                            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                                override fun onScale(detector: ScaleGestureDetector): Boolean {
                                    streamer.zoomByScale(detector.scaleFactor)
                                    return true
                                }
                            },
                        )
                        var moved = false
                        // A double-tap fires two independent tapFocus() calls at (roughly) the
                        // same point a few hundred ms apart — pointless (refocusing where we
                        // just focused) and a suspected trigger for the camera preview freeze
                        // (two near-simultaneous HAL focus requests). Debounce here rather than
                        // relying on cancelling the coroutine in SrtlaStreamer — tapToFocus() is
                        // a synchronous HAL call, so a Job cancellation can't interrupt one
                        // that's already running.
                        var lastTapFocusAt = 0L
                        SurfaceView(ctx).also { sv ->
                            previewSurfaceView = sv
                            // The preview surface's buffer is composited at
                            // whatever pixel size it's given — leaving it at
                            // the SurfaceView's full display size (e.g.
                            // 2400x1080 on the S21) means every preview frame
                            // is blitted at ~2.8x the pixel count actually
                            // being encoded, for no visible benefit (the
                            // compositor upscales to fill the view either
                            // way). Fixing it to the stream's own resolution
                            // is free GPU savings, independent of the
                            // separate encoder surface (confirmed:
                            // GlStreamInterface tracks preview/encoder
                            // resolution in separate fields).
                            // Ниже разрешения потока, а не вровень с ним. Бюджет
                            // 6.0-П5: превью стоит 2.80 Вт из 5.31, больше, чем весь
                            // эфир поверх него (1.22 Вт) — и это уже с привязкой
                            // буфера к потоку. Дальше удешевлять можно только за счёт
                            // видоискателя, и это законный размен: зритель превью не
                            // видит, качество эфира не трогается (в отличие от
                            // троттлинга битрейта, убранного сознательно).
                            // Половина по стороне — четверть заливки на двух проходах
                            // GL из пяти (drawFilters(true) и drawScreenPreview в
                            // GlStreamInterface существуют только ради превью) плюс
                            // дешевле композиция. Растягивает обратно SurfaceFlinger.
                            profile?.video?.let { vs ->
                                sv.holder.setFixedSize(
                                    (vs.width / PREVIEW_DOWNSCALE).coerceAtLeast(320),
                                    (vs.height / PREVIEW_DOWNSCALE).coerceAtLeast(180),
                                )
                            }
                            sv.setOnTouchListener { v, event ->
                                // Diagnostics for the multi-round "camera freeze" saga: raw
                                // actionMasked/pointerCount/pointerIds for every touch reaching
                                // this listener. A prior capture showed several single-finger
                                // taps that reached ViewRootImpl (system ViewPostIme logs) but
                                // never produced a tapFocus() call — no exception, no HAL hang,
                                // just silence — consistent with a stuck/ghost pointer making
                                // later taps arrive as pointerCount>1 events this listener's
                                // gating silently drops. This log is what will confirm or rule
                                // that out next capture.
                                if (android.util.Log.isLoggable("BrixTouch", android.util.Log.DEBUG)) {
                                    // Was unconditional: a string plus a list
                                    // allocation on EVERY MotionEvent, so a
                                    // pinch-zoom logged hundreds of lines a
                                    // second. Kept for the freeze
                                    // investigation, now behind
                                    // `setprop log.tag.BrixTouch DEBUG`.
                                    android.util.Log.d(
                                        "BrixTouch",
                                        "actionMasked=${event.actionMasked} pointerCount=${event.pointerCount} " +
                                            "pointerIds=${(0 until event.pointerCount).map { event.getPointerId(it) }}",
                                    )
                                }
                                scaleDetector.onTouchEvent(event)
                                when (event.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> moved = false
                                    MotionEvent.ACTION_MOVE ->
                                        if (event.pointerCount > 1) moved = true
                                    MotionEvent.ACTION_CANCEL -> moved = false
                                    MotionEvent.ACTION_UP -> {
                                        v.performClick()
                                        if (!moved && event.pointerCount == 1) {
                                            val now = System.currentTimeMillis()
                                            if (currentCameraDefaults.tapToFocus && now - lastTapFocusAt > 400L) {
                                                lastTapFocusAt = now
                                                streamer.tapFocus(v, event.x, event.y)
                                            }
                                        }
                                    }
                                }
                                true
                            }
                            sv.holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    android.util.Log.w("BrixPreview", "surfaceCreated ready=$ready")
                                    try {
                                        if (ready) streamer.startPreview(sv)
                                    } catch (e: Exception) {
                                        android.util.Log.e("BrixPreview", "startPreview failed", e)
                                    }
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int,
                                ) {
                                    android.util.Log.w("BrixPreview", "surfaceChanged width=$width height=$height format=$format")
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    // Camera-freeze diagnostics: if the surface is ever destroyed
                                    // and recreated mid-session without an explained backgrounding
                                    // (BrixLifecycle onPause), this is the direct trigger — round 9
                                    // found exactly such an unexplained visibility loss, but we
                                    // never logged the surface event itself, only the Activity's.
                                    android.util.Log.w("BrixPreview", "surfaceDestroyed")
                                    try {
                                        streamer.stopPreview()
                                    } catch (e: Exception) {
                                        android.util.Log.e("BrixPreview", "stopPreview failed", e)
                                    }
                                }
                            })
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = stringResource(R.string.status_waiting_permission),
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            val placementOverlay = overlays.firstOrNull { it.id == placementOverlayId }
            if (placementOverlay != null) {
                OverlayTuner(
                    posX = placementOverlay.posX,
                    posY = placementOverlay.posY,
                    widthFraction = placementOverlay.width,
                    heightFraction = placementOverlay.height,
                    onMove = { px, py, w, h -> onOverlayChange(placementOverlay.id, px, py, w, h) },
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.overlay_tune_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onPlacementDone) {
                        Text(stringResource(R.string.btn_done))
                    }
                }
            }

            val placementWidget = browserWidgets.firstOrNull { it.id == placementWidgetId }
            if (placementWidget != null) {
                OverlayTuner(
                    posX = placementWidget.posX,
                    posY = placementWidget.posY,
                    widthFraction = placementWidget.width,
                    heightFraction = placementWidget.height,
                    onMove = { px, py, w, h -> onWidgetChange(placementWidget.id, px, py, w, h) },
                    onLiveMove = { px, py, w, h ->
                        streamer.attachLiveOverlay(placementWidget.id, px, py, app.brix.streaming.overlay.OverlaySize(w, h))
                    },
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.overlay_tune_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onWidgetPlacementDone) {
                        Text(stringResource(R.string.btn_done))
                    }
                }
            }

            if (hudVisible) StatusOverlay(
                state = state,
                uptimeTick = streamer.uptimeTick,
                connectedAtElapsedMs = state.connectedAtElapsedMs,
                hud = hud,
                sceneName = scene?.name,
                expanded = hudExpanded,
                onToggleExpand = { hudExpanded = !hudExpanded },
                telemetry = telemetry,
            )
            ReconnectOverlay(state = state, onReconnect = { streamer.reconnect() })

            if (chatAnyEnabled && !placementChat) {
                FractionalBox(posX = chat.posX, posY = chat.posY, widthFraction = chat.width, heightFraction = chat.height) {
                    ChatPanel(
                        messages = chatMessages,
                        platformStatuses = chatPlatformStatuses,
                        fontScale = chat.fontScale,
                        onFontScaleChange = { onChatChange(chat.copy(fontScale = it)) },
                        onEnterPlacement = { placementChat = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (placementChat) {
                OverlayTuner(
                    posX = chat.posX,
                    posY = chat.posY,
                    widthFraction = chat.width,
                    heightFraction = chat.height,
                    onMove = { px, py, w, h -> onChatChange(chat.copy(posX = px, posY = py, width = w, height = h)) },
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.overlay_tune_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { placementChat = false }) {
                        Text(stringResource(R.string.btn_done))
                    }
                }
            }

            if (rearLensList.isNotEmpty()) {
                LensBar(
                    lenses = rearLensList,
                    selectedLensId = selectedLensId,
                    onSelect = { lens ->
                        when {
                            // Front requested
                            lens == null -> {
                                streamer.switchCamera()
                                selectedLensId = null
                            }
                            // Currently on front camera: setLens flips to back
                            // itself (on the camera dispatcher, after the
                            // flip). Calling switchCamera() here first raced
                            // it — the facing check saw "front", bailed, and
                            // the lens was never applied.
                            selectedLensId == null -> {
                                if (streamer.setLens(lens.cameraId, lens.zoom)) {
                                    selectedLensId = lens.id
                                }
                            }
                            else -> if (streamer.setLens(lens.cameraId, lens.zoom)) {
                                selectedLensId = lens.id
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                )
            }

            val cornerAction = quickButtons.cornerSlotAction
            if (editing || cornerAction != null) {
                CornerSlotView(
                    action = cornerAction,
                    editing = editing,
                    active = cornerAction?.let { activeActions.contains(it) } ?: false,
                    usedActions = quickButtons.slots.map { it.action }.toSet(),
                    onAction = handleAction,
                    onAssign = { a -> onConfigChange(quickButtons.copy(cornerSlotAction = a)) },
                    onRemove = { onConfigChange(quickButtons.copy(cornerSlotAction = null)) },
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                    mascotState = mascotState,
                )
            }
        }

        QuickButtonGrid(
            config = quickButtons,
            streaming = uiStreaming,
            // Toggled-state highlighting (mute/ABR/torch/black screen).
            activeActions = activeActions,
            onAction = handleAction,
            onStop = startOrStop,
            onConfigChange = onConfigChange,
            editing = editing,
            onEditingChange = { editing = it },
            mascotState = mascotState,
            modifier = Modifier.width(132.dp),
        )
        }

        if (uiLocked) {
            LockOverlay(onUnlock = { uiLocked = false })
        }

    }
}

/**
 * Owns one overlay's detection WebView + JS bridge + [OverlayController],
 * keyed by [overlay.id] at the call site (`key(overlay.id) { OverlayHost(...) }`)
 * so Compose disposes everything automatically when the overlay is removed or
 * disabled — no manual bookkeeping needed. Multiple instances run
 * side-by-side for multiple configured overlays.
 */
@Composable
private fun OverlayHost(
    overlay: app.brix.core.OverlayConfig,
    context: Context,
    streamer: LiveStreamer,
    overlayShown: Boolean,
) {
    val overlayController = remember(context, streamer, overlay.id) {
        OverlayController(context, streamer, overlay.id)
    }
    val overlayJsBridge = remember(overlayController) { OverlayJsBridge(overlayController) }
    // Loading the widget page (real network + JS execution) competes with the
    // camera for the main thread; doing both at once right at cold start was
    // observed to make Camera2 force-close and thrash-reopen the camera
    // repeatedly. A donation can't physically arrive in the first couple of
    // seconds anyway, so there's nothing lost by giving camera init a head
    // start.
    var readyToLoad by remember(overlay.id) { mutableStateOf(false) }
    LaunchedEffect(overlay.id) {
        kotlinx.coroutines.delay(3000)
        readyToLoad = true
    }
    // Kept alive (VISIBLE but alpha=0, attached to the activity window) for as
    // long as the overlay is enabled, independent of whether streaming is
    // active: donations can arrive at any time and the widget's own WebSocket
    // must stay connected to catch them. It renders nothing in-stream —
    // alerts are drawn natively via OverlayController -> streamer.showOverlay()
    // once detected. Deliberately NOT Android-INVISIBLE: Chromium maps
    // View.getVisibility() to the page's document.visibilityState/
    // document.hidden, and "browser source" widgets (built for OBS, which
    // forces itself always-visible for this exact reason) commonly skip
    // alert/audio work while hidden. alpha=0 keeps the view "shown" to
    // Chromium while staying invisible to the user. A near-zero size can also
    // break responsive widget layout, so give it a real (if modest — this
    // never needs to look good, only "visible" to Chromium and non-tiny for
    // the widget's own layout) viewport instead of 1x1.
    val overlayPreview = remember(overlay.url, overlay.enabled, readyToLoad) {
        if (overlay.enabled && overlay.url.isNotBlank() && readyToLoad) {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                // WebView defaults to requiring a user gesture before playing any media.
                // Confirmed via diagnostics: the widget's own sound.play() attempts were
                // failing with "NotAllowedError: play() can only be initiated by a user
                // gesture" on every donation — this WebView never gets a real tap/click, so
                // that block was firing on every alert. Doesn't affect our own createSound-URL
                // capture (already independent of the widget's play() succeeding), but may be
                // why some other audio path (e.g. a TTS readout) never got far enough to leave
                // any trace we could pick up.
                settings.mediaPlaybackRequiresUserGesture = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                        val text = message.message()
                        android.util.Log.d("Overlay", "[${overlay.id.take(8)}] console: $text (${message.sourceId()}:${message.lineNumber()})")
                        // Best-effort: the widget's own console logging is the
                        // only signal we have for its connection state to the
                        // alert server — matched on wording observed across
                        // every diagnostics capture this session. If
                        // DonationAlerts changes the wording, this just stops
                        // updating rather than breaking anything.
                        if (text.contains("WS: connected") || text.contains("[SocketIOClient] Connected") || text.contains("[SocketIOClient] Reconnected")) {
                            streamer.setOverlayConnectionState(overlay.id, true)
                        } else if (text.contains("WS: connection_error")) {
                            streamer.setOverlayConnectionState(overlay.id, false)
                        }
                        return true
                    }
                }
                overlayJsBridge.installInto(this)
                loadUrl(overlay.url)
                visibility = android.view.View.VISIBLE
                alpha = 0f
                layoutParams = android.view.ViewGroup.LayoutParams(480, 270)
                (context as? android.app.Activity)?.addContentView(this, layoutParams)
                // alpha=0 alone still leaves the view touchable — addContentView
                // places it at the default top-left corner, which overlaps real
                // UI (e.g. the settings panel's close button) and silently
                // swallows taps landing there. Chromium's Page Visibility check
                // only cares about View.getVisibility(), not screen position, so
                // push it fully off-screen instead.
                translationX = -10_000f
                translationY = -10_000f
            }
        } else {
            null
        }
    }
    DisposableEffect(overlayPreview) {
        onDispose {
            overlayPreview?.let { view ->
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
            }
            streamer.setOverlayConnectionState(overlay.id, false)
        }
    }
    DisposableEffect(overlayController) {
        onDispose { overlayController.release() }
    }
    LaunchedEffect(overlayShown, overlay.posX, overlay.posY, overlay.width, overlay.height, overlay.audioOnDevice, overlay.audioInStream) {
        overlayJsBridge.enabled = overlayShown
        overlayJsBridge.posX = overlay.posX
        overlayJsBridge.posY = overlay.posY
        overlayJsBridge.widthFraction = overlay.width
        overlayJsBridge.heightFraction = overlay.height
        overlayJsBridge.audioOnDevice = overlay.audioOnDevice
        overlayJsBridge.audioInStream = overlay.audioInStream
    }
    LaunchedEffect(overlayController, overlay.enabled, overlay.audioInStream) {
        overlayController.setInStreamMixing(overlay.enabled && overlay.audioInStream)
    }
}

/**
 * A general "browser source" — unlike [OverlayHost] (donation detect, hidden,
 * event-triggered) this WebView's content IS the overlay: rendered live via
 * periodic [android.view.View.draw] snapshots pushed through
 * [LiveStreamer.updateLiveOverlayFrame]. Kept off-screen the same way as the
 * donation detect WebView (touch-swallow lesson from that overlay applies
 * equally here) — what actually appears in the video is the captured bitmap,
 * not the WebView itself.
 */
@Composable
private fun BrowserWidgetHost(
    widget: app.brix.core.BrowserWidgetConfig,
    context: Context,
    streamer: LiveStreamer,
    shown: Boolean,
) {
    val webView = remember(widget.url, widget.enabled) {
        if (widget.enabled && widget.url.isNotBlank()) {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                // REQUIRED for the snapshot loop below to capture anything at
                // all. A hardware-accelerated WebView renders through its own
                // hardware layer, and drawing it into a Bitmap-backed (i.e.
                // software) Canvas yields the background and nothing else — so
                // every captured frame was blank and the widget never appeared
                // in the stream, donation or not. Confirmed in the
                // 20260831_001056 capture: the page was laid out at 720x720 and
                // actively drawing (WV.sf.onDraw, repeated "finish load image"),
                // while the overlay stayed empty.
                //
                // This is a workaround, not the destination: software layer
                // means the page rasterises on the CPU, which is exactly the
                // cost the ViewSurfaceFilterRender rework removes.
                setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                loadUrl(widget.url)
                layoutParams = android.view.ViewGroup.LayoutParams(720, 720)
                (context as? android.app.Activity)?.addContentView(this, layoutParams)
                translationX = -10_000f
                translationY = -10_000f
            }
        } else {
            null
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView?.let { view ->
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
            }
        }
    }
    DisposableEffect(widget.id) {
        onDispose { streamer.detachLiveOverlay(widget.id) }
    }
    LaunchedEffect(webView, shown, widget.enabled, widget.posX, widget.posY, widget.width, widget.height) {
        if (webView == null || !shown || !widget.enabled) {
            streamer.detachLiveOverlay(widget.id)
            return@LaunchedEffect
        }
        streamer.attachLiveOverlay(
            widget.id,
            widget.posX,
            widget.posY,
            app.brix.streaming.overlay.OverlaySize(widget.width, widget.height),
        )
        // Double-buffered, not one fresh bitmap per tick: a 720x720 ARGB_8888
        // frame is ~2 MB, and at the default 200ms refresh that allocated
        // ~10 MB/s of garbage for the whole session on a phone that is already
        // fighting thermal throttling. Two buffers rather than one because the
        // GL filter uploads the bitmap on the render thread some time after
        // updateLiveOverlayFrame() returns — alternating gives that upload a
        // full refresh period before we draw over the buffer again.
        //
        // Deliberately never recycle()d, only dropped for the GC: the filter
        // uploads the bitmap asynchronously on the GL thread, and detach is
        // itself asynchronous, so an explicit recycle races that upload and
        // crashes it with "trying to use a recycled bitmap". Reuse is what
        // removes the garbage; recycling adds nothing but risk here.
        var buffers: Array<android.graphics.Bitmap>? = null
        var bufferIndex = 0
        var bufferW = 0
        var bufferH = 0
        // Самая дорогая из периодических задач по замеру 12.09 — 9-10% одного
        // ядра при периоде 200 мс, вчетверо больше на 50 мс. Именно её отсутствие
        // в отчёте не давало понять, чем был занят телефон в сессии 07.09.
        app.brix.core.diagnostics.PeriodicTasks.register("widget-${widget.id.take(4)}", widget.refreshMs).use {
        while (true) {
            val w = webView.width.coerceAtLeast(1)
            val h = webView.height.coerceAtLeast(1)
            val current = buffers
            val pair = if (current == null || w != bufferW || h != bufferH) {
                bufferW = w
                bufferH = h
                Array(2) {
                    android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                }.also { buffers = it }
            } else {
                current
            }
            val bitmap = pair[bufferIndex]
            bufferIndex = (bufferIndex + 1) % pair.size
            // The WebView paints only its own content; without erasing, a page
            // with transparency keeps the previous frame's pixels underneath.
            bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
            webView.draw(android.graphics.Canvas(bitmap))
            streamer.updateLiveOverlayFrame(widget.id, bitmap)
            kotlinx.coroutines.delay(widget.refreshMs)
        }
        }
    }
}

@Composable
private fun StatusOverlay(
    state: app.brix.streaming.StreamState,
    /** Тик часов эфира отдельным потоком, а не числом сверху: собирается ЗДЕСЬ,
     *  чтобы ежесекундное изменение перерисовывало только эту панель, а не всё
     *  тело экрана стримера. Раньше отметка времени лежала в StreamState,
     *  который собирается в самом верху, и одна цифра часов тянула за собой
     *  пересборку двух с лишним тысяч строк. */
    uptimeTick: kotlinx.coroutines.flow.StateFlow<Long>,
    connectedAtElapsedMs: Long?,
    hud: app.brix.core.HudConfig,
    sceneName: String?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    telemetry: TelemetryView,
) {
    val tick by uptimeTick.collectAsState()
    val uptimeSec = connectedAtElapsedMs?.let { startedAt ->
        ((tick - startedAt) / 1000L).toInt().coerceAtLeast(0)
    } ?: 0
    val statusText = when (state.status) {
        StreamStatus.Idle -> stringResource(R.string.status_idle)
        StreamStatus.Connecting -> if (state.reconnectAttempt > 0) {
            // Explicit reconnect state with reason + attempt number.
            stringResource(R.string.status_reconnecting, state.reconnectAttempt) +
                (state.error?.message ?: state.message?.let { " · $it" } ?: "")
        } else {
            stringResource(R.string.status_connecting)
        }
        StreamStatus.Connected -> stringResource(R.string.status_connected)
        StreamStatus.Disconnected -> stringResource(R.string.status_disconnected)
        StreamStatus.Rejected ->
            stringResource(R.string.status_rejected) + (state.message?.let { ": $it" } ?: "")
        StreamStatus.Failed ->
            stringResource(R.string.status_failed) + ": " + (
                state.error?.message ?: state.message ?: "unknown"
                )
    }
    val bitrate = if (state.adaptiveBitrateEnabled && state.adaptiveBitrateKbps > 0) {
        state.adaptiveBitrateKbps
    } else {
        state.bitrateKbps
    }
    val uptime = if (state.status == StreamStatus.Connected && hud.showUptime) {
        " · %02d:%02d".format(java.util.Locale.US, uptimeSec / 60, uptimeSec % 60)
    } else {
        ""
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val versionName = remember(context) {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
    }
    val totalBytes = state.connections.sumOf { it.bytesSent }
    val bitrateMbps = if (bitrate > 0) bitrate / 1000.0 else 0.0
    val droppedTotal = state.connections.sumOf { it.packetsDropped }
    val sentPkts = (totalBytes / 1316L).coerceAtLeast(1)
    val dropPct = if (droppedTotal > 0) (droppedTotal * 100f) / (droppedTotal + sentPkts).toFloat() else 0f
    val batteryWarn = telemetry.batteryPct in 0..15
    val thermalWarn = telemetry.thermalLevel >= android.os.PowerManager.THERMAL_STATUS_SEVERE
    val bitrateWarn = state.status == StreamStatus.Connected && bitrate in 1..499

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        HudCard(
            state = state,
            statusText = statusText,
            uptime = uptime,
            bitrateMbps = bitrateMbps,
            hud = hud,
            sceneName = sceneName,
            telemetry = telemetry,
            versionName = versionName,
            expanded = expanded,
            onToggleExpand = onToggleExpand,
            batteryWarn = batteryWarn,
            thermalWarn = thermalWarn,
            bitrateWarn = bitrateWarn,
            dropPct = dropPct,
            totalBytes = totalBytes,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun HudCard(
    state: app.brix.streaming.StreamState,
    statusText: String,
    uptime: String,
    bitrateMbps: Double,
    hud: app.brix.core.HudConfig,
    sceneName: String?,
    telemetry: TelemetryView,
    versionName: String?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    batteryWarn: Boolean,
    thermalWarn: Boolean,
    bitrateWarn: Boolean,
    dropPct: Float,
    totalBytes: Long,
    modifier: Modifier = Modifier,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val tertiary = MaterialTheme.colorScheme.tertiary
    val warn = MaterialTheme.colorScheme.error
    val border = MaterialTheme.colorScheme.outlineVariant
    val compact = MaterialTheme.typography.labelSmall
    Column(
        modifier = modifier
            .widthIn(max = 520.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
            .border(0.5.dp, border, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggleExpand)
            .padding(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(6.dp).background(
                        if (state.status == StreamStatus.Connected) Color(0xFFE53935) else Color(0xFF9E9E9E),
                        CircleShape,
                    ),
                )
                Spacer(Modifier.width(6.dp))
                Text("LIVE", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = onSurface)
                if (hud.showUptime) {
                    Spacer(Modifier.width(6.dp))
                    Text(uptime.removePrefix(" · "), style = compact, color = onSurfaceVariant)
                }
                // Название сцены рядом со временем, а не в правом блоке: справа
                // живут числа, за которыми следят непрерывно, и вклинивать между
                // ними текст переменной длины значило бы дёргать их положение при
                // каждом переключении сцены.
                if (hud.showScene && !sceneName.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.Layers, null, Modifier.size(12.dp), tint = onSurfaceVariant)
                    Spacer(Modifier.width(3.dp))
                    Text(
                        sceneName,
                        style = compact,
                        color = onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp),
                    )
                }
                state.donationWidgetConnected?.let { connected ->
                    Spacer(Modifier.width(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(6.dp).background(
                                if (connected) Color(0xFF43A047) else Color(0xFFE53935),
                                CircleShape,
                            ),
                        )
                        Spacer(Modifier.width(4.dp))
                        Image(
                            painter = painterResource(R.drawable.ic_donationalerts_logo),
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hud.showNetworks && state.connections.isNotEmpty()) {
                    ChannelBars(state = state, totalBytes = totalBytes, modifier = Modifier.height(14.dp))
                    Spacer(Modifier.width(6.dp))
                }
                if (bitrateMbps > 0 && hud.showBitrate) {
                    Text("%.1f Mb".format(java.util.Locale.US, bitrateMbps), style = compact, color = if (bitrateWarn) warn else onSurfaceVariant)
                }
                if (state.connections.any { it.rtt > 0 }) {
                    Spacer(Modifier.width(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Schedule, null, Modifier.size(12.dp), tint = onSurfaceVariant)
                        Spacer(Modifier.width(2.dp))
                        Text("${state.connections.first { it.rtt > 0 }.rtt}ms", style = compact, color = onSurfaceVariant)
                    }
                }
                if (hud.showThermal) {
                    Spacer(Modifier.width(8.dp))
                    val tColor = if (thermalWarn) warn else tertiary
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Thermostat, null, Modifier.size(13.dp), tint = tColor)
                        Spacer(Modifier.width(2.dp))
                        Text(thermalText(telemetry), style = compact, color = tColor, fontWeight = if (thermalWarn) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                if (telemetry.batteryPct >= 0 && hud.showBattery) {
                    Spacer(Modifier.width(8.dp))
                    val bColor = if (batteryWarn) warn else tertiary
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(batteryIcon(telemetry), null, Modifier.size(13.dp), tint = bColor)
                        Spacer(Modifier.width(2.dp))
                        Text("${telemetry.batteryPct}%", style = compact, color = bColor, fontWeight = if (batteryWarn) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                if (hud.showVersion) {
                    Spacer(Modifier.width(8.dp))
                    Text("v$versionName", style = compact, color = onSurfaceVariant)
                }
                Spacer(Modifier.width(4.dp))
                Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, Modifier.size(14.dp), tint = onSurfaceVariant)
            }
        }

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = border, thickness = 0.5.dp)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.stats_connections).uppercase(), style = compact, color = onSurfaceVariant)
                Text(
                    stringResource(R.string.hud_total_mbps).format(java.util.Locale.US, bitrateMbps),
                    style = compact,
                    color = tertiary,
                )
            }
            Spacer(Modifier.height(6.dp))
            state.connections.forEach { conn ->
                val share = if (totalBytes > 0) (conn.bytesSent * 100 / totalBytes).toInt() else 0
                val chanMbps = bitrateMbps * share / 100.0
                val color = channelColor(conn.type)
                Column(Modifier.padding(vertical = 3.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(channelIcon(conn.type), null, Modifier.size(13.dp), tint = color)
                            Spacer(Modifier.width(5.dp))
                            Text(conn.type.replaceFirstChar { it.uppercase() }, style = compact, color = onSurface)
                        }
                        Text("%.1f Mbps · %d%%".format(java.util.Locale.US, chanMbps, share), style = compact, color = onSurfaceVariant)
                    }
                    Spacer(Modifier.height(3.dp))
                    LinearProgressIndicator(
                        progress = { share / 100f },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                        color = color,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = border, thickness = 0.5.dp)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCell("RTT", firstRtt(state), false)
                MetricCell(stringResource(R.string.hud_drops), "%.1f%%".format(java.util.Locale.US, dropPct), dropPct > 2f)
                MetricCell(
                    stringResource(R.string.hud_battery),
                    if (telemetry.batteryPct >= 0) "${telemetry.batteryPct}%" else "—",
                    batteryWarn,
                )
                MetricCell(stringResource(R.string.hud_thermal), thermalText(telemetry), thermalWarn)
            }
        }
    }
}

@Composable
private fun ChannelBars(
    state: app.brix.streaming.StreamState,
    totalBytes: Long,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        state.connections.forEach { conn ->
            val share = if (totalBytes > 0) (conn.bytesSent * 100 / totalBytes).toInt() else 0
            val h = (4 + (share.coerceIn(0, 100) * 10 / 100)).dp
            Box(Modifier.width(3.dp).height(h).background(channelColor(conn.type), RoundedCornerShape(1.dp)))
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, warn: Boolean) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

private fun batteryIcon(t: TelemetryView): ImageVector = when {
    t.batteryCharging -> Icons.Filled.Bolt
    t.batteryPct <= 15 -> Icons.Filled.BatteryAlert
    else -> Icons.Filled.BatteryStd
}

@Composable
private fun channelColor(type: String): Color = when (type.lowercase()) {
    "wifi" -> MaterialTheme.colorScheme.primary
    "cellular" -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.tertiary
}

private fun channelIcon(type: String): ImageVector = when (type.lowercase()) {
    "wifi" -> Icons.Filled.Wifi
    "cellular" -> Icons.Filled.SignalCellularAlt
    else -> Icons.Filled.Lan
}

@Composable
private fun thermalText(t: TelemetryView): String =
    if (t.batteryTempC != null) "%.0f°".format(java.util.Locale.US, t.batteryTempC)
    else if (t.thermalLabelRes != 0) stringResource(t.thermalLabelRes)
    else "—"

/** Real StreamState -> mascot expression. DONATION isn't wired here yet — it
 *  needs a transient "alert just fired" signal from the donation-overlay
 *  pipeline (OverlayController/OverlayJsBridge), which is a separate, later
 *  bit of plumbing; the state and its animation are ready for it. */
private fun mascotStateFor(state: app.brix.streaming.StreamState, thermalLevel: Int): MascotState = when {
    thermalLevel >= android.os.PowerManager.THERMAL_STATUS_SEVERE -> MascotState.OVERHEAT
    state.status == StreamStatus.Connecting -> MascotState.RECONNECTING
    state.status == StreamStatus.Connected && state.micMuted -> MascotState.MUTED
    state.status == StreamStatus.Connected -> MascotState.LIVE
    state.status == StreamStatus.Idle -> MascotState.IDLE
    else -> MascotState.OFFLINE // Failed, Rejected, Disconnected
}

private fun firstRtt(state: app.brix.streaming.StreamState): String {
    val rtt = state.connections.firstOrNull { it.rtt > 0 }?.rtt
    return if (rtt != null) "${rtt}ms" else "—"
}

private data class TelemetryView(
    val batteryPct: Int,
    val batteryCharging: Boolean,
    val thermalLevel: Int,
    val thermalLabelRes: Int,
    val batteryTempC: Float? = null,
)

@Composable
private fun rememberTelemetry(): TelemetryView {
    val context = androidx.compose.ui.platform.LocalContext.current
    var batteryPct by remember { mutableIntStateOf(-1) }
    var batteryCharging by remember { mutableStateOf(false) }
    var batteryTempC by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: android.content.Intent) {
                val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) batteryPct = level * 100 / scale
                batteryCharging = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ==
                    android.os.BatteryManager.BATTERY_STATUS_CHARGING
                val temp = intent.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (temp != Int.MIN_VALUE) batteryTempC = temp / 10f
            }
        }
        context.registerReceiver(
            receiver,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    var thermalLabelRes by remember { mutableIntStateOf(0) }
    var thermalLevel by remember { mutableIntStateOf(android.os.PowerManager.THERMAL_STATUS_NONE) }
    LaunchedEffect(Unit) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        app.brix.core.diagnostics.PeriodicTasks.register("thermal", 5000).use {
        while (true) {
            // Single binder read per tick, reused for both the label and the
            // level — a second currentThermalStatus call used to run inline
            // in the composable body itself, i.e. on every recomposition.
            val status = pm.currentThermalStatus
            thermalLevel = status
            thermalLabelRes = when (status) {
                android.os.PowerManager.THERMAL_STATUS_NONE,
                android.os.PowerManager.THERMAL_STATUS_LIGHT -> R.string.thermal_ok
                android.os.PowerManager.THERMAL_STATUS_MODERATE -> R.string.thermal_warm
                android.os.PowerManager.THERMAL_STATUS_SEVERE -> R.string.thermal_hot
                else -> R.string.thermal_critical
            }
            kotlinx.coroutines.delay(5000)
        }
        }
    }
    return TelemetryView(batteryPct, batteryCharging, thermalLevel, thermalLabelRes, batteryTempC)
}


@Composable
private fun ReconnectOverlay(state: app.brix.streaming.StreamState, onReconnect: () -> Unit) {
    val status = state.status
    if (status != StreamStatus.Connecting &&
        status != StreamStatus.Rejected &&
        status != StreamStatus.Failed &&
        status != StreamStatus.Disconnected
    ) {
        return
    }
    val modifier = if (status == StreamStatus.Connecting) {
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
    } else {
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable { onReconnect() }
    }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (status == StreamStatus.Connecting) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.status_connecting),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                val msg = when (status) {
                    StreamStatus.Rejected ->
                        stringResource(R.string.status_rejected) + (state.message?.let { ": $it" } ?: "")
                    StreamStatus.Failed ->
                        stringResource(R.string.status_failed) + ": " + (state.error?.message ?: state.message ?: "unknown")
                    StreamStatus.Disconnected ->
                        stringResource(R.string.status_disconnected)
                    else -> ""
                }
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.overlay_tap_reconnect),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun LensBar(
    lenses: List<RearLens>,
    selectedLensId: String?,
    onSelect: (RearLens?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        // Rear lens zoom factors (0.5x / 1x / 3x), Moblin-style.
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            lenses.forEach { lens ->
                LensPill(
                    label = lens.label,
                    selected = selectedLensId == lens.id,
                    onClick = { onSelect(lens) },
                )
            }
        }
        // Rear / Front switch below the lens row.
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LensPill(
                label = stringResource(R.string.btn_rear),
                selected = selectedLensId != null,
                onClick = { lenses.firstOrNull()?.let { onSelect(it) } },
            )
            LensPill(
                label = stringResource(R.string.btn_front),
                selected = selectedLensId == null,
                onClick = { onSelect(null) },
            )
        }
    }
}

@Composable
private fun LensPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun LockOverlay(onUnlock: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable { onUnlock() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                modifier = Modifier.size(40.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.overlay_unlock_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
            )
        }
    }
}



@Composable
private fun SettingsPanelContent(
    viewModel: SettingsViewModel,
    route: SettingsRoute,
    category: SettingsCategory,
    onRoute: (SettingsRoute) -> Unit,
    onCategoryChange: (SettingsCategory) -> Unit,
    onClose: () -> Unit,
    onPlaceOverlay: (String) -> Unit,
    onPlaceWidget: (String) -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        CategoryRail(
            selected = category,
            onSelect = {
                onCategoryChange(it)
                onRoute(SettingsRoute.Menu)
            },
            onClose = onClose,
        )
        when (route) {
            SettingsRoute.Menu -> CategoryHome(
                category = category,
                viewModel = viewModel,
                onRoute = onRoute,
                modifier = Modifier.weight(1f),
            )
            SettingsRoute.StreamProfiles -> StreamProfilesScreen(
                viewModel = viewModel,
                onEdit = { id -> onRoute(SettingsRoute.StreamProfileEdit(id)) },
                onBack = { onRoute(SettingsRoute.Menu) },
                modifier = Modifier.weight(1f),
            )
            SettingsRoute.ServerProfiles -> ServerProfilesScreen(
                viewModel = viewModel,
                onEdit = { id -> onRoute(SettingsRoute.ServerProfileEdit(id)) },
                onBack = { onRoute(SettingsRoute.Menu) },
                modifier = Modifier.weight(1f),
            )
            SettingsRoute.Scenes -> ScenesScreen(
                viewModel = viewModel,
                onEdit = { id -> onRoute(SettingsRoute.SceneEdit(id)) },
                onBack = { onRoute(SettingsRoute.Menu) },
                modifier = Modifier.weight(1f),
            )
            is SettingsRoute.SceneEdit -> SceneEditScreen(
                viewModel = viewModel,
                sceneId = route.sceneId,
                onBack = { onRoute(SettingsRoute.Scenes) },
                modifier = Modifier.weight(1f),
            )
            SettingsRoute.ChannelPriorities -> ChannelPrioritiesScreen(
                viewModel = viewModel,
                onBack = { onRoute(SettingsRoute.Menu) },
                modifier = Modifier.weight(1f),
            )
            SettingsRoute.QuickButtons -> QuickButtonsConfigScreen(
                viewModel = viewModel,
                onBack = { onRoute(SettingsRoute.Menu) },
                modifier = Modifier.weight(1f),
            )
            SettingsRoute.Camera -> CameraSettingsScreen(
                viewModel = viewModel,
                modifier = Modifier.weight(1f),
            )
            SettingsRoute.Audio -> AudioSettingsScreen(
                viewModel = viewModel,
                modifier = Modifier.weight(1f),
            )
            SettingsRoute.Appearance -> AppearanceSettingsScreen(
                viewModel = viewModel,
                onRoute = onRoute,
                modifier = Modifier.weight(1f),
            )
            SettingsRoute.Hud -> HudSettingsScreen(
                viewModel = viewModel,
                onBack = { onRoute(SettingsRoute.Menu) },
                modifier = Modifier.weight(1f),
            )
            SettingsRoute.Language -> LanguageScreen(
                viewModel = viewModel,
                onBack = { onRoute(SettingsRoute.Appearance) },
                modifier = Modifier.weight(1f),
            )
            SettingsRoute.Advanced -> AdvancedSettingsScreen(
                viewModel = viewModel,
                modifier = Modifier.weight(1f),
            )
            SettingsRoute.About -> AboutScreen(
                onBack = { onRoute(SettingsRoute.Menu) },
                modifier = Modifier.weight(1f),
            )
            SettingsRoute.Diagnostics -> DiagnosticsScreen(
                viewModel = viewModel,
                onBack = { onRoute(SettingsRoute.Menu) },
                modifier = Modifier.weight(1f),
            )
            SettingsRoute.Overlay -> OverlaySettingsScreen(
                viewModel = viewModel,
                onEdit = { id -> onRoute(SettingsRoute.OverlayEdit(id)) },
                onPlaceOverlay = onPlaceOverlay,
                onBack = { onRoute(SettingsRoute.Menu) },
                modifier = Modifier.weight(1f),
            )
            is SettingsRoute.OverlayEdit -> OverlayEditScreen(
                viewModel = viewModel,
                overlayId = route.overlayId,
                onNext = { id -> onPlaceOverlay(id) },
                onBack = { onRoute(SettingsRoute.Overlay) },
                modifier = Modifier.weight(1f),
            )
            SettingsRoute.BrowserWidgets -> BrowserWidgetSettingsScreen(
                viewModel = viewModel,
                onEdit = { id -> onRoute(SettingsRoute.BrowserWidgetEdit(id)) },
                onPlaceWidget = onPlaceWidget,
                onBack = { onRoute(SettingsRoute.Menu) },
                modifier = Modifier.weight(1f),
            )
            is SettingsRoute.BrowserWidgetEdit -> BrowserWidgetEditScreen(
                viewModel = viewModel,
                widgetId = route.widgetId,
                onNext = { id -> onPlaceWidget(id) },
                onBack = { onRoute(SettingsRoute.BrowserWidgets) },
                modifier = Modifier.weight(1f),
            )
            is SettingsRoute.StreamProfileEdit -> StreamProfileEditScreen(
                viewModel = viewModel,
                profileId = route.profileId,
                onBack = { onRoute(SettingsRoute.StreamProfiles) },
                modifier = Modifier.weight(1f),
            )
            is SettingsRoute.ServerProfileEdit -> ServerProfileEditScreen(
                viewModel = viewModel,
                profileId = route.profileId,
                onBack = { onRoute(SettingsRoute.ServerProfiles) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Places [content] at [posX]/[posY]/[widthFraction]/[heightFraction] fractions
 * of the parent — same anchor/size convention as [OverlayConfig], and the same
 * geometry [OverlayTuner] draws its drag box with, so what a streamer sees
 * while dragging matches exactly where the panel ends up.
 */
@Composable
private fun FractionalBox(
    posX: Float,
    posY: Float,
    widthFraction: Float,
    heightFraction: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var container by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { container = it },
    ) {
        val boxW = (container.width * widthFraction).toInt().coerceAtLeast(20)
        val boxH = (container.height * heightFraction).toInt().coerceAtLeast(20)
        val boxX = (container.width * posX - boxW / 2).toInt()
        val boxY = (container.height * posY - boxH / 2).toInt()
        Box(
            modifier = Modifier
                .offset { IntOffset(boxX, boxY) }
                // Высота — потолок, не фиксированный размер: свёрнутое
                // содержимое (ChatPanel, header-only) должно занимать только
                // свою собственную высоту, а не всю отведённую область с
                // пустотой под ней.
                .width(boxW.dp)
                .heightIn(max = boxH.dp),
        ) { content() }
    }
}

/**
 * Placement box for one overlay at [posX]/[posY] fractions of the parent: drag
 * it to move (updates posX/posY), drag the bottom-right corner to resize
 * (updates width/height). Fractions are reported back through [onMove] live.
 * Always shown with a visible border + translucent fill so the placement
 * area is actually legible — previously this box had no background/border at
 * all, so entering placement mode looked like nothing happened.
 */
@Composable
private fun OverlayTuner(
    posX: Float,
    posY: Float,
    widthFraction: Float,
    heightFraction: Float,
    onMove: (Float, Float, Float, Float) -> Unit,
    // Fired on every drag delta (not just onDragEnd like onMove) — for a
    // permanently-visible live overlay (a browser widget snapshot), the
    // rendered GL transform must track the finger in real time, otherwise
    // the box moves smoothly while the content stays put until release and
    // visibly "detaches" from the frame. Cheap (just a transform update, no
    // settings/disk write) so it's safe to call on every delta. Donations
    // have no continuously-visible content during placement, so they leave
    // this at the no-op default.
    onLiveMove: (Float, Float, Float, Float) -> Unit = { _, _, _, _ -> },
) {
    var container by remember { mutableStateOf(IntSize.Zero) }
    // Local, immediately-responsive drag state. pointerInput(Unit) below never
    // restarts across recompositions, so its gesture closure only ever sees
    // the posX/posY/widthFraction/heightFraction it captured on the very
    // first frame. Calling onMove (which persists to settings, round-tripping
    // through the ViewModel/StateFlow/disk) on every single drag delta used
    // to compute each new position from that same frozen starting point
    // instead of the previous delta's result — the box barely moved and
    // snapped back. Tracking position/size as local state that's updated
    // synchronously within the gesture (and re-synced from the real value via
    // `remember(posX)` etc. once it changes) fixes both the snap-back and the
    // per-pixel disk writes: onMove now fires once, at gesture end.
    var localPosX by remember(posX) { mutableStateOf(posX) }
    var localPosY by remember(posY) { mutableStateOf(posY) }
    var localWidth by remember(widthFraction) { mutableStateOf(widthFraction) }
    var localHeight by remember(heightFraction) { mutableStateOf(heightFraction) }

    val boxW = (container.width * localWidth).toInt().coerceAtLeast(20)
    val boxH = (container.height * localHeight).toInt().coerceAtLeast(20)
    val boxX = (container.width * localPosX - boxW / 2).toInt()
    val boxY = (container.height * localPosY - boxH / 2).toInt()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { container = it },
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(boxX, boxY) }
                .size(boxW.dp, boxH.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { onMove(localPosX, localPosY, localWidth, localHeight) },
                    ) { change, dragAmount ->
                        change.consume()
                        val cw = container.width.coerceAtLeast(1)
                        val ch = container.height.coerceAtLeast(1)
                        localPosX = (localPosX + dragAmount.x / cw).coerceIn(0.05f, 0.95f)
                        localPosY = (localPosY + dragAmount.y / ch).coerceIn(0.05f, 0.95f)
                        onLiveMove(localPosX, localPosY, localWidth, localHeight)
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(20.dp, 20.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { onMove(localPosX, localPosY, localWidth, localHeight) },
                        ) { change, dragAmount ->
                            change.consume()
                            val cw = container.width.coerceAtLeast(1)
                            val ch = container.height.coerceAtLeast(1)
                            localWidth = (localWidth + dragAmount.x / cw).coerceIn(0.05f, 1f)
                            localHeight = (localHeight + dragAmount.y / ch).coerceIn(0.05f, 1f)
                            // localWidth/localHeight are fractions of DIFFERENT
                            // base dimensions (frame width vs. height, usually
                            // 16:9) — equal fractions never look square
                            // on-screen, and a pure-diagonal drag can't reach
                            // one either (both grow by the same pixel delta, so
                            // their pixel difference is invariant along a
                            // diagonal). Snap height once the on-screen box is
                            // close to square so it's actually reachable.
                            val boxWPx = cw * localWidth
                            val boxHPx = ch * localHeight
                            if (boxWPx > 0f && kotlin.math.abs(boxWPx - boxHPx) / boxWPx < 0.04f) {
                                localHeight = (localWidth * cw / ch).coerceIn(0.05f, 1f)
                            }
                            onLiveMove(localPosX, localPosY, localWidth, localHeight)
                        }
                    },
            )
        }
    }
}