package com.nanocomm.nanosmart.eventos

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertDisplayPolicyTest {
    @Test
    fun `la pantalla principal muestra solamente cinco alertas`() {
        assertEquals((1..5).toList(), AlertDisplayPolicy.main((1..40).toList()))
    }

    @Test
    fun `el historico conserva como maximo treinta alertas`() {
        assertEquals((1..30).toList(), AlertDisplayPolicy.history((1..40).toList()))
    }
}
