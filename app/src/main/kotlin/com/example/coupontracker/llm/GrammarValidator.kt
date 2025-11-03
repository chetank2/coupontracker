package com.example.coupontracker.llm

import android.util.Log

/**
 * Simple guard-rails around coupon-schema grammar assets so we never deploy
 * an invalid grammar that will crash llama.cpp during warm-up.
 */
object GrammarValidator {

    private const val TAG = "GrammarValidator"

    /**
     * Hard-coded whitelist of grammar hashes that have been validated with the
     * llama sampler. Update this list whenever the grammar changes and has
     * been re-verified offline.
     */
    private val TRUSTED_HASHES = setOf(
        "b616496ee909c35621e7da6e170a82723e4334c434d9806af25a642ea6bfa258"
    )

    fun isTrustedHash(hash: String?): Boolean {
        if (hash.isNullOrBlank()) return false
        return TRUSTED_HASHES.any { trusted -> trusted.equals(hash, ignoreCase = true) }
    }

    fun isStructurallyValid(grammar: String): Boolean {
        val trimmed = grammar.trim()
        if (trimmed.isEmpty()) {
            return false
        }

        // Basic sanity: the critical rules must exist exactly once
        val requiredFragments = listOf(
            "root ::= ws object ws",
            "storeNamePair ::= \"\\\"storeName\\\"\" ws \":\" ws string",
            "redeemPair ::= \"\\\"redeemCode\\\"\" ws \":\" ws string",
            "storeNameEvidencePair ::= \"\\\"storeNameEvidence\\\"\" ws \":\" ws evidenceArray",
            "string ::= \"\\\"\" stringBody \"\\\"\"",
            "evidenceArray ::= \"[\" ws evidenceList ws \"]\" | \"[\" ws \"]\"",
            "boolean ::= \"true\" | \"false\""
        )

        if (requiredFragments.any { fragment -> !trimmed.contains(fragment) }) {
            return false
        }

        return true
    }

    fun validateOrThrow(assetHash: String, grammar: String) {
        if (!isTrustedHash(assetHash)) {
            logError("Untrusted grammar hash: $assetHash")
            throw IllegalStateException("Untrusted grammar asset hash $assetHash")
        }

        if (!isStructurallyValid(grammar)) {
            logError("Grammar failed structural validation")
            throw IllegalStateException("Grammar structure invalid for coupon schema")
        }
    }

    private fun logError(message: String) {
        runCatching { Log.e(TAG, message) }
    }
}

