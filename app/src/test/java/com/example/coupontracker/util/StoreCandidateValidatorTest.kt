package com.example.coupontracker.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreCandidateValidatorTest {

    @Test
    fun `rejects store candidates contaminated with offer words`() {
        val rawOcr = """
            T TOOTHSI
            Flat 20k off
            On toothsi invisible aligners
            TOOTHSI20KOFF
        """.trimIndent()

        assertFalse(StoreCandidateValidator.isAcceptable("TOOTHSI Flat", rawOcr))
        assertTrue(StoreCandidateValidator.isAcceptable("TOOTHSI", rawOcr))
    }

    @Test
    fun `rejects scratch and win reward words as store candidates`() {
        val rawOcr = """
            SCRATCH & WIN
            REWARDS!
            ixigo
            Up to 30% Off
            SWGG224CYNU9SPA
        """.trimIndent()

        assertFalse(StoreCandidateValidator.isAcceptable("WIN", rawOcr))
        assertFalse(StoreCandidateValidator.isAcceptable("REWARDS", rawOcr))
        assertTrue(StoreCandidateValidator.isAcceptable("ixigo", rawOcr))
    }

    @Test
    fun `accepts OCR supported stylized alpha numeric store names`() {
        val rawOcr = """
            be1ox
            BE 10X
            Free AI Webinar
            NO CODE NEEDED
        """.trimIndent()

        assertTrue(StoreCandidateValidator.isAcceptable("BE 10X", rawOcr))
    }

    @Test
    fun `rejects event terms as store candidates`() {
        val rawOcr = """
            BE 10X
            Free AI Webinar
            NO CODE NEEDED
        """.trimIndent()

        assertFalse(StoreCandidateValidator.isAcceptable("Webinar", rawOcr))
        assertTrue(StoreCandidateValidator.isAcceptable("BE 10X", rawOcr))
    }
}
