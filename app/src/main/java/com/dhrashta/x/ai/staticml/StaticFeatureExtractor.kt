package com.dhrashta.x.ai.staticml

import java.io.File

/**
 * Manifest-level facts about one APK. On the phone they come from PackageManager; in unit tests
 * they come from androguard, so the code below is tested against the Python reference.
 */
data class ApkFacts(
    val pkg: String,
    val permissions: Collection<String>,
    val services: List<String>,
    val activities: List<String>,
    val receivers: List<String>,
    val hasLauncher: Boolean,
    val apkFile: File,
)

/** What the extractor saw, kept so the UI can say why a score is what it is. */
data class StaticFeatures(
    val fired: Set<String>,
    val dexFiles: Int,
    val dexUnreadable: Int,
    val strings: Int,
    val methods: Int,
)

/**
 * Kotlin port of extract_v6.py (Drebin-215 + MODERN v5 + MODERN v6), reproducing its rules exactly,
 * including its quirks, because the LightGBM model and the boost weights were tuned on its output.
 *
 * IMPORTANT, found while porting: with androguard 4.x, `DEX.get_strings()` returns plain `str`, so the
 * script's `s.get()` raised and every string was skipped. In the training/validation CSV
 * (extracted_v6.csv) all string-content features are therefore always 0 (e.g. MODERN_VNC_RAT,
 * MODERN_BANK_IMPERSONATION, MODERN_ANTI_ANALYSIS, android.intent.action.SEND). The model and the
 * boost weights were tuned on that behaviour, so [USE_STRING_CONTENT] is false: the phone computes
 * exactly what was validated. Turning it on needs the weights re-tuned first (tested here: with
 * real strings, a benign SMS app picks up VNC_RAT, BANK_IMPERSONATION and CRYPTO_STEALER boosts).
 *
 * Deliberate, documented differences from the Python script:
 *  - Python builds its strings blob from `list(set)[:30000]`, whose order is random per process.
 *    Here the first 30,000 distinct strings in dex order are used (deterministic).
 *  - Python looks for "accessibility" in a random 5,000 of the class names; here all are checked.
 *  - "No launcher activity" uses PackageManager, which also sees launchers disabled at runtime.
 */
class StaticFeatureExtractor(drebinFeatures: List<String>) {
    private val drebin = drebinFeatures
    private val drebinSet = drebinFeatures.toHashSet()
    private val apiFeatures: List<ApiRule> = drebinFeatures.filterNot(::pyIsUpper).map { feat ->
        val fn = normalize(feat)
        val parts = fn.split('.')
        ApiRule(feat, fn, parts.last(), if (parts.size >= 2) parts[parts.size - 2] else "")
    }

    private data class ApiRule(val feature: String, val normalized: String, val method: String, val cls: String)

