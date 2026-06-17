package com.example.coupontracker.schema

import com.example.coupontracker.contract.CouponJsonContract
import com.example.coupontracker.llm.CouponSchemaKeys
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CouponJsonContractTest {

    @Test
    fun `enforce extracts json from markdown fenced model response`() {
        val raw = """
            ```json
            {
              "storeName": "Mamaearth",
              "description": "100% cashback on skincare essentials from Mamaearth",
              "couponCode": "C1C4A129JPB7OBE",
              "expiryDate": "Expires in 14 days",
              "extra": "remove me",
              "storeNameSource": "vision",
              "storeNameEvidence": ["Mamaearth"],
              "needsAttention": false
            }
            ```
        """.trimIndent()

        val obj = JSONObject(CouponJsonContract.enforce(raw))

        assertEquals("Mamaearth", obj.getString(CouponSchemaKeys.STORE_NAME))
        assertEquals("C1C4A129JPB7OBE", obj.getString(CouponSchemaKeys.REDEEM_CODE))
        assertFalse(obj.has("couponCode"))
        assertFalse(obj.has("extra"))
    }
}
