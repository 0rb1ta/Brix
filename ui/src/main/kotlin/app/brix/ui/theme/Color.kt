package app.brix.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Beam / Brix — Material 3 palette (design/brix-m3-color-palette.md)
// Dark-first; seed amber/gold #FFB300. `live` is a custom token outside the M3
// base: it is the ONLY color that never follows dynamic color (stays red so the
// LIVE/REC status never blends with error or red wallpapers).

// ---- Dark theme (default) ----
val DarkPrimary = Color(0xFFFFC257)
val DarkOnPrimary = Color(0xFF3E2E00)
val DarkPrimaryContainer = Color(0xFF5C4300)
val DarkOnPrimaryContainer = Color(0xFFFFDDA1)
val DarkSecondary = Color(0xFFD6C4A1)
val DarkOnSecondary = Color(0xFF392F16)
val DarkSecondaryContainer = Color(0xFF51452A)
val DarkOnSecondaryContainer = Color(0xFFF3E0BB)
val DarkTertiary = Color(0xFFA8CFB0)
val DarkOnTertiary = Color(0xFF123821)
val DarkTertiaryContainer = Color(0xFF2A4F34)
val DarkOnTertiaryContainer = Color(0xFFC4ECC9)
val DarkError = Color(0xFFFFB4A9)
val DarkOnError = Color(0xFF680003)
val DarkErrorContainer = Color(0xFF930006)
val DarkOnErrorContainer = Color(0xFFFFDAD4)
val DarkBackground = Color(0xFF1B1B1F)
val DarkOnBackground = Color(0xFFE4E2E6)
val DarkSurface = Color(0xFF1B1B1F)
val DarkOnSurface = Color(0xFFE4E2E6)
val DarkSurfaceVariant = Color(0xFF48464C)
val DarkOnSurfaceVariant = Color(0xFFC9C5D0)
val DarkOutline = Color(0xFF938F99)
val DarkOutlineVariant = Color(0xFF48464C)
val DarkSurfaceContainerLowest = Color(0xFF0F0F12)
val DarkSurfaceContainerLow = Color(0xFF232327)
val DarkSurfaceContainer = Color(0xFF28282C)
val DarkSurfaceContainerHigh = Color(0xFF333237)
val DarkSurfaceContainerHighest = Color(0xFF3E3D42)
val DarkInverseSurface = Color(0xFFE4E2E6)
val DarkInverseOnSurface = Color(0xFF303034)
val DarkInversePrimary = Color(0xFF7A5700)
val DarkScrim = Color(0xFF000000)

// ---- Light theme (secondary, System theme) ----
val LightPrimary = Color(0xFF7A5700)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFFFDDA1)
val LightOnPrimaryContainer = Color(0xFF5C4300)
val LightBackground = Color(0xFFFFFBFF)
val LightOnBackground = Color(0xFF1C1B1F)
val LightSurface = Color(0xFFFFFBFF)
val LightOnSurface = Color(0xFF1C1B1F)
val LightSurfaceVariant = Color(0xFFEDE1CF)
val LightOnSurfaceVariant = Color(0xFF1C1B1F)
val LightOutline = Color(0xFF7D7667)

// ---- Custom `live` token (NOT part of M3 base, always fixed red) ----
val LiveDark = Color(0xFFE53935)
val LiveLight = Color(0xFFD32F2F)

val LocalLiveColor = staticCompositionLocalOf { LiveDark }

@Composable
fun liveColor(): Color = LocalLiveColor.current
