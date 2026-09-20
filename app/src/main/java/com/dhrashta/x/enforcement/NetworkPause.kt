package com.dhrashta.x.enforcement

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.dhrashta.x.data.EvaluationStateStore

object NetworkPause {
    fun pause(context: Context, uid: Int): Boolean {
        if (VpnService.prepare(context) != null) {
            EvaluationStateStore.update {
                it.copy(contained = false, message = "VPN approval is required before network containment")
            }
            return false
        }
        ContextCompat.startForegroundService(
            context,
            Intent(context, GuardVpnService::class.java)
                .setAction(GuardVpnService.ACTION_PAUSE_UID)
                .putExtra(GuardVpnService.EXTRA_UID, uid),
        )
        return true
    }

    fun resume(context: Context, uid: Int) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, GuardVpnService::class.java)
                .setAction(GuardVpnService.ACTION_RESUME_UID)
                .putExtra(GuardVpnService.EXTRA_UID, uid),
        )
    }
}
