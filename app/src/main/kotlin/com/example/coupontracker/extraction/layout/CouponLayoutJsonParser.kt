package com.example.coupontracker.extraction.layout

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class CouponLayoutJsonParser {

    fun parse(raw: String): CouponLayoutDetection {
        val root = JSONObject(extractJsonObject(raw))
        val cardsArray = root.optJSONArray("cards") ?: JSONArray()
        val cards = buildList {
            for (i in 0 until cardsArray.length()) {
                val item = cardsArray.optJSONObject(i) ?: continue
                parseCard(item, i)?.let(::add)
            }
        }
        return CouponLayoutDetection(
            cards = cards,
            source = LayoutDetectionSource.VLM,
            confidence = root.optDouble("confidence", cards.map { it.confidence }.averageOrZero()).toFloat(),
            diagnostics = LayoutDiagnostics(
                detectorName = "vlm",
                rawCardCount = cardsArray.length()
            )
        )
    }

    private fun parseCard(json: JSONObject, index: Int): CouponCardRegion? {
        val box = json.optJSONObject("box") ?: json.optJSONObject("bounds") ?: return null
        val x = box.optDouble("x", Double.NaN)
        val y = box.optDouble("y", Double.NaN)
        val width = box.optDouble("width", Double.NaN)
        val height = box.optDouble("height", Double.NaN)
        if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite()) return null

        val completeness = parseCompleteness(json.optString("completeness", "partial"))
        val confidence = json.optDouble("confidence", 0.5).toFloat().coerceIn(0f, 1f)
        return CouponCardRegion(
            bounds = Rect(
                x.toInt(),
                y.toInt(),
                (x + width).toInt(),
                (y + height).toInt()
            ),
            completeness = completeness,
            confidence = confidence,
            visibleFields = parseVisibleFields(json.optJSONArray("visibleFields")),
            reason = json.optString("reason").takeIf { it.isNotBlank() },
            sourceIndex = index
        )
    }

    private fun parseCompleteness(value: String): CardCompleteness {
        return when (value.normalizeToken()) {
            "complete" -> CardCompleteness.COMPLETE
            "too_incomplete", "tooincomplete", "incomplete" -> CardCompleteness.TOO_INCOMPLETE
            else -> CardCompleteness.PARTIAL
        }
    }

    private fun parseVisibleFields(array: JSONArray?): Set<VisibleCouponField> {
        if (array == null) return emptySet()
        return buildSet {
            for (i in 0 until array.length()) {
                val field = when (array.optString(i).normalizeToken()) {
                    "merchant", "store", "store_name", "brand" -> VisibleCouponField.MERCHANT
                    "offer", "discount", "cashback", "benefit" -> VisibleCouponField.OFFER
                    "code", "coupon_code", "promo_code", "redeem_code" -> VisibleCouponField.CODE
                    "expiry", "expiration", "validity", "valid_until" -> VisibleCouponField.EXPIRY
                    "terms", "tnc", "conditions" -> VisibleCouponField.TERMS
                    "action", "redeem", "copy", "apply" -> VisibleCouponField.ACTION
                    else -> null
                }
                field?.let(::add)
            }
        }
    }

    private fun extractJsonObject(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed

        val start = raw.indexOf('{')
        require(start >= 0) { "No JSON object found in layout response" }
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val char = raw[i]
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                inString -> Unit
                char == '{' -> depth++
                char == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        error("Unterminated JSON object in layout response")
    }

    private fun String.normalizeToken(): String {
        return trim()
            .lowercase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_')
    }

    private fun List<Float>.averageOrZero(): Double {
        return if (isEmpty()) 0.0 else average()
    }
}
