package com.dhrashta.x.ui.theme

import android.content.Context

enum class AppThemeMode { Dark, Light }

/** A UI-only preference; the selected appearance survives app restarts. */
object ThemePreference {
    private const val PREFS = "appearance"
    private const val KEY_MODE = "mode"

    fun read(context: Context): AppThemeMode = when (
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, AppThemeMode.Dark.name)
    ) {
        AppThemeMode.Light.name -> AppThemeMode.Light
        else -> AppThemeMode.Dark
    }

    fun save(context: Context, mode: AppThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode.name).apply()
    }
}
