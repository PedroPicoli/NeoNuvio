package com.nuvio.tv.updater

internal data class SemanticVersion(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val prerelease: List<String>
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }

        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1
        if (other.prerelease.isEmpty()) return -1

        val sharedSize = minOf(prerelease.size, other.prerelease.size)
        for (index in 0 until sharedSize) {
            comparePrereleaseIdentifier(
                prerelease[index],
                other.prerelease[index]
            ).takeIf { it != 0 }?.let { return it }
        }
        return compareValues(prerelease.size, other.prerelease.size)
    }

    private fun comparePrereleaseIdentifier(left: String, right: String): Int {
        val leftNumeric = left.all(Char::isDigit)
        val rightNumeric = right.all(Char::isDigit)

        if (leftNumeric && rightNumeric) {
            val normalizedLeft = left.trimStart('0').ifEmpty { "0" }
            val normalizedRight = right.trimStart('0').ifEmpty { "0" }
            compareValues(normalizedLeft.length, normalizedRight.length)
                .takeIf { it != 0 }
                ?.let { return it }
            return normalizedLeft.compareTo(normalizedRight)
        }
        if (leftNumeric) return -1
        if (rightNumeric) return 1
        return left.compareTo(right)
    }
}

internal object VersionUtils {
    private val versionExtractRegex = Regex(
        """(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?"""
    )

    fun extractVersion(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val match = versionExtractRegex.find(raw) ?: return null
        val major = match.groupValues[1]
        val minor = match.groupValues[2]
        val patch = match.groupValues[3].ifEmpty { "0" }
        val prerelease = match.groupValues[4]
        return if (prerelease.isNotEmpty()) "$major.$minor.$patch-$prerelease" else "$major.$minor.$patch"
    }

    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val extracted = extractVersion(raw)
        if (extracted != null) return extracted
        return raw.trim().removePrefix("v").removePrefix("V")
    }

    fun parse(raw: String?): SemanticVersion? {
        if (raw.isNullOrBlank()) return null
        val match = versionExtractRegex.find(raw) ?: return null
        return SemanticVersion(
            major = match.groupValues[1].toLongOrNull() ?: return null,
            minor = match.groupValues[2].toLongOrNull() ?: return null,
            patch = match.groupValues[3].takeIf(String::isNotEmpty)?.toLongOrNull() ?: 0L,
            prerelease = match.groupValues[4]
                .takeIf(String::isNotEmpty)
                ?.split('.')
                .orEmpty()
        )
    }

    fun isPrerelease(raw: String?): Boolean = parse(raw)?.prerelease?.isNotEmpty() == true

    fun isRemoteNewer(remote: String?, local: String?): Boolean {
        val remoteVersion = parse(remote) ?: return false
        val localVersion = parse(local) ?: return false
        return remoteVersion > localVersion
    }
}
