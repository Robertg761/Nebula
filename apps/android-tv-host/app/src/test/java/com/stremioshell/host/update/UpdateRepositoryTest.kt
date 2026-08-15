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

  private fun release(
    tag: String,
    prerelease: Boolean = false,
    draft: Boolean = false,
  ) = GitHubLatestReleaseDto(
    tagName = tag,
    htmlUrl = null,
    body = null,
    publishedAt = null,
    assets = emptyList(),
    prerelease = prerelease,
    draft = draft,
  )

  @Test
  fun `stable installs ignore prereleases even when their numeric core is newer`() {
    val selected = UpdateRepository.selectRelease(
      releases = listOf(
        release("v0.7.0-beta.1", prerelease = true),
        release("v0.6.2"),
      ),
      currentVersionName = "0.6.1",
    )

    assertEquals("v0.6.2", selected?.tagName)
  }

  @Test
  fun `an installed beta discovers a later beta`() {
    val selected = UpdateRepository.selectRelease(
      releases = listOf(
        release("v0.6.2-beta.1", prerelease = true),
        release("v0.6.2-beta.2", prerelease = true),
      ),
      currentVersionName = "0.6.2-beta.1",
    )

    assertEquals("v0.6.2-beta.2", selected?.tagName)
  }

  @Test
  fun `stable wins over prerelease of the same core and drafts never participate`() {
    val selected = UpdateRepository.selectRelease(
      releases = listOf(
        release("v9.0.0", draft = true),
        release("v0.6.2-beta.2", prerelease = true),
        release("v0.6.2"),
      ),
      currentVersionName = "0.6.2-beta.1",
    )

    assertEquals("v0.6.2", selected?.tagName)
  }

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
  fun `a prerelease tag still selects the numerically named asset`() {
    // The workflow tags a prerelease v0.6.2-beta.1 but names the file from versionName, so the
    // asset is StremioShell-tv-0.6.2.apk. The lookup key is the core version, never the tag.
    val expected = asset("StremioShell-tv-0.6.2.apk")

    assertEquals(
      expected,
      UpdateRepository.selectApkAsset(
        assets = listOf(expected),
        versionName = SemVer.coreLabel("v0.6.2-beta.1"),
      ),
    )
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

  @Test
  fun `accepts only the exact https GitHub release asset route`() {
    val name = "StremioShell-tv-0.6.2.apk"
    assertEquals(
      true,
      UpdateRepository.isTrustedApkUrl(
        "https://github.com/Robert-026/Nebula/releases/download/v0.6.2/$name",
        "Robert-026",
        "Nebula",
        name,
      ),
    )
    val rejected = listOf(
      "http://github.com/Robert-026/Nebula/releases/download/v0.6.2/$name",
      "https://github.example/Robert-026/Nebula/releases/download/v0.6.2/$name",
      "https://github.com/other/Nebula/releases/download/v0.6.2/$name",
      "https://github.com/Robert-026/Nebula/releases/download/v0.6.2/other.apk",
      "https://objects.example/$name",
    )
    assertEquals(
      rejected,
      rejected.filterNot {
        UpdateRepository.isTrustedApkUrl(it, "Robert-026", "Nebula", name)
      },
    )
  }
}
