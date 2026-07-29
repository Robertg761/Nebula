package com.stremioshell.host.tv.pairing

import com.stremioshell.host.tv.data.addon.AddonList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigMergeTest {
  private val storedKey = "stored-tmdb-key"
  private val storedUrl = "https://comet.example/rd-token/manifest.json"
  private val storedUrls = listOf(storedUrl, "https://torrentio.example/manifest.json")

  private fun merge(submission: PairingSubmission) =
    ConfigMerge.merge(submission, currentTmdbKey = storedKey, currentAddonUrls = storedUrls)

  @Test
  fun `interactive addon validation rejects any malformed line`() {
    assertEquals(
      "Every addon line must be a usable manifest link.",
      PairingSubmission.addonInputError(
        "https://valid.example/manifest.json\nstremio://",
      ),
    )
  }

  @Test
  fun `interactive addon validation explains when every line is unusable`() {
    assertEquals(
      "No usable addon link in that box. Paste the manifest URL.",
      PairingSubmission.addonInputError("stremio://"),
    )
  }

  @Test
  fun `interactive addon validation rejects overflow instead of truncating`() {
    val raw = (1..AddonList.MAX_ADDONS + 1)
      .joinToString("\n") { "https://a$it.example/manifest.json" }

    assertEquals(
      "Enter no more than ${AddonList.MAX_ADDONS} addon links.",
      PairingSubmission.addonInputError(raw),
    )
  }

  @Test
  fun `blank fields are read as absent, not as an empty value`() {
    val submission = PairingSubmission.of(rawTmdbKey = "  ", rawAddonUrls = null)

    assertNull(submission.tmdbKey)
    assertNull(submission.addonUrls)
    assertTrue(submission.isEmpty)
  }

  @Test
  fun `surrounding whitespace is trimmed off submitted values`() {
    val submission = PairingSubmission.of(rawTmdbKey = "  new-key", rawAddonUrls = " https://x/manifest.json ")

    assertEquals("new-key", submission.tmdbKey)
    assertEquals(listOf("https://x/manifest.json"), submission.addonUrls)
    assertFalse(submission.isEmpty)
  }

  @Test
  fun `the addon box is read as one url per line`() {
    val submission = PairingSubmission.of(
      rawTmdbKey = null,
      rawAddonUrls = "https://a.example/manifest.json\r\nhttps://b.example/manifest.json",
    )

    assertEquals(
      listOf("https://a.example/manifest.json", "https://b.example/manifest.json"),
      submission.addonUrls,
    )
  }

  @Test
  fun `submitted urls arrive in the form the tv stores`() {
    // A bare host, a stremio:// install link and a URL missing its manifest tail are all things a
    // phone keyboard and a copied browser link actually produce.
    val submission = PairingSubmission.of(
      rawTmdbKey = null,
      rawAddonUrls = "comet.example\nstremio://torrentio.example/manifest.json\nhttps://c.example",
    )

    assertEquals(
      listOf(
        "https://comet.example/manifest.json",
        "https://torrentio.example/manifest.json",
        "https://c.example/manifest.json",
      ),
      submission.addonUrls,
    )
  }

  @Test
  fun `blank lines and duplicates are dropped, and the list is capped`() {
    val raw = (1..AddonList.MAX_ADDONS + 3).joinToString("\n\n") { "https://a$it.example/manifest.json" }
    val submission = PairingSubmission.of(null, "$raw\n   \nhttps://a1.example/manifest.json")

    assertEquals(AddonList.MAX_ADDONS, submission.addonUrls?.size)
    assertEquals(submission.addonUrls, submission.addonUrls?.distinct())
  }

  @Test
  fun `a box with nothing url-shaped in it submits no addons at all`() {
    // Distinguishable from a blank box only by the raw text, which is what lets the server report
    // a typo instead of silently saving nothing.
    assertNull(PairingSubmission.of(null, "https://").addonUrls)
    assertEquals(emptyList<String>(), PairingSubmission.addonUrlsIn("stremio://"))
  }

  @Test
  fun `submitting only the tmdb key keeps the stored addons`() {
    val merged = merge(PairingSubmission.of("new-key", ""))

    assertEquals("new-key", merged.tmdbKey)
    assertEquals(storedUrls, merged.addonUrls)
    assertTrue(merged.tmdbKeyChanged)
    assertFalse(merged.addonUrlsChanged)
    assertTrue(merged.changed)
  }

  @Test
  fun `submitting only the addons keeps the stored tmdb key`() {
    val merged = merge(PairingSubmission.of(null, "https://new/manifest.json"))

    assertEquals(storedKey, merged.tmdbKey)
    assertEquals(listOf("https://new/manifest.json"), merged.addonUrls)
    assertFalse(merged.tmdbKeyChanged)
    assertTrue(merged.addonUrlsChanged)
  }

  @Test
  fun `a submitted list replaces the stored one rather than appending to it`() {
    // The phone form is the whole list, not an add button: what the viewer sees on their screen
    // has to be what the TV ends up with.
    val merged = merge(PairingSubmission.of(null, "https://new/manifest.json\nhttps://other/manifest.json"))

    assertEquals(listOf("https://new/manifest.json", "https://other/manifest.json"), merged.addonUrls)
    assertTrue(merged.addonUrlsChanged)
  }

  @Test
  fun `submitting both replaces both`() {
    val merged = merge(PairingSubmission.of("k", "https://new/manifest.json"))

    assertEquals("k", merged.tmdbKey)
    assertEquals(listOf("https://new/manifest.json"), merged.addonUrls)
    assertTrue(merged.tmdbKeyChanged)
    assertTrue(merged.addonUrlsChanged)
  }

  @Test
  fun `re-submitting the stored values is not a change`() {
    val merged = merge(PairingSubmission.of(storedKey, storedUrls.joinToString("\n")))

    assertEquals(storedKey, merged.tmdbKey)
    assertEquals(storedUrls, merged.addonUrls)
    assertFalse(merged.tmdbKeyChanged)
    assertFalse(merged.addonUrlsChanged)
    assertFalse(merged.changed)
  }

  @Test
  fun `re-submitting a stored url in a looser form is not a change either`() {
    // Compared after normalising, so re-pasting the same addon as a bare host does not churn
    // DataStore or restart every stream request behind it.
    val merged = ConfigMerge.merge(
      PairingSubmission.of(null, "comet.example\ntorrentio.example"),
      currentTmdbKey = storedKey,
      currentAddonUrls = listOf("https://comet.example/manifest.json", "https://torrentio.example"),
    )

    assertFalse(merged.addonUrlsChanged)
  }

  @Test
  fun `reordering the submitted urls is a change`() {
    // Order is the viewer's own preference: it decides which addon's row wins a duplicate release.
    val merged = merge(PairingSubmission.of(null, storedUrls.reversed().joinToString("\n")))

    assertEquals(storedUrls.reversed(), merged.addonUrls)
    assertTrue(merged.addonUrlsChanged)
  }

  @Test
  fun `an empty submission leaves a fresh install empty rather than writing blanks`() {
    val merged = ConfigMerge.merge(PairingSubmission.of("", ""), "", emptyList())

    assertEquals("", merged.tmdbKey)
    assertEquals(emptyList<String>(), merged.addonUrls)
    assertFalse(merged.changed)
  }
}
