package org.bayton.gmc.ui.theme

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView

private fun scheme(c: BaytonColors) = if (c.isDark) darkColorScheme(
    primary = c.accent, onPrimary = Color.White,
    background = c.canvas, onBackground = c.ink,
    surface = c.surface, onSurface = c.ink,
    surfaceVariant = c.surface2, onSurfaceVariant = c.inkDim,
    outline = c.line2, error = c.red,
) else lightColorScheme(
    primary = c.accent, onPrimary = Color.White,
    background = c.canvas, onBackground = c.ink,
    surface = c.surface, onSurface = c.ink,
    surfaceVariant = c.surface2, onSurfaceVariant = c.inkDim,
    outline = c.line2, error = c.red,
)

@Composable
fun GwentMusicTheme(
    darkTheme: Boolean = when (ThemeController.mode.value) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    },
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) BaytonDark else BaytonLight
    val activity = LocalActivity.current as? ComponentActivity
    if (activity != null && !LocalView.current.isInEditMode) {
        SideEffect {
            // Both MainActivity and the standalone updater use this theme. Supply the effective
            // app mode instead of enableEdgeToEdge's default system-theme detector, including
            // live preference changes while the Activity stays open.
            val bars = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { darkTheme }
            activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Our screen background already extends under the navigation controls.
                activity.window.isNavigationBarContrastEnforced = false
            }
        }
    }
    CompositionLocalProvider(LocalBayton provides colors) {
        MaterialTheme(colorScheme = scheme(colors), typography = BaytonTypography, content = content)
    }
}
