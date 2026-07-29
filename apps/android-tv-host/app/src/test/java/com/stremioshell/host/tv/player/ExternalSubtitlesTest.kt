package com.stremioshell.host.tv.player

import com.stremioshell.host.tv.data.addon.AddonStreamSubtitle
import com.stremioshell.host.tv.data.subtitles.AddonSubtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSubtitlesTest {
  @Test
  fun `embedded stream subtitles are bounded deduplicated http urls`() {
    val valid = (1..100).map {
      AddonStreamSubtitle(url = "https://subs.example/$it.srt", lang = "eng")
    }
    val response = listOf(
      AddonStreamSubtitle(url = "ftp://subs.example/no.srt"),
      AddonStreamSubtitle(url = "data:text/plain,no"),
      AddonStreamSubtitle(url = "relative.srt"),
      AddonStreamSubtitle(url = "https://subs.example/duplicate.srt"),
      AddonStreamSubtitle(url = " https://subs.example/duplicate.srt "),
    ) + valid

    val sanitized = EmbeddedSubtitles.sanitize(response)

    assertEquals(EmbeddedSubtitles.MAX_OPTIONS, sanitized.size)
    assertEquals(1, sanitized.count { it.url.endsWith("duplicate.srt") })
    assertTrue(sanitized.all { it.url.startsWith("https://") })
  }

  @Test
  fun `embedded subtitle url and metadata lengths are bounded`() {
    val overlongUrl = "https://subs.example/" +
      "x".repeat(EmbeddedSubtitles.MAX_URL_LENGTH) +
      ".srt"
    val sanitized = EmbeddedSubtitles.sanitize(
      listOf(
        AddonStreamSubtitle(url = overlongUrl),
        AddonStreamSubtitle(
          id = "i".repeat(EmbeddedSubtitles.MAX_ID_LENGTH + 20),
          url = "http://subs.example/good.srt",
          lang = "l".repeat(EmbeddedSubtitles.MAX_LANGUAGE_LENGTH + 20),
        ),
      ),
    )

    assertEquals(1, sanitized.size)
    assertEquals(EmbeddedSubtitles.MAX_ID_LENGTH, sanitized.single().id?.length)
    assertEquals(EmbeddedSubtitles.MAX_LANGUAGE_LENGTH, sanitized.single().lang?.length)
  }

  @Test
  fun `each language is capped, keeping the addon's own order`() {
    val options = ExternalSubtitles.options(subtitles("eng" to 5, "fra" to 2), preferredLanguage = "")

    assertEquals(ExternalSubtitles.PER_LANGUAGE + 2, options.size)
    assertEquals(
      listOf("eng-1", "eng-2", "eng-3", "fra-1", "fra-2"),
      options.map { it.url },
    )
  }

  @Test
  fun `the preferred language comes first, then the rest alphabetically`() {
    val options = ExternalSubtitles.options(
      subtitles("fra" to 1, "eng" to 1, "spa" to 1, "deu" to 1),
      preferredLanguage = "spa",
    )

    assertEquals(
      listOf("Spanish", "English", "French", "German"),
      options.map { it.label },
    )
  }

  @Test
  fun `a preference stored in the other iso form still matches`() {
    val options = ExternalSubtitles.options(subtitles("eng" to 1, "por" to 1), preferredLanguage = "pt")

    assertEquals("Portuguese", options.first().label)
  }

  @Test
  fun `a regional tag is folded onto its language`() {
    val options = ExternalSubtitles.options(
      listOf(subtitle("pt-BR", "a"), subtitle("por", "b")),
      preferredLanguage = "",
    )

    assertEquals(listOf("Portuguese", "Portuguese"), options.map { it.label })
    assertEquals("Online subtitle 1 of 2", options.first().detail)
  }

  @Test
  fun `switched-off subtitles are not a preferred language`() {
    // A viewer asking an addon for subtitles is choosing one now; "off" is what
    // they had before, not a language to float to the top.
    val options = ExternalSubtitles.options(
      subtitles("fra" to 1, "eng" to 1),
      preferredLanguage = TrackPreferences.SUBTITLES_OFF,
    )

    assertEquals(listOf("English", "French"), options.map { it.label })
  }

  @Test
  fun `identical urls are offered once`() {
    val duplicated = listOf(
      subtitle("eng", "same"),
      subtitle("eng", "same"),
      subtitle("eng", "other"),
    )

    val options = ExternalSubtitles.options(duplicated, preferredLanguage = "eng")

    assertEquals(listOf("same", "other"), options.map { it.url })
  }

  @Test
  fun `a duplicate does not use up one of the three offered per language`() {
    val entries = listOf(
      subtitle("eng", "a"),
      subtitle("eng", "a"),
      subtitle("eng", "b"),
      subtitle("eng", "c"),
    )

    val options = ExternalSubtitles.options(entries, preferredLanguage = "")

    assertEquals(listOf("a", "b", "c"), options.map { it.url })
  }

  @Test
  fun `blank urls are dropped`() {
    val options = ExternalSubtitles.options(
      listOf(AddonSubtitle(id = "1", url = "  ", lang = "eng"), subtitle("eng", "a")),
      preferredLanguage = "",
    )

    assertEquals(listOf("a"), options.map { it.url })
  }

  @Test
  fun `urls are trimmed, so padding cannot smuggle a duplicate through`() {
    val options = ExternalSubtitles.options(
      listOf(subtitle("eng", "a"), AddonSubtitle(id = "2", url = " a ", lang = "eng")),
      preferredLanguage = "",
    )

    assertEquals(listOf("a"), options.map { it.url })
  }

  @Test
  fun `untagged files are offered last, under a name that admits as much`() {
    val options = ExternalSubtitles.options(
      listOf(subtitle("", "unknown"), subtitle("zho", "chinese"), subtitle("eng", "english")),
      preferredLanguage = "",
    )

    assertEquals(listOf("Chinese", "English", "Unknown language"), options.map { it.label })
    assertEquals("", options.last().lang)
  }

  @Test
  fun `an unrecognised code is shown as the code itself rather than hidden`() {
    val options = ExternalSubtitles.options(listOf(subtitle("qaa", "a")), preferredLanguage = "")

    assertEquals("QAA", options.first().label)
    assertEquals("qaa", options.first().lang)
  }

  @Test
  fun `the language a file is in is what its lang carries, normalized`() {
    // The value handed to `sub-add` and to the stored preference has to be the
    // canonical code, not whatever spelling the addon used.
    val options = ExternalSubtitles.options(listOf(subtitle("fre", "a")), preferredLanguage = "")

    assertEquals("fra", options.first().lang)
  }

  @Test
  fun `a language with one file says so plainly`() {
    val options = ExternalSubtitles.options(subtitles("eng" to 1), preferredLanguage = "")

    assertEquals("Online subtitle", options.first().detail)
    assertEquals("Online", options.first().trackTitle)
  }

  @Test
  fun `several files of one language are numbered, in the list and in mpv`() {
    val options = ExternalSubtitles.options(subtitles("eng" to 3), preferredLanguage = "")

    assertEquals(
      listOf("Online subtitle 1 of 3", "Online subtitle 2 of 3", "Online subtitle 3 of 3"),
      options.map { it.detail },
    )
    assertEquals(listOf("Online 1", "Online 2", "Online 3"), options.map { it.trackTitle })
  }

  @Test
  fun `the count is what is offered, not what the addon returned`() {
    val options = ExternalSubtitles.options(subtitles("eng" to 40), preferredLanguage = "")

    assertTrue(options.all { it.detail.endsWith("of ${ExternalSubtitles.PER_LANGUAGE}") })
  }

  @Test
  fun `a malicious language fanout cannot create an unwalkable menu`() {
    val response = (1..100).map { subtitle("q$it", "https://subs.example/$it.srt") }

    assertEquals(
      ExternalSubtitles.MAX_OPTIONS,
      ExternalSubtitles.options(response, preferredLanguage = "").size,
    )
  }

  @Test
  fun `an empty answer yields nothing to offer rather than throwing`() {
    assertTrue(ExternalSubtitles.options(emptyList(), preferredLanguage = "eng").isEmpty())
  }

  @Test
  fun `a track title never just repeats the language, which mpv would show twice`() {
    // mpv's track list is labelled "<language> - <title>", so a title of "English"
    // on an English track reads as "English - English".
    val options = ExternalSubtitles.options(subtitles("eng" to 2, "spa" to 1), preferredLanguage = "")

    assertTrue(options.none { it.trackTitle.equals(it.label, ignoreCase = true) })
  }

  @Test
  fun `no id to ask with means no search row at all`() {
    assertNull(ExternalSubtitles.action(ExternalSubtitlesState.Unavailable))
  }

  @Test
  fun `the search row is pressable in every state but the one already searching`() {
    val ready = ExternalSubtitlesState.Ready(
      ExternalSubtitles.options(subtitles("eng" to 1), preferredLanguage = ""),
    )
    val states = listOf(
      ExternalSubtitlesState.Idle,
      ExternalSubtitlesState.Failed,
      ExternalSubtitlesState.Ready(emptyList()),
      ready,
    )

    assertTrue(states.all { ExternalSubtitles.action(it)?.enabled == true })
    assertEquals(false, ExternalSubtitles.action(ExternalSubtitlesState.Loading)?.enabled)
  }

  @Test
  fun `a failure and an empty answer read as different things`() {
    // "The addon could not be reached" and "the addon has nothing" are two
    // different problems, and only one of them is worth retrying immediately.
    assertEquals(
      "Couldn't load subtitles",
      ExternalSubtitles.action(ExternalSubtitlesState.Failed)?.label,
    )
    assertEquals(
      "No subtitles found",
      ExternalSubtitles.action(ExternalSubtitlesState.Ready(emptyList()))?.label,
    )
  }

  /** [counts] as language code to how many files the addon returned for it. */
  private fun subtitles(vararg counts: Pair<String, Int>): List<AddonSubtitle> =
    counts.flatMap { (lang, count) ->
      (1..count).map { subtitle(lang, "$lang-$it") }
    }

  private fun subtitle(lang: String, url: String) =
    AddonSubtitle(id = url, url = url, lang = lang)
}
