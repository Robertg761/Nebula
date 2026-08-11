package com.stremioshell.host.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadIntegrityPolicyTest {
  @Test
  fun `verified when file length matches the release asset size`() {
    assertEquals(
      DownloadIntegrityPolicy.Verdict.VERIFIED,
      DownloadIntegrityPolicy.verify(expectedSizeBytes = 117_000_000L, actualSizeBytes = 117_000_000L)
    )
  }

  @Test
  fun `corrupt when the download is truncated`() {
    assertEquals(
      DownloadIntegrityPolicy.Verdict.CORRUPT,
      DownloadIntegrityPolicy.verify(expectedSizeBytes = 117_000_000L, actualSizeBytes = 42_000_000L)
    )
  }

  @Test
  fun `corrupt when the file is larger than the release asset`() {
    assertEquals(
      DownloadIntegrityPolicy.Verdict.CORRUPT,
      DownloadIntegrityPolicy.verify(expectedSizeBytes = 117_000_000L, actualSizeBytes = 117_000_001L)
    )
  }

  @Test
  fun `corrupt when the file is missing`() {
    assertEquals(
      DownloadIntegrityPolicy.Verdict.CORRUPT,
      DownloadIntegrityPolicy.verify(expectedSizeBytes = 117_000_000L, actualSizeBytes = null)
    )
  }

  @Test
  fun `corrupt when the file is empty`() {
    assertEquals(
      DownloadIntegrityPolicy.Verdict.CORRUPT,
      DownloadIntegrityPolicy.verify(expectedSizeBytes = null, actualSizeBytes = 0L)
    )
  }

  @Test
  fun `unverifiable when the release json omitted the asset size`() {
    assertEquals(
      DownloadIntegrityPolicy.Verdict.UNVERIFIABLE,
      DownloadIntegrityPolicy.verify(expectedSizeBytes = null, actualSizeBytes = 117_000_000L)
    )
  }

  @Test
  fun `unverifiable when a pre-upgrade download recorded a zero size`() {
    assertEquals(
      DownloadIntegrityPolicy.Verdict.UNVERIFIABLE,
      DownloadIntegrityPolicy.verify(expectedSizeBytes = 0L, actualSizeBytes = 117_000_000L)
    )
  }

  @Test
  fun `only size-verified downloads may proceed to archive verification`() {
    assertTrue(DownloadIntegrityPolicy.isInstallable(DownloadIntegrityPolicy.Verdict.VERIFIED))
    assertFalse(DownloadIntegrityPolicy.isInstallable(DownloadIntegrityPolicy.Verdict.UNVERIFIABLE))
    assertFalse(DownloadIntegrityPolicy.isInstallable(DownloadIntegrityPolicy.Verdict.CORRUPT))
  }

  // --- SHA-256 digest ---------------------------------------------------------------------------

  private val digest = "a".repeat(64)

  @Test
  fun `a published digest is worth hashing the file for`() {
    assertTrue(
      DownloadIntegrityPolicy.requiresDigestVerification(
        DownloadIntegrityPolicy.Verdict.VERIFIED,
        digest,
      ),
    )
    // No size was recorded, but the digest can still settle it on its own.
    assertTrue(
      DownloadIntegrityPolicy.requiresDigestVerification(
        DownloadIntegrityPolicy.Verdict.UNVERIFIABLE,
        digest,
      ),
    )
  }

  @Test
  fun `a file already known to be the wrong size is not hashed`() {
    // Reading ~117 MB to confirm what length() already proved would be the most expensive
    // possible way to say no.
    assertFalse(
      DownloadIntegrityPolicy.requiresDigestVerification(
        DownloadIntegrityPolicy.Verdict.CORRUPT,
        digest,
      ),
    )
  }

  @Test
  fun `a release with no digest falls back to the byte count`() {
    assertFalse(
      DownloadIntegrityPolicy.requiresDigestVerification(
        DownloadIntegrityPolicy.Verdict.VERIFIED,
        null,
      ),
    )
    assertFalse(
      DownloadIntegrityPolicy.requiresDigestVerification(
        DownloadIntegrityPolicy.Verdict.VERIFIED,
        "   ",
      ),
    )
  }

  @Test
  fun `verified when the downloaded bytes hash to the published digest`() {
    assertEquals(
      DownloadIntegrityPolicy.Verdict.VERIFIED,
      DownloadIntegrityPolicy.verifyDigest(expectedSha256 = digest, actualSha256 = digest),
    )
  }

  @Test
  fun `digest comparison ignores hex casing`() {
    assertEquals(
      DownloadIntegrityPolicy.Verdict.VERIFIED,
      DownloadIntegrityPolicy.verifyDigest(
        expectedSha256 = digest,
        actualSha256 = digest.uppercase(),
      ),
    )
  }

  @Test
  fun `corrupt when the right number of bytes hash to the wrong digest`() {
    assertEquals(
      DownloadIntegrityPolicy.Verdict.CORRUPT,
      DownloadIntegrityPolicy.verifyDigest(expectedSha256 = digest, actualSha256 = "b".repeat(64)),
    )
  }

  @Test
  fun `corrupt when a file with a published digest cannot be hashed`() {
    // A file we are about to install and cannot read through is not a file we install.
    assertEquals(
      DownloadIntegrityPolicy.Verdict.CORRUPT,
      DownloadIntegrityPolicy.verifyDigest(expectedSha256 = digest, actualSha256 = null),
    )
  }

  @Test
  fun `unverifiable when there was no published digest to compare against`() {
    assertEquals(
      DownloadIntegrityPolicy.Verdict.UNVERIFIABLE,
      DownloadIntegrityPolicy.verifyDigest(expectedSha256 = null, actualSha256 = digest),
    )
  }
}
