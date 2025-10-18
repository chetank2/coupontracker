package com.example.coupontracker.extraction.deterministic

import com.example.coupontracker.extraction.region.CouponRegionizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class DeterministicCouponExtractorTest {

    private val storeCanon = StoreCanon.fromConfig(
        StoreCanon.StoreCanonConfig(
            entries = listOf(
                StoreCanon.StoreEntry("Myntra", listOf("myntra")),
                StoreCanon.StoreEntry("boAt", listOf("boat")),
                StoreCanon.StoreEntry("Times Prime", listOf("timesprime", "times prime"))
            ),
            badWords = setOf("now", "shop")
        )
    )

    private val extractor = DeterministicCouponExtractor(
        storeCanon = storeCanon,
        rewardDropPhrases = listOf("you won", "jackpot")
    )

    @Test
    fun `extractor finds offer code and store`() {
        val text = """
            Flat T300 Off | Use code MISSED20
            Myntra Exclusive Sale
        """.trimIndent()

        val result = extractor.extract(text, CouponRegionizer.RegionMode.DEFAULT)

        assertEquals("Flat ₹300 Off", result.offer)
        assertEquals("MISSED20", result.code)
        assertEquals("Myntra", result.storeCandidate)
        assertFalse(result.requiresFallback())
    }

    @Test
    fun `reward mode drops celebratory phrases`() {
        val text = """
            You won a jackpot!
            Claim boAt rewards now
            Upto 80% Off on Earbuds
        """.trimIndent()

        val result = extractor.extract(text, CouponRegionizer.RegionMode.REWARD)

        assertNotNull(result.offer)
        assertEquals("Upto 80% Off", result.offer)
    }
}
