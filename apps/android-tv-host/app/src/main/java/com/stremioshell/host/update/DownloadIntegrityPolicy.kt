package com.stremioshell.host.update

/**
 * Decides whether a finished download may be handed to the package installer.
 * DownloadManager reports STATUS_SUCCESSFUL for a truncated file when the CDN
 * closes the connection early, so the release asset's byte size is the cheapest
 * signal we have that the ~117 MB APK actually arrived whole.
 *
 * Size is the fallback, not the whole story. GitHub publishes a `digest` field
 * ("sha256:<hex>") beside every asset, in the same release JSON the updater already
 * fetches, and when it is present the download is checked against it: a byte count
 * says the transfer was not cut short, a SHA-256 says the bytes are the ones that
 * were published. The check runs in two steps because the digest costs a full read
 * of the file - [verify] answers from the size alone, and only if
 * [requiresDigestVerification] then says the hash can still change the answer does
 * the caller pay for it.
 */
object DownloadIntegrityPolicy {
  enum class Verdict {
    /** The file on disk matches what the release published. */
    VERIFIED,

    /** Nothing to check against (pre-upgrade download, or the release JSON omitted both fields). */
    UNVERIFIABLE,

    /** File is missing, empty, a different size, or a different SHA-256 than the release asset. */
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
   * Whether hashing the ~117 MB archive can still change [sizeVerdict].
   *
   * No recorded digest, nothing to compare against. Already CORRUPT on size, and the answer is
   * settled - reading the whole file to confirm what a `length()` already proved would be the
   * most expensive possible way to say no.
   */
  fun requiresDigestVerification(sizeVerdict: Verdict, expectedSha256: String?): Boolean =
    !expectedSha256.isNullOrBlank() && sizeVerdict != Verdict.CORRUPT

  /**
   * The verdict once the file has actually been hashed. A null [actualSha256] means the file
   * could not be read through, which for a file we are about to install is the same answer as a
   * mismatch.
   */
  fun verifyDigest(expectedSha256: String?, actualSha256: String?): Verdict {
    if (expectedSha256.isNullOrBlank()) {
      // Nothing was published to compare against; the caller should not have asked.
      return Verdict.UNVERIFIABLE
    }
    if (actualSha256.isNullOrBlank()) {
      return Verdict.CORRUPT
    }
    return if (expectedSha256.equals(actualSha256, ignoreCase = true)) {
      Verdict.VERIFIED
    } else {
      Verdict.CORRUPT
    }
  }

  /**
   * Only a byte-for-byte match may proceed to package/signer verification. Old downloads with no
   * recorded release size and no recorded digest are re-downloaded rather than treated as trusted.
   */
  fun isInstallable(verdict: Verdict): Boolean = verdict == Verdict.VERIFIED
}