    fun extract(facts: ApkFacts): StaticFeatures {
        val fired = HashSet<String>()
        val perms = facts.permissions.toSet()
        for (perm in perms) {
            val short = perm.substringAfterLast('.')
            if (short in drebinSet) fired += short
        }
        val shortPerms = perms.mapTo(HashSet()) { it.substringAfterLast('.') }
        val customPerms = perms.filter {
            !it.startsWith("android.permission.") && !it.startsWith("com.android.") && !it.startsWith("com.google.")
        }

        // ---------------- DEX
        val archive = runCatching { ApkArchive.open(facts.apkFile) }.getOrNull()
        val allStrings = LinkedHashSet<String>()
        val allMethods = HashSet<String>()
        val allClasses = HashSet<String>()
        val classNamesRaw = ArrayList<String>(512)
        var methodCount = 0
        var dexFiles = 0
        var dexUnreadable = 0
        archive?.dexEntries()?.forEach { entry ->
            dexFiles++
            val bytes = archive.read(entry)
            if (bytes == null) { dexUnreadable++; return@forEach }
            runCatching {
                val dex = DexReader(bytes)
                if (USE_STRING_CONTENT) for (i in 0 until dex.stringCount()) {
                    val v = runCatching { dex.string(i) }.getOrNull() ?: continue
                    if (v.isNotEmpty() && v.codePointCount(0, v.length) < 200) allStrings += v.lowercase()
                }
                dex.forEachMethodId { name, descriptor ->
                    val cls = normalize(descriptor)
                    allMethods += name.lowercase()
                    allClasses += cls
                    allClasses += cls.substringAfterLast('.')
                    if (classNamesRaw.size < 500) classNamesRaw += cls
                    methodCount++
                }
            }.onFailure { dexUnreadable++ }
        }

        for (rule in apiFeatures) {
            if (rule.normalized in allStrings) {
                fired += rule.feature
            } else if (rule.method.length > 5 && rule.method in allMethods) {
                if (rule.cls.isEmpty() || rule.cls in allClasses) fired += rule.feature
            }
        }

        val services = facts.services
        val activities = facts.activities
        val receivers = facts.receivers
        val allComponents = (services + activities + receivers).joinToString(" ").lowercase()
        val blob = buildString {
            var n = 0
            for (s in allStrings) {
                if (n == BLOB_LIMIT) break
                if (n > 0) append(' ')
                append(s)
                n++
            }
        }
        fun inBlob(x: String) = blob.contains(x)
        fun anyInBlob(xs: List<String>) = xs.any { blob.contains(it) }
        val pkg = facts.pkg
        fun has(perm: String) = perm in shortPerms
        fun set(name: String, cond: Boolean) { if (cond) fired += name }

        // ---------------- V5
        set("MODERN_A11Y_SERVICE", "accessibility" in allComponents)
        set("MODERN_OVERLAY_AND_A11Y", has("SYSTEM_ALERT_WINDOW") && "MODERN_A11Y_SERVICE" in fired)
        set("MODERN_SYSTEM_ALERT_WINDOW", has("SYSTEM_ALERT_WINDOW"))
        set("MODERN_NOTIFICATION_LISTENER", inBlob("notificationlistener"))
        set("MODERN_SCREEN_CAPTURE", anyInBlob(listOf("mediarecorder", "screencapture", "virtualdisplay", "mediaprojection")))
        set("MODERN_SMS_INTERNET_COMBO", listOf("SEND_SMS", "RECEIVE_SMS", "READ_SMS").any(::has) && has("INTERNET"))
        set("MODERN_CLIPBOARD_ACCESS", inBlob("clipboardmanager") || "getprimaryclip" in allMethods)
        set("MODERN_CLIPBOARD_API_CALLS", "getprimaryclip" in allMethods || "setprimaryclip" in allMethods)
        set("MODERN_CRYPTO_WALLET_PATHS", anyInBlob(listOf("wallet.dat", "bitcoin", "ethereum", "blockchain", "metamask")))
        set("MODERN_REQUEST_INSTALL", has("REQUEST_INSTALL_PACKAGES"))
        set("MODERN_INSTALL_PACKAGES_REQ", has("REQUEST_INSTALL_PACKAGES"))
        // The Python script also tests the upper-case "BIND_DEVICE_ADMIN" against a lower-case blob,
        // which can never match; only the lower-case test is effective, so only it is reproduced.
        set("MODERN_DEVICE_ADMIN", inBlob("deviceadmin"))
        set("MODERN_USAGE_STATS", has("PACKAGE_USAGE_STATS"))
        set("MODERN_QUERY_ALL_PACKAGES", has("QUERY_ALL_PACKAGES"))
        set("MODERN_FOREGROUND_SERVICE", has("FOREGROUND_SERVICE"))
        set("MODERN_EXACT_ALARM", has("SCHEDULE_EXACT_ALARM") || has("USE_EXACT_ALARM"))
        set("MODERN_MANAGE_EXTERNAL_STORAGE", has("MANAGE_EXTERNAL_STORAGE"))
        set("MODERN_ACCESSIBILITY_API_CALLS", allClasses.any { "accessibility" in it })
        set("MODERN_CRYPTO_KEYSTORE", inBlob("keystore") || inBlob("keychain"))
        set("MODERN_MULTIPLE_C2_DOMAINS", URL_HOST.findAll(blob).map { it.groupValues[1] }.toHashSet().size >= 5)
        set("MODERN_NFC_EMULATION", has("NFC") && ("emulation" in allComponents || "hostapdu" in allComponents))
        set("MODERN_MQTT_SERVICE", "mqtt" in allComponents || "paho" in allComponents || inBlob("mqtt"))
        set("MODERN_FIREBASE_MESSAGING", "firebasemessagingservice" in allComponents)
        set("MODERN_AD_ID_PERMS", listOf("AD_ID", "ACCESS_ADSERVICES_AD_ID", "ACCESS_ADSERVICES_ATTRIBUTION").any(::has))
        set("MODERN_GET_INSTALL_REFERRER", has("BIND_GET_INSTALL_REFERRER_SERVICE"))
        set("MODERN_RANDOM_CLASS_NAME", classNamesRaw.count { looksRandom(it.substringAfterLast('.')) } >= 3)
        set("MODERN_RANDOM_PACKAGE", pkg.isNotEmpty() && looksRandom(pkg.replace(".", "")))
        set("MODERN_SHORT_SERVICE_NAME", services.any { s ->
            val last = s.substringAfterLast('.')
            last.length <= 3 && pyIsAlpha(last)
        })
        set("MODERN_MULTI_FG_SERVICE", shortPerms.count { it.startsWith("FOREGROUND_SERVICE_") } >= 3)
        set("MODERN_WORKMANAGER", "androidx.work" in allComponents || inBlob("workmanager"))
        set("MODERN_WEBVIEW", inBlob("webview") || "android.webkit" in allClasses)
        set("MODERN_TELEPHONY_COMBO", has("CALL_PHONE") && has("READ_PHONE_STATE"))
        set("MODERN_SMS_TRIAD", has("SEND_SMS") && has("RECEIVE_SMS") && has("READ_SMS"))
        set("MODERN_EXT_STORAGE_COMBO", has("MANAGE_EXTERNAL_STORAGE") && has("WRITE_EXTERNAL_STORAGE"))
        set("MODERN_LOCATION_COMBO", has("ACCESS_FINE_LOCATION") && has("ACCESS_COARSE_LOCATION"))
        set("MODERN_AD_SDK_PRESENT", anyInBlob(listOf("admob", "com.google.android.gms.ads", "com.facebook.ads", "applovin", "unityads", "vungle", "inmobi")))
        set("MODERN_TINY_APK", facts.apkFile.length() / 1024.0 < 1024)
        var shortRandom = 0
        var seen = 0
        for (s in allStrings) {
            if (seen++ == 10_000) break
            val len = s.codePointCount(0, s.length)
            if (len in 4..12 && s.count { it.isLetter() } >= len * 0.8 && s.none { it in "aeiou" }) shortRandom++
        }
        set("MODERN_OBFUSCATED_STRINGS", shortRandom > 200)
        set("MODERN_WEBVIEW_JS_BRIDGE", "addjavascriptinterface" in allMethods)
        set("MODERN_PERSISTENCE_COMBO", has("RECEIVE_BOOT_COMPLETED") &&
            ("androidx.work" in allComponents || allComponents.split(WHITESPACE).any { "jobscheduler" in it }))
        set("MODERN_ANTI_ANALYSIS", anyInBlob(listOf("/su", "superuser", "isrooted", "emulator", "qemu", "genymotion")))
        set("MODERN_DYNAMIC_CODE_LOAD", inBlob("dexclassloader") || inBlob("pathclassloader"))
        set("MODERN_EXFIL_STORAGE", has("WRITE_EXTERNAL_STORAGE") && has("INTERNET") &&
            (inBlob("zip") || inBlob("gzip") || inBlob("compress")))
        set("MODERN_RANDOM_PERMISSION", customPerms.any { looksRandom(it.substringAfterLast('.')) })
        set("MODERN_MANY_CUSTOM_PERMS", customPerms.size >= 3)
        set("MODERN_VPN_SERVICE", services.any { "vpn" in it.lowercase() })
        set("MODERN_RANDOM_PKG_SEGMENT", pkg.isNotEmpty() && pkg.split('.').count(::looksRandom) >= 2)
        set("MODERN_RANDOM_SERVICE_CLASS", services.any { looksRandom(it.substringAfterLast('.')) })
        set("MODERN_LOCATION_FGS", has("FOREGROUND_SERVICE_LOCATION"))
        set("MODERN_MICROPHONE_FGS", has("FOREGROUND_SERVICE_MICROPHONE"))
        set("MODERN_ANDROID13_PERMS", ANDROID13_PERMS.any(::has))

        // ---------------- V6 (modern trojan families)
        set("MODERN_VNC_RAT", anyInBlob(listOf("vnc", "rfb", "tightvnc", "ultravnc", "vnckeyboard", "vncconnection")))
        val hasUpdate = anyInBlob(listOf("system update", "security update", "android update", "update required", "critical update"))
        val isSideloaded = !pkg.startsWith("com.android.") && !pkg.startsWith("com.google.")
        set("MODERN_FAKE_UPDATE_PROMPT", hasUpdate && has("INTERNET") && isSideloaded)
        val hasA11y = services.any { "accessibility" in it.lowercase() }
        val hasScreen = anyInBlob(listOf("mediaprojection", "virtualdisplay", "screencapture"))
        set("MODERN_ACCESSIBILITY_VNC", hasA11y && hasScreen)
        set("MODERN_UI_TREE_LOGGING", "getrootinactivewindow" in allMethods || inBlob("getrootinactivewindow"))
        set("MODERN_MEDIA_PROJECTION_FGS", has("FOREGROUND_SERVICE_MEDIA_PROJECTION"))
        set("MODERN_ACCESSIBILITY_KEYLOG", inBlob("type_view_text_changed") || inBlob("type_view_text_selection_changed"))
        set("MODERN_NO_LAUNCHER_ACTIVITY", !facts.hasLauncher)
        if (archive != null) {
            val names = archive.names
            val duplicates = names.size - names.toSet().size
            set("MODERN_APK_TAMPERED", duplicates > 0 || names.none { "AndroidManifest.xml" in it } || names.none { "classes.dex" in it })
            set("MODERN_EMBEDDED_PAYLOAD", archive.entries.any { e ->
                val low = e.name.lowercase()
                (low.startsWith("res/raw/") || low.startsWith("assets/")) &&
                    PAYLOAD_EXT.any { low.endsWith(it) } && e.uncompressedSize > 50_000
            })
        }
        set("MODERN_FAKE_JOB_LURE", JOB.count { inBlob(it) } >= 2)
        set("MODERN_FAKE_UPDATE_UI", hasUpdate && isSideloaded)
        set("MODERN_DROPPER_LOADER", LOADER.count { inBlob(it) } >= 3)
        set("MODERN_TRADINGVIEW_LURE", anyInBlob(TRADE))
        set("MODERN_NFC_HCE", "hostapduservice" in allComponents || "hostapdu" in allComponents)
        set("MODERN_NFC_RELAY", has("NFC") && has("INTERNET") &&
            listOf("getdefaultadapter", "isenabled", "enableforegrounddispatch").any { it in allMethods })
        set("MODERN_SPYNOTE_COMBO", hasA11y && has("NFC") && has("SYSTEM_ALERT_WINDOW"))
        set("MODERN_WEBVIEW_OVERLAY", has("SYSTEM_ALERT_WINDOW") && (services + activities).any { "webview" in it.lowercase() })
        set("MODERN_SMS_INTERCEPT", receivers.any { "sms" in it.lowercase() } && has("READ_SMS"))
        set("MODERN_DEVICE_ADMIN_PERSIST", services.any { "deviceadmin" in it.lowercase() } && hasA11y)
        set("MODERN_BANK_IMPERSONATION", BANKS.any { inBlob(it) } && pkg !in OFFICIAL_BANK_PKGS && isSideloaded)
        set("MODERN_RAT_STRINGS", anyInBlob(RAT))
        set("MODERN_SECOND_STAGE_LOADER", inBlob("dexclassloader") && (inBlob("assets/") || inBlob("res/raw")))
        set("MODERN_SCREEN_OFF_ACTIVITY", shortPerms.any { "wake_lock" in it.lowercase() } && hasA11y)
        set("MODERN_CRYPTO_STEALER", ("MODERN_CRYPTO_WALLET_PATHS" in fired || "MODERN_CRYPTO_KEYSTORE" in fired) &&
            listOf("SEND_SMS", "READ_SMS", "RECEIVE_SMS").any(::has))

        return StaticFeatures(fired, dexFiles, dexUnreadable, allStrings.size, methodCount)
    }

