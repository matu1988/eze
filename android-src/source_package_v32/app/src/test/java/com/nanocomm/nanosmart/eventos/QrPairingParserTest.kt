package com.nanocomm.nanosmart.eventos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrPairingParserTest {
    @Test
    fun `interpreta la etiqueta generada por el receptor`() {
        val pairing = QrPairingParser.parse(
            "NS1|869671077527867|NS-ABCD-EFGH-JKLM-NPQR"
        )
        assertEquals("869671077527867", pairing?.imei)
        assertEquals("NS-ABCD-EFGH-JKLM-NPQR", pairing?.accessKey)
    }

    @Test
    fun `rechaza codigos ajenos o incompletos`() {
        assertNull(QrPairingParser.parse("https://ejemplo.com"))
        assertNull(QrPairingParser.parse("NS1|123|NS-ABCD-EFGH-JKLM-NPQR"))
        assertNull(QrPairingParser.parse("NS1|869671077527867|clave"))
    }
}
