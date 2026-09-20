package com.dhrashta.x.ui

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.dhrashta.x.data.EventLogger
import com.dhrashta.x.sensing.DhrashtaForegroundService

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) scanNow()
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
                DhrashtaScreen(
                    onScan = ::scanNow,
                    onEnableContainment = ::requestVpnPermission,
                )
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
        if (permissionIntent == null) scanNow() else vpnPermission.launch(permissionIntent)
    }
}
