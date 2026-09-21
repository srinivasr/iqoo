package com.dhrashta.x.data

import android.content.Context

/**
 * Apps the user chose to trust. Stored in the same prefs the foreground service reads when scoring,
 * so a trusted app gets signal N2 (-100) on its next evaluation.
 */
object AllowList {
    private const val PREFS = "allow_list"
    private const val KEY = "packages"

    /** Packages the user trusts. */
    fun get(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty()

    /** Adds or removes [pkg] from the trusted set. */
    fun set(context: Context, pkg: String, trusted: Boolean) {
        val updated = get(context).toMutableSet().apply { if (trusted) add(pkg) else remove(pkg) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, updated).apply()
    }
}
