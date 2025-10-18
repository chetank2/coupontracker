package com.example.coupontracker.extraction.validation

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Brand lexicon loaded from assets with tiered priority information.
 */
class BrandLexicon private constructor(
    private val aliasToEntry: Map<String, Entry>,
    private val priorityMap: Map<String, Int>,
    val ctaStopwords: Set<String>
) {
    data class Entry(
        val canonical: String,
        val priority: Int,
        val aliases: Set<String>
    )

    data class Match(
        val entry: Entry,
        val alias: String,
        val priority: Int
    )

    fun match(value: String?): Match? {
        if (value.isNullOrBlank()) return null
        val normalized = normalize(value)
        val entry = aliasToEntry[normalized] ?: return null
        val priority = priorityMap[entry.canonical] ?: entry.priority
        return Match(entry, normalized, priority)
    }

    companion object {
        private const val FILE_PATH = "brand_lexicon.json"
        private const val TAG = "BrandLexicon"

        fun load(context: Context): BrandLexicon {
            return runCatching {
                context.assets.open(FILE_PATH).bufferedReader().use { reader ->
                    fromJson(JSONObject(reader.readText()))
                }
            }.getOrElse { error ->
                Log.w(TAG, "Unable to load brand lexicon: ${error.message}")
                empty()
            }
        }

        fun fromJson(json: JSONObject): BrandLexicon {
            val aliasMap = mutableMapOf<String, Entry>()
            val priorityMap = mutableMapOf<String, Int>()
            val tiers = json.optJSONArray("tiers") ?: JSONArray()
            for (tierIndex in 0 until tiers.length()) {
                val tierObject = tiers.optJSONObject(tierIndex) ?: continue
                val priority = tierObject.optInt("priority", tierIndex + 1)
                val entries = tierObject.optJSONArray("entries") ?: continue
                for (entryIndex in 0 until entries.length()) {
                    val entryObject = entries.optJSONObject(entryIndex) ?: continue
                    val canonical = entryObject.optString("canonical")
                    if (canonical.isBlank()) continue
                    val aliasSet = buildSet {
                        add(normalize(canonical))
                        val aliases = entryObject.optJSONArray("aliases") ?: JSONArray()
                        for (aliasIndex in 0 until aliases.length()) {
                            val alias = aliases.optString(aliasIndex)
                            if (alias.isNotBlank()) {
                                add(normalize(alias))
                            }
                        }
                    }
                    val entry = Entry(canonical = canonical, priority = priority, aliases = aliasSet)
                    priorityMap[canonical] = priority
                    aliasSet.forEach { token -> aliasMap[token] = entry }
                }
            }

            val stopwords = buildSet {
                val array = json.optJSONArray("ctaStopwords") ?: JSONArray()
                for (index in 0 until array.length()) {
                    val value = array.optString(index)
                    if (value.isNotBlank()) add(value.lowercase(Locale.ROOT))
                }
            }

            return BrandLexicon(aliasMap, priorityMap, stopwords)
        }

        fun empty(): BrandLexicon = BrandLexicon(emptyMap(), emptyMap(), emptySet())

        private fun normalize(value: String): String {
            return value.lowercase(Locale.ROOT)
                .replace("&", "and")
                .replace(Regex("[^a-z0-9]"), "")
        }
    }
}
