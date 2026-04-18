package com.example.coupontracker.benchmark

import com.example.coupontracker.contract.CouponJsonContract
import com.example.coupontracker.llm.CouponSchemaKeys
import org.json.JSONObject
import java.util.Locale

data class SampleMetrics(
    val id: String,
    val brand: String,
    val redeemCodeExact: Boolean,
    val storeNameNormalizedMatch: Boolean,
    val expiryDateMatch: Boolean,
    val jsonValid: Boolean,
    val hallucinationSuspect: Boolean,
    val needsAttention: Boolean,
    val latencyMs: Long
)

data class AggregateMetrics(
    val total: Int,
    val redeemCodeAccuracy: Double,
    val storeNameAccuracy: Double,
    val expiryDateAccuracy: Double,
    val jsonValidity: Double,
    val hallucinationRate: Double,
    val lowConfidenceRate: Double,
    val meanLatencyMs: Double
) {
    fun toMarkdown(): String = buildString {
        append("| metric | value |\n")
        append("|---|---|\n")
        append("| samples | %d |\n".format(total))
        append("| redeemCode exact | %.3f |\n".format(Locale.US, redeemCodeAccuracy))
        append("| storeName normalized | %.3f |\n".format(Locale.US, storeNameAccuracy))
        append("| expiryDate match | %.3f |\n".format(Locale.US, expiryDateAccuracy))
        append("| JSON validity | %.3f |\n".format(Locale.US, jsonValidity))
        append("| hallucination rate | %.3f |\n".format(Locale.US, hallucinationRate))
        append("| low-confidence rate | %.3f |\n".format(Locale.US, lowConfidenceRate))
        append("| mean latency ms | %.1f |\n".format(Locale.US, meanLatencyMs))
    }
}

object MetricsCalculator {

    fun score(sample: GoldenSetSample, actualJson: String, latencyMs: Long): SampleMetrics {
        val expected = sample.expected
        val validity = CouponJsonContract.validate(actualJson)
        val jsonValid = validity.valid

        val actual = if (jsonValid) JSONObject(actualJson) else JSONObject()
        val redeemMatch = normalizeCode(expected.optString(CouponSchemaKeys.REDEEM_CODE)) ==
            normalizeCode(actual.optString(CouponSchemaKeys.REDEEM_CODE))
        val storeMatch = normalizeStore(expected.optString(CouponSchemaKeys.STORE_NAME)) ==
            normalizeStore(actual.optString(CouponSchemaKeys.STORE_NAME))
        val expiryMatch = normalizeDate(expected.optString(CouponSchemaKeys.EXPIRY_DATE)) ==
            normalizeDate(actual.optString(CouponSchemaKeys.EXPIRY_DATE))

        val hallucinationSuspect =
            !redeemMatch && actual.optString(CouponSchemaKeys.REDEEM_CODE) !in setOf("", "unknown")

        val needsAttention = jsonValid && actual.optBoolean(CouponSchemaKeys.NEEDS_ATTENTION, false)

        return SampleMetrics(
            id = sample.id,
            brand = sample.brand,
            redeemCodeExact = redeemMatch,
            storeNameNormalizedMatch = storeMatch,
            expiryDateMatch = expiryMatch,
            jsonValid = jsonValid,
            hallucinationSuspect = hallucinationSuspect,
            needsAttention = needsAttention,
            latencyMs = latencyMs
        )
    }

    fun aggregate(rows: List<SampleMetrics>): AggregateMetrics {
        if (rows.isEmpty()) return AggregateMetrics(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val n = rows.size.toDouble()
        return AggregateMetrics(
            total = rows.size,
            redeemCodeAccuracy = rows.count { it.redeemCodeExact } / n,
            storeNameAccuracy = rows.count { it.storeNameNormalizedMatch } / n,
            expiryDateAccuracy = rows.count { it.expiryDateMatch } / n,
            jsonValidity = rows.count { it.jsonValid } / n,
            hallucinationRate = rows.count { it.hallucinationSuspect } / n,
            lowConfidenceRate = rows.count { it.needsAttention } / n,
            meanLatencyMs = rows.sumOf { it.latencyMs } / n
        )
    }

    private fun normalizeCode(s: String?): String =
        s.orEmpty().trim().uppercase(Locale.US).replace("\\s+".toRegex(), "")

    private fun normalizeStore(s: String?): String =
        s.orEmpty().trim().lowercase(Locale.US).replace("\\s+".toRegex(), " ")

    private fun normalizeDate(s: String?): String = s.orEmpty().trim()
}
