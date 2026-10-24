package dev.streetsnap.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessMemoryFormatTest {
    @Test
    fun formatsMegabytesWithOneDecimal() {
        assertEquals("512.0 MB", formatMemoryKb(524_288L))
        assertEquals("1.5 MB", formatMemoryKb(1_536L))
    }

    @Test
    fun formatsSmallKilobyteValues() {
        assertEquals("768 KB", formatMemoryKb(768L))
        assertEquals("1 KB", formatMemoryKb(1L))
    }

    @Test
    fun zeroIsMegabytesNotKb() {
        assertEquals("0 MB", formatMemoryKb(0L))
    }
}
