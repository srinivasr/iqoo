package com.dhrashta.x.decision

/**
 * "Name mimics a bank or government brand" without the old false positives:
 *  - Official packages of those brands never match (the real SBI app is not an impersonator).
 *  - Short brand names (sbi, hdfc, ...) only match a WHOLE word/package segment, so "Usb input"
 *    ("usbinput" contains s-b-i) no longer matches. Fuzzy (edit-distance 1) matching is used only
 *    for long names where a one-letter change is still unmistakable (aadhaar, axisbank, incometax).
 *  - Apps installed from Google Play or pre-installed on the system are not flagged by this heuristic.
 */
object BrandHeuristic {
    private const val PLAY_STORE = "com.android.vending"

    /** Brand name -> how strictly to match it. */
    private val SHORT_TOKENS = setOf("sbi", "hdfc", "icici", "paytm", "uidai", "yono", "bhim")
    private val LONG_FUZZY = setOf("aadhaar", "axisbank", "incometax", "phonepe", "kotakbank")

    /** Official packages of the protected brands. Extend when adding a brand. */
    val OFFICIAL_PACKAGES: Set<String> = setOf(
        "com.sbi.lotusintouch", "com.sbi.SBIFreedomPlus", "com.sbi.upi", "com.sbi.yono", "com.sbi.yonolite",
        "com.snapwork.hdfc", "com.hdfcbank.payzapp", "com.csam.icici.bank.imobile", "com.icicibank.imobile",
        "com.axis.mobile", "net.one97.paytm", "com.phonepe.app", "com.msf.kbank.mobile",
        "in.gov.uidai.mAadhaarPlus", "in.gov.uidai.maadhaarplus", "in.org.npci.upiapp",
        "com.google.android.apps.nbu.paisa.user",
    )
    private val OFFICIAL_PACKAGES_LOWER: Set<String> = OFFICIAL_PACKAGES.map { it.lowercase() }.toSet()

    fun mimics(label: String, pkg: String, installer: String?, isSystemApp: Boolean): Boolean {
        if (isSystemApp || installer == PLAY_STORE) return false
        if (pkg in OFFICIAL_PACKAGES || pkg.lowercase() in OFFICIAL_PACKAGES_LOWER) return false
        return matchesName(label, pkg)
    }

    /** Pure name test (no install-source policy); exposed for unit tests. */
    fun matchesName(label: String, pkg: String): Boolean {
        val tokens = tokens(label) + tokens(pkg)
        if (tokens.any { it in SHORT_TOKENS }) return true
        val joined = tokens.joinToString("")
        return LONG_FUZZY.any { name ->
            joined.contains(name) || tokens.any { token ->
                levenshtein(token, name) <= 1 ||
                    token.windowed(name.length, 1, partialWindows = false).any { levenshtein(it, name) <= 1 }
            }
        }
    }

    /** Lower-case words: split on anything that is not a letter or digit, and on camelCase humps. */
    private fun tokens(text: String): List<String> =
        text.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }

    private fun levenshtein(left: String, right: String): Int {
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { i, leftChar ->
            val current = IntArray(right.length + 1)
            current[0] = i + 1
            right.forEachIndexed { j, rightChar ->
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (leftChar == rightChar) 0 else 1)
            }
            previous = current
        }
        return previous[right.length]
    }
}
