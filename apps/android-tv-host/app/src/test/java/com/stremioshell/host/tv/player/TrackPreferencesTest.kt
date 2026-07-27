package com.stremioshell.host.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPreferencesTest {
  private fun audio(lang: String) = MpvTrack(id = 1, kind = TrackKind.Audio, lang = lang)
  private fun sub(lang: String) = MpvTrack(id = 1, kind = TrackKind.Subtitle, lang = lang)

  @Test
  fun `both ISO forms of a language fold onto one code`() {
    assertEquals("eng", LanguageCodes.normalize("en"))
    assertEquals("eng", LanguageCodes.normalize("eng"))
    assertEquals("eng", LanguageCodes.normalize("EN"))
    assertEquals("deu", LanguageCodes.normalize("ger"))
    assertEquals("zho", LanguageCodes.normalize("chi"))
  }

  @Test
  fun `a regional tag folds onto its language`() {
    assertEquals("por", LanguageCodes.normalize("pt-BR"))
    assertEquals("eng", LanguageCodes.normalize("en_GB"))
  }

  @Test
  fun `an absent or undetermined tag normalizes to nothing`() {
    assertEquals("", LanguageCodes.normalize(null))
    assertEquals("", LanguageCodes.normalize("  "))
    assertEquals("", LanguageCodes.normalize("und"))
  }

  @Test
  fun `an unknown code is kept as it came`() {
    assertEquals("qaa", LanguageCodes.normalize("qaa"))
    assertEquals("QAA", LanguageCodes.displayName("qaa"))
  }

  @Test
  fun `the mpv language list carries every spelling a container might use`() {
    val aliases = LanguageCodes.aliases("eng")

    assertEquals("eng", aliases.first())
    assertTrue(aliases.contains("en"))
    assertEquals("eng,en", TrackPreferences.alangValue("eng"))
    // Both bibliographic and terminological codes, or a preference learned from
    // one release does not match the next.
    assertEquals("deu,de,ger", TrackPreferences.alangValue("de"))
  }

  @Test
  fun `no stored language means no option to set`() {
    assertNull(TrackPreferences.alangValue(null))
    assertNull(TrackPreferences.alangValue(""))
    assertNull(TrackPreferences.slangValue(""))
  }

  @Test
  fun `switched-off subtitles are not expressed as a language`() {
    assertTrue(TrackPreferences.subtitlesOff("off"))
    assertTrue(TrackPreferences.subtitlesOff("OFF"))
    assertFalse(TrackPreferences.subtitlesOff("eng"))
    assertFalse(TrackPreferences.subtitlesOff(""))
    // slang cannot say "none": a leftover list would have mpv pick a track back
    // up on the next file.
    assertNull(TrackPreferences.slangValue("off"))
    assertEquals("spa,es", TrackPreferences.slangValue("spa"))
  }

  @Test
  fun `picking a tagged audio track becomes the preference`() {
    assertEquals(
      TrackPreferences.Update.Set("jpn"),
      TrackPreferences.audioUpdate(audio("ja")),
    )
  }

  @Test
  fun `picking an untagged audio track leaves the preference alone`() {
    // Nothing to learn: the preference the viewer set earlier has to survive a
    // file whose tracks carry no language at all.
    assertEquals(
      TrackPreferences.Update.Unchanged,
      TrackPreferences.audioUpdate(audio("")),
    )
    assertEquals(
      TrackPreferences.Update.Unchanged,
      TrackPreferences.audioUpdate(audio("und")),
    )
  }

  @Test
  fun `choosing Off is remembered as off`() {
    assertEquals(
      TrackPreferences.Update.Set(TrackPreferences.SUBTITLES_OFF),
      TrackPreferences.subtitleUpdate(null, "eng"),
    )
  }

  @Test
  fun `picking a tagged subtitle track becomes the preference`() {
    assertEquals(
      TrackPreferences.Update.Set("eng"),
      TrackPreferences.subtitleUpdate(sub("en"), TrackPreferences.SUBTITLES_OFF),
    )
  }

  @Test
  fun `switching untagged subtitles on clears a stored off`() {
    // Otherwise the next episode starts with subtitles off again and the viewer
    // switches them on every single time.
    assertEquals(
      TrackPreferences.Update.Set(""),
      TrackPreferences.subtitleUpdate(sub(""), TrackPreferences.SUBTITLES_OFF),
    )
  }

  @Test
  fun `picking an untagged subtitle track otherwise leaves the preference alone`() {
    assertEquals(
      TrackPreferences.Update.Unchanged,
      TrackPreferences.subtitleUpdate(sub(""), "eng"),
    )
    assertEquals(
      TrackPreferences.Update.Unchanged,
      TrackPreferences.subtitleUpdate(sub(""), ""),
    )
  }

  @Test
  fun `adding an external subtitle counts as choosing its language`() {
    // Same verdict an embedded track in that language gets: the addon's own tag is
    // all there is to go on before mpv has fetched the file.
    assertEquals(
      TrackPreferences.Update.Set("spa"),
      TrackPreferences.subtitleLanguageUpdate("es", "eng"),
    )
    assertEquals(
      TrackPreferences.Update.Set("eng"),
      TrackPreferences.subtitleLanguageUpdate("eng", TrackPreferences.SUBTITLES_OFF),
    )
  }

  @Test
  fun `adding an untagged external subtitle still clears a stored off`() {
    assertEquals(
      TrackPreferences.Update.Set(""),
      TrackPreferences.subtitleLanguageUpdate("", TrackPreferences.SUBTITLES_OFF),
    )
    assertEquals(
      TrackPreferences.Update.Unchanged,
      TrackPreferences.subtitleLanguageUpdate(null, "eng"),
    )
  }

  @Test
  fun `language names are what a viewer would call them`() {
    assertEquals("English", LanguageNames.display("eng"))
    assertEquals("Japanese", LanguageNames.display("ja"))
    assertEquals("French", LanguageNames.display("fre"))
    assertEquals("", LanguageNames.display(""))
  }
}
