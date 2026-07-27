package com.stremioshell.host.tv.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigMergeTest {
  private val storedKey = "stored-tmdb-key"
  private val storedUrl = "https://comet.example/rd-token/manifest.json"

  private fun merge(submission: PairingSubmission) =
    ConfigMerge.merge(submission, currentTmdbKey = storedKey, currentAddonUrl = storedUrl)

  @Test
  fun `blank fields are read as absent, not as an empty value`() {
    val submission = PairingSubmission.of(rawTmdbKey = "  ", rawAddonUrl = null)

    assertNull(submission.tmdbKey)
    assertNull(submission.addonUrl)
    assertTrue(submission.isEmpty)
  }

  @Test
  fun `surrounding whitespace is trimmed off submitted values`() {
    val submission = PairingSubmission.of(rawTmdbKey = "  new-key\n", rawAddonUrl = " https://x/m.json ")

    assertEquals("new-key", submission.tmdbKey)
    assertEquals("https://x/m.json", submission.addonUrl)
    assertFalse(submission.isEmpty)
  }

  @Test
  fun `submitting only the tmdb key keeps the stored addon url`() {
    val merged = merge(PairingSubmission.of("new-key", ""))

    assertEquals("new-key", merged.tmdbKey)
    assertEquals(storedUrl, merged.addonUrl)
    assertTrue(merged.tmdbKeyChanged)
    assertFalse(merged.addonUrlChanged)
    assertTrue(merged.changed)
  }

  @Test
  fun `submitting only the addon url keeps the stored tmdb key`() {
    val merged = merge(PairingSubmission.of(null, "https://new/manifest.json"))

    assertEquals(storedKey, merged.tmdbKey)
    assertEquals("https://new/manifest.json", merged.addonUrl)
    assertFalse(merged.tmdbKeyChanged)
    assertTrue(merged.addonUrlChanged)
  }

  @Test
  fun `submitting both replaces both`() {
    val merged = merge(PairingSubmission.of("k", "https://new/manifest.json"))

    assertEquals("k", merged.tmdbKey)
    assertEquals("https://new/manifest.json", merged.addonUrl)
    assertTrue(merged.tmdbKeyChanged)
    assertTrue(merged.addonUrlChanged)
  }

  @Test
  fun `re-submitting the stored values is not a change`() {
    val merged = merge(PairingSubmission.of(storedKey, storedUrl))

    assertEquals(storedKey, merged.tmdbKey)
    assertEquals(storedUrl, merged.addonUrl)
    assertFalse(merged.tmdbKeyChanged)
    assertFalse(merged.addonUrlChanged)
    assertFalse(merged.changed)
  }

  @Test
  fun `an empty submission leaves a fresh install empty rather than writing blanks`() {
    val merged = ConfigMerge.merge(PairingSubmission.of("", ""), "", "")

    assertEquals("", merged.tmdbKey)
    assertEquals("", merged.addonUrl)
    assertFalse(merged.changed)
  }
}
