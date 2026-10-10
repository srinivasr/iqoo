package com.dhrashta.x.data

import android.content.Context

object PolicyPreference {
    private const val PREFS = "dhrashta_policies"
    private const val KEY_ACCEPTED = "policy_accepted"

    fun isAccepted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACCEPTED, false)

    fun setAccepted(context: Context, accepted: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACCEPTED, accepted)
            .apply()
    }
}
