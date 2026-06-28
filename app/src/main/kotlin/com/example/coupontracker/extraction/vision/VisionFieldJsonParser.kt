package com.example.coupontracker.extraction.vision

import android.graphics.Rect
import com.example.coupontracker.data.model.Coupon
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import javax.inject.Inject

class VisionFieldJsonParser @Inject constructor() {

    fun parse(raw: String): VisionFieldExtraction {
        val root = JSONObject(extractJsonObject(raw, "vision field response"))
        if (root.has("ls") || root.has("s") || root.has("cs") || root.has("es")) {
            val labels = parseCompactFieldLabels(root, raw)
            val card = labels.toLegacyCard()
            return VisionFieldExtraction(
                cards = listOf(card),
                activeCard = card,
                confidence = labels.confidence,
                rawEvidence = raw
            )
        }
        if (root.has("fields")) {
            val labels = parseFieldLabels(root, raw)
            val card = labels.toLegacyCard()
            return VisionFieldExtraction(
                cards = listOf(card),
                activeCard = card,
                confidence = labels.confidence,
                rawEvidence = raw
            )
        }
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
            ?: cards.maxWithOrNull(
                compareBy<VisionCouponCard> { it.layoutState == Coupon.LayoutState.MODAL_FOREGROUND }
                    .thenBy { it.confidence }
            )
        return VisionFieldExtraction(
            cards = cards,
            activeCard = activeCard,
            confidence = parseConfidence(root, cards.map { it.confidence }.average().toFloat()),
            rawEvidence = raw
        )
    }

    fun parseLayout(raw: String): VisionLayoutResult {
        val root = JSONObject(extractJsonObject(raw, "layout response"))
        rejectFinalFields(root)
        val cardsArray = root.firstArray("cards", "couponCards", "regions", "items")
            ?: throw IllegalArgumentException("Vision layout response missing cards[]")
        val cards = buildList {
            for (i in 0 until cardsArray.length()) {
                val item = cardsArray.optJSONObject(i)
                    ?: throw IllegalArgumentException("Malformed layout card at index $i")
                rejectFinalFields(item)
                add(parseLayoutCard(item, i))
            }
        }
        require(cards.isNotEmpty()) { "Vision layout response contains no cards" }
        val layoutState = parseState(
            raw = root.optString("layoutState", root.optString("layout", Coupon.LayoutState.LOW_CONFIDENCE)),
            valid = VALID_LAYOUT_STATES,
            field = "layoutState"
        )
        val active = cards.firstOrNull { it.active || it.modal }
            ?: cards.maxWithOrNull(compareBy<VisionLayoutCard> { it.confidence }.thenBy { it.bounds.h * it.bounds.w })
        val confidence = parseConfidence(root, cards.map { it.confidence }.average().toFloat())
        return VisionLayoutResult(
            cards = cards,
            activeCard = active,
            confidence = confidence,
            layoutState = layoutState,
            rawEvidence = raw
        )
    }

    fun parseFieldLabels(raw: String): VisionFieldLabelResult {
        val root = JSONObject(extractJsonObject(raw, "field-label response"))
        if (root.has("ls") || root.has("s") || root.has("cs") || root.has("es")) {
            return parseCompactFieldLabels(root, raw)
        }
        return parseFieldLabels(root, raw)
    }

