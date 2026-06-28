package com.example.coupontracker.llm

/**
 * Mirrors the response that mlc_llm_jni.cpp must emit whenever the native
 * runtime fails an inference. This exists so the contract can be asserted from
 * JVM tests without loading the JNI library.
 *
 * Native inference failure must not produce coupon-shaped JSON. Returning null
 * lets the JVM wrapper fail over safely instead of accepting synthetic coupon
 * fields.
 */
object JniFallbackFixtures {
    val INFERENCE_FAILURE_RESPONSE: String? = null
}