    /** Feature vector in model order: 1f if the feature fired, else 0f. */
    fun vector(order: List<String>, fired: Set<String>): FloatArray = FloatArray(order.size) { if (order[it] in fired) 1f else 0f }

    companion object {
        const val BLOB_LIMIT = 30_000
        /** Must stay false until the boost weights are re-tuned on real string content (see class doc). */
        const val USE_STRING_CONTENT = false
        private val URL_HOST = Regex("https?://([a-z0-9.-]+)")
        private val WHITESPACE = Regex("\\s+")
        private val PAYLOAD_EXT = listOf(".bin", ".dat", ".enc", ".apk", ".dex")
        private val ANDROID13_PERMS = listOf(
            "NEARBY_WIFI_DEVICES", "POST_NOTIFICATIONS", "READ_MEDIA_IMAGES", "FOREGROUND_SERVICE_LOCATION",
            "FOREGROUND_SERVICE_MICROPHONE", "FOREGROUND_SERVICE_MEDIA_PLAYBACK", "NAVIGATION_TEMPLATES",
            "ACCESS_SURFACE", "RAISED_THREAD_PRIORITY",
        )
        private val JOB = listOf("recruitment", "job offer", "hiring", "salary", "work from home", "part time job", "earn money", "daily payment")
        private val LOADER = listOf("payload", "decrypt", "aes", "unpack", "extract_apk", "load_dex", "download_apk", "install_payload")
        private val TRADE = listOf("tradingview", "binance", "coinbase", "metamask", "trustwallet", "crypto invest", "forex trading")
        private val BANKS = listOf("sbi", "hdfc", "icici", "axis", "kotak", "paytm", "phonepe", "gpay", "googlepay", "pnb", "bob", "canara")
        private val OFFICIAL_BANK_PKGS = setOf("com.sbi.lotusintouch", "net.one97.paytm", "com.phonepe.app", "com.google.android.apps.nbu.paisa.user")
        private val RAT = listOf("rat_client", "remote_shell", "reverse_shell", "bind_shell", "keylogger", "screen_stream", "mic_record", "cam_capture")

        /** extract_v6.normalize: drops the FIRST 'L' anywhere (a quirk kept on purpose), ';' and '/'. */
        fun normalize(s: String): String = s.replaceFirst("L", "").replace(";", "").replace('/', '.').lowercase()

        /** Python str.isupper(). */
        fun pyIsUpper(s: String): Boolean = s.any { it.isUpperCase() } && s.none { it.isLowerCase() }

        /** Python str.isalpha(): non-empty and all letters. */
        fun pyIsAlpha(s: String): Boolean = s.isNotEmpty() && s.all { it.isLetter() }

        /** extract_v6.looks_random, line for line. */
        fun looksRandom(s: String): Boolean {
            if (s.length < 4) return false
            val letters = s.filter { it.isLetter() }
            if (letters.length < 4) return false
            val vowels = letters.count { it.lowercaseChar() in "aeiou" }
            if (pyIsUpper(s) && s.length >= 6 && vowels < 3) return true
            var runs = 0
            var cur = 0
            for (c in letters) {
                if (c.lowercaseChar() !in "aeiou") {
                    cur++
                    if (cur >= 4) { runs++; cur = 0 }
                } else {
                    cur = 0
                }
            }
            if (runs >= 2) return true
            if (letters.length >= 6 && vowels <= 1) return true
            return false
        }
    }
}
