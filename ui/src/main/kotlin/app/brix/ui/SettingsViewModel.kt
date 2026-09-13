package app.brix.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.brix.core.ConnectionPriority
import app.brix.core.AppSettings
import app.brix.core.Ids
import app.brix.core.Scene
import app.brix.core.ServerProfile
import app.brix.core.ServerType
import app.brix.core.SettingsStore
import app.brix.core.StreamProfile
import app.brix.core.defaultConnectionPriorities
import app.brix.core.QuickButtonConfig
import app.brix.core.AppearanceSettings
import app.brix.core.AdvancedSettings
import app.brix.core.AudioSettings
import app.brix.core.HudConfig
import app.brix.core.CameraDefaults
import app.brix.core.OverlayConfig
import app.brix.core.BrowserWidgetConfig
import app.brix.core.ChatSettings
import app.brix.core.MoblinkSettings
import app.brix.core.StreamPreset
import app.brix.core.defaultAppSettings
import app.brix.core.withStreamProfilesEnsured
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SettingsStore.create(application)

    // Start from defaults so the first composition is instant, then load the
    // persisted settings off the main thread (H13: a synchronous disk + JSON
    // read in the ViewModel constructor blocked the UI on cold start).
    private val _settings = MutableStateFlow(defaultAppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    // Distinguishes "still the placeholder defaultAppSettings()" from a real
    // (possibly identical-looking) loaded value — MainActivity's language-
    // change recreate() effect needs this to avoid treating the placeholder
    // -> real-settings transition on every cold start as a language change.
    private val _settingsLoaded = MutableStateFlow(false)
    val settingsLoaded: StateFlow<Boolean> = _settingsLoaded.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _settings.value = loadSettings()
            _settingsLoaded.value = true
        }
    }

    private fun loadSettings(): AppSettings {
        val result = store.load()
        result.exceptionOrNull()?.let { e ->
            Log.e("SettingsViewModel", "Settings file corrupt, resetting to defaults", e)
        }
        // Пустой список профилей чинится подстановкой одного профиля, а не
        // сбросом всего файла: серверы, оверлеи и раскладка кнопок к нему
        // отношения не имеют и терять их незачем.
        return result.getOrNull()?.withStreamProfilesEnsured() ?: defaultAppSettings()
    }

    // Single-threaded save queue: two rapid mutations must hit the disk in
    // mutation order, otherwise an older snapshot can overwrite a newer one
    //. The conflated state is read INSIDE the queued coroutine.
    private val saveDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Persist to disk on a background thread — never blocks the main loop. */
    private fun persist() {
        viewModelScope.launch(saveDispatcher) {
            store.save(_settings.value)
        }
    }

    private var debouncedPersistJob: Job? = null

    /**
     * Отложенная запись для действий, которые пользователь делает подряд.
     *
     * `store.save` — это сериализация всего файла настроек, `fsync` и копия в
     * `.bak`. На тумблере в списке серверов каждый тап запускал такую запись, и
     * при быстром переключении они выстраивались в очередь на однопоточном
     * диспетчере: интерфейс отвечал, а состояние догоняло рывками. Память
     * обновляем сразу, на диск пишем один раз, когда человек закончил.
     */
    private fun persistDebounced(delayMs: Long = 400) {
        debouncedPersistJob?.cancel()
        debouncedPersistJob = viewModelScope.launch(saveDispatcher) {
            delay(delayMs)
            store.save(_settings.value)
        }
    }

    /**
     * Сделать сервер активным — остальные выключаются.
     *
     * Ровно один активный, потому что так работает само приложение: экран эфира
     * берёт `enabledServers.firstOrNull()`, а переключателя серверов там нет.
     * Включить несколько было можно, но вещал всё равно первый по списку —
     * остальные оставались мёртвым грузом, а флажок обещал не то, что делал.
     * Отсюда и радиокнопка вместо тумблера: она не даёт выразить состояние,
     * которого у приложения нет.
     */
    fun setActiveServer(id: String) {
        _settings.value = _settings.value.copy(
            serverProfiles = _settings.value.serverProfiles.map { it.copy(enabled = it.id == id) },
        )
        persistDebounced()
    }

    /** Writes whatever is currently in [_settings] to disk right now. For
     *  fields updated live with `commit = false` (Moblink text fields,
     *  connection-priority sliders) — the in-memory state (and anything
     *  reading it, e.g. Moblink's live port/weight) updates on every
     *  keystroke/drag step, but the full serialize+fsync+`.bak` copy only
     *  needs to happen once, when the user is done editing (H: this used to
     *  fire on every keystroke and every slider step). */
    fun flush() {
        persist()
    }

    fun saveStreamProfile(profile: StreamProfile) {
        _settings.value = _settings.value.copy(
            streamProfiles = _settings.value.streamProfiles
                .filterNot { it.id == profile.id } + profile,
        )
        persist()
    }

    fun deleteStreamProfile(id: String) {
        val current = _settings.value
        val remaining = current.streamProfiles.filterNot { it.id == id }
        _settings.value = current.copy(
            streamProfiles = remaining,
            selectedStreamProfileId = if (current.selectedStreamProfileId == id) {
                remaining.firstOrNull()?.id
            } else {
                current.selectedStreamProfileId
            },
        )
        persist()
    }

    fun selectStreamProfile(id: String) {
        _settings.value = _settings.value.copy(selectedStreamProfileId = id)
        persist()
    }

    fun saveServerProfile(profile: ServerProfile) {
        // Порядок сохраняем: раньше правка вырезала профиль и дописывала в
        // конец, то есть любое сохранение переставляло его в списке. С
        // тумблером прямо в списке это стало видно сразу — строка уезжала
        // вниз под пальцем.
        val current = _settings.value.serverProfiles
        val updated = if (current.any { it.id == profile.id }) {
            current.map { if (it.id == profile.id) profile else it }
        } else {
            current + profile
        }
        _settings.value = _settings.value.copy(serverProfiles = updated)
        persist()
    }

    fun deleteServerProfile(id: String) {
        _settings.value = _settings.value.copy(
            serverProfiles = _settings.value.serverProfiles.filterNot { it.id == id },
        )
        persist()
    }

    /** Adds profiles from a `brix://`/`moblin://` deep link (see
     *  [app.brix.core.SettingsDeepLink]) — appends, never replaces existing
     *  profiles, since the whole point is combining configs from multiple
     *  devices, not letting one silently clobber the other. Quick-button
     *  layout, if present, DOES replace — it's a single, device-wide
     *  setting, not a list to merge into. */
    fun importSharedConfig(config: app.brix.core.SharedConfig) {
        val current = _settings.value
        _settings.value = current.copy(
            streamProfiles = current.streamProfiles + config.streamProfiles,
            serverProfiles = current.serverProfiles + config.serverProfiles,
            quickButtons = config.quickButtons ?: current.quickButtons,
            selectedStreamProfileId = current.selectedStreamProfileId
                ?: config.streamProfiles.firstOrNull()?.id,
        )
        persist()
    }

    fun updateQuickButtons(config: QuickButtonConfig) {
        _settings.value = _settings.value.copy(quickButtons = config)
        persist()
    }

    fun updateCameraDefaults(camera: CameraDefaults) {
        _settings.value = _settings.value.copy(cameraDefaults = camera)
        persist()
    }

    fun updateAppearance(appearance: AppearanceSettings) {
        _settings.value = _settings.value.copy(appearance = appearance)
        persist()
    }

    fun updateHud(hud: HudConfig) {
        _settings.value = _settings.value.copy(hud = hud)
        persist()
    }

    fun updateAdvanced(advanced: AdvancedSettings) {
        _settings.value = _settings.value.copy(advanced = advanced)
        persist()
    }

    fun saveOverlay(config: OverlayConfig) {
        _settings.value = _settings.value.copy(
            overlays = _settings.value.overlays.filterNot { it.id == config.id } + config,
        )
        persist()
    }

    fun deleteOverlay(id: String) {
        _settings.value = _settings.value.copy(
            overlays = _settings.value.overlays.filterNot { it.id == id },
        )
        persist()
    }

    /** [commit] = false for a live keystroke update (Moblink port/password/
     *  weight fields): the value is applied to in-memory state right away —
     *  MoblinkServer picks it up on the next start/reconfigure — but not
     *  persisted. Callers flush() once editing is done (focus lost / screen
     *  left). */
    fun updateMoblink(settings: MoblinkSettings, commit: Boolean = true) {
        _settings.value = _settings.value.copy(moblink = settings)
        if (commit) persist()
    }

    fun updateChat(settings: ChatSettings, commit: Boolean = true) {
        _settings.value = _settings.value.copy(chat = settings)
        if (commit) persist()
    }

    fun saveCustomPreset(preset: StreamPreset) {
        _settings.value = _settings.value.copy(
            customPresets = _settings.value.customPresets.filterNot { it.name == preset.name } + preset,
        )
        persist()
    }

    fun deleteCustomPreset(name: String) {
        _settings.value = _settings.value.copy(
            customPresets = _settings.value.customPresets.filterNot { it.name == name },
        )
        persist()
    }

    fun saveBrowserWidget(config: BrowserWidgetConfig) {
        _settings.value = _settings.value.copy(
            browserWidgets = _settings.value.browserWidgets.filterNot { it.id == config.id } + config,
        )
        persist()
    }

    fun deleteBrowserWidget(id: String) {
        _settings.value = _settings.value.copy(
            browserWidgets = _settings.value.browserWidgets.filterNot { it.id == id },
        )
        persist()
    }

    /** [commit] = false while a Slider is being dragged: state updates so the
     *  UI (share %, switch) reflects the new weight instantly, but the
     *  serialize+fsync+`.bak` write is deferred to onValueChangeFinished. */
    fun updateConnectionPriority(
        profileId: String,
        name: String,
        enabled: Boolean,
        weight: Int,
        commit: Boolean = true,
    ) {
        val current = _settings.value
        val updated = current.streamProfiles.map { profile ->
            if (profile.id != profileId) return@map profile
            val list = profile.srtConnectionPriorities.toMutableList()
            val idx = list.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            val entry = ConnectionPriority(name, enabled = enabled, weight = weight)
            if (idx >= 0) list[idx] = entry else list.add(entry)
            val defaultsPresent = list.any { it.name.equals("WIFI", true) } &&
                list.any { it.name.equals("CELLULAR", true) }
            profile.copy(
                srtConnectionPriorities = if (defaultsPresent) list
                else defaultConnectionPriorities() + list,
            )
        }
        _settings.value = current.copy(streamProfiles = updated)
        if (commit) persist()
    }

    /** [commit] = false, пока тянут ползунок усиления: память обновляется на
     *  каждый шаг, а полная запись файла с fsync — один раз, когда отпустили. */
    // --- Сцены ---
    //
    // Модель Scene и поля AppSettings.scenes/selectedSceneId лежали в коде с
    // самого начала и не читались НИКЕМ: четвёртый за сегодня случай мёртвой
    // модели. Здесь они наконец оживают.

    fun saveScene(scene: Scene) {
        val current = _settings.value.scenes
        val updated = if (current.any { it.id == scene.id }) {
            current.map { if (it.id == scene.id) scene else it }
        } else {
            current + scene
        }
        _settings.value = _settings.value.copy(
            scenes = updated,
            // Первая созданная сцена сразу становится активной: сцена, которую
            // никто не выбрал, ничего не делает, и человек решил бы, что не
            // работает.
            selectedSceneId = _settings.value.selectedSceneId ?: scene.id,
        )
        persist()
    }

    fun deleteScene(id: String) {
        val current = _settings.value
        val remaining = current.scenes.filterNot { it.id == id }
        _settings.value = current.copy(
            scenes = remaining,
            selectedSceneId = if (current.selectedSceneId == id) remaining.firstOrNull()?.id else current.selectedSceneId,
        )
        persist()
    }

    fun selectScene(id: String?) {
        _settings.value = _settings.value.copy(selectedSceneId = id)
        persist()
    }

    fun updateAudio(audio: AudioSettings, commit: Boolean = true) {
        _settings.value = _settings.value.copy(audio = audio)
        if (commit) persist()
    }
}

fun newStreamProfile(name: String): StreamProfile =
    StreamProfile(id = Ids.newId(), name = name)

fun newOverlay(url: String): OverlayConfig =
    OverlayConfig(id = Ids.newId(), url = url)

fun newBrowserWidget(url: String): BrowserWidgetConfig =
    BrowserWidgetConfig(id = Ids.newId(), url = url)

fun newServerProfile(name: String, url: String): ServerProfile =
    ServerProfile(
        id = Ids.newId(),
        name = name,
        type = if (url.startsWith("srtla://")) ServerType.SRTLA else ServerType.RTMP,
        url = url,
        baseUrl = url,
    )
