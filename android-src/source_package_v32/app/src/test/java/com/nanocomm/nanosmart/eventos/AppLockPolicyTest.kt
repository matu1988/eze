package com.nanocomm.nanosmart.eventos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPolicyTest {
    @Test
    fun `mantiene abierta la sesion antes de treinta segundos`() {
        assertFalse(AppLockPolicy.shouldLock(29_999L))
    }

    @Test
    fun `bloquea la app a partir de treinta segundos`() {
        assertTrue(AppLockPolicy.shouldLock(30_000L))
        assertTrue(AppLockPolicy.shouldLock(90_000L))
    }
}
