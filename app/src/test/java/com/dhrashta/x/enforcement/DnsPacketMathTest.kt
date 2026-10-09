package com.dhrashta.x.enforcement

import org.junit.Assert.assertEquals
import org.junit.Test

class DnsPacketMathTest {
    @Test
    fun ipv4HeaderChecksumMatchesKnownValue() {
        // Classic example header (checksum field zeroed): expected checksum 0xB861.
        val header = intArrayOf(
            0x45, 0x00, 0x00, 0x73, 0x00, 0x00, 0x40, 0x00, 0x40, 0x11,
            0x00, 0x00, 0xC0, 0xA8, 0x00, 0x01, 0xC0, 0xA8, 0x00, 0xC7,
        ).map(Int::toByte).toByteArray()
        assertEquals(0xB861, DnsForwarder.checksum(header, 0, header.size, 0))
    }

    @Test
    fun readWriteRoundTrip() {
        val buffer = ByteArray(8)
        DnsForwarder.writeU32(buffer, 0, 0x0A000035)
        DnsForwarder.writeU16(buffer, 4, 0xBEEF)
        assertEquals(0x0A000035L, DnsForwarder.readU32(buffer, 0))
        assertEquals(0xBEEF, DnsForwarder.readU16(buffer, 4))
    }
}
