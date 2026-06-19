package com.example.coupontracker.extraction.validation

import android.graphics.RectF
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.extraction.FieldCandidate
import com.example.coupontracker.extraction.TextBlock
import java.util.Locale
import kotlin.math.abs

/**
 * Validates that primary coupon fields are anchored to the same visual region.
 *
 * This catches multi-card association failures where, for example, a store name
 * is read from the upper card while the coupon code is read from a lower card.
 */
class SpatialFieldConsistencyValidator {

    data class Result(
        val consistent: Boolean,
        val reason: String? = null,
        val matchedAnchors: Map<FieldType, RectF> = emptyMap()
    )

    fun validate(
        fields: Map<FieldType, FieldCandidate>,
        ocrBlocks: List<TextBlock>,
        imageHeight: Int
    ): Result {
        if (ocrBlocks.isEmpty() || imageHeight <= 0) {
            return Result(consistent = true)
        }

        val anchorCandidates = fields
            .filterKeys { it in PRIMARY_FIELDS }
            .mapValues { (field, candidate) -> findAnchors(field, candidate.value, ocrBlocks) }
            .filterValues { it.isNotEmpty() }

        if (anchorCandidates.size < 2) {
            return Result(
                consistent = true,
                matchedAnchors = anchorCandidates.mapValues { (_, candidates) -> candidates.first() }
            )
        }

        val anchors = chooseTightestAnchorSet(anchorCandidates)
        val centers = anchors.mapValues { (_, rect) -> rect.centerY() }
        val minY = centers.values.minOrNull() ?: return Result(consistent = true, matchedAnchors = anchors)
        val maxY = centers.values.maxOrNull() ?: return Result(consistent = true, matchedAnchors = anchors)
        val spread = maxY - minY
        val allowedSpread = maxOf(imageHeight * MAX_VERTICAL_SPREAD_RATIO, MIN_VERTICAL_SPREAD_PX)

        if (spread > allowedSpread) {
            val fieldSummary = centers.entries
                .sortedBy { it.value }
                .joinToString { (field, y) -> "${field.name}@${y.toInt()}" }
            return Result(
                consistent = false,
                reason = "Fields are too far apart vertically for one coupon card: $fieldSummary",
                matchedAnchors = anchors
            )
        }

        val codeAnchor = anchors[FieldType.COUPON_CODE]
        val storeAnchor = anchors[FieldType.STORE_NAME]
        val descriptionAnchor = anchors[FieldType.DESCRIPTION]
        if (codeAnchor != null && storeAnchor != null && descriptionAnchor != null) {
            val storeDescriptionDistance = abs(storeAnchor.centerY() - descriptionAnchor.centerY())
            val codeStoreDistance = abs(codeAnchor.centerY() - storeAnchor.centerY())
            val codeDescriptionDistance = abs(codeAnchor.centerY() - descriptionAnchor.centerY())
            if (codeStoreDistance > allowedSpread && codeDescriptionDistance > allowedSpread &&
                storeDescriptionDistance <= allowedSpread
            ) {
                return Result(
                    consistent = false,
                    reason = "Coupon code is spatially detached from merchant and offer text",
                    matchedAnchors = anchors
                )
            }
        }

        return Result(consistent = true, matchedAnchors = anchors)
    }

    private fun findAnchors(
        field: FieldType,
        value: String,
        ocrBlocks: List<TextBlock>
    ): List<RectF> {
        val normalizedValue = normalize(value)
        if (normalizedValue.isBlank()) return emptyList()

        val matches = mutableListOf<RectF>()

        if (field == FieldType.EXPIRY_DATE) {
            ocrBlocks.filterToBounds(matches) { block ->
                val text = normalize(block.text)
                EXPIRY_WORDS.any { text.contains(it) }
            }
        }

        ocrBlocks.filterToBounds(matches) { block ->
            val text = normalize(block.text)
            text.contains(normalizedValue) || normalizedValue.contains(text)
        }

        val tokens = normalizedValue
            .split(' ')
            .filter { it.length >= 3 }
            .take(5)
        if (tokens.isNotEmpty()) {
            ocrBlocks.filterToBounds(matches) { block ->
                val text = normalize(block.text)
                val hits = tokens.count { token -> text.contains(token) }
                hits >= minOf(2, tokens.size)
            }
        }

        return matches
            .distinctBy { rect -> "${rect.left}:${rect.top}:${rect.right}:${rect.bottom}" }
            .take(MAX_ANCHOR_CANDIDATES)
    }

    private fun List<TextBlock>.filterToBounds(
        output: MutableList<RectF>,
        predicate: (TextBlock) -> Boolean
    ) {
        forEach { block ->
            if (predicate(block)) output += block.bounds
        }
    }

    private fun chooseTightestAnchorSet(anchorCandidates: Map<FieldType, List<RectF>>): Map<FieldType, RectF> {
        var combinations = listOf(emptyMap<FieldType, RectF>())
        anchorCandidates.forEach { (field, candidates) ->
            combinations = combinations.flatMap { partial ->
                candidates.map { candidate -> partial + (field to candidate) }
            }
        }
        return combinations.minByOrNull(::verticalSpread) ?: anchorCandidates.mapValues { (_, candidates) -> candidates.first() }
    }

    private fun verticalSpread(anchors: Map<FieldType, RectF>): Float {
        val centers = anchors.values.map { it.centerY() }
        val minY = centers.minOrNull() ?: return 0f
        val maxY = centers.maxOrNull() ?: return 0f
        return maxY - minY
    }

    private fun normalize(value: String): String {
        return value
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9%₹]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private companion object {
        private val PRIMARY_FIELDS = setOf(
            FieldType.STORE_NAME,
            FieldType.DESCRIPTION,
            FieldType.COUPON_CODE,
            FieldType.EXPIRY_DATE
        )
        private val EXPIRY_WORDS = setOf("expires", "expiry", "valid", "validity")
        private const val MAX_VERTICAL_SPREAD_RATIO = 0.55f
        private const val MIN_VERTICAL_SPREAD_PX = 220f
        private const val MAX_ANCHOR_CANDIDATES = 6
    }
}
