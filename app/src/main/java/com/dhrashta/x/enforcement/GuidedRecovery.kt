package com.dhrashta.x.enforcement

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object GuidedRecovery {
    fun openA11ySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openAppInfo(context: Context, pkg: String) {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", pkg, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun requestUninstall(context: Context, pkg: String) {
        context.startActivity(
            Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
