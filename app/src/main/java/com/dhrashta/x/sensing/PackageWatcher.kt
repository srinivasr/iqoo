package com.dhrashta.x.sensing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

class PackageWatcher(
    private val context: Context,
    private val onPackageAdded: (String) -> Unit,
) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_PACKAGE_ADDED && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                intent.data?.schemeSpecificPart?.let(onPackageAdded)
            }
        }
    }

    fun start() {
        val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }
}
