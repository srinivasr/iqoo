package com.dhrashta.x.ai.staticml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Helpers must behave exactly like extract_v6.py. Full-APK parity against the Python reference is
 * checked with tools/static_parity (real APKs are not committed).
 */
class StaticFeatureExtractorTest {
    @Test
    fun normalizeKeepsThePythonQuirk() {
        assertEquals("java.lang.class.getmethods", StaticFeatureExtractor.normalize("Ljava.lang.Class.getMethods"))
        // The FIRST 'L' anywhere is dropped, so this intent never matches a real string (as in training).
        assertEquals("android.intent.action.boot_competed", StaticFeatureExtractor.normalize("android.intent.action.BOOT_COMPLETED"))
        assertEquals("com.foo.bar", StaticFeatureExtractor.normalize("Lcom/foo/Bar;"))
    }

    @Test
    fun pythonStringPredicates() {
        assertTrue(StaticFeatureExtractor.pyIsUpper("SEND_SMS"))
        assertFalse(StaticFeatureExtractor.pyIsUpper("android.intent.action.SEND"))
        assertFalse(StaticFeatureExtractor.pyIsUpper("123_"))
        assertTrue(StaticFeatureExtractor.pyIsAlpha("abc"))
        assertFalse(StaticFeatureExtractor.pyIsAlpha(""))
        assertFalse(StaticFeatureExtractor.pyIsAlpha("a1"))
    }

    @Test
    fun looksRandomMatchesReference() {
        // Values checked against extract_v6.looks_random.
        assertTrue(StaticFeatureExtractor.looksRandom("xkqzrtbvmw"))
        assertTrue(StaticFeatureExtractor.looksRandom("QWRTYP"))
        assertFalse(StaticFeatureExtractor.looksRandom("MainActivity"))
        assertFalse(StaticFeatureExtractor.looksRandom("abc"))
        assertFalse(StaticFeatureExtractor.looksRandom("service"))
    }

    @Test
    fun manifestRulesAndTamperCheckOnASyntheticApk() {
        val apk = File.createTempFile("s0scan", ".apk").apply { deleteOnExit() }
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml")); zip.write(ByteArray(10)); zip.closeEntry()
            // No classes.dex at all: tampered by the reference rule. Plus a big payload in assets.
            zip.putNextEntry(ZipEntry("assets/core.dat")); zip.write(ByteArray(60_000)); zip.closeEntry()
        }
        val facts = ApkFacts(
            pkg = "com.xkqzrt.bvmwpl",
            permissions = listOf(
                "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS", "android.permission.READ_SMS",
                "android.permission.INTERNET", "android.permission.SYSTEM_ALERT_WINDOW", "android.permission.NFC",
            ),
            services = listOf("com.x.AccessibilityHelper", "com.x.Abc"),
            activities = emptyList(),
            receivers = listOf("com.x.SmsReceiver"),
            hasLauncher = false,
            apkFile = apk,
        )
        val fired = StaticFeatureExtractor(listOf("SEND_SMS", "READ_SMS", "INTERNET", "transact")).extract(facts).fired
        for (f in listOf(
            "SEND_SMS", "READ_SMS", "INTERNET", "MODERN_SMS_TRIAD", "MODERN_SMS_INTERNET_COMBO", "MODERN_A11Y_SERVICE",
            "MODERN_OVERLAY_AND_A11Y", "MODERN_SHORT_SERVICE_NAME", "MODERN_NO_LAUNCHER_ACTIVITY", "MODERN_APK_TAMPERED",
            "MODERN_EMBEDDED_PAYLOAD", "MODERN_SMS_INTERCEPT", "MODERN_SPYNOTE_COMBO", "MODERN_RANDOM_PKG_SEGMENT",
            "MODERN_TINY_APK",
        )) assertTrue("$f should fire", f in fired)
        assertFalse("transact" in fired)
    }

    @Test
    fun boostFollowsPredictV6() {
        val boost = StaticBoost(
            strongAlone = mapOf("MODERN_SMS_TRIAD" to 0.30),
            v6Strong = mapOf("MODERN_SMS_INTERCEPT" to 0.25),
            weak = mapOf("MODERN_FIREBASE_MESSAGING" to 0.05),
            coOccurrence = mapOf("MODERN_MANY_CUSTOM_PERMS" to 0.15),
            strictTriggers = setOf("MODERN_SMS_TRIAD"),
        )
        assertEquals(0.05, boost.apply(setOf("MODERN_MANY_CUSTOM_PERMS", "MODERN_FIREBASE_MESSAGING")).boost, 1e-9)
        assertEquals(0.70, boost.apply(setOf("MODERN_SMS_TRIAD", "MODERN_SMS_INTERCEPT", "MODERN_MANY_CUSTOM_PERMS")).boost, 1e-9)
    }
}
