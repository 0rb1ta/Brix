package app.brix

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.brix.core.SettingsDeepLink
import app.brix.core.SettingsStore
import app.brix.core.normalizeLanguageTag
import app.brix.ui.R
import app.brix.ui.BrixIntro
import app.brix.ui.OnboardingScreen
import app.brix.ui.SettingsViewModel
import app.brix.ui.StreamScreen
import app.brix.ui.theme.BrixTheme
import java.util.Locale

/** Wraps [this] with a Configuration pinned to [tag], or returns [this]
 *  unchanged for an empty tag (system locale, resource qualifiers already
 *  handle it with no extra code). */
private fun Context.withLocale(tag: String): Context {
    if (tag.isEmpty()) return this
    val locale = Locale.forLanguageTag(tag)
    Locale.setDefault(locale)
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    return createConfigurationContext(config)
}

class MainActivity : ComponentActivity() {

    companion object {
        // Set right before we call recreate() for a language change (see
        // AppRoot below) so the attachBaseContext() that recreate() triggers
        // reuses the value we already have in memory instead of a redundant
        // synchronous disk read + JSON parse on the main thread for what the
        // user experiences as a simple settings toggle. Null on a genuine
        // cold start (fresh process), where a disk read is unavoidable
        // anyway — nothing has loaded yet at this point in the lifecycle.
        @Volatile
        internal var cachedLanguage: String? = null

        private const val KEY_LANDSCAPE = "brix.landscape"
    }

    // Runs before onCreate/Compose — settings aren't loaded into a ViewModel
    // yet at this point, so read just the language field directly off disk.
    // A small synchronous read of a few-KB JSON file at this exact lifecycle
    // point is the standard pattern for per-app locale override.
    override fun attachBaseContext(newBase: Context) {
        val language = cachedLanguage ?: runCatching {
            SettingsStore.create(newBase).load().getOrNull()?.appearance?.language
        }.getOrNull() ?: ""
        cachedLanguage = language
        super.attachBaseContext(newBase.withLocale(normalizeLanguageTag(language)))
    }

    // Holds the most recent brix://settings deep link (see
    // core/SettingsDeepLink.kt) so Compose can show an import-confirmation
    // dialog — set from both onCreate (cold start via the link) and
    // onNewIntent (already running, singleTask routes here instead of
    // spawning a second Activity on top of a live camera/stream session).
    private val pendingDeepLink = mutableStateOf<String?>(null)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        pendingDeepLink.value = intent?.dataString
        if (savedInstanceState?.getBoolean(KEY_LANDSCAPE) == true) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        setContent {
            val settingsViewModel: SettingsViewModel = viewModel()
            val settings by settingsViewModel.settings.collectAsState()
            BrixTheme(appearance = settings.appearance) {
                var introDone by rememberSaveable { mutableStateOf(false) }
                val settingsLoaded by settingsViewModel.settingsLoaded.collectAsState()
                // Настоящие настройки, а не заглушка до чтения диска: по заглушке
                // (onboardingDone = false) мастер выскакивал бы у всех.
                val real = settingsLoaded && !settingsViewModel.isPlaceholder(settings)
                val needWizard = real && settings.onboardingDone == false
                val livePhase = introDone && real && !needWizard

                // Ориентация следует за фазой: заставка и мастер портретные,
                // эфир альбомный (владелец, 16.09). Экран эфира создаётся только
                // когда окно УЖЕ альбомное — камера стартует один раз и в своей
                // ориентации. 15.09 заставка шла поверх живой камеры, и поворот
                // под ней трижды ломал превью (коммит 0f190e1); цена нового
                // порядка — секунда пустого экрана, пока камера поднимается.
                LaunchedEffect(livePhase) {
                    landscape = livePhase
                    requestedOrientation = if (livePhase) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                }
                LanguageRecreateEffect(settings = settings, settingsLoaded = settingsLoaded)
                // «Окно уже альбомное» — по фактическому размеру, а не по
                // LocalConfiguration: 16.09 окно повернулось, а orientation в
                // композиции так и осталась портретной — экран эфира не
                // создался, светился пустой фон. Конфигурация у нас подменена
                // ради языка (attachBaseContext), размер же врать не может.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    if (livePhase && maxWidth > maxHeight) {
                        StreamScreen(settings = settings, settingsViewModel = settingsViewModel)
                        DeepLinkImportDialog(pendingDeepLink, settingsViewModel::importSharedConfig)
                    }
                }
                if (introDone && needWizard) {
                    OnboardingScreen(viewModel = settingsViewModel)
                    // Ссылка brix:// или moblin:// на свежей установке — это и
                    // есть ответ на мастер: берём её целиком и мастер закрываем.
                    DeepLinkImportDialog(pendingDeepLink, settingsViewModel::completeOnboardingWithImport)
                }
                // rememberSaveable — чтобы смена языка или поворот, которые
                // пересоздают Activity, не проигрывали заставку заново.
                if (!introDone) {
                    BrixIntro(onFinished = { introDone = true })
                }
            }
        }
    }

    // Пересоздание (смена языка) открывает окно по манифесту, то есть портретным.
    // Если до него был эфир — сразу просим альбомное, без лишнего переворота.
    private var landscape = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LANDSCAPE, landscape)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLink.value = intent.dataString
    }

    // Camera-preview freeze diagnostics: several rounds of guessing at the
    // trigger (pinch zoom, lens taps, double-tap) failed to hold up, and a
    // recent capture showed the window losing visibility (visible=false)
    // with no explanation — we never logged our own lifecycle at all, so
    // there was no way to tell a real background/foreground transition
    // (onPause called) from something merely drawing on top while still
    // resumed (onWindowFocusChanged(false) without onPause).
    override fun onPause() {
        super.onPause()
        Log.w("BrixLifecycle", "onPause")
    }

    override fun onResume() {
        super.onResume()
        Log.w("BrixLifecycle", "onResume")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.w("BrixLifecycle", "onWindowFocusChanged hasFocus=$hasFocus")
    }
}

