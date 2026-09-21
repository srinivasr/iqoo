package com.dhrashta.x.sensing

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import com.dhrashta.x.data.EventLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Second half of CC-5: logs [EventLogger.CLONED_APP_LAUNCHED] (device-wide) the first time a package
 * appears inside another profile (work profile / app clone) of this device.
 *
 * Proxy, not true launch detection: public APIs do not expose another profile's usage events, so this
 * fires when a package is added to (cloned into) the profile, which precedes its first launch there.
 */
class ProfileAppWatcher(context: Context) {
    private val context = context.applicationContext
    private val launcherApps = this.context.getSystemService(LauncherApps::class.java)
    private val userManager = this.context.getSystemService(UserManager::class.java)
    private val seen = ConcurrentHashMap.newKeySet<String>()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = onPackageInProfile(packageName, user)
        override fun onPackageChanged(packageName: String, user: UserHandle) = onPackageInProfile(packageName, user)
        override fun onPackageRemoved(packageName: String, user: UserHandle) = Unit
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) {
            packageNames.forEach { onPackageInProfile(it, user) }
        }
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = Unit
    }

    /** Registers for package changes in all profiles of this user. */
    fun start() {
        runCatching { launcherApps.registerCallback(callback, Handler(Looper.getMainLooper())) }
            .onFailure { Log.w(TAG, "Could not watch profile packages", it) }
    }

    /** Unregisters the package callback. */
    fun stop() {
        runCatching { launcherApps.unregisterCallback(callback) }
    }

    private fun onPackageInProfile(pkg: String, user: UserHandle) {
        if (user == Process.myUserHandle() || user !in userManager.userProfiles) return
        if (!seen.add("$user/$pkg")) return
        EventLogger.recordEvent(EventLogger.DEVICE_PKG, EventLogger.CLONED_APP_LAUNCHED)
        Log.i(TAG, "Package $pkg appeared in profile $user")
    }

    private companion object {
        const val TAG = "DhrashtaProfile"
    }
}
