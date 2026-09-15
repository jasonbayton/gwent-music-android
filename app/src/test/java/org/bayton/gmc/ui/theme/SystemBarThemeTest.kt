package org.bayton.gmc.ui.theme

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.WindowCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise the real shared theme against an Activity window, not a boolean-only helper. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SystemBarThemeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var previous: ThemeMode

    @Before fun saveMode() { previous = ThemeController.mode.value }
    @After fun restoreMode() { ThemeController.mode.value = previous }

    private fun assertIcons(dark: Boolean) = compose.runOnIdle {
        val window = compose.activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        assertEquals("status icons", !dark, controller.isAppearanceLightStatusBars)
        assertEquals("navigation icons", !dark, controller.isAppearanceLightNavigationBars)
        assertFalse(window.isNavigationBarContrastEnforced)
    }

    @Test @Config(qualifiers = "notnight")
    fun `dark override and live switch work while Android is light`() {
        ThemeController.mode.value = ThemeMode.DARK
        compose.setContent { GwentMusicTheme { Text("Fixture") } }
        assertIcons(dark = true)
        compose.runOnIdle { ThemeController.mode.value = ThemeMode.LIGHT }
        assertIcons(dark = false)
        compose.runOnIdle { ThemeController.mode.value = ThemeMode.DARK }
        assertIcons(dark = true)
    }

    @Test @Config(qualifiers = "night")
    fun `light override wins over dark Android and system mode restores dark icons policy`() {
        ThemeController.mode.value = ThemeMode.LIGHT
        compose.setContent { GwentMusicTheme { Text("Fixture") } }
        assertIcons(dark = false)
        compose.runOnIdle { ThemeController.mode.value = ThemeMode.SYSTEM }
        assertIcons(dark = true)
    }

    @Test @Config(qualifiers = "notnight")
    fun `system mode follows light Android`() {
        ThemeController.mode.value = ThemeMode.SYSTEM
        compose.setContent { GwentMusicTheme { Text("Fixture") } }
        assertIcons(dark = false)
    }
}
