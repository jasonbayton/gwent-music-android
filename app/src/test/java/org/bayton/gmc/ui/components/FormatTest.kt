package org.bayton.gmc.ui.components

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun `money retains UK separators regardless of device locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("£1,234.50", money(1234.5))
            assertEquals("£-12.50", money(-12.5))
            assertEquals("£0.00", money(0.0))
            assertEquals("£1.00", money(1.0, null))
            assertEquals("EUR 1.00", money(1.0, "EUR "))
            assertEquals("", money(null))
        } finally {
            Locale.setDefault(original)
        }
    }
}
