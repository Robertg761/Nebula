package com.stremioshell.host.update

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ApkUpdateManagerTest {
  @Test
  fun `only pending running and paused records are active downloads`() {
    val active = listOf(
      DownloadManager.STATUS_PENDING,
      DownloadManager.STATUS_RUNNING,
      DownloadManager.STATUS_PAUSED,
    )

    active.forEach { status ->
      assertEquals(
        DownloadRecordState.IN_PROGRESS,
        ApkUpdateManager.downloadRecordState(status),
      )
    }
  }

  @Test
  fun `successful record is a downloaded candidate`() {
    assertEquals(
      DownloadRecordState.DOWNLOADED,
      ApkUpdateManager.downloadRecordState(DownloadManager.STATUS_SUCCESSFUL),
    )
  }

  @Test
  fun `failed missing and unknown records are stale`() {
    listOf(DownloadManager.STATUS_FAILED, null, Int.MAX_VALUE).forEach { status ->
      assertEquals(
        DownloadRecordState.STALE,
        ApkUpdateManager.downloadRecordState(status),
      )
    }
  }

  @Test
  fun `the same archive keeps the same verification fingerprint`() {
    assertEquals(
      ApkUpdateManager.verificationFingerprint("/downloads/app.apk", 117L, 1_700_000_000_000L),
      ApkUpdateManager.verificationFingerprint("/downloads/app.apk", 117L, 1_700_000_000_000L),
    )
  }

  @Test
  fun `a rewritten or replaced archive does not reuse a verification fingerprint`() {
    val verified = ApkUpdateManager.verificationFingerprint(
      path = "/downloads/app.apk",
      lengthBytes = 117L,
      lastModifiedMs = 1_700_000_000_000L,
    )

    // Every field is one DownloadManager can change while leaving the other two alone: a new
    // release writes a new name, a resumed transfer only moves the size and the clock, and a
    // re-download of the identical release moves only the clock.
    listOf(
      ApkUpdateManager.verificationFingerprint("/downloads/other.apk", 117L, 1_700_000_000_000L),
      ApkUpdateManager.verificationFingerprint("/downloads/app.apk", 118L, 1_700_000_000_000L),
      ApkUpdateManager.verificationFingerprint("/downloads/app.apk", 117L, 1_700_000_000_001L),
    ).forEach { assertNotEquals(verified, it) }
  }
}
