package com.stremioshell.host.update

import java.util.Locale

/**
 * Enough of semver 2.0 for the tags this project actually publishes.
 *
 * The release workflow validates `versionName` as a numeric `major.minor.patch` and then builds
 * the tag as either `v<version>` or, for a prerelease, `v<version>-<suffix>` where the suffix is
 * dot- or hyphen-separated alphanumerics: `v0.6.2-beta.1`, `v0.6.2-rc.2`. Ordering therefore has
 * to understand that suffix. It used to be thrown away by the parser, which made `0.6.2-beta.1`
 * and `0.6.2` literally the same version - so a viewer who installed a beta was never offered the
 * stable release that followed it, for as long as that stable was the newest tag.
 *
 * Build metadata (`+sha`) is parsed off and ignored, which is what semver 2.0 §10 asks for.
 */
data class SemVer(
  val major: Int,
  val minor: Int,
  val patch: Int,
  /** Dot-separated pre-release identifiers; empty for a release. */
  val preRelease: List<String> = emptyList()
) : Comparable<SemVer> {
  override fun compareTo(other: SemVer): Int {
    if (major != other.major) return major.compareTo(other.major)
    if (minor != other.minor) return minor.compareTo(other.minor)
    if (patch != other.patch) return patch.compareTo(other.patch)
    return comparePreRelease(preRelease, other.preRelease)
  }

  companion object {
    /**
     * Deliberately lenient after the version itself: anything trailing that is not a well-formed
     * pre-release suffix is ignored rather than rejected, which is how a stored label from an
     * older build still parses. The fourth group is the pre-release, and it only matches the
     * charset semver allows, so a trailing " (build 3)" still lands in the ignored tail.
     */
    private val VERSION_RE =
      Regex("""^[vV]?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z][0-9A-Za-z.-]*))?.*$""")

    /**
     * `-tv` is this app's historical flavor suffix, not a pre-release: `0.1.0-tv` and `0.1.0` are
     * the same build of the same release. Treating it as a pre-release would make every stable
     * tag look like an upgrade over the flavor-suffixed build of the identical version, and the
     * archive check would then reject the download as NOT_NEWER.
     */
    private val FLAVOR_IDENTIFIERS = setOf("tv")

    fun parseOrNull(raw: String): SemVer? {
      val input = raw.trim()
      val match = VERSION_RE.matchEntire(input) ?: return null
      val major = match.groupValues[1].toIntOrNull() ?: return null
      val minor = match.groupValues[2].toIntOrNull() ?: 0
      val patch = match.groupValues[3].toIntOrNull() ?: 0
      return SemVer(
        major = major,
        minor = minor,
        patch = patch,
        preRelease = parsePreRelease(match.groupValues[4]),
      )
    }

    /**
     * The comparable form of a raw tag or versionName: no `v` prefix, no build metadata, no
     * flavor suffix, lower-cased. The pre-release identifiers stay - dropping them here is the
     * other half of the bug described on this class, because every equality check in the updater
     * goes through this function.
     */
    fun normalizeLabel(raw: String): String {
      val trimmed = raw.trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('+')
      val core = trimmed.substringBefore('-')
      val preRelease = parsePreRelease(trimmed.substringAfter('-', ""))
      val label = if (preRelease.isEmpty()) core else "$core-${preRelease.joinToString(".")}"
      return label.lowercase(Locale.ROOT)
    }

    /**
     * Just the `major.minor.patch` core, with any pre-release dropped.
     *
     * This is what names a release asset: the workflow publishes `StremioShell-tv-<version>.apk`
     * where `<version>` is the numeric versionName, even when the tag carries a `-beta.1` suffix.
     * Use [normalizeLabel] for anything that is comparing two versions.
     */
    fun coreLabel(raw: String): String = raw.trim()
      .removePrefix("v")
      .removePrefix("V")
      .substringBefore('+')
      .substringBefore('-')
      .lowercase(Locale.ROOT)

    internal fun parsePreRelease(raw: String): List<String> = raw
      .split('.')
      .filter { it.isNotEmpty() }
      .dropWhile { it.lowercase(Locale.ROOT) in FLAVOR_IDENTIFIERS }

    private fun comparePreRelease(left: List<String>, right: List<String>): Int {
      if (left.isEmpty() && right.isEmpty()) return 0
      // semver 2.0 §11.3: a release outranks every pre-release of the same core version.
      if (left.isEmpty()) return 1
      if (right.isEmpty()) return -1

      val shared = minOf(left.size, right.size)
      for (index in 0 until shared) {
        val result = compareIdentifier(left[index], right[index])
        if (result != 0) {
          return result
        }
      }
      // §11.4.4: with a common prefix, the longer pre-release is the higher one.
      return left.size.compareTo(right.size)
    }

    private fun compareIdentifier(left: String, right: String): Int {
      val leftNumber = left.asIdentifierNumber()
      val rightNumber = right.asIdentifierNumber()
      return when {
        leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
        // §11.4.3: numeric identifiers always sort below alphanumeric ones.
        leftNumber != null -> -1
        rightNumber != null -> 1
        // §11.4.2: ASCII order, which is what String.compareTo gives for this charset.
        else -> left.compareTo(right)
      }
    }

    /** Null for anything that is not a plain decimal identifier, including one too big to hold. */
    private fun String.asIdentifierNumber(): Long? =
      if (isNotEmpty() && all { it in '0'..'9' }) toLongOrNull() else null
  }
}
