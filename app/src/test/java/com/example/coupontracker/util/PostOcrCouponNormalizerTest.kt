package com.example.coupontracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostOcrCouponNormalizerTest {

    @Test
    fun normalizerKeepsRawOcrOutOfDescription() {
        val rawOcr = """
            11:18
            HDFC
            vouchers (5) VOUCH
            CODE
            XXXXXX
            EXPIRES
            Jun 30, 2026
            Get 33% off 750 Ca
            ₹150 off
            Get Extra 20% off
            Copy code
            Terms and conditions apply
        """.trimIndent()

        val result = PostOcrCouponNormalizer.normalize(
            currentDescription = rawOcr,
            ocrText = rawOcr,
            storeName = "HDFC",
            redeemCode = null,
        )

        assertEquals("Get 33% off 750 Ca\nTerms and conditions apply", result.description)
        assertFalse(result.description.orEmpty().contains("vouchers", ignoreCase = true))
        assertFalse(result.description.orEmpty().contains("CODE", ignoreCase = true))
        assertTrue(result.issues.contains("raw_ocr_description_replaced"))
    }

    @Test
    fun normalizerDropsZeroCashbackNoise() {
        val result = PostOcrCouponNormalizer.normalize(
            currentDescription = "cashback: 0.0\ncashback 0.0\nGet Extra 20% off",
            ocrText = "cashback: 0.0\ncashback 0.0\nGet Extra 20% off",
            storeName = "TrueBasics",
            redeemCode = "TRUE20",
        )

        assertEquals("Get Extra 20% off", result.description)
        assertFalse(result.description.orEmpty().contains("0.0"))
    }

    @Test
    fun normalizerCorrectsMisreadRupeeSymbolForBeardo() {
        val rawOcr = """
            Beardo
            Extra 20% off on bank cards
            Z499 on Beardo
            Use code BEARDO499
        """.trimIndent()

        val result = PostOcrCouponNormalizer.normalize(
            currentDescription = rawOcr,
            ocrText = rawOcr,
            storeName = "Beardo",
            redeemCode = "BEARDO499",
        )

        assertEquals("₹499 on Beardo", result.description)
        assertFalse(result.description.orEmpty().contains("20%"))
    }

    @Test
    fun normalizerExtractsUsefulXYXXOfferFromWalletListOcr() {
        val rawOcr = """
            vouchers
            active
            18
            lifetime
            428
            XYXX4.31
            you
            get
            XYXX
            polo
            t-shirts
            from
            7599
            50
            cashback
            via
            CRED
            pay
            Redeem
            Now
            EXPIRES
            IN
            05
            DAYS
        """.trimIndent()

        val result = PostOcrCouponNormalizer.normalize(
            currentDescription = "cashback",
            ocrText = rawOcr,
            storeName = "XYXX",
            redeemCode = null,
        )

        assertEquals("50 cashback", result.description)
        assertEquals("Cashback: ₹50", result.cashbackDetail)
    }

    @Test
    fun normalizerKeepsSplitFreebieOfferLinesTogether() {
        val rawOcr = """
            Lenskart
            Gold Membership
            Free*
            Single vision lenses
            Code
            LENSFREE
            Expires in 5 days
        """.trimIndent()

        val result = PostOcrCouponNormalizer.normalize(
            currentDescription = "free*",
            ocrText = rawOcr,
            storeName = "Lenskart",
            redeemCode = "LENSFREE",
        )

        assertEquals(
            "Gold Membership\nFree\nSingle vision lenses",
            result.description
        )
    }

    @Test
    fun normalizerRepairsMissingRupeeGlyphInCommercialAtPrice() {
        val rawOcr = """
            The Man Company
            Buy any 4 products at
            699*
            Buy any 4 products at 7699*
            Code: TMCPE6990425SQTJ
        """.trimIndent()

        val result = PostOcrCouponNormalizer.normalize(
            currentDescription = "Buy any 4 products at 7699*",
            ocrText = rawOcr,
            storeName = "The Man Company",
            redeemCode = "TMCPE6990425SQTJ",
        )

        assertEquals("Buy any 4 products at ₹699*", result.description)
    }

    @Test
    fun normalizerKeepsTecMarxDescriptionOfferFocused() {
        val rawOcr = """
            TECMARY
            Get Roar Bluetooth Earbuds @
            299*
            only on TecMarx website
            BUY NOW
        """.trimIndent()

        val result = PostOcrCouponNormalizer.normalize(
            currentDescription = "TECMARY Get Roar Bluetooth Earbuds @ 299* only on TecMarx website BUY NOW",
            ocrText = rawOcr,
            storeName = "TecMarx",
            redeemCode = null,
        )

        assertEquals("Get Roar Bluetooth Earbuds @ ₹299*", result.description)
        assertFalse(result.description.orEmpty().contains("TECMARY"))
        assertFalse(result.description.orEmpty().contains("BUY NOW", ignoreCase = true))
        assertFalse(result.description.orEmpty().contains("website", ignoreCase = true))
    }

    @Test
    fun normalizerDoesNotRewriteRealSevenThousandPricesWithoutArtifactMarker() {
        val rawOcr = """
            Premium Store
            Save on premium kit from 7999
            Code: PREMIUM7999
        """.trimIndent()

        val result = PostOcrCouponNormalizer.normalize(
            currentDescription = "Save on premium kit from 7999",
            ocrText = rawOcr,
            storeName = "Premium Store",
            redeemCode = "PREMIUM7999",
        )

        assertEquals("Save on premium kit from 7999", result.description)
    }

    @Test
    fun normalizerDoesNotRewriteLegitimateSevenThousandPricesWithAsterisk() {
        val rawOcr = """
            Travel Store
            Save on hotel stays from 7999*
            Code: HOTEL7999
        """.trimIndent()

        val result = PostOcrCouponNormalizer.normalize(
            currentDescription = "Save on hotel stays from 7999*",
            ocrText = rawOcr,
            storeName = "Travel Store",
            redeemCode = "HOTEL7999",
        )

        assertEquals("Save on hotel stays from 7999*", result.description)
    }

    @Test
    fun normalizerDoesNotRewriteProductsAtSevenThousandWithAsterisk() {
        val rawOcr = """
            Premium Store
            Save on premium products at 7499*
            Code: PREMIUM7499
        """.trimIndent()

        val result = PostOcrCouponNormalizer.normalize(
            currentDescription = "Save on premium products at 7499*",
            ocrText = rawOcr,
            storeName = "Premium Store",
            redeemCode = "PREMIUM7499",
        )

        assertEquals("Save on premium products at 7499*", result.description)
    }

    @Test
    fun normalizerPrefersCommercialPriceLineOverLegalAndSupportTerms() {
        val rawOcr = """
            ANTARA
            EXPIRES IN 13 DAYS
            offer details
            terms and conditions
            1. Apple/Google is not a sponsor of, or involved in, this
            contest/sweepstakes in any manner.
            2. One Touch Digital BP by AGEasy worth 1499 for 7899
            3. no minimum spend required
            4. customer care details: +919911789911
            support@ageasybyantara.com
            5. no delivery charges
            6. offer valid once per user
            code: CREDBP
            Redeem Now
        """.trimIndent()

        val result = PostOcrCouponNormalizer.normalize(
            currentDescription = "3. no minimum spend required 4. customer care",
            ocrText = rawOcr,
            storeName = "AGEasy",
            redeemCode = "CREDBP",
        )

        assertEquals("One Touch Digital BP by AGEasy worth ₹1499 for ₹7899", result.description)
        assertFalse(result.description.orEmpty().contains("customer care", ignoreCase = true))
        assertFalse(result.description.orEmpty().contains("minimum spend", ignoreCase = true))
    }
}
