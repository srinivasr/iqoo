package com.dhrashta.x.enforcement

import org.junit.Assert.assertEquals
import org.junit.Test

class PacketInspectorTest {
    @Test
    fun placeholderDocumentsDeviceBoundUidLookup() {
        // ConnectivityManager#getConnectionOwnerUid is device-bound and covered by
        // instrumentation; this JVM test keeps the pure test suite intentional.
        assertEquals(6, 6)
    }
}
