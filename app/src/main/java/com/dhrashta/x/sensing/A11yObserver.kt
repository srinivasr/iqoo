package com.dhrashta.x.sensing

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils

class A11yObserver(
    private val context: Context,
    private val onPackagesAdded: (Set<String>) -> Unit,
) : ContentObserver(Handler(Looper.getMainLooper())) {
    private var enabledPackages: Set<String> = readEnabledPackages()

    fun start() {
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false,
            this,
        )
        enabledPackages = readEnabledPackages()
    }

    fun stop() {
        context.contentResolver.unregisterContentObserver(this)
    }

    override fun onChange(selfChange: Boolean) {
        val current = readEnabledPackages()
        val added = current - enabledPackages
        enabledPackages = current
        if (added.isNotEmpty()) onPackagesAdded(added)
    }

    private fun readEnabledPackages(): Set<String> {
        val flattened = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return TextUtils.SimpleStringSplitter(':').run {
            setString(flattened)
            asSequence().mapNotNull { value ->
                android.content.ComponentName.unflattenFromString(value)?.packageName
            }.toSet()
        }
    }
}
