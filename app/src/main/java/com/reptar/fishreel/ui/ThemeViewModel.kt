package com.reptar.fishreel.ui

import android.app.Application
import android.content.res.Configuration
import androidx.lifecycle.AndroidViewModel
import com.reptar.fishreel.data.ThemePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Holds the app's current light/dark theme choice, shared across every screen. */
class ThemeViewModel(application: Application) : AndroidViewModel(application) {

    private val _isDarkTheme = MutableStateFlow(loadInitialValue())
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme

    fun setDarkTheme(isDark: Boolean) {
        _isDarkTheme.value = isDark
        ThemePreferences.setDarkTheme(getApplication(), isDark)
    }

    /** Falls back to the device's current system setting the first time, before any explicit choice. */
    private fun loadInitialValue(): Boolean {
        val context = getApplication<Application>()
        val nightModeFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val systemDefault = nightModeFlags == Configuration.UI_MODE_NIGHT_YES
        return ThemePreferences.getDarkTheme(context, default = systemDefault)
    }
}
