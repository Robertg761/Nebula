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
}