    private fun parseCompactFieldLabels(root: JSONObject, raw: String): VisionFieldLabelResult {
        val confidence = parseConfidence(root, 0.5f)
        val isReviewOnly = confidence <= MIN_USABLE_CONFIDENCE
        val layoutState = parseState(
            raw = root.optNullableToken("ls", "layoutState") ?: Coupon.LayoutState.LOW_CONFIDENCE,
            valid = VALID_LAYOUT_STATES,
            field = "ls"
        )
        val codeState = if (isReviewOnly) {
            Coupon.CodeState.UNKNOWN
        } else {
            parseState(
                raw = root.optNullableToken("cs", "codeState") ?: Coupon.CodeState.UNKNOWN,
                valid = VALID_CODE_STATES,
                field = "cs"
            )
        }
        val rawExpiryState = root.optNullableToken("es", "expiryState")
        val malformedExpiryState = !rawExpiryState.isNullOrBlank() &&
            rawExpiryState.normalizeToken() !in VALID_EXPIRY_STATES
        val expiryState = if (isReviewOnly) {
            Coupon.ExpiryState.UNKNOWN
        } else {
            parseCompactExpiryState(rawExpiryState)
        }
        val store = root.optNullableString("s")
            ?.takeUnless { isReviewOnly || it.isPlaceholderFieldLabel("store") }
        val description = root.optNullableString("d")
            ?.takeUnless { isReviewOnly || it.isPlaceholderFieldLabel("description", "offer") }
        val rawCode = root.optNullableString("c")
        val rawExpiry = root.optNullableString("e")
            ?.takeUnless { malformedExpiryState }
        val code = rawCode?.takeUnless { isReviewOnly || codeState != Coupon.CodeState.PRESENT }
        val expiry = rawExpiry?.takeUnless { isReviewOnly }
        val safeCodeState = when {
            isReviewOnly -> Coupon.CodeState.UNKNOWN
            codeState == Coupon.CodeState.PRESENT && code.isNullOrBlank() -> Coupon.CodeState.UNKNOWN
            else -> codeState
        }
        val safeExpiryState = when {
            isReviewOnly -> Coupon.ExpiryState.UNKNOWN
            expiryState == Coupon.ExpiryState.PRESENT && expiry.isNullOrBlank() -> Coupon.ExpiryState.UNKNOWN
            else -> expiryState
        }
        return VisionFieldLabelResult(
            fields = VisionCouponFields(
                store = VisionFieldLabel(
                    state = if (store.isNullOrBlank()) Coupon.ExpiryState.UNKNOWN else "PRESENT",
                    text = store,
                    evidence = listOfNotNull(store),
                    confidence = confidence
                ),
                description = VisionFieldLabel(
                    state = if (description.isNullOrBlank()) Coupon.ExpiryState.UNKNOWN else "PRESENT",
                    text = description,
                    evidence = listOfNotNull(description),
                    confidence = confidence
                ),
                code = VisionFieldLabel(
                    state = safeCodeState,
                    text = code,
                    evidence = listOfNotNull(code),
                    confidence = confidence
                ),
                expiry = VisionFieldLabel(
                    state = safeExpiryState,
                    text = expiry,
                    evidence = listOfNotNull(expiry),
                    confidence = confidence
                )
            ),
            noise = emptyList(),
            layoutState = layoutState,
            confidence = confidence,
            rawEvidence = raw
        )
    }

