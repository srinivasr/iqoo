package com.dhrashta.x.sensing

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

data class Identity(
    val pkg: String,
    val appLabel: String,
    val uid: Int,
    val installer: String?,
    val certSha256: String,
    val hasLauncher: Boolean,
    val permissions: Set<String>,
    val firstInstallTime: Long,
    val isSystemApp: Boolean,
)

class IdentityResolver(private val context: Context) {
    private val packageManager = context.packageManager

    @Suppress("DEPRECATION")
    fun resolve(pkg: String): Identity {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PERMISSIONS
        } else {
            PackageManager.GET_SIGNATURES or PackageManager.GET_PERMISSIONS
        }
        val info = packageManager.getPackageInfo(pkg, flags)
        val appInfo = info.applicationInfo ?: packageManager.getApplicationInfo(pkg, 0)
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { packageManager.getInstallSourceInfo(pkg).installingPackageName }.getOrNull()
        } else {
            packageManager.getInstallerPackageName(pkg)
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo
            if (signingInfo?.hasMultipleSigners() == true) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo?.signingCertificateHistory.orEmpty()
            }
        } else {
            info.signatures.orEmpty()
        }
        val cert = signatures.firstOrNull()?.toByteArray()?.let(::sha256).orEmpty()
        return Identity(
            pkg = pkg,
            appLabel = packageManager.getApplicationLabel(appInfo).toString(),
            uid = appInfo.uid,
            installer = installer,
            certSha256 = cert,
            hasLauncher = packageManager.getLaunchIntentForPackage(pkg) != null,
            permissions = info.requestedPermissions.orEmpty().toSet(),
            firstInstallTime = info.firstInstallTime,
            isSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(":") { "%02X".format(it) }
}
