package com.dhrashta.x.decision

import com.dhrashta.x.data.Event
import com.dhrashta.x.data.EventLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CausalChainsTest {
    private var nextId = 1L
    private fun ev(pkg: String, type: String, ts: Long) = Event(id = nextId++, timestamp = ts, pkg = pkg, signalId = type, weight = 0)

    @Test
    fun deviceWideOnlySequenceDoesNotScoreEveryApp() {
        val now = 1_000_000L
        val events = listOf(
            ev(EventLogger.DEVICE_PKG, EventLogger.WORK_PROFILE_CREATED, now - 60_000),
            ev(EventLogger.DEVICE_PKG, EventLogger.CLONED_APP_LAUNCHED, now - 30_000),
        )
        assertEquals(0, CausalChains.match(events, now, "com.innocent.app"))
    }

    @Test
    fun chainCountsWhenTheAppItselfTookAStep() {
        val now = 1_000_000L
        val events = listOf(
            ev(EventLogger.DEVICE_PKG, EventLogger.WORK_PROFILE_CREATED, now - 60_000),
            ev("com.cloned.app", EventLogger.CLONED_APP_LAUNCHED, now - 30_000),
        )
        assertTrue(CausalChains.matchedChains(events, now, "com.cloned.app").any { it.id == "CC-5" })
        assertEquals(0, CausalChains.match(events, now, "com.other.app"))
    }

    @Test
    fun bankThenBlockedDecoyAttemptIsCc3() {
        val now = 1_000_000L
        val events = listOf(
            ev(EventLogger.DEVICE_PKG, EventLogger.BANK_FOREGROUND, now - 20_000),
            ev("com.suspect", EventLogger.CANARY_SEND_ATTEMPT, now - 5_000),
        )
        assertTrue(CausalChains.matchedChains(events, now, "com.suspect").any { it.id == "CC-3" })
    }

    @Test
    fun anotherAppsEventsAreIgnored() {
        val now = 1_000_000L
        val events = listOf(
            ev("com.a", EventLogger.SIDELOAD, now - 60_000),
            ev("com.b", EventLogger.A11Y_ENABLED, now - 30_000),
        )
        assertEquals(0, CausalChains.match(events, now, "com.b"))
    }
}
