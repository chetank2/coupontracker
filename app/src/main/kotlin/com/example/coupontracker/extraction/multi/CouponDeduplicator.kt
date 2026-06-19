package com.example.coupontracker.extraction.multi

import org.json.JSONObject

/**
 * Compatibility adapter while the implementation lives in extraction.merge.
 */
object CouponDeduplicator {

    fun dedupe(coupons: List<JSONObject>): List<JSONObject> =
        com.example.coupontracker.extraction.merge.CouponDeduplicator.dedupe(coupons)
}
