package org.bayton.gmc.ui.theme

import android.content.Context
import androidx.compose.runtime.mutableStateOf

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * App-wide theme mode, backed by SharedPreferences and exposed as a Compose state so a change from
 * the settings screen recomposes the whole app. Load once at startup.
 */
object ThemeController {
    private const val PREFS = "gmc_appearance"
    private const val KEY = "theme_mode"

    val mode = mutableStateOf(ThemeMode.SYSTEM)

    fun load(context: Context) {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        mode.value = runCatching { ThemeMode.valueOf(v ?: ThemeMode.SYSTEM.name) }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun set(context: Context, m: ThemeMode) {
        mode.value = m
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, m.name).apply()
    }
}
