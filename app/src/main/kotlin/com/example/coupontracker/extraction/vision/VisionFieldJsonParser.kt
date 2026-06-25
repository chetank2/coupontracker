package com.example.coupontracker.extraction.vision

import android.graphics.Rect
import com.example.coupontracker.data.model.Coupon
import org.json.JSONObject
import java.util.Locale
import javax.inject.Inject

class VisionFieldJsonParser @Inject constructor() {

    fun parse(raw: String): VisionFieldExtraction {
        val root = JSONObject(extractJsonObject(raw))
        val cardsArray = root.optJSONArray("cards")
            ?: throw IllegalArgumentException("Vision field response missing cards[]")
        val cards = buildList {
            for (i in 0 until cardsArray.length()) {
                val item = cardsArray.optJSONObject(i)
                    ?: throw IllegalArgumentException("Malformed card at index $i")
                add(parseCard(item))
            }
        }
        require(cards.isNotEmpty()) { "Vision field response contains no cards" }

        val activeCard = cards.firstOrNull { it.active }
            ?: cards.maxWithOrNull(compareBy<VisionCouponCard> { it.layoutState == Coupon.LayoutState.MODAL_FOREGROUND }
                .thenBy { it.confidence })
        return VisionFieldExtraction(
            cards = cards,
            activeCard = activeCard,
            confidence = root.optDouble("confidence", cards.map { it.confidence }.average()).toFloat().coerceIn(0f, 1f),
            rawEvidence = raw
        )
    }

    private fun parseCard(json: JSONObject): VisionCouponCard {
        val codeState = parseState(
            raw = json.optString("codeState", Coupon.CodeState.UNKNOWN),
            valid = VALID_CODE_STATES,
            field = "codeState"
        )
        val expiryState = parseState(
            raw = json.optString("expiryState", Coupon.ExpiryState.UNKNOWN),
            valid = VALID_EXPIRY_STATES,
            field = "expiryState"
        )
        val layoutState = parseState(
            raw = json.optString("layoutState", json.optString("layout", Coupon.LayoutState.LOW_CONFIDENCE)),
            valid = VALID_LAYOUT_STATES,
            field = "layoutState"
        )
        val confidence = json.optDouble("confidence", 0.5).toFloat().coerceIn(0f, 1f)
        return VisionCouponCard(
            storeName = json.firstString("storeName", "merchant", "brand"),
            description = json.firstString("description", "offer", "benefit"),
            redeemCode = json.firstString("redeemCode", "code", "couponCode", "promoCode"),
            expiryText = json.firstString("expiryText", "expiry", "validUntil"),
            codeState = codeState,
            expiryState = expiryState,
            layoutState = layoutState,
            confidence = confidence,
            evidence = json.firstString("evidence", "reason", "visibleText"),
            bounds = parseBounds(json.optJSONObject("box") ?: json.optJSONObject("bounds")),
            active = json.optBoolean("active", false) || json.optBoolean("foreground", false)
        )
    }

    private fun parseState(raw: String, valid: Set<String>, field: String): String {
        val normalized = raw.normalizeToken()
        require(normalized in valid) { "Invalid $field: $raw" }
        return normalized
    }

    private fun parseBounds(box: JSONObject?): Rect? {
        if (box == null) return null
        val x = box.optDouble("x", Double.NaN)
        val y = box.optDouble("y", Double.NaN)
        val width = box.optDouble("width", Double.NaN)
        val height = box.optDouble("height", Double.NaN)
        if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite()) return null
        return Rect(x.toInt(), y.toInt(), (x + width).toInt(), (y + height).toInt())
    }

    private fun JSONObject.firstString(vararg keys: String): String? {
        for (key in keys) {
            val value = optString(key).trim()
            if (value.isNotBlank() && !value.equals("null", ignoreCase = true)) return value
        }
        return null
    }

    private fun extractJsonObject(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed

        val start = raw.indexOf('{')
        require(start >= 0) { "No JSON object found in vision field response" }
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
        error("Unterminated JSON object in vision field response")
    }

    private fun String.normalizeToken(): String {
        return trim()
            .uppercase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_')
    }
}
