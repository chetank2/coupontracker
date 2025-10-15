package com.example.coupontracker.extraction.deterministic

/**
 * Produces canonical coupon descriptions with consistent formatting.
 */
class DescriptionComposer(
    private val storeCanon: StoreCanon
) {

    fun compose(offer: String?, store: String?): String {
        val cleanedOffer = normalizeOffer(offer)
        val canonicalStore = store?.let { storeCanon.canonicalize(it) } ?: store
        return when {
            !cleanedOffer.isNullOrBlank() && !canonicalStore.isNullOrBlank() ->
                "$cleanedOffer from $canonicalStore"
            !cleanedOffer.isNullOrBlank() -> cleanedOffer
            !canonicalStore.isNullOrBlank() -> "Exclusive offer from $canonicalStore"
            else -> "Special discount available"
        }
    }

    fun normalizeOffer(rawOffer: String?): String? {
        if (rawOffer.isNullOrBlank()) return null
        return rawOffer
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""₹\s*(\d)""")) { match -> "₹${match.groupValues[1]}" }
            .replace(Regex("""\s*%"""), "%")
            .trim()
    }
}
