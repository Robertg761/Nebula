package com.stremioshell.host.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateRepositoryTest {
  private fun asset(name: String) = GitHubAssetDto(
    name = name,
    browserDownloadUrl = "https://example.invalid/$name",
    size = 123L,
  )

  @Test
  fun `selects only the canonical tv artifact for the release version`() {
    val expected = asset("StremioShell-tv-0.6.2.apk")
    val selected = UpdateRepository.selectApkAsset(
      assets = listOf(
        asset("StremioShell-0.6.2.apk"),
        asset("StremioShell-tv-0.6.1.apk"),
        asset("StremioShell-tv-0.6.2-debug.apk"),
        expected,
      ),
      versionName = "0.6.2",
    )

    assertEquals(expected, selected)
  }

  @Test
  fun `does not accept casing variants or another apk as a fallback`() {
    val selected = UpdateRepository.selectApkAsset(
      assets = listOf(
        asset("stremioshell-tv-0.6.2.apk"),
        asset("StremioShell-mobile-0.6.2.apk"),
        asset("StremioShell-tv-0.6.3.apk"),
      ),
      versionName = "0.6.2",
    )

    assertNull(selected)
  }

  @Test
  fun `rejects ambiguous duplicate canonical assets`() {
    val expected = asset("StremioShell-tv-0.6.2.apk")

    assertNull(
      UpdateRepository.selectApkAsset(
        assets = listOf(expected, expected.copy(browserDownloadUrl = "https://other.invalid/app.apk")),
        versionName = "0.6.2",
      ),
    )
  }
}
