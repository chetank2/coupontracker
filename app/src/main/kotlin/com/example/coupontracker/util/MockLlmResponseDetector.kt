package com.example.coupontracker.util

import java.util.Locale

/**
 * Detects mock or placeholder responses returned by the stubbed LLM runtime.
 *
 * The current JNI bridge still returns hard-coded sample values whenever the
 * real MiniCPM inference pipeline is unavailable. Those placeholder values are
 * short strings such as "Mock Store"/"Mock coupon offer" with promo codes that
 * start with "MOCK". When these samples slip through the validation pipeline
 * the UI displays incorrect information (store name, description, etc.).
 *
 * To prevent this we look for a combination of signals that uniquely identify
 * placeholder payloads without triggering on legitimate coupons.
 */
internal object MockLlmResponseDetector {

    private val MOCK_STORE_PATTERNS = listOf(
        Regex("^mock\\s+store$", RegexOption.IGNORE_CASE),
        Regex("^example\\s+store$", RegexOption.IGNORE_CASE),
        Regex("^sample\\s+store$", RegexOption.IGNORE_CASE),
        Regex("^demo\\s+store$", RegexOption.IGNORE_CASE)
    )

    private val MOCK_CODE_PATTERNS = listOf(
        Regex("^MOCK[0-9A-Z_-]*$", RegexOption.IGNORE_CASE),
        Regex("^EXAMPLE[0-9A-Z_-]*$", RegexOption.IGNORE_CASE)
    )

    private val MOCK_DESCRIPTION_PATTERNS = listOf(
        Regex("mock\\s+coupon\\s+offer", RegexOption.IGNORE_CASE),
        Regex("placeholder\\s+offer", RegexOption.IGNORE_CASE),
        Regex("sample\\s+coupon", RegexOption.IGNORE_CASE),
        Regex("example\\s+coupon", RegexOption.IGNORE_CASE),
        Regex("demo\\s+coupon", RegexOption.IGNORE_CASE)
    )

    private val MOCK_TOKEN_REGEX = Regex("\\bmock(?:ed)?\\b", RegexOption.IGNORE_CASE)
    private val PLACEHOLDER_REGEX = Regex("\\b(?:placeholder|sample|demo|example)\\b", RegexOption.IGNORE_CASE)
    private val STUB_SOURCE_HINTS = listOf("stub", "mock", "placeholder")

    /**
     * Returns true when the coupon info clearly matches a mock response.
     *
     * We require at least two mock indicators to avoid false positives while
     * still catching the hard coded combinations from the stub runtime.
     */
    fun isMockResponse(couponInfo: CouponInfo): Boolean {
        val store = couponInfo.storeName.ifBlank { "" }
        val description = couponInfo.description.ifBlank { "" }
        val code = couponInfo.redeemCode?.trim().orEmpty()

        val isMockStore = MOCK_STORE_PATTERNS.any { it.containsMatchIn(store) }

        val isMockCode = code.isNotEmpty() && (
            MOCK_CODE_PATTERNS.any { it.matches(code) }
        )

        val isMockDescription = MOCK_DESCRIPTION_PATTERNS.any {
            it.containsMatchIn(description)
        } || description.lowercase(Locale.ROOT).let { lowerDesc ->
            lowerDesc.startsWith("mock ") || lowerDesc.contains("mock coupon")
        }

        val hasMockTokens = sequenceOf(
            store,
            description,
            couponInfo.cashbackDetail,
            couponInfo.category,
            couponInfo.status,
            couponInfo.discountType
        )
            .filterNotNull()
            .any { MOCK_TOKEN_REGEX.containsMatchIn(it) }

        val placeholderLanguage = listOf(store, description)
            .any { value -> PLACEHOLDER_REGEX.containsMatchIn(value) }

        val stubSource = couponInfo.storeNameSource
            ?.let { source -> STUB_SOURCE_HINTS.any { hint -> source.contains(hint, ignoreCase = true) } }
            ?: false

        val evidenceHints = couponInfo.storeNameEvidence.any { evidence ->
            STUB_SOURCE_HINTS.any { hint -> evidence.contains(hint, ignoreCase = true) } ||
                MOCK_TOKEN_REGEX.containsMatchIn(evidence)
        }

        val lowConfidenceSignals = couponInfo.needsAttention &&
            (couponInfo.storeNameEvidence.isEmpty() || stubSource)

        val normalizedStore = store.normalizeForComparison()
        val normalizedCode = code.normalizeForComparison()
        val identicalStoreAndCode = normalizedStore.isNotEmpty() && normalizedStore == normalizedCode

        val stubSignature = store.equals("mock store", ignoreCase = true) &&
            code.equals("MOCK50", ignoreCase = true) &&
            description.contains("50%", ignoreCase = true)

        val indicators = listOf(
            isMockStore,
            isMockCode,
            isMockDescription,
            stubSignature,
            hasMockTokens,
            placeholderLanguage,
            stubSource,
            evidenceHints,
            lowConfidenceSignals,
            identicalStoreAndCode
        )

        return indicators.count { it } >= 2
    }

    private fun String.normalizeForComparison(): String {
        return filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT)
    }
}

