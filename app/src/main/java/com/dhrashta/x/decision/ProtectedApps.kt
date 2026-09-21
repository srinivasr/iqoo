package com.dhrashta.x.decision

/**
 * Packages whose foreground sessions are sensitive (banking / UPI). Used by UsageWatcher to emit
 * `bank_foreground`, the first step of causal chain CC-3. Add a line to extend.
 */
object ProtectedApps {
    // TODO(protected-apps): load this list from the remote threat list feed alongside threats.json.
    val BANKING: Set<String> = setOf(
        "com.sbi.lotusintouch",
        "com.sbi.SBIFreedomPlus",
        "net.one97.paytm",
        "com.google.android.apps.nbu.paisa.user", // Google Pay
        "com.phonepe.app",
        "com.microsoft.mobile.personal", // placeholder for bank apps
    )

    /** True if [pkg] is a known banking / payments app. */
    fun isBanking(pkg: String): Boolean = pkg in BANKING
}
