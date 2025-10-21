package com.example.build

/**
 * Simple data holder describing the semantic version of the application.
 */
data class VersionInfo(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val metadata: String = ""
) {
    /**
     * Generates an Android friendly versionCode. The multiplier keeps room for future growth.
     */
    fun versionCode(): Int = (major * 10000) + (minor * 100) + patch

    /**
     * Formats the version name with optional metadata and git hash suffixes.
     */
    fun formatted(metadata: String? = this.metadata, gitHash: String? = null): String {
        val base = "$major.$minor.$patch"
        val metadataSuffix = metadata?.takeIf { it.isNotBlank() }?.let { "-$it" }.orEmpty()
        val hashValue = gitHash?.takeIf { it.isNotBlank() }
        val hashSuffix = hashValue?.let { value ->
            val normalized = if (value.startsWith("g")) value else "g$value"
            "-$normalized"
        }.orEmpty()
        return buildString {
            append(base)
            append(metadataSuffix)
            append(hashSuffix)
        }
    }
}
