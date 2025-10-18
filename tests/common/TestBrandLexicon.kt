package com.example.coupontracker.extraction.validation

import org.json.JSONObject

internal object TestBrandLexicon {
    fun create(): BrandLexicon {
        val json = JSONObject(
            """
            {
              "tiers": [
                {
                  "priority": 1,
                  "entries": [
                    {"canonical": "Myntra", "aliases": ["Myntra", "MYNTRA"]},
                    {"canonical": "Nykaa", "aliases": ["Nykaa", "NYKAA"]},
                    {"canonical": "boAt", "aliases": ["boAt", "Boat", "BOAT"]}
                  ]
                },
                {
                  "priority": 2,
                  "entries": [
                    {"canonical": "Amazon", "aliases": ["Amazon", "Amazon.in"]},
                    {"canonical": "Flipkart", "aliases": ["Flipkart"]}
                  ]
                }
              ],
              "ctaStopwords": [
                "shop now",
                "tap to claim",
                "claim now",
                "redeem now",
                "copy code",
                "apply now"
              ]
            }
            """.trimIndent()
        )
        return BrandLexicon.fromJson(json)
    }
}
