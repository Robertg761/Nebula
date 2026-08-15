package com.stremioshell.host.tv.data

import com.stremioshell.host.tv.data.subtitles.SubtitlesClient
import java.net.UnknownHostException
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
  fun `subtitle base keeps configuration query but drops manifest case and fragment`() {
    assertEquals(
      "https://subs.example/cfg?token=secret",
      SettingsSaveGuard.normalizeSubtitlesBase(
        "https://subs.example/cfg/MANIFEST.JSON?token=secret#install",
      ),
    )
  }

  @Test
  fun `cleartext subtitle base falls back to the secure built-in addon`() {
    assertEquals(
      SubtitlesClient.OPENSUBTITLES_V3_BASE,
      SettingsSaveGuard.normalizeSubtitlesBase("http://subs.example/manifest.json"),
    )
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

  @Test
  fun `a save during a failed read cannot erase a key it could not see`() {
    // recoveringData hands blank values to a failed read, which look exactly like an unconfigured
    // TV. Acting on them turned a transient disk error into a wiped TMDB key and an empty addon
    // list - and the viewer's next Home load into "no key".
    val resolved = SettingsSaveGuard.resolve(
      draft(tmdbKey = "", addonUrls = emptyList(), subtitlesBaseUrl = "subs.example"),
      StoredSettings(tmdbKey = "", addonUrls = emptyList(), readable = false),
    )

    assertTrue(resolved.keptTmdbKey)
    assertTrue(resolved.keptAddonUrls)
    assertTrue(resolved.storedUnreadable)
    // Kept means "do not write this key at all"; see SettingsStore.persist.
    assertEquals(
      "Couldn't read your saved settings just now, so the values that could not be read were " +
        "left as they are. Try Save again in a moment.",
      SettingsSaveGuard.keptNotice(resolved),
    )
  }

  @Test
  fun `a failed read still lets the viewer write values they actually typed`() {
    val resolved = SettingsSaveGuard.resolve(
      draft(
        tmdbKey = "typed-key",
        addonUrls = listOf("comet.example"),
        subtitlesBaseUrl = "subs.example",
      ),
      StoredSettings(tmdbKey = "", addonUrls = emptyList(), readable = false),
    )

    assertFalse(resolved.keptTmdbKey)
    assertFalse(resolved.keptAddonUrls)
    assertFalse(resolved.keptSubtitlesBaseUrl)
    assertEquals("typed-key", resolved.tmdbKey)
    assertEquals(listOf("https://comet.example/manifest.json"), resolved.addonUrls)
    assertNull(SettingsSaveGuard.keptNotice(resolved))
  }

  @Test
  fun `a malformed stored addon list is always kept even when the draft has urls`() {
    val resolved = SettingsSaveGuard.resolve(
      draft(tmdbKey = "typed-key", addonUrls = listOf("new.example")),
      StoredSettings(
        tmdbKey = "old-key",
        addonUrls = emptyList(),
        addonUrlsReadable = false,
      ),
    )

    assertFalse(resolved.keptTmdbKey)
    assertTrue(resolved.keptAddonUrls)
    assertTrue(resolved.addonUrlsUnreadable)
    assertEquals(emptyList<String>(), resolved.addonUrls)
    assertTrue(SettingsSaveGuard.keptNotice(resolved)!!.contains("left untouched"))
  }

  @Test
  fun `a degraded default subtitle seed does not reset an unknown custom addon`() {
    val resolved = SettingsSaveGuard.resolve(
      draft(subtitlesBaseUrl = SubtitlesClient.OPENSUBTITLES_V3_BASE),
      StoredSettings(
        tmdbKey = "",
        addonUrls = emptyList(),
        readable = false,
        subtitlesBaseUrl = "https://saved-subs.example",
      ),
    )

    assertTrue(resolved.keptSubtitlesBaseUrl)
    assertEquals("https://saved-subs.example", resolved.subtitlesBaseUrl)
  }

  @Test
  fun `a rejected addon url is not reported as an empty list`() {
    // "the list was empty" describes what the guard did, not what the viewer did - and left them
    // with no idea that the address they typed was the problem.
    val resolved = SettingsSaveGuard.resolve(
      draft(tmdbKey = "k", addonUrls = listOf("http:/comet.example")),
      stored(addonUrls = listOf("https://saved.example/manifest.json")),
    )

    assertTrue(resolved.keptAddonUrls)
    assertTrue(resolved.addonInputRejected)
    assertEquals(
      "Kept your saved addons - none of the addon URLs were usable. Each one needs an https " +
        "address.",
      SettingsSaveGuard.keptNotice(resolved),
    )
  }

  @Test
  fun `rejected addon urls are reported even when there was nothing to keep`() {
    val resolved = SettingsSaveGuard.resolve(
      draft(tmdbKey = "k", addonUrls = listOf("http://cleartext.example")),
      stored(),
    )

    assertFalse(resolved.keptAddonUrls)
    assertTrue(resolved.addonInputRejected)
    assertEquals(
      "No addons saved - none of the addon URLs were usable. Each one needs an https address.",
      SettingsSaveGuard.keptNotice(resolved),
    )
  }

  @Test
  fun `a genuinely blank list keeps the wording it had`() {
    val resolved = SettingsSaveGuard.resolve(
      draft(tmdbKey = "k", addonUrls = listOf("  ", "")),
      stored(addonUrls = listOf("https://saved.example/manifest.json")),
    )

    assertFalse(resolved.addonInputRejected)
    assertEquals("Kept your saved addons - the list was empty.", SettingsSaveGuard.keptNotice(resolved))
  }

  @Test
  fun `one usable url among rejects is a save, not a complaint`() {
    val resolved = SettingsSaveGuard.resolve(
      draft(tmdbKey = "k", addonUrls = listOf("nonsense://x", "comet.example")),
      stored(),
    )

    assertFalse(resolved.addonInputRejected)
    assertEquals(listOf("https://comet.example/manifest.json"), resolved.addonUrls)
    assertNull(SettingsSaveGuard.keptNotice(resolved))
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
  fun `an outage is not reported as a bad key`() {
    // A boolean could not tell the two apart, so a router reboot sent viewers off to retype a
    // perfectly good key on a remote.
    val offline = TmdbProbeResult.of("abc", UnknownHostException("api.themoviedb.org"))

    assertTrue(offline is TmdbProbeResult.NetworkFailure)
    assertFalse(offline.connected)
    assertEquals(
      "TMDB: not checked - No internet connection. Check your network and try again.",
      SettingsStatus.tmdbStatus("abc", offline),
    )
  }

  @Test
  fun `only TMDB's own rejection blames the key`() {
    val rejected = TmdbProbeResult.of("abc", HttpStatusException(401, "api.themoviedb.org"))
    assertEquals(TmdbProbeResult.BadCredentials, rejected)
    assertEquals("TMDB: failed (check the key)", SettingsStatus.tmdbStatus("abc", rejected))

    assertEquals(
      TmdbProbeResult.BadCredentials,
      TmdbProbeResult.of("abc", HttpStatusException(403, "api.themoviedb.org")),
    )
    // A rate limit or an outage at TMDB says nothing about the credential.
    assertTrue(
      TmdbProbeResult.of("abc", HttpStatusException(429, "api.themoviedb.org"))
        is TmdbProbeResult.NetworkFailure,
    )
    assertTrue(
      TmdbProbeResult.of("abc", HttpStatusException(503, "api.themoviedb.org"))
        is TmdbProbeResult.NetworkFailure,
    )
  }

  @Test
  fun `a wrapped rejection is still recognised`() {
    val wrapped = IllegalStateException("probe", HttpStatusException(401, "api.themoviedb.org"))

    assertEquals(TmdbProbeResult.BadCredentials, TmdbProbeResult.of("abc", wrapped))
  }

  @Test
  fun `a probe that returned is connected, and a blank key is never probed`() {
    assertEquals(TmdbProbeResult.Ok, TmdbProbeResult.of("abc", null))
    assertTrue(TmdbProbeResult.of("abc", null).connected)
    assertEquals("TMDB: connected", SettingsStatus.tmdbStatus("abc", TmdbProbeResult.Ok))

    assertEquals(TmdbProbeResult.NoKey, TmdbProbeResult.of("   ", null))
    assertFalse(TmdbProbeResult.NoKey.connected)
    assertEquals("TMDB: no key", SettingsStatus.tmdbStatus("", TmdbProbeResult.NoKey))
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

  @Test
  fun `remote manifest names cannot inject controls or unbounded status text`() {
    val unsafe = "Comet\nConnected\u202E" + "x".repeat(100)
    val status = SettingsStatus.addonStatus(listOf(AddonProbe("Comet", unsafe)))

    assertEquals("Addon: connected (CometConnected${"x".repeat(66)})", status)
  }
}