    private fun parseFieldLabels(root: JSONObject, raw: String): VisionFieldLabelResult {
        val fields = root.optJSONObject("fields")
            ?: throw IllegalArgumentException("Vision field-label response missing fields{}")
        val layoutState = parseState(
            raw = root.optString("layoutState", root.optString("layout", Coupon.LayoutState.LOW_CONFIDENCE)),
            valid = VALID_LAYOUT_STATES,
            field = "layoutState"
        )
        return VisionFieldLabelResult(
            fields = VisionCouponFields(
                store = parseTextField(fields.requiredObject("store"), "store"),
                description = parseTextField(fields.requiredObject("description"), "description"),
                code = parseCodeField(fields.requiredObject("code")),
                expiry = parseExpiryField(fields.requiredObject("expiry"))
            ),
            noise = root.optJSONArray("noise").toStringList(),
            layoutState = layoutState,
            confidence = parseConfidence(root, 0.5f),
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
        val fields = json.optJSONObject("fields")
        val confidence = parseConfidence(json, 0.5f)
        return VisionCouponCard(
            storeName = json.firstString("storeName", "merchant", "brand") ?: fields?.optJSONObject("store")?.firstString("text"),
            description = json.firstString("description", "offer", "benefit") ?: fields?.optJSONObject("description")?.firstString("text"),
            redeemCode = json.firstString("redeemCode", "code", "couponCode", "promoCode") ?: fields?.optJSONObject("code")?.firstString("text"),
            expiryText = json.firstString("expiryText", "expiry", "validUntil") ?: fields?.optJSONObject("expiry")?.firstString("text"),
            codeState = codeState,
            expiryState = expiryState,
            layoutState = layoutState,
            confidence = confidence,
            evidence = json.firstString("evidence", "reason", "visibleText"),
            bounds = parsePixelBounds(json.optJSONObject("box") ?: json.optJSONObject("bounds")),
            active = json.optBoolean("active", false) || json.optBoolean("foreground", false),
            normalizedBounds = parseNormalizedBounds(
                box = json.optJSONObject("normalizedBounds") ?: json.optJSONObject("bounds"),
                required = false,
                context = "Vision card"
            ),
            fieldEvidence = parseFieldEvidence(fields),
            noise = json.optJSONArray("noise").toStringList()
        )
    }

    private fun parseLayoutCard(json: JSONObject, index: Int): VisionLayoutCard {
        val bounds = parseNormalizedBounds(
            box = json.optJSONObject("bounds") ?: json.optJSONObject("box"),
            required = true,
            context = "Layout card $index"
        )
            ?: throw IllegalArgumentException("Layout card $index missing bounds")
        require(bounds.isUsable()) { "Layout card $index has unusable normalized bounds" }
        val layoutState = parseState(
            raw = json.optString("layoutState", json.optString("layout", Coupon.LayoutState.COMPLETE)),
            valid = VALID_LAYOUT_STATES,
            field = "layoutState"
        )
        val confidence = parseConfidence(json, 0.5f)
        require(confidence >= MIN_LAYOUT_CONFIDENCE) { "Layout card $index confidence too low" }
        return VisionLayoutCard(
            id = json.firstString("id"),
            bounds = bounds,
            confidence = confidence,
            layoutState = layoutState,
            active = json.optBoolean("active", false) || json.optBoolean("foreground", false),
            modal = json.optBoolean("modal", false) || layoutState == Coupon.LayoutState.MODAL_FOREGROUND
        )
    }

    private fun parseTextField(json: JSONObject?, field: String): VisionFieldLabel {
        return parseField(
            json = json,
            field = field,
            validStates = VALID_TEXT_FIELD_STATES
        )
    }

    private fun parseCodeField(json: JSONObject?): VisionFieldLabel {
        return parseField(
            json = json,
            field = "code",
            validStates = VALID_CODE_STATES
        )
    }

    private fun parseExpiryField(json: JSONObject?): VisionFieldLabel {
        return parseField(
            json = json,
            field = "expiry",
            validStates = VALID_EXPIRY_STATES
        )
    }

    private fun parseField(json: JSONObject?, field: String, validStates: Set<String>): VisionFieldLabel {
        require(json != null) { "Vision field-label response missing fields.$field" }
        require(json.has("state")) { "Vision field-label response missing fields.$field.state" }
        require(json.has("confidence")) { "Vision field-label response missing fields.$field.confidence" }
        val evidenceJson = json.optJSONArray("evidence")
            ?: throw IllegalArgumentException("Vision field-label response missing fields.$field.evidence[]")
        val state = parseState(
            raw = json.optString("state", Coupon.CodeState.UNKNOWN),
            valid = validStates,
            field = "$field.state"
        )
        val evidence = evidenceJson.toStringList()
        val text = json.firstString("text", "value")
        if (state == "PRESENT") {
            require(!text.isNullOrBlank()) { "$field PRESENT requires text" }
            require(evidence.isNotEmpty()) { "$field PRESENT requires evidence[]" }
        }
        return VisionFieldLabel(
            state = state,
            text = text,
            evidence = evidence,
            confidence = parseConfidence(json, 0.5f)
        )
    }

    private fun VisionFieldLabelResult.toLegacyCard(): VisionCouponCard {
        val joinedEvidence = buildList {
            addAll(fields.store.evidence)
            addAll(fields.description.evidence)
            addAll(fields.code.evidence)
            addAll(fields.expiry.evidence)
        }.joinToString(separator = "\n").takeIf { it.isNotBlank() }
        return VisionCouponCard(
            storeName = fields.store.text,
            description = fields.description.text,
            redeemCode = fields.code.text,
            expiryText = fields.expiry.text,
            codeState = fields.code.state,
            expiryState = fields.expiry.state,
            layoutState = layoutState,
            confidence = confidence,
            evidence = joinedEvidence,
            bounds = null,
            active = true,
            fieldEvidence = mapOf(
                "store" to fields.store.evidence,
                "description" to fields.description.evidence,
                "code" to fields.code.evidence,
                "expiry" to fields.expiry.evidence
            ),
            noise = noise
        )
    }

    private fun parseState(raw: String, valid: Set<String>, field: String): String {
        val normalized = raw.normalizeToken()
        require(normalized in valid) { "Invalid $field: $raw" }
        return normalized
    }

    private fun parseCompactExpiryState(raw: String?): String {
        if (raw.isNullOrBlank()) return Coupon.ExpiryState.UNKNOWN
        val normalized = raw.normalizeToken()
        return when {
            normalized in VALID_EXPIRY_STATES -> normalized
            else -> Coupon.ExpiryState.UNKNOWN
        }
    }

    private fun looksLikeExpiryText(value: String): Boolean {
        val normalized = value.trim().lowercase(Locale.ROOT)
        return normalized.contains("expir") ||
            normalized.contains("expira") ||
            normalized.contains("vence") ||
            normalized.contains("valid") ||
            Regex("""\b\d{1,2}\s*(?:day|days|hour|hours)\b""").containsMatchIn(normalized) ||
            Regex("""\b\d{1,2}\s*(?:dia|dias|hora|horas)\b""").containsMatchIn(normalized) ||
            Regex("""\b\d{1,2}\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)\b""")
                .containsMatchIn(normalized)
    }

    private fun parsePixelBounds(box: JSONObject?): Rect? {
        if (box == null) return null
        val x = box.optDouble("x", Double.NaN)
        val y = box.optDouble("y", Double.NaN)
        val width = box.optDouble("width", box.optDouble("w", Double.NaN))
        val height = box.optDouble("height", box.optDouble("h", Double.NaN))
        if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite()) return null
        if (x in 0.0..1.0 && y in 0.0..1.0 && width in 0.0..1.0 && height in 0.0..1.0) return null
        return Rect(x.toInt(), y.toInt(), (x + width).toInt(), (y + height).toInt())
    }

