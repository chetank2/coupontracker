package com.example.coupontracker.llm

/**
 * Mirrors the JSON that BuildFallbackResponse() in mlc_llm_jni.cpp must emit
 * whenever the native runtime fails an inference. This exists so the contract
 * can be asserted from JVM tests without loading the JNI library.
 *
 * If BuildFallbackResponse() is changed, update this fixture in the same commit.
 */
object JniFallbackFixtures {
    const val CANONICAL_FALLBACK_JSON = """{"storeName":"unknown","description":"unknown","redeemCode":"unknown","expiryDate":"unknown","storeNameSource":"fallback","storeNameEvidence":[],"needsAttention":true}"""
}
