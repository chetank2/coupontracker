package com.example.coupontracker.ml

/**
 * Shared integrity checks for shipped model artifacts.
 */
object ModelAssetIntegrity {

    /**
     * Verifies that a bundled model asset meets the minimum expected size.
     *
     * @throws IllegalStateException when the asset is suspiciously small, which
     * usually indicates that a placeholder file was packaged instead of a
     * trained TensorFlow Lite or GGUF binary.
     */
    fun ensureMinSize(
        assetName: String,
        actualBytes: Long,
        minExpectedBytes: Long,
        remediationHint: String
    ) {
        if (actualBytes < minExpectedBytes) {
            throw IllegalStateException(
                "Model asset $assetName is $actualBytes bytes; expected at least " +
                    "$minExpectedBytes bytes. $remediationHint"
            )
        }
    }
}

