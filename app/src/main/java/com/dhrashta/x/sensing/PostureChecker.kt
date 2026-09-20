package com.dhrashta.x.sensing

import android.content.Context
import android.os.UserManager
import android.provider.Settings

data class Posture(
    val adbEnabled: Boolean,
    val adbWifiEnabled: Boolean,
    val profileCount: Int,
    val newProfileAppeared: Boolean = false,
)

class PostureChecker(private val context: Context) {
    private var previousProfileCount: Int? = null

    fun check(): Posture {
        val count = context.getSystemService(UserManager::class.java).userProfiles.size
        val previous = previousProfileCount
        previousProfileCount = count
        return Posture(
            adbEnabled = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1,
            adbWifiEnabled = Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1,
            profileCount = count,
            newProfileAppeared = previous != null && count > previous,
        )
    }
}
