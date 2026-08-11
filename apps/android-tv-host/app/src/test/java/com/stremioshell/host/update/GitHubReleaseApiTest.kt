package com.stremioshell.host.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubReleaseApiTest {
  private val sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

  private fun release(json: String): GitHubLatestReleaseDto =
    GitHubReleaseApi.parseRelease(JSONObject(json))

  @Test
  fun `parses the asset digest the release publishes beside the file`() {
    val parsed = release(
      """
      {
        "tag_name": "v0.6.2",
        "assets": [
          {
            "name": "StremioShell-tv-0.6.2.apk",
            "browser_download_url": "https://example.invalid/StremioShell-tv-0.6.2.apk",
            "size": 117000000,
            "digest": "sha256:$sha256"
          }
        ]
      }
      """.trimIndent()
    )

    val asset = parsed.assets.single()
    assertEquals(117_000_000L, asset.size)
    assertEquals(sha256, asset.sha256)
  }

  @Test
  fun `an absent digest leaves the byte count as the only check`() {
    val parsed = release(
      """
      {
        "tag_name": "v0.6.2",
        "assets": [
          {
            "name": "StremioShell-tv-0.6.2.apk",
            "browser_download_url": "https://example.invalid/a.apk",
            "size": 117000000
          },
          {
            "name": "other.apk",
            "browser_download_url": "https://example.invalid/b.apk",
            "size": 1,
            "digest": null
          }
        ]
      }
      """.trimIndent()
    )

    assertNull(parsed.assets[0].sha256)
    assertNull(parsed.assets[1].sha256)
  }

  @Test
  fun `only a well-formed sha256 digest is accepted`() {
    assertEquals(sha256, GitHubReleaseApi.parseSha256Digest("sha256:$sha256"))
    // Casing is normalized so the comparison downstream never has to think about it.
    assertEquals(sha256, GitHubReleaseApi.parseSha256Digest("SHA256:${sha256.uppercase()}"))
    assertEquals(sha256, GitHubReleaseApi.parseSha256Digest("  sha256:$sha256  "))

    // Another algorithm, a truncated value, or a non-hex body is not something to guess at.
    assertNull(GitHubReleaseApi.parseSha256Digest("sha512:$sha256"))
    assertNull(GitHubReleaseApi.parseSha256Digest("sha256:${sha256.dropLast(1)}"))
    assertNull(GitHubReleaseApi.parseSha256Digest("sha256:${"z".repeat(64)}"))
    assertNull(GitHubReleaseApi.parseSha256Digest(sha256))
    assertNull(GitHubReleaseApi.parseSha256Digest(null))
    assertNull(GitHubReleaseApi.parseSha256Digest(""))
  }

  @Test
  fun `an assetsless or malformed release yields no assets`() {
    assertEquals(emptyList<GitHubAssetDto>(), release("""{"tag_name": "v0.6.2"}""").assets)
    assertEquals(
      emptyList<GitHubAssetDto>(),
      release("""{"tag_name": "v0.6.2", "assets": [{"name": "x.apk"}, {"size": 1}]}""").assets,
    )
  }

  @Test
  fun `a release published with no body has no release notes`() {
    // org.json has no nullable accessor: on Android optString renders a JSON null as the literal
    // string "null", which is what the update prompt used to show as its release notes.
    val parsed = release("""{"tag_name": "v0.6.2", "body": null, "html_url": null}""")

    assertNull(parsed.body)
    assertNull(parsed.htmlUrl)
  }

  @Test
  fun `an empty or whitespace body is the same as no body`() {
    assertNull(release("""{"tag_name": "v0.6.2", "body": ""}""").body)
    assertNull(release("""{"tag_name": "v0.6.2", "body": "   "}""").body)
  }

  @Test
  fun `a body that is present is trimmed and kept`() {
    assertEquals(
      "Fixed the updater.",
      release("""{"tag_name": "v0.6.2", "body": "  Fixed the updater.\n"}""").body,
    )
  }

  @Test
  fun `the tag and publish date come through`() {
    val parsed = release(
      """{"tag_name": " v0.6.2-beta.1 ", "published_at": "2026-08-10T00:00:00Z"}"""
    )

    assertEquals("v0.6.2-beta.1", parsed.tagName)
    assertEquals("2026-08-10T00:00:00Z", parsed.publishedAt)
  }
}
