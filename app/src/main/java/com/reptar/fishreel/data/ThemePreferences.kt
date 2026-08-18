package com.reptar.fishreel.data

import android.content.Context

private const val PREFS_NAME = "fishreel_prefs"
private const val KEY_DARK_THEME = "dark_theme"

/** Persists the user's light/dark theme choice locally (device-specific, not synced). */
object ThemePreferences {
    fun getDarkTheme(context: Context, default: Boolean): Boolean {
        return prefs(context).getBoolean(KEY_DARK_THEME, default)
    }

    fun setDarkTheme(context: Context, isDark: Boolean) {
        prefs(context).edit().putBoolean(KEY_DARK_THEME, isDark).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
