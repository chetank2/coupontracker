package com.example.coupontracker.extraction.validation

import java.io.BufferedReader
import java.io.InputStreamReader
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandGoldenSetTest {
    private val validator = StoreNameValidator(TestBrandLexicon.create())

    data class Fixture(
        val id: String,
        val type: String,
        val source: String,
        val value: String,
        val description: String?,
        val redeemCode: String?,
        val expectedCanonical: String?
    )

    @Test
    fun `golden set prevents CTA strings from becoming brands`() {
        val fixtures = loadFixtures().filter { it.type == "cta" }
        assertTrue("The golden dataset must include CTA fixtures", fixtures.isNotEmpty())

        fixtures.forEach { fixture ->
            val assessment = validator.assessCandidate(
                value = fixture.value,
                description = fixture.description,
                redeemCode = fixture.redeemCode,
                source = fixture.source
            )

            assertFalse("${fixture.id} should never be accepted", assessment.isAccepted)
            assertTrue(
                "${fixture.id} should always expose the CTA issue",
                assessment.issues.contains("cta_stopword")
            )
            assertTrue("${fixture.id} should always need operator review", assessment.needsAttention)
        }
    }

    @Test
    fun `golden set maintains canonical mapping for trusted brands`() {
        val fixtures = loadFixtures().filter { it.type == "brand" }
        assertTrue("The golden dataset must include brand fixtures", fixtures.isNotEmpty())

        fixtures.forEach { fixture ->
            val assessment = validator.assessCandidate(
                value = fixture.value,
                description = fixture.description,
                redeemCode = fixture.redeemCode,
                source = fixture.source
            )

            assertTrue("${fixture.id} expected to be accepted", assessment.isAccepted)
            val canonical = assessment.canonical
            assertNotNull("${fixture.id} should carry a canonical name", canonical)
            assertEquals(fixture.expectedCanonical, canonical)
        }
    }

    private fun loadFixtures(): List<Fixture> {
        val resource = requireNotNull(
            javaClass.classLoader?.getResource("brand_golden_set/dataset.json")
        ) { "brand_golden_set/dataset.json missing from test resources" }
        val text = resource.openStream().use { stream ->
            BufferedReader(InputStreamReader(stream)).use { reader -> reader.readText() }
        }
        val array = JSONArray(text)
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.getJSONObject(index)
                add(
                    Fixture(
                        id = obj.getString("id"),
                        type = obj.getString("type"),
                        source = obj.getString("source"),
                        value = obj.getString("value"),
                        description = obj.optString("description", null),
                        redeemCode = obj.optString("redeemCode", null),
                        expectedCanonical = if (obj.has("expectedCanonical")) {
                            obj.getString("expectedCanonical")
                        } else {
                            null
                        }
                    )
                )
            }
        }
    }
}
