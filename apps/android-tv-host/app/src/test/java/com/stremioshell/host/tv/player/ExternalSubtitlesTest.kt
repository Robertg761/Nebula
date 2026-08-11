package com.stremioshell.host.tv.player

import com.stremioshell.host.tv.data.addon.AddonStreamSubtitle
import com.stremioshell.host.tv.data.subtitles.AddonSubtitle
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSubtitlesTest {
  @Test
  fun `embedded stream subtitles are bounded deduplicated public https urls`() {
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
          url = "https://subs.example/good.srt",
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
      optionIds(options),
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

    assertEquals(listOf("same", "other"), optionIds(options))
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

    assertEquals(listOf("a", "b", "c"), optionIds(options))
  }

  @Test
  fun `blank urls are dropped`() {
    val options = ExternalSubtitles.options(
      listOf(AddonSubtitle(id = "1", url = "  ", lang = "eng"), subtitle("eng", "a")),
      preferredLanguage = "",
    )

    assertEquals(listOf("a"), optionIds(options))
  }

  @Test
  fun `urls are trimmed, so padding cannot smuggle a duplicate through`() {
    val options = ExternalSubtitles.options(
      listOf(
        subtitle("eng", "a"),
        AddonSubtitle(id = "2", url = " ${subtitleUrl("a")} ", lang = "eng"),
      ),
      preferredLanguage = "",
    )

    assertEquals(listOf("a"), optionIds(options))
  }

  @Test
  fun `embedded and fetched subtitle options reject cleartext and non public targets`() {
    val unsafeEmbedded = listOf(
      AddonStreamSubtitle(url = "http://subs.example/cleartext.srt"),
      AddonStreamSubtitle(url = "https://127.0.0.1/loopback.srt"),
      AddonStreamSubtitle(url = "https://127.1/abbreviated.srt"),
    )
    assertTrue(EmbeddedSubtitles.sanitize(unsafeEmbedded).isEmpty())

    val fetched = listOf(
      AddonSubtitle(id = "1", url = "http://subs.example/cleartext.srt", lang = "eng"),
      AddonSubtitle(id = "2", url = "https://10.0.0.1/private.srt", lang = "eng"),
      AddonSubtitle(id = "3", url = "https://192.168.1/private.srt", lang = "eng"),
      subtitle("eng", "safe"),
    )
    assertEquals(listOf("safe"), optionIds(ExternalSubtitles.options(fetched, "eng")))
  }

  @Test
  fun `subtitle redirects are canonicalized and every target is revalidated`() {
    val from = "https://SUBS.EXAMPLE/base/file.srt".toHttpUrl()

    assertEquals(
      "https://subs.example/next/file.srt",
      SubtitleUrlPolicy.redirectUrlOrNull(from, "../next/file.srt"),
    )
    assertNull(SubtitleUrlPolicy.redirectUrlOrNull(from, "http://subs.example/file.srt"))
    assertNull(SubtitleUrlPolicy.redirectUrlOrNull(from, "https://127.0.0.1/file.srt"))
    assertNull(SubtitleUrlPolicy.redirectUrlOrNull(from, "https://10.1/file.srt"))
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
    assertEquals(false, ExternalSubtitles.action(ExternalSubtitlesState.Loading())?.enabled)
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

  @Test
  fun `languages are ordered by the name the menu will show, not the english one`() {
    // The menu renders language names in the viewer's own locale, so ordering by the English
    // names leaves a French TV showing a list that is alphabetical in no language on screen.
    val rendered = mapOf("deu" to "Allemand", "eng" to "Anglais", "spa" to "Espagnol")

    val options = ExternalSubtitles.options(
      subtitles("eng" to 1, "spa" to 1, "deu" to 1),
      preferredLanguage = "",
      displayLabel = { rendered.getValue(it) },
    )

    assertEquals(listOf("Allemand", "Anglais", "Espagnol"), options.map { it.label })
  }

  @Test
  fun `the rendered name falls back to the english one, then to the code`() {
    // What the platform can localize varies by device and by code; what it cannot must still read
    // as a language rather than as a three-letter tag.
    assertEquals("German", ExternalSubtitles.menuLabel("deu"))
    assertEquals("English", ExternalSubtitles.menuLabel("en"))
    assertEquals("QAA", ExternalSubtitles.menuLabel("qaa"))
    assertEquals("Unknown language", ExternalSubtitles.menuLabel(""))
  }

  @Test
  fun `the cap drops whole languages, never part of one`() {
    // Each row says "2 of 3". A cap applied to the flattened list cuts a language in half and
    // leaves two rows both promising a third file that is not on the menu.
    val response = (1..19).flatMap { subtitles("qa${it.toString().padStart(2, '0')}" to 3) } +
      subtitles("qa20" to 2) +
      subtitles("qa21" to 3)

    val options = ExternalSubtitles.options(response, preferredLanguage = "")

    assertEquals(59, options.size)
    assertTrue(options.none { it.lang == "qa21" })
    assertTrue(options.groupBy { it.lang }.all { (_, rows) -> rows.size == rows.first().total })
  }

  @Test
  fun `the addon's answer is bounded before it is grouped`() {
    // Nothing downstream is quadratic, but normalizing and grouping an unbounded response to then
    // show sixty rows of it is still work done on a set-top box's main thread.
    val response = (1..ExternalSubtitles.MAX_CANDIDATES).map { subtitle("qaa", "qaa-$it") } +
      subtitle("eng", "english")

    val options = ExternalSubtitles.options(response, preferredLanguage = "eng")

    assertEquals(listOf("qaa"), options.map { it.lang }.distinct())
  }

  @Test
  fun `a search adds to the stream's own subtitles rather than replacing them`() {
    val embedded = listOf(embeddedOption("included-1"), embeddedOption("included-2"))
    val online = ExternalSubtitles.options(subtitles("eng" to 2), preferredLanguage = "")

    val merged = ExternalSubtitles.merge(embedded, online)

    assertEquals(
      listOf("included-1", "included-2", "eng-1", "eng-2"),
      optionIds(merged),
    )
    assertEquals(
      listOf(ExternalSubtitleSource.Embedded, ExternalSubtitleSource.Online),
      merged.map { it.source }.distinct(),
    )
  }

  @Test
  fun `a file both the stream and the addon name is offered once, as the stream's`() {
    val embedded = listOf(embeddedOption("shared"))
    val online = ExternalSubtitles.options(
      listOf(subtitle("eng", "shared"), subtitle("eng", "extra")),
      preferredLanguage = "",
    )

    val merged = ExternalSubtitles.merge(embedded, online)

    assertEquals(listOf("shared", "extra"), optionIds(merged))
    assertEquals(ExternalSubtitleSource.Embedded, merged.first().source)
  }

  @Test
  fun `a list with no search behind it is offered without a pressable search row`() {
    // A stream that named subtitle files but carries no IMDb id: the files are still worth
    // listing, and the row they hang off must not invite a press that can only do nothing.
    val state = ExternalSubtitlesState.Ready(
      listOf(embeddedOption("included")),
      searchable = false,
    )

    assertEquals(false, ExternalSubtitles.action(state)?.enabled)
  }

  @Test
  fun `nothing listed and nothing to search is no section at all`() {
    assertNull(
      ExternalSubtitles.action(ExternalSubtitlesState.Ready(emptyList(), searchable = false)),
    )
  }

  private fun embeddedOption(id: String) = ExternalSubtitleOption(
    url = subtitleUrl(id),
    lang = "eng",
    label = "English",
    detail = "Included with the stream",
    trackTitle = id,
  )

  /** [counts] as language code to how many files the addon returned for it. */
  private fun subtitles(vararg counts: Pair<String, Int>): List<AddonSubtitle> =
    counts.flatMap { (lang, count) ->
      (1..count).map { subtitle(lang, "$lang-$it") }
    }

  private fun subtitle(lang: String, url: String) =
    AddonSubtitle(id = url, url = subtitleUrl(url), lang = lang)

  private fun subtitleUrl(id: String): String =
    if (id.startsWith("https://")) id else "https://subs.example/$id.srt"

  private fun optionIds(options: List<ExternalSubtitleOption>): List<String> =
    options.map { it.url.substringAfterLast('/').removeSuffix(".srt") }
}
