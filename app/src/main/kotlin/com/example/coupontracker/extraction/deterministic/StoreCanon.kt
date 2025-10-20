package com.example.coupontracker.extraction.deterministic

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Canonicalizes store names using a curated alias list and stopword guard rails.
 */
class StoreCanon private constructor(
    private val aliasToCanonical: Map<String, String>,
    private val canonicalToAliases: Map<String, List<String>>, 
    private val badWords: Set<String>
) {

    data class StoreEntry(val canonical: String, val aliases: List<String>)
    data class StoreCanonConfig(val entries: List<StoreEntry>, val badWords: Set<String>)

    constructor(context: Context) : this(loadConfig(context))

    private constructor(config: StoreCanonConfig) : this(
        aliasToCanonical = buildAliasMap(config.entries),
        canonicalToAliases = config.entries.associate { entry ->
            entry.canonical to (entry.aliases + entry.canonical)
        },
        badWords = config.badWords.map { normalizeToken(it) }.toSet()
    )

    fun resolve(storeGuess: String?, ocrText: String, descriptionHint: String?): String? {
        canonicalize(storeGuess)?.let { canonical ->
            if (!isBadWord(canonical)) {
                return canonical
            }
        }

        canonicalize(descriptionHint)?.let { canonical ->
            if (!isBadWord(canonical)) {
                return canonical
            }
        }

        findInText(ocrText)?.let { return it }
        return null
    }

    fun findInText(text: String): String? {
        if (text.isBlank()) return null
        val normalized = normalizeToken(text)
        aliasToCanonical.forEach { (alias, canonical) ->
            if (normalized.contains(alias) && !isBadWord(canonical)) {
                return canonical
            }
        }
        return null
    }

    fun canonicalize(store: String?): String? {
        if (store.isNullOrBlank()) return null
        val normalized = normalizeToken(store)
        return aliasToCanonical[normalized]
    }

    fun isBadWord(token: String?): Boolean {
        if (token.isNullOrBlank()) return true
        return badWords.contains(normalizeToken(token))
    }

    fun aliasesFor(canonical: String): List<String> {
        return canonicalToAliases[canonical] ?: emptyList()
    }

    companion object {
        private const val CONFIG_PATH = "stores.json"
        private const val TAG = "StoreCanon"

        private fun loadConfig(context: Context): StoreCanonConfig {
            return runCatching {
                context.assets.open(CONFIG_PATH).bufferedReader().use { reader ->
                    parse(JSONObject(reader.readText()))
                }
            }.getOrElse { error ->
                Log.w(TAG, "Failed to load stores.json: ${error.message}")
                StoreCanonConfig(emptyList(), emptySet())
            }
        }

        private fun parse(json: JSONObject): StoreCanonConfig {
            val entriesArray = json.optJSONArray("entries") ?: JSONArray()
            val entries = buildList {
                for (index in 0 until entriesArray.length()) {
                    val entry = entriesArray.optJSONObject(index) ?: continue
                    val canonical = entry.optString("canonical").takeIf { it.isNotBlank() } ?: continue
                    val aliases = entry.optJSONArray("aliases")?.let { array ->
                        buildList {
                            for (aliasIndex in 0 until array.length()) {
                                val alias = array.optString(aliasIndex)
                                if (alias.isNotBlank()) add(alias.lowercase())
                            }
                        }
                    } ?: emptyList()
                    add(StoreEntry(canonical, aliases))
                }
            }

            val badWordsArray = json.optJSONArray("badWords") ?: JSONArray()
            val badWords = buildSet {
                for (index in 0 until badWordsArray.length()) {
                    val value = badWordsArray.optString(index)
                    if (!value.isNullOrBlank()) add(value)
                }
            }
            return StoreCanonConfig(entries, badWords)
        }

        fun fromConfig(config: StoreCanonConfig): StoreCanon = StoreCanon(config)

        private fun buildAliasMap(entries: List<StoreEntry>): Map<String, String> {
            val map = mutableMapOf<String, String>()
            entries.forEach { entry ->
                val canonicalNormalized = normalizeToken(entry.canonical)
                if (canonicalNormalized.isNotBlank()) {
                    map[canonicalNormalized] = entry.canonical
                }
                entry.aliases.forEach { alias ->
                    val normalized = normalizeToken(alias)
                    if (normalized.isNotBlank()) {
                        map[normalized] = entry.canonical
                    }
                }
            }
            return map
        }

        private fun normalizeToken(value: String): String {
            return value.lowercase(Locale.ROOT)
                .replace("&", "and")
                .replace(Regex("[^a-z0-9]"), "")
        }
    }
}
