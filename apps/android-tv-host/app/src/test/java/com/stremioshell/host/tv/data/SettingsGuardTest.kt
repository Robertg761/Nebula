package com.stremioshell.host.tv.data

import com.stremioshell.host.tv.data.subtitles.SubtitlesClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSaveGuardTest {
  @Test
  fun `saving a blank key over a working one keeps the working one`() {
    // The field is seeded from storage, so blank is an accident far more often than
    // an instruction - and retyping a TMDB key on a remote costs minutes.
    val resolved = SettingsSaveGuard.resolve(
      draft(tmdbKey = "   "),
      stored(tmdbKey = "abc123"),
    )

    assertEquals("abc123", resolved.tmdbKey)
    assertTrue(resolved.keptTmdbKey)
  }

  @Test
  fun `a blank key with nothing stored saves as blank`() {
    val resolved = SettingsSaveGuard.resolve(draft(tmdbKey = ""), stored(tmdbKey = ""))

    assertEquals("", resolved.tmdbKey)
    assertFalse(resolved.keptTmdbKey)
  }

  @Test
  fun `a key that was typed replaces the stored one`() {
    val resolved = SettingsSaveGuard.resolve(
      draft(tmdbKey = "  new-key  "),
      stored(tmdbKey = "old-key"),
    )

    assertEquals("new-key", resolved.tmdbKey)
    assertFalse(resolved.keptTmdbKey)
  }

  @Test
  fun `an empty addon list does not wipe a stored one`() {
    val resolved = SettingsSaveGuard.resolve(
      draft(addonUrls = emptyList()),
      stored(addonUrls = listOf("https://comet.example/manifest.json")),
    )

    assertEquals(listOf("https://comet.example/manifest.json"), resolved.addonUrls)
    assertTrue(resolved.keptAddonUrls)
  }

  @Test
  fun `an empty addon list with nothing stored stays empty`() {
    val resolved = SettingsSaveGuard.resolve(draft(), stored())

    assertEquals(emptyList<String>(), resolved.addonUrls)
    assertFalse(resolved.keptAddonUrls)
  }

  @Test
  fun `the saved addon list is normalized and deduplicated`() {
    val resolved = SettingsSaveGuard.resolve(
      draft(addonUrls = listOf("comet.example", "https://comet.example/", " ")),
      stored(),
    )

    assertEquals(listOf("https://comet.example/manifest.json"), resolved.addonUrls)
  }

  @Test
  fun `a blank subtitles url resets to the built-in addon`() {
    assertEquals(
      SubtitlesClient.OPENSUBTITLES_V3_BASE,
      SettingsSaveGuard.normalizeSubtitlesBase("   "),
    )
  }

  @Test
  fun `a subtitles url is stored as a base, whichever form was pasted`() {
    assertEquals(
      "https://subs.example",
      SettingsSaveGuard.normalizeSubtitlesBase("https://subs.example/manifest.json"),
    )
    assertEquals("https://subs.example", SettingsSaveGuard.normalizeSubtitlesBase("subs.example/"))
  }

  @Test
  fun `the guard says what it held back`() {
    assertNull(SettingsSaveGuard.keptNotice(SettingsSaveGuard.resolve(draft("k"), stored())))

    val keptKey = SettingsSaveGuard.resolve(draft(tmdbKey = ""), stored(tmdbKey = "abc"))
    assertEquals(
      "Kept your saved TMDB key - the field was blank. Use Clear to remove it.",
      SettingsSaveGuard.keptNotice(keptKey),
    )

    val keptAddons = SettingsSaveGuard.resolve(
      draft(tmdbKey = "k"),
      stored(addonUrls = listOf("https://a.example/manifest.json")),
    )
    assertEquals("Kept your saved addons - the list was empty.", SettingsSaveGuard.keptNotice(keptAddons))

    val keptBoth = SettingsSaveGuard.resolve(
      draft(),
      stored(tmdbKey = "abc", addonUrls = listOf("https://a.example/manifest.json")),
    )
    assertEquals(
      "Kept your saved TMDB key and addons - both were blank. Use Clear to remove them.",
      SettingsSaveGuard.keptNotice(keptBoth),
    )
  }

  private fun draft(
    tmdbKey: String = "",
    addonUrls: List<String> = emptyList(),
    subtitlesBaseUrl: String = "",
  ) = SettingsDraft(tmdbKey, addonUrls, subtitlesBaseUrl)

  private fun stored(
    tmdbKey: String = "",
    addonUrls: List<String> = emptyList(),
  ) = StoredSettings(tmdbKey, addonUrls)
}

class SettingsStatusTest {
  @Test
  fun `a missing key is reported as missing, not as a failure`() {
    assertEquals("TMDB: no key", SettingsStatus.tmdbStatus("", null))
    assertEquals("TMDB: connected", SettingsStatus.tmdbStatus("abc", true))
    assertEquals("TMDB: failed (check the key)", SettingsStatus.tmdbStatus("abc", false))
  }

  @Test
  fun `one addon keeps the wording it had before the list existed`() {
    assertEquals(
      "Addon: connected (Comet)",
      SettingsStatus.addonStatus(listOf(AddonProbe("Comet", "Comet"))),
    )
    assertEquals("Addon: failed (check the URL)", SettingsStatus.addonStatus(listOf(AddonProbe("Comet", null))))
  }

  @Test
  fun `several addons report a count, and name only the ones that failed`() {
    assertEquals("Addons: none configured", SettingsStatus.addonStatus(emptyList()))
    assertEquals(
      "Addons: 2 connected",
      SettingsStatus.addonStatus(listOf(AddonProbe("Comet", "Comet"), AddonProbe("Torrentio", "Torrentio"))),
    )
    assertEquals(
      "Addons: 1 of 2 connected (Torrentio failed)",
      SettingsStatus.addonStatus(listOf(AddonProbe("Comet", "Comet"), AddonProbe("Torrentio", null))),
    )
    assertEquals(
      "Addons: none connected (check the URLs)",
      SettingsStatus.addonStatus(listOf(AddonProbe("Comet", null), AddonProbe("Torrentio", null))),
    )
  }

  @Test
  fun `an addon whose manifest has no name still reads as connected`() {
    assertEquals(
      "Addon: connected (addon)",
      SettingsStatus.addonStatus(listOf(AddonProbe("Comet", ""))),
    )
  }
}
