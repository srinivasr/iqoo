package com.dhrashta.x.decision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandHeuristicTest {
    @Test
    fun officialBankAppIsNotAnImpersonator() {
        assertFalse(BrandHeuristic.mimics("YONO SBI", "com.sbi.lotusintouch", "com.android.vending", false))
        assertFalse(BrandHeuristic.mimics("SBI", "com.sbi.lotusintouch", null, false))
    }

    @Test
    fun unrelatedNamesContainingLettersDoNotMatch() {
        assertFalse(BrandHeuristic.matchesName("Usb input", "com.android.usbinput"))
        assertFalse(BrandHeuristic.matchesName("Hobbies", "com.example.hobbies"))
    }

    @Test
    fun sideloadedLookalikesMatch() {
        assertTrue(BrandHeuristic.mimics("SBI KYC Update", "com.fake.sbi.kyc", null, false))
        assertTrue(BrandHeuristic.mimics("Aadhar Update", "com.update.aadhar", null, false))
        assertTrue(BrandHeuristic.mimics("PM Kisan", "in.incometaxx.refund", null, false))
    }

    @Test
    fun playStoreAndSystemAppsAreNotFlaggedByName() {
        assertFalse(BrandHeuristic.mimics("SBI Card", "com.sbicard.app", "com.android.vending", false))
        assertFalse(BrandHeuristic.mimics("SBI", "com.vendor.sbi", null, true))
    }
}
