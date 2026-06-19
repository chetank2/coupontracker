package com.example.coupontracker.extraction.retry

import org.json.JSONObject

/**
 * Compatibility adapter while the implementation lives in extraction.merge.
 */
object VlmMerger {
    fun merge(primary: JSONObject, vlm: JSONObject, ocrText: String): JSONObject =
        com.example.coupontracker.extraction.merge.VlmMerger.merge(primary, vlm, ocrText)
}
