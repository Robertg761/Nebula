package com.stremioshell.host.update

/**
 * Decides whether a finished download may be handed to the package installer.
 * DownloadManager reports STATUS_SUCCESSFUL for a truncated file when the CDN
 * closes the connection early, so the release asset's byte size is the cheapest
 * signal we have that the ~117 MB APK actually arrived whole.
 */
object DownloadIntegrityPolicy {
  enum class Verdict {
    /** Expected size is known and the file on disk matches it. */
    VERIFIED,

    /** No expected size recorded (pre-upgrade download, or the release JSON omitted `size`). */
    UNVERIFIABLE,

    /** File is missing, empty, or a different size than the release asset. */
    CORRUPT
  }

  fun verify(expectedSizeBytes: Long?, actualSizeBytes: Long?): Verdict {
    if (actualSizeBytes == null || actualSizeBytes <= 0L) {
      return Verdict.CORRUPT
    }
    if (expectedSizeBytes == null || expectedSizeBytes <= 0L) {
      return Verdict.UNVERIFIABLE
    }
    return if (expectedSizeBytes == actualSizeBytes) Verdict.VERIFIED else Verdict.CORRUPT
  }

  /**
   * Only a byte-for-byte size match may proceed to package/signer verification. Old downloads
   * with no recorded release size are re-downloaded rather than treated as trusted.
   */
  fun isInstallable(verdict: Verdict): Boolean = verdict == Verdict.VERIFIED
}