    private fun parseConfidence(json: JSONObject, fallback: Float): Float {
        val raw = when {
            json.has("confidence") && !json.isNull("confidence") -> json.opt("confidence")
            json.has("conf") && !json.isNull("conf") -> json.opt("conf")
            else -> fallback
        }
        val value = when (raw) {
            is Number -> raw.toFloat()
            is String -> raw.trim().toFloatOrNull() ?: confidenceLabelToScore(raw)
            else -> fallback
        }
        require(value.isFinite()) { "Invalid confidence" }
        require(value in MIN_USABLE_CONFIDENCE..1f) { "Unusable confidence: $value" }
        return value
    }

    private fun confidenceLabelToScore(raw: String): Float {
        return when (raw.trim().normalizeToken()) {
            "HIGH", "HIGH_CONFIDENCE" -> 0.85f
            "MEDIUM", "MEDIUM_CONFIDENCE" -> 0.5f
            "LOW", "LOW_CONFIDENCE", "UNKNOWN" -> 0f
            else -> 0f
        }
    }

    private fun parseNormalizedBounds(
        box: JSONObject?,
        required: Boolean,
        context: String
    ): VisionNormalizedBounds? {
        if (box == null) {
            require(!required) { "$context missing normalized bounds" }
            return null
        }
        val x = box.optDouble("x", Double.NaN)
        val y = box.optDouble("y", Double.NaN)
        val width = box.optDouble("w", box.optDouble("width", Double.NaN))
        val height = box.optDouble("h", box.optDouble("height", Double.NaN))
        if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite()) {
            require(!required) { "$context has malformed normalized bounds" }
            return null
        }
        val bounds = normalizeBoundsWithMinorDrift(
            x = x.toFloat(),
            y = y.toFloat(),
            w = width.toFloat(),
            h = height.toFloat()
        )
        if (!required && !bounds.isUsable()) return null
        return bounds
    }

    private fun normalizeBoundsWithMinorDrift(
        x: Float,
        y: Float,
        w: Float,
        h: Float
    ): VisionNormalizedBounds {
        val right = x + w
        val bottom = y + h
        val hasOnlyMinorDrift = x >= -NORMALIZED_BOUNDS_DRIFT &&
            y >= -NORMALIZED_BOUNDS_DRIFT &&
            right <= 1f + NORMALIZED_BOUNDS_DRIFT &&
            bottom <= 1f + NORMALIZED_BOUNDS_DRIFT
        if (!hasOnlyMinorDrift) {
            return VisionNormalizedBounds(x, y, w, h)
        }

        val clampedX = x.coerceIn(0f, 1f)
        val clampedY = y.coerceIn(0f, 1f)
        val clampedRight = right.coerceIn(clampedX, 1f)
        val clampedBottom = bottom.coerceIn(clampedY, 1f)
        return VisionNormalizedBounds(
            x = clampedX,
            y = clampedY,
            w = clampedRight - clampedX,
            h = clampedBottom - clampedY
        )
    }

    private fun parseFieldEvidence(fields: JSONObject?): Map<String, List<String>> {
        if (fields == null) return emptyMap()
        return buildMap {
            for (field in listOf("store", "description", "code", "expiry")) {
                val evidence = fields.optJSONObject(field)?.optJSONArray("evidence").toStringList()
                if (evidence.isNotEmpty()) put(field, evidence)
            }
        }
    }

    private fun rejectFinalFields(json: JSONObject) {
        val forbidden = listOf("storeName", "merchant", "brand", "description", "offer", "benefit", "redeemCode", "code", "couponCode", "promoCode", "expiry", "expiryText", "validUntil", "fields")
        val found = forbidden.firstOrNull { json.has(it) }
        require(found == null) { "Layout response must not contain final coupon field '$found'" }
    }

    private fun JSONObject?.firstString(vararg keys: String): String? {
        if (this == null) return null
        for (key in keys) {
            val value = optString(key).trim()
            if (value.isNotBlank() && !value.equals("null", ignoreCase = true)) return value
        }
        return null
    }

    private fun JSONObject.firstArray(vararg keys: String): JSONArray? {
        for (key in keys) {
            optJSONArray(key)?.let { return it }
        }
        return null
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    private fun JSONObject.optNullableToken(vararg keys: String): String? {
        for (key in keys) {
            val value = optNullableString(key)
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun String.isPlaceholderFieldLabel(vararg labels: String): Boolean {
        val normalized = trim().lowercase(Locale.ROOT)
        return labels.any { label -> normalized == label.lowercase(Locale.ROOT) }
    }

    private fun JSONObject.requiredObject(key: String): JSONObject {
        return optJSONObject(key) ?: throw IllegalArgumentException("Missing field object: $key")
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                val value = optString(i).trim()
                require(value.isNotBlank() && !value.equals("null", ignoreCase = true)) {
                    "Malformed string array entry at index $i"
                }
                add(value)
            }
        }
    }

    private fun extractJsonObject(raw: String, responseName: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed

        val start = raw.indexOf('{')
        require(start >= 0) { "No JSON object found in $responseName" }
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
        throw IllegalArgumentException("Unterminated JSON object in $responseName")
    }

    private fun String.normalizeToken(): String {
        return trim()
            .uppercase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_')
    }

    companion object {
        private const val MIN_LAYOUT_CONFIDENCE = 0.35f
        private const val MIN_USABLE_CONFIDENCE = 0f
        private const val NORMALIZED_BOUNDS_DRIFT = 0.02f
    }
}
