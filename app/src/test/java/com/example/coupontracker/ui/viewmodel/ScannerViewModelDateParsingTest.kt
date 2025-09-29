package com.example.coupontracker.ui.viewmodel

import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Locale

class ScannerViewModelDateParsingTest {

    @Test
    fun `parseExpiryDate handles slash separated two digit year`() {
        val result = ScannerViewModel.parseExpiryDate("31/12/24", Locale.UK)

        assertNotNull(result)
    }

    @Test
    fun `parseExpiryDate handles month text with two digit year`() {
        val result = ScannerViewModel.parseExpiryDate("15 Aug 25", Locale.UK)

        assertNotNull(result)
    }
}