@Composable
private fun LanguageRecreateEffect(
    settings: app.brix.core.AppSettings,
    settingsLoaded: Boolean,
) {
    // attachBaseContext() only runs on (re)creation — picking a new language
    // in Settings needs an explicit recreate() to actually re-read it.
    // rememberSaveable survives that recreate() (SavedStateRegistry), so this
    // only fires once per real language change, not on every recreate.
    //
    // settingsLoaded gates this: SettingsViewModel.settings starts at
    // defaultAppSettings() (language "") and only reaches the real persisted
    // value asynchronously. Without the gate, that placeholder -> real-value
    // transition looks exactly like a language change on every cold start
    // for anyone not on "Auto" — triggering a needless recreate() (camera/
    // permissions/preview all restart) even though attachBaseContext()
    // already applied the correct locale before Compose ever ran. It also
    // double-fires after a real recreate() restores appliedLanguage from
    // SavedStateRegistry before the fresh ViewModel's own load completes.
    val context = LocalContext.current
    var appliedLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(settingsLoaded, settings.appearance.language) {
        if (!settingsLoaded) return@LaunchedEffect
        val current = appliedLanguage
        if (current == null) {
            // First real settings observed this process — already applied
            // by attachBaseContext(), just record the baseline.
            appliedLanguage = settings.appearance.language
        } else if (normalizeLanguageTag(settings.appearance.language) != normalizeLanguageTag(current)) {
            // Compare NORMALIZED tags, the same form attachBaseContext() and
            // LanguageScreen use. On raw strings, an upgrading user whose
            // settings still hold a legacy enum value ("AUTO"/"RU"/"EN") got a
            // full Activity rebuild — camera, permissions and preview — the
            // first time they re-picked the row that was already ticked, for a
            // locale that did not actually change.
            appliedLanguage = settings.appearance.language
            MainActivity.cachedLanguage = settings.appearance.language
            (context as? Activity)?.recreate()
        }
    }
}

/** Схема и хост адреса сервера — без пути, запроса и учётных данных, где
 *  лежат ключи. Для показа в диалоге импорта. */
private fun serverHostOf(url: String): String {
    val scheme = url.substringBefore("://", "")
    val host = url.substringAfter("://")
        .takeWhile { it != '/' && it != '?' && it != '#' }
        .substringAfterLast('@')
    return if (scheme.isEmpty()) host else "$scheme://$host"
}

/** Shows an import-confirmation dialog for an incoming `brix://`/`moblin://`
 *  link — decoded via [SettingsDeepLink], never applied silently. Clears
 *  [pendingLink] on both accept and dismiss so re-showing the same Activity
 *  (e.g. a config change recreating it) doesn't re-prompt for a link already
 *  handled. */
@Composable
private fun DeepLinkImportDialog(
    pendingLink: androidx.compose.runtime.MutableState<String?>,
    onImport: (app.brix.core.SharedConfig) -> Unit,
) {
    val link = pendingLink.value ?: return
    val config = SettingsDeepLink.decodeAny(link)
    if (config == null) {
        // Саму ссылку не пишем: в ней бывают ключи трансляции (аудит 23.09).
        Log.w("BrixDeepLink", "unrecognized or malformed link, len=${link.length}")
        pendingLink.value = null
        return
    }
    AlertDialog(
        onDismissRequest = { pendingLink.value = null },
        title = { Text(stringResource(R.string.deep_link_import_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    stringResource(
                        R.string.deep_link_import_message,
                        config.streamProfiles.size,
                        config.serverProfiles.size,
                    ),
                )
                // Куда пойдёт эфир — до нажатия «Импортировать». Раньше решение
                // принималось вслепую по счётчикам, а ссылку с подставным
                // сервером легко прислать под видом «вот мой конфиг» (аудит
                // 23.09). Показываем схему и хост, без пути и ключа.
                config.serverProfiles.forEach { server ->
                    Text(
                        text = "${server.name} — ${serverHostOf(server.baseUrl)}",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onImport(config)
                pendingLink.value = null
            }) {
                Text(stringResource(R.string.btn_import))
            }
        },
        dismissButton = {
            TextButton(onClick = { pendingLink.value = null }) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
    )
}
