package com.dhrashta.x.enforcement.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveWindowFeaturesTest {
    @Test
    fun windowMatchesMischiefDefinition() {
        val f = LiveWindowFeatures()
        val t0 = 1_000_000_000L // window-aligned
        // SYN out, data in, UDP out to a second host
        f.record(7, t0 + 0, 60, true, 0x01010101, 443, Ipv4.TCP, true)
        f.record(7, t0 + 500, 1500, false, 0x01010101, 443, Ipv4.TCP, false)
        f.record(7, t0 + 2_000, 80, true, 0x08080808, 53, Ipv4.UDP, false)
        val seq = f.roll(t0 + 10_000).single()
        assertEquals(7, seq.uid)
        assertEquals(10, seq.windows.size)
        assertTrue(seq.windows.dropLast(1).all { w -> w.all { it == 0f } })
        val expected = floatArrayOf(
            140f, 1500f, 2f, 1f, (140.0 / 1501.0).toFloat(), (1640.0 / 3).toFloat(),
            2f, 2f, 1.0f, 0.5f, 1f, (1.0 / 3).toFloat(),
        )
        assertArrayEquals(expected, seq.windows.last(), 1e-5f)
    }

    @Test
    fun idleAppsDropOutAfterTenEmptyWindows() {
        val f = LiveWindowFeatures()
        f.record(1, 0, 100, true, 1, 80, Ipv4.TCP, false)
        assertEquals(1, f.roll(10_000).size)
        assertEquals(1, f.roll(100_000).size)   // the busy window is still within the last 10
        assertEquals(0, f.roll(200_000).size)   // now 10+ idle windows: nothing to score
    }
}
