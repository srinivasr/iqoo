package com.dhrashta.x.enforcement

/**
 * Finds planted canary tokens in raw outbound packets. Runs on every tunnel packet, so the common
 * case is a single allocation-free byte scan for "DHRX-" (case-insensitive); only packets that pass
 * that gate are decoded to a String once and checked against the active tokens with indexOf.
 */
object CanaryMatcher {
    private val GATE = CanaryManager.PREFIX.toByteArray(Charsets.US_ASCII)

    /** Returns the value of the canary token found in [payload]'s first [length] bytes, or null. */
    fun scan(payload: ByteArray, length: Int): String? {
        val end = minOf(length, payload.size)
        if (!containsGate(payload, end)) return null
        val tokens = CanaryManager.getActiveCanaries()
        if (tokens.isEmpty()) return null
        val text = String(payload, 0, end, Charsets.ISO_8859_1)
        for (i in tokens.indices) {
            val value = tokens[i].value
            if (text.indexOf(value, ignoreCase = true) >= 0) return value
        }
        return null
    }

    private fun containsGate(payload: ByteArray, end: Int): Boolean {
        val last = end - GATE.size
        var i = 0
        while (i <= last) {
            var j = 0
            while (j < GATE.size && upper(payload[i + j]) == GATE[j]) j++
            if (j == GATE.size) return true
            i++
        }
        return false
    }

    /** ASCII upper-case without allocation; DNS names are often lower-cased on the wire. */
    private fun upper(byte: Byte): Byte = if (byte >= LOWER_A && byte <= LOWER_Z) (byte - 32).toByte() else byte

    private const val LOWER_A: Byte = 97
    private const val LOWER_Z: Byte = 122
}
