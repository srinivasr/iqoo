package com.dhrashta.x.enforcement

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** A planted decoy value; [value] always starts with [CanaryManager.PREFIX]. */
data class CanaryToken(val id: String, val value: String, val type: CanaryType)

enum class CanaryType { CONTACT, UUID, CREDENTIAL, DEVICE_MARKER }

/**
 * Plants decoy values ("canaries") that no legitimate app should ever send off the device, and tracks
 * which packages were caught sending them. Every value starts with "DHRX-" so it is easy to spot in
 * packet captures and to clean up.
 *
 * OPT-IN ONLY: nothing is planted unless the user turns decoys on in Settings ([setEnabled]). Turning
 * them off forgets the tokens.
 *
 * What a match means: a decoy appeared in an outbound packet that entered OUR tunnel (a DNS lookup,
 * or traffic of an app that is already contained). Both tunnels drop that packet, so a match is a
 * blocked send attempt, not proof of a leak. Encrypted (TLS) payloads never match, and traffic that
 * does not pass through our tunnel is not seen at all. This is not runtime taint tracking.
 */
object CanaryManager {
    const val PREFIX = "DHRX-"
    const val CREDENTIAL_VALUE = "DHRX-ACC-7712-9910-4408"

    private const val TAG = "DhrashtaCanary"
    private const val PREFS = "dhrashta_canary_prefs"
    private const val KEY_UUID = "device_uuid"
    private const val KEY_ACCOUNT = "account_number"
    private const val KEY_ENABLED = "decoys_enabled"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reads = ConcurrentHashMap<String, Long>()

    @Volatile private var tokens: List<CanaryToken> = emptyList()

    /** True only after the user explicitly opted in to decoys. */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    /** Opt in (plants decoys) or opt out (clears tokens). */
    fun setEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) plantCanaries(appContext) else removeCanaries(appContext)
    }

    /** Creates (or reloads) the canaries on a background thread, but only when the user opted in. */
    fun plantCanaries(context: Context) {
        val appContext = context.applicationContext
        if (!isEnabled(appContext)) {
            tokens = emptyList()
            return
        }
        scope.launch {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val uuid = prefs.getString(KEY_UUID, null) ?: "$PREFIX${UUID.randomUUID()}"
            prefs.edit()
                .putString(KEY_UUID, uuid)
                .putString(KEY_ACCOUNT, CREDENTIAL_VALUE)
                .apply()
            val androidId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
                .orEmpty().padEnd(8, '0').take(8)
            tokens = listOf(
                CanaryToken("uuid", uuid, CanaryType.UUID),
                CanaryToken("credential", CREDENTIAL_VALUE, CanaryType.CREDENTIAL),
                CanaryToken("device", "${PREFIX}DEV-$androidId", CanaryType.DEVICE_MARKER),
            )
            Log.i(TAG, "Canaries active: ${tokens.map { it.id }}")
        }
    }

    /** Current canary tokens; empty until [plantCanaries] finishes. */
    fun getActiveCanaries(): List<CanaryToken> = tokens

    /** Records that [pkg] attempted to send [token] at [ts] (the packet was dropped). */
    fun markAttempt(token: String, pkg: String, ts: Long) {
        reads[key(token, pkg)] = ts
    }

    /** When [pkg] last attempted to send [token], or null if never. */
    fun lastAttemptAt(token: String, pkg: String): Long? = reads[key(token, pkg)]

    /** Clears all planted tokens and preferences. */
    private fun removeCanaries(context: Context) {
        tokens = emptyList()
        reads.clear()
        scope.launch {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().clear().putBoolean(KEY_ENABLED, false).apply()
            Log.i(TAG, "Decoys removed")
        }
    }

    private fun key(token: String, pkg: String) = "$pkg|$token"
}
