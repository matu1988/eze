package com.nanocomm.nanosmart.eventos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyLocationPolicyTest {
    @Test
    fun `acepta coordenadas geograficas validas`() {
        assertTrue(EmergencyLocationPolicy.validCoordinates(-34.6037, -58.3816))
        assertTrue(EmergencyLocationPolicy.validCoordinates(90.0, 180.0))
        assertFalse(EmergencyLocationPolicy.validCoordinates(91.0, -58.0))
        assertFalse(EmergencyLocationPolicy.validCoordinates(-34.0, -181.0))
    }

    @Test
    fun `descarta ubicaciones anteriores a diez minutos`() {
        val now = 1_000_000L
        assertTrue(EmergencyLocationPolicy.isRecent(now, now - 599_999L))
        assertTrue(EmergencyLocationPolicy.isRecent(now, now - 600_000L))
        assertFalse(EmergencyLocationPolicy.isRecent(now, now - 600_001L))
    }
}
