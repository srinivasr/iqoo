package com.dhrashta.x.enforcement

import android.util.SparseArray
import kotlin.math.sqrt

/** A domain an app is looking up on a suspiciously regular schedule. */
data class BeaconResult(
    val domain: String,
    val intervalMillis: Long,
    val coefficientOfVariation: Double,
    val queryCount: Int,
)

/**
 * Detects periodic DNS lookups ("beacons") per UID from the queries GuardVpnService's DNS monitor sees.
 * Keeps a rolling 10-minute history per UID and reports a domain once it has [MIN_QUERIES]+ lookups
 * whose interval coefficient of variation is below [MAX_CV] and it is not on [KNOWN_GOOD].
 *
 * Raw-IP connections with no DNS lookup are invisible to this detector. That is the honest
 * limitation of DNS-only capture. The system resolver also caches answers for their TTL, so a beacon
 * is only visible when its domain's TTL is shorter than the beacon interval.
 */
object BeaconDetector {
    const val WINDOW_MILLIS = 10 * 60 * 1_000L
    const val MIN_QUERIES = 5
    const val MAX_CV = 0.15

    /** Lookups of one domain closer together than this are one logical lookup (A + AAAA, retries). */
    private const val COALESCE_MILLIS = 2_000L
    private const val CAPACITY = 128

    /** Domains (and their subdomains) that never count as unknown hosts. Add a line to extend. */
    val KNOWN_GOOD: Set<String> = setOf(
        "google.com",
        "googleapis.com",
        "whatsapp.net",
        "facebook.com",
        "instagram.com",
        "amazonaws.com",
        "cloudfront.net",
        "akamai.net",
    )

    private class History {
        val timestamps = LongArray(CAPACITY)
        val domains = arrayOfNulls<String>(CAPACITY)
        var head = 0
        var size = 0
        val lastReported = HashMap<String, Long>()
    }

    private val histories = SparseArray<History>()
    private val scratch = LongArray(CAPACITY)

    /** Records one DNS lookup of [domain] by [uid] at [ts]. The oldest entry is overwritten when full. */
    fun recordDnsQuery(uid: Int, domain: String, ts: Long) = synchronized(this) {
        val history = histories[uid] ?: History().also { histories.put(uid, it) }
        history.timestamps[history.head] = ts
        history.domains[history.head] = domain
        history.head = (history.head + 1) % CAPACITY
        if (history.size < CAPACITY) history.size++
    }

    /**
     * Checks [uid]'s most recently queried domain for beacon timing within the last [WINDOW_MILLIS].
     * Returns a result at most once per domain per window, so callers can log it directly.
     */
    fun checkForBeacon(uid: Int): BeaconResult? = synchronized(this) {
        val history = histories[uid] ?: return null
        if (history.size == 0) return null
        val latestIndex = (history.head - 1 + CAPACITY) % CAPACITY
        val domain = history.domains[latestIndex] ?: return null
        val latest = history.timestamps[latestIndex]
        if (isKnownGood(domain)) return null
        var count = 0
        for (i in 0 until history.size) {
            val index = (history.head - history.size + i + CAPACITY) % CAPACITY
            val ts = history.timestamps[index]
            if (ts < latest - WINDOW_MILLIS || history.domains[index] != domain) continue
            if (count > 0 && ts - scratch[count - 1] < COALESCE_MILLIS) continue
            scratch[count++] = ts
        }
        if (count < MIN_QUERIES) return null
        val mean = (scratch[count - 1] - scratch[0]).toDouble() / (count - 1)
        if (mean <= 0.0) return null
        var variance = 0.0
        for (i in 1 until count) {
            val delta = (scratch[i] - scratch[i - 1]) - mean
            variance += delta * delta
        }
        val cv = sqrt(variance / (count - 1)) / mean
        if (cv >= MAX_CV) return null
        val reported = history.lastReported[domain]
        if (reported != null && latest - reported < WINDOW_MILLIS) return null
        history.lastReported[domain] = latest
        BeaconResult(domain, mean.toLong(), cv, count)
    }

    /** True if [domain] equals or is a subdomain of an entry in [KNOWN_GOOD]. */
    fun isKnownGood(domain: String): Boolean = KNOWN_GOOD.any { good ->
        domain.endsWith(good) && (domain.length == good.length || domain[domain.length - good.length - 1] == '.')
    }
}
