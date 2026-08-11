package com.stremioshell.host.update

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoUpdatePolicyTest {
  private val sampleUpdate = UpdateInfo(
    latestVersionName = "0.2.0",
    apkName = "StremioShell-tv-0.2.0.apk",
    apkUrl = "https://example.invalid/tv.apk"
  )

  @Test
  fun `returns no update when update info is missing`() {
    val decision = AutoUpdatePolicy.decide(
      updateInfo = null,
      hasDownloadedForVersion = false,
      hasActiveDownload = false
    )

    assertEquals(AutoUpdatePolicy.Decision.NO_UPDATE, decision)
  }

  @Test
  fun `returns already downloaded when matching apk is present`() {
    val decision = AutoUpdatePolicy.decide(
      updateInfo = sampleUpdate,
      hasDownloadedForVersion = true,
      hasActiveDownload = false
    )

    assertEquals(AutoUpdatePolicy.Decision.ALREADY_DOWNLOADED, decision)
  }

  @Test
  fun `returns download in progress when active download exists`() {
    val decision = AutoUpdatePolicy.decide(
      updateInfo = sampleUpdate,
      hasDownloadedForVersion = false,
      hasActiveDownload = true
    )

    assertEquals(AutoUpdatePolicy.Decision.DOWNLOAD_IN_PROGRESS, decision)
  }

  @Test
  fun `returns start download when update is new and idle`() {
    val decision = AutoUpdatePolicy.decide(
      updateInfo = sampleUpdate,
      hasDownloadedForVersion = false,
      hasActiveDownload = false
    )

    assertEquals(AutoUpdatePolicy.Decision.START_DOWNLOAD, decision)
  }

  @Test
  fun `does not re-download a release this device already rejected`() {
    // The ~117 MB loop: a release that fails the archive check is deleted, and without this the
    // six-hourly worker fetched the identical file again, forever.
    val decision = AutoUpdatePolicy.decide(
      updateInfo = sampleUpdate,
      hasDownloadedForVersion = false,
      hasActiveDownload = false,
      isRejectedRelease = true
    )

    assertEquals(AutoUpdatePolicy.Decision.REJECTED_RELEASE, decision)
  }

  @Test
  fun `an active download still outranks a remembered rejection`() {
    // The rejection is keyed on the version, and a download in flight is the more specific fact.
    assertEquals(
      AutoUpdatePolicy.Decision.DOWNLOAD_IN_PROGRESS,
      AutoUpdatePolicy.decide(
        updateInfo = sampleUpdate,
        hasDownloadedForVersion = false,
        hasActiveDownload = true,
        isRejectedRelease = true
      )
    )
    assertEquals(
      AutoUpdatePolicy.Decision.ALREADY_DOWNLOADED,
      AutoUpdatePolicy.decide(
        updateInfo = sampleUpdate,
        hasDownloadedForVersion = true,
        hasActiveDownload = false,
        isRejectedRelease = true
      )
    )
  }

  @Test
  fun `a rejection cannot invent an update out of nothing`() {
    assertEquals(
      AutoUpdatePolicy.Decision.NO_UPDATE,
      AutoUpdatePolicy.decide(
        updateInfo = null,
        hasDownloadedForVersion = false,
        hasActiveDownload = false,
        isRejectedRelease = true
      )
    )
  }
}
