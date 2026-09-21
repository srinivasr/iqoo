package com.dhrashta.x.enforcement

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
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
 * packet captures and to clean up (decoy prefs file, and one contact whose raw ID is stored in it).
 *
 * Canary-based, not true runtime taint tracking. Only catches unencrypted exfiltration. Encrypted
 * payloads will not match. GuardVpnService only sees DNS queries and paused apps' traffic, so a canary
 * is caught when it leaves through a DNS lookup or from an app that is already contained.
 */
object CanaryManager {
    const val PREFIX = "DHRX-"
    const val CONTACT_NAME = "DHRASHTA Canary"
    const val CONTACT_NUMBER = "+91 90000 00000"
    const val CREDENTIAL_VALUE = "DHRX-ACC-7712-9910-4408"

    private const val TAG = "DhrashtaCanary"
    private const val PREFS = "dhrashta_canary_prefs"
    private const val KEY_UUID = "device_uuid"
    private const val KEY_ACCOUNT = "account_number"
    private const val KEY_CONTACT_TOKEN = "contact_token"
    private const val KEY_CONTACT_RAW_ID = "contact_raw_id"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reads = ConcurrentHashMap<String, Long>()

    @Volatile private var tokens: List<CanaryToken> = emptyList()

    /** Creates (or reloads) all four canaries on a background thread. Safe to call on every app start. */
    fun plantCanaries(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val uuid = prefs.getString(KEY_UUID, null) ?: "$PREFIX${UUID.randomUUID()}"
            val contactToken = prefs.getString(KEY_CONTACT_TOKEN, null)
                ?: "${PREFIX}CT-${UUID.randomUUID().toString().take(8)}"
            prefs.edit()
                .putString(KEY_UUID, uuid)
                .putString(KEY_ACCOUNT, CREDENTIAL_VALUE)
                .putString(KEY_CONTACT_TOKEN, contactToken)
                .apply()
            val androidId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
                .orEmpty().padEnd(8, '0').take(8)
            tokens = listOf(
                CanaryToken("contact", contactToken, CanaryType.CONTACT),
                CanaryToken("uuid", uuid, CanaryType.UUID),
                CanaryToken("credential", CREDENTIAL_VALUE, CanaryType.CREDENTIAL),
                CanaryToken("device", "${PREFIX}DEV-$androidId", CanaryType.DEVICE_MARKER),
            )
            if (!prefs.contains(KEY_CONTACT_RAW_ID)) plantContact(appContext, contactToken)
            Log.i(TAG, "Canaries active: ${tokens.map { it.id }}")
        }
    }

    /** Current canary tokens; empty until [plantCanaries] finishes. */
    fun getActiveCanaries(): List<CanaryToken> = tokens

    /** Records that [pkg] was seen sending [token] at [ts]. */
    fun markRead(token: String, pkg: String, ts: Long) {
        reads[key(token, pkg)] = ts
    }

    /** True if [pkg] has been seen sending [token] since the process started. */
    fun isRead(token: String, pkg: String): Boolean = reads.containsKey(key(token, pkg))

    /** When [pkg] was last seen sending [token], or null if never. */
    fun lastReadAt(token: String, pkg: String): Long? = reads[key(token, pkg)]

    private fun key(token: String, pkg: String) = "$pkg|$token"

    /**
     * Inserts the decoy contact. The DHRX- token goes in the email field, since the name and number
     * are fixed and contact stealers typically take name, number and email together.
     */
    private fun plantContact(context: Context, token: String) {
        val operations = arrayListOf(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, CONTACT_NAME)
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, CONTACT_NUMBER)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, "$token@canary.invalid")
                .build(),
        )
        try {
            val result = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
            val rawId = result.firstOrNull()?.uri?.lastPathSegment?.toLongOrNull() ?: return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_CONTACT_RAW_ID, rawId).apply()
            Log.i(TAG, "Planted canary contact rawId=$rawId")
        } catch (error: SecurityException) {
            Log.w(TAG, "WRITE_CONTACTS not granted; contact canary skipped, other canaries active")
        } catch (error: Exception) {
            Log.w(TAG, "Could not plant contact canary", error)
        }
    }
}
