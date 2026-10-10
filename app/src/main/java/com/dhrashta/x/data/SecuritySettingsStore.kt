package com.dhrashta.x.data

import android.content.Context

object SecuritySettingsStore {
    private const val PREFS = "dhrashta_security_prefs"
    private const val KEY_AUTO_CONTAIN = "auto_contain_enabled"

    const val PRIVACY_POLICY_URL = "https://dhrashta.com/privacy"

    fun isAutoContainmentEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CONTAIN, false)

    fun setAutoContainmentEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_CONTAIN, enabled)
            .apply()
    }
}
