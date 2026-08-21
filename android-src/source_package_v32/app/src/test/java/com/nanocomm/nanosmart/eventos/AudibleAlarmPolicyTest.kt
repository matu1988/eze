package com.nanocomm.nanosmart.eventos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudibleAlarmPolicyTest {
    @Test
    fun `activa sonido para emergencias y robo`() {
        val criticalCodes = listOf("100", "110", "120") + (130..139).map(Int::toString)
        for (code in criticalCodes) {
            assertTrue(AudibleAlarmPolicy.shouldSound(mapOf("type" to "ALERT", "eventCode" to code)))
        }
    }

    @Test
    fun `no activa sonido para estado del panel ni otros eventos`() {
        assertFalse(
            AudibleAlarmPolicy.shouldSound(
                mapOf("type" to "PANEL_STATE", "eventCode" to "130")
            )
        )
        assertFalse(AudibleAlarmPolicy.shouldSound(mapOf("type" to "ALERT", "eventCode" to "401")))
        assertFalse(AudibleAlarmPolicy.shouldSound(emptyMap()))
    }
}
