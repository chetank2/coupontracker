package com.example.coupontracker.util

/**
 * Simple semantic version representation with comparison support.
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val raw: String
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = raw

    companion object {
        private val VERSION_REGEX = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)")

        fun parse(value: String): SemVer {
            val match = VERSION_REGEX.find(value.trim())
                ?: throw IllegalArgumentException("Invalid semantic version: $value")
            val (major, minor, patch) = match.destructured
            return SemVer(major.toInt(), minor.toInt(), patch.toInt(), value.trim())
        }
    }
}
