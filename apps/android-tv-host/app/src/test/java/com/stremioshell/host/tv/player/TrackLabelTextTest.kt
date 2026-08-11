package com.stremioshell.host.tv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackLabelTextTest {
  @Test
  fun `a title that says something the language does not is kept`() {
    assertEquals(
      "Spanish - Latino",
      TrackLabelText.line(language = "Spanish", sourceLanguage = "Spanish", title = "Latino"),
    )
  }

  @Test
  fun `a title that is only the English language name is dropped`() {
    assertEquals(
      "Spanish",
      TrackLabelText.line(language = "Spanish", sourceLanguage = "Spanish", title = "Spanish"),
    )
  }

  @Test
  fun `a title that is only the localized language name is dropped`() {
    // The bug this check was written for and missed: on a Spanish device the row
    // leads with "Español" and the muxer's own "Español" was compared against the
    // English "Spanish" alone, so the line read "Español - Español".
    assertEquals(
      "Español",
      TrackLabelText.line(language = "Español", sourceLanguage = "Spanish", title = "Español"),
    )
    assertEquals(
      "Español",
      TrackLabelText.line(language = "Español", sourceLanguage = "Spanish", title = " español "),
    )
    // The English spelling still goes, whichever locale the row is drawn in.
    assertEquals(
      "Español",
      TrackLabelText.line(language = "Español", sourceLanguage = "Spanish", title = "SPANISH"),
    )
  }

  @Test
  fun `an untagged track is named by its title alone`() {
    assertEquals(
      "Director commentary",
      TrackLabelText.line(language = "", sourceLanguage = "", title = "Director commentary"),
    )
  }

  @Test
  fun `nothing to say leaves the caller its track-number fallback`() {
    assertEquals("", TrackLabelText.line(language = "", sourceLanguage = "", title = "   "))
    // A title that is the language and no language to lead with cannot happen from
    // the menu, but it must not produce a stray separator if it ever did.
    assertEquals("English", TrackLabelText.line("English", "English", "English"))
  }
}
