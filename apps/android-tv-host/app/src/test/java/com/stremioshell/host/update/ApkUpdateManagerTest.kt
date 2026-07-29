package com.stremioshell.host.update

import android.app.DownloadManager
import org.junit.Assert.assertEquals
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
}
