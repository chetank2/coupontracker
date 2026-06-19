package com.example.coupontracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TextExtractorTest {

    private val extractor = TextExtractor()

    private val samplePaytmOcr = """
        8:19
        Paytm
        M
        /oTTplay
        PREMIUM
        OTTplay
        149*
        LIONSGATS
        aha
        Code: TTPHONEBUFF
        Offer Details
        SUBSCRIBE NOW
        About OTTplay
        TELUGU
        WIN,
        Scratch card received on offer
        Enjoy 30+ OTTs at Just 149*
        SONY
        Expires on 31 May, 2025, 11:59 PM
        liv
        +26
        Enjoy 30+ OTTs at Just
        More OTTS
        Yo 5G36%
        includes SonyLIV, ZEE5, Sun NXT and much more,
        along with 500+ Live TV channels
        SUN
        NT
        500+
        Live Channels
        ZEE5
        COPY
    """.trimIndent()

    @Test
    fun `extractCouponInfoSync should recover store code and expiry from Paytm OCR`() {
        val result = extractor.extractCouponInfoSync(samplePaytmOcr)

        assertEquals("Paytm", result.storeName)
        assertEquals("TTPHONEBUFF", result.redeemCode)

        val expiry = result.expiryDate
        assertNotNull("Expected expiry date to be parsed", expiry)

        val calendar = Calendar.getInstance(Locale.US).apply { time = expiry!! }
        assertEquals(2025, calendar.get(Calendar.YEAR))
        assertEquals(Calendar.MAY, calendar.get(Calendar.MONTH))
        assertEquals(31, calendar.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `extractCouponInfoSync prefers commercial merchant phrase and offer line over footer noise`() {
        val text = """
            8:19
            X
            Paytm
            M
            Lhe
            Man
            CompanvJE
            MAN
            COHDANY
            BLANC
            POUR
            HOMME
            EALU
            DE
            TOILETTE
            Buy any 4 products at
            699*
            from The Man Company website
            Code: TMCPE6990425SQTJ
            Expires on 31 May, 2025, 11:59 PM
            About The Man Company
            Scratch card received on offer
            May, 2025, PM
            o
            vo
        """.trimIndent()

        val result = extractor.extractCouponInfoSync(text)

        assertEquals("The Man Company", result.storeName)
        assertEquals("TMCPE6990425SQTJ", result.redeemCode)
        assertEquals("Buy any 4 products at 699* from The Man Company website", result.description)
    }

    @Test
    fun `extractCouponInfoSync rejects expiry badge as store and description`() {
        val baseDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse("2026-06-18 22:53:50")
        val text = """
            PokerBaazi
            Details
            PokerBaazi
            code:
            PBJP75
            you won 100% bonus up to 775,000
            on your first deposit from
            PokerBaazi
            EXPIRES
            IN
            04
            HOURS
        """.trimIndent()

        val result = extractor.extractCouponInfoSync(text, baseDate)

        assertEquals("PokerBaazi", result.storeName)
        assertNull(result.toString(), result.redeemCode)
        assertEquals("you won 100% bonus up to 775,000 on your first deposit from PokerBaazi", result.description)
        assertNotNull(result.expiryDate)
    }

    @Test
    fun `extractRedeemCode supports lowercase code label followed by next line`() {
        val result = extractor.extractRedeemCode(
            """
                code:
                PBJP75
                you won 100% bonus up to 775,000
                EXPIRES
                IN
                04
                HOURS
            """.trimIndent()
        )

        assertEquals("PBJP75", result)
    }

    @Test
    fun `extractDescription combines multi-line offer text`() {
        val text = """
            Buy 2 Get 2 FREE*
            + Up to 5% Foxcoins
        """.trimIndent()

        val result = extractor.extractDescription(text)

        assertEquals(
            "Buy 2 Get 2 FREE* + Up to 5% Foxcoins",
            result
        )
    }

    @Test
    fun `extractDescription preserves upto connector without whitespace`() {
        val text = """
            Buy 2 Get 2 FREE* +
            Upto 5% Foxcoins
        """.trimIndent()

        val result = extractor.extractDescription(text)

        assertEquals(
            "Buy 2 Get 2 FREE* + Upto 5% Foxcoins",
            result
        )
    }

    @Test
    fun `extractDescription builds rupee summary when LLM returns placeholder`() {
        val text = """
            Minimalist
            Offer Details
            Flat 7100 Off + 750 Cashback
            Use code MNPPRK100UAPR255QYSGZA
        """.trimIndent()

        val result = extractor.extractDescription(text)

        assertEquals(
            "Minimalist Coupon - Flat ₹7100 off",
            result
        )
    }

    @Test
    fun `extractStoreName skips watermark noise like Pastm`() {
        val text = """
            Pastm rewards watermark
            Redeem on Aha Annual Plan Offer
        """.trimIndent()

        val result = extractor.extractStoreName(text)

        assertEquals("Aha", result)
    }

    @Test
    fun `extractStoreName removes leading details label`() {
        val text = """
            Details
            Leaf
            you won 16099 off on Leaf Halo Smart Ring
        """.trimIndent()

        val result = extractor.extractStoreName(text)

        assertEquals("Leaf", result)
    }

    @Test
    fun `extractStoreName rejects weak pm vit fragment and keeps real OCR brand`() {
        val text = """
            pm Vit
            Gritzo
            Flat off for kids Products on Gritzo
            Code PHGZQZG2DCY9WB
            ORDER NOW
        """.trimIndent()

        val result = extractor.extractStoreName(text)

        assertEquals("Gritzo", result)
    }

    @Test
    fun `extractStoreName normalizes wallet counter artifact without brand hardcode`() {
        val text = """
            vouchers
            active
            NOVA4.31
            you
            get
            NOVA
            polo
            t-shirts
            from
            7599
            50
            cashback
            via
            CRED
            pay
        """.trimIndent()

        val result = extractor.extractStoreName(text)

        assertEquals("NOVA", result)
    }

    @Test
    fun `extractCouponInfoSync scopes fields to matching store block in wallet OCR`() {
        val baseDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2026-06-16")
        val text = """
            vouchers
            active : 25 lifetime : 279
            code: PBXWOF110K
            Details
            Redeem now
            EXPIRES IN 29 DAYS
            you won Lenskart Gold Max membership at just ₹49
            Lenskart
            4.47
            code: AFFLCRDG-OKTYZX6-6TOZ
            Details
            Redeem now
            EXPIRES IN 28 DAYS
            another coupon card
            code: OTHER123
        """.trimIndent()

        val result = extractor.extractCouponInfoSync(text, baseDate)

        assertEquals("Lenskart", result.storeName)
        assertEquals("AFFLCRDG-OKTYZX6-6TOZ", result.redeemCode)
        assertEquals("you won Lenskart Gold Max membership at just ₹49", result.description)

        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        assertEquals("2026-07-15", formatter.format(result.expiryDate!!))
    }

    @Test
    fun `extractRedeemCode finds token on line after indicator`() {
        val text = """
            Stream exclusively on aha
            Use code
            XYXXCRED2024 today
        """.trimIndent()

        val result = extractor.extractRedeemCode(text)

        assertEquals("XYXXCRED2024", result)
    }

    @Test
    fun `extractRedeemCode preserves hyphenated explicit code`() {
        val text = """
            Lenskart
            Gold Membership offer
            Code: AFFLPHG-UFPJ-TDOAB
            Valid till 30 Jun 2026
        """.trimIndent()

        val result = extractor.extractRedeemCode(text)

        assertEquals("AFFLPHG-UFPJ-TDOAB", result)
    }

    @Test
    fun `extractRedeemCode reads mixed case split explicit code line`() {
        val text = """
            KAPIVA
            Flat off on all Kapiva Products
            Code: KAPSUMUIWNPe pQv Copy
            BUY NOW
        """.trimIndent()

        val result = extractor.extractRedeemCode(text)

        assertEquals("KAPSUMUIWNPEPQV", result)
    }

    @Test
    fun `extractRedeemCode does not use short merchant token as fallback code`() {
        val text = """
            KAPIVA
            Flat off on all Kapiva Products
            BUY NOW
        """.trimIndent()

        val result = extractor.extractRedeemCode(text)

        assertEquals(null, result)
    }

    @Test
    fun `extractCouponInfo falls back to full OCR when scoped store block misses code`() {
        val text = """
            Vouchers
            active
            18
            lifetime
            428
            LEAF
            Details
            Leaf
            code:
            CREDJP70
            you
            won
            16099
            off
            on
            Leaf
            Halo
            Smart
            Ring
            Expires in 13 days
        """.trimIndent()

        val result = extractor.extractCouponInfoSync(text)

        assertEquals("LEAF", result.storeName)
        assertEquals("CREDJP70", result.redeemCode)
    }

    @Test
    fun `extractCouponInfo keeps BigBasket offer separate from code actions and status bar noise`() {
        val baseDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2026-06-17")
        val text = """
            9:41
            bbnow
            BigBasket
            You won flat ₹150 off
            on orders above ₹400 on BigBasket Details code:
            BBNOWCRED3-GZGE7F7BAHEXFY
            Details
            Redeem Now
            Expires in 06 days
            Vo 5G O
        """.trimIndent()

        val result = extractor.extractCouponInfoSync(text, baseDate)

        assertEquals("BigBasket", result.storeName)
        assertEquals("You won flat ₹150 off on orders above ₹400 on BigBasket", result.description)
        assertEquals("BBNOWCRED3-GZGE7F7BAHEXFY", result.redeemCode)
        assertEquals("Cashback: ₹150 off", result.cashbackDetail)
        assertEquals(400.0, result.minimumPurchase!!, 0.0)

        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        assertEquals("2026-06-23", formatter.format(result.expiryDate as Date))
    }

    @Test
    fun `extractCouponInfo prefers merchant in offer over wallet watermark and uses capture date`() {
        val baseDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2026-06-17")
        val text = """
            8:05
            Pautm
            vouchers
            active : 25 lifetime : 279
            EXPIRES IN 29 DAYS
            you won ₹16,500 off on Toothsi aligners DAYS
            Toothsi
            4.33
            code: CREDJACKAPR252C1KQC
            Details
            Redeem now
        """.trimIndent()

        val result = extractor.extractCouponInfoSync(text, baseDate)

        assertEquals("Toothsi", result.storeName)
        assertEquals("you won ₹16,500 off on Toothsi aligners", result.description)
        assertEquals("CREDJACKAPR252C1KQC", result.redeemCode)

        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        assertEquals("2026-07-16", formatter.format(result.expiryDate as Date))
    }

    @Test
    fun `extractCouponInfo rejects previous card code and parses selected card hour expiry`() {
        val baseDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("2026-06-18 17:57")
        val text = """
            code: CRDLUKES799
            Details
            Redeem Now
            EXPIRES IN 14 HOURS
            you won 5 products at ₹999 + ₹150 cashback via CRED pay on XYXX
            XYXX
            4.31
            Details
            Redeem Now
            EXPIRES IN 07 DAYS
        """.trimIndent()

        val result = extractor.extractCouponInfoSync(text, baseDate)

        assertEquals("XYXX", result.storeName)
        assertEquals("you won 5 products at ₹999 + ₹150 cashback via CRED pay on XYXX", result.description)
        assertNull(result.redeemCode)

        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        assertEquals("2026-06-19 07:57", formatter.format(result.expiryDate as Date))
    }

    @Test
    fun `extractCouponInfo ignores wallet header merchant noise for visible coupon card`() {
        val baseDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("2026-06-19 09:38")
        val text = """
            7:27
            NamM
            vouchers
            active
            :
            25
            lifetime
            :
            279
            Details
            ${'$'}Skulcandy
            code:
            CRSD
            Skullcandy
            Details
            you won 80% off on Skullcandy
            EXPIRES IN 12 DAYS
            mamaearth
            Onion
            Shampoo
            ZEN
        """.trimIndent()

        val result = extractor.extractCouponInfoSync(text, baseDate)

        assertEquals("Skullcandy", result.storeName)
        assertEquals("you won 80% off on Skullcandy", result.description)
    }

    @Test
    fun `extractStoreName rejects standalone cashback as merchant`() {
        val result = extractor.extractStoreName("cashback")

        assertNull(result)
    }

    @Test
    fun `extractCouponInfo falls back to useful OCR description after removing protected fields`() {
        val text = """
            12:58
            ShopEasy
            Applicable on selected products
            Maximum discount ₹200
            Valid for first order
            Code: EASY200
            Copy
            Expires in 2 days
            100%
        """.trimIndent()

        val result = extractor.extractCouponInfoSync(text)

        assertEquals("ShopEasy", result.storeName)
        assertEquals("Applicable on selected products Maximum discount ₹200 Valid for first order", result.description)
        assertEquals("EASY200", result.redeemCode)
    }

    @Test
    fun `extractCouponInfo handles varied app coupons with generic rules`() {
        data class Case(
            val text: String,
            val store: String,
            val code: String?,
            val description: String
        )

        val cases = listOf(
            Case(
                text = """
                    Myntra
                    Flat 50% off on selected styles
                    Coupon Code: MYNTRA50
                    Copy Code
                    Valid till 30 Jun 2026
                """.trimIndent(),
                store = "Myntra",
                code = "MYNTRA50",
                description = "Flat 50% off on selected styles"
            ),
            Case(
                text = """
                    Swiggy
                    Get ₹120 off on food orders above ₹299
                    Use code SWIGGY120
                    Redeem Now
                """.trimIndent(),
                store = "Swiggy",
                code = "SWIGGY120",
                description = "Get ₹120 off on food orders above ₹299"
            ),
            Case(
                text = """
                    Zomato
                    Applicable on selected restaurants
                    Maximum discount ₹100
                    Code: ZOMATO100
                    Details
                """.trimIndent(),
                store = "Zomato",
                code = "ZOMATO100",
                description = "Applicable on selected restaurants Maximum discount ₹100"
            ),
            Case(
                text = """
                    Amazon
                    Extra 10% off on electronics
                    Promo code: AMAZON10
                    5G
                    Copy
                """.trimIndent(),
                store = "Amazon",
                code = "AMAZON10",
                description = "Extra 10% off on electronics"
            ),
            Case(
                text = """
                    Flipkart
                    Save ₹500 on mobile phones
                    Code FLIP500
                    Details
                """.trimIndent(),
                store = "Flipkart",
                code = "FLIP500",
                description = "Save ₹500 on mobile phones"
            ),
            Case(
                text = """
                    Aha
                    Stream annual plan at just ₹399
                    Use code AHA399
                    Redeem Now
                """.trimIndent(),
                store = "Aha",
                code = "AHA399",
                description = "Stream annual plan at just ₹399"
            )
        )

        cases.forEach { case ->
            val result = extractor.extractCouponInfoSync(case.text)
            assertEquals(case.store, result.storeName)
            assertEquals(case.code, result.redeemCode)
            assertEquals(case.description, result.description)
        }
    }

    @Test
    fun `extractCouponInfo prefers numbered purchase offer over date and footer noise`() {
        val text = """
            Paytm
            Lhe Man
            CompanvJE
            MAN COHDANY
            BLANC POUR HOMME EALU DE TOILETTE
            Buy any 4 products at The Man Company website
            Code: TMCPE6990425SQTJ
            Expires on 31 May, 2025, 11:59 PM
            About The Company
            Scratch card received on offer
            o vo
        """.trimIndent()

        val result = extractor.extractCouponInfoSync(text)

        assertEquals("The Man Company", result.storeName)
        assertEquals("TMCPE6990425SQTJ", result.redeemCode)
        assertEquals("Buy any 4 products at The Man Company website", result.description)
    }

    @Test
    fun `extractStoreName strengthens partial merchant token from commercial OCR context`() {
        val text = """
            Lhe Man
            MAN COHDANY
            Buy any 4 products at The Man Company website
        """.trimIndent()

        val result = extractor.extractStoreName(text)

        assertEquals("The Man Company", result)
    }

    @Test
    fun `parseExpiryDate handles day first format with trailing time`() {
        val text = "Get 2 Months Audible Premium Plus\nExpires on 31 May, 2025, 11:59 PM"

        val result = extractor.parseExpiryDate(text)

        assertNotNull(result)
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        assertEquals("2025-05-31", formatter.format(result!!))
    }
}
