package com.dhrashta.x.enforcement

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.dhrashta.x.data.EvaluationStateStore
import kotlinx.coroutines.delay

/**
 * Requests to GuardVpnService. A request is not containment: callers that need to tell the user
 * "paused" must use [pauseAndConfirm], which only returns true once the tunnel containing the UID
 * has actually been established.
 */
object NetworkPause {
    /** Sends a pause request. Returns false if VPN consent is missing or the service could not start. */
    fun requestPause(context: Context, uid: Int): Boolean {
        if (VpnService.prepare(context) != null) {
            EvaluationStateStore.update {
                it.copy(contained = false, message = "VPN approval is required before network containment")
            }
            return false
        }
        return runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GuardVpnService::class.java)
                    .setAction(GuardVpnService.ACTION_PAUSE_UID)
                    .putExtra(GuardVpnService.EXTRA_UID, uid),
            )
        }.isSuccess
    }

    /** Requests a pause and waits (up to [timeoutMillis]) until [uid] is really inside the tunnel. */
    suspend fun pauseAndConfirm(context: Context, uid: Int, timeoutMillis: Long = 3_000L): Boolean {
        if (!requestPause(context, uid)) return false
        return awaitContained(context, uid, timeoutMillis)
    }

    /** True once [uid] appears in [GuardVpnService.containedUids], polling until [timeoutMillis]. */
    suspend fun awaitContained(context: Context, uid: Int, timeoutMillis: Long = 3_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (uid in GuardVpnService.containedUids(context)) return true
            delay(150)
        }
        return uid in GuardVpnService.containedUids(context)
    }

    /** Starts DNS-only beacon monitoring if VPN consent was already granted; returns false otherwise. */
    fun startDnsMonitor(context: Context): Boolean {
        if (VpnService.prepare(context) != null) return false
        return runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GuardVpnService::class.java).setAction(GuardVpnService.ACTION_START_MONITOR),
            )
        }.isSuccess
    }

    fun resume(context: Context, uid: Int) {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GuardVpnService::class.java)
                    .setAction(GuardVpnService.ACTION_RESUME_UID)
                    .putExtra(GuardVpnService.EXTRA_UID, uid),
            )
        }
    }
}
