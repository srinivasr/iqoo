package com.dhrashta.x.ui

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.dhrashta.x.data.EventLogger
import com.dhrashta.x.enforcement.GuidedRecovery
import com.dhrashta.x.sensing.DhrashtaForegroundService
import com.dhrashta.x.ui.theme.DhrashtaTheme

class MainActivity : ComponentActivity() {
    private var destination by mutableStateOf(AppDestination.Dashboard)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            scanNow()
            destination = AppDestination.Paused
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EventLogger.init(applicationContext)
        ContextCompat.startForegroundService(this, Intent(this, DhrashtaForegroundService::class.java))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            DhrashtaTheme {
                when (destination) {
                    AppDestination.Dashboard -> DashboardScreen(
                        onRiskDetails = { destination = AppDestination.RiskDetails },
                        onActivity = { showPlaceholder("Activity") },
                        onSettings = { showPlaceholder("Settings") },
                    )
                    AppDestination.RiskDetails -> RiskDetailsScreen(
                        onBack = { destination = AppDestination.Dashboard },
                        onPauseInternet = ::requestVpnPermission,
                        onReviewPermissions = { GuidedRecovery.openAppInfo(this, QUICK_TOOLS_PACKAGE) },
                        onUninstall = { GuidedRecovery.requestUninstall(this, QUICK_TOOLS_PACKAGE) },
                        onListen = { showPlaceholder("Explanation playback") },
                    )
                    AppDestination.Paused -> Unit
                }
            }
        }
    }

    private fun scanNow() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, DhrashtaForegroundService::class.java).setAction(DhrashtaForegroundService.ACTION_SCAN),
        )
    }

    private fun requestVpnPermission() {
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) {
            scanNow()
            destination = AppDestination.Paused
        } else vpnPermission.launch(permissionIntent)
    }

    private fun showPlaceholder(feature: String) {
        android.widget.Toast.makeText(this, "$feature is not available in this prototype.", android.widget.Toast.LENGTH_SHORT).show()
    }

    private enum class AppDestination { Dashboard, RiskDetails, Paused }

    private companion object { const val QUICK_TOOLS_PACKAGE = "com.example.quicktools" }
}
