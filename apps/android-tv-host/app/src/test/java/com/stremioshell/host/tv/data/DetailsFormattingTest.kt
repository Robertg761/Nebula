package com.stremioshell.host.tv.data

import com.stremioshell.host.tv.data.tmdb.AirDate
import com.stremioshell.host.tv.data.tmdb.ContentRating
import com.stremioshell.host.tv.data.tmdb.DetailsMetadata
import com.stremioshell.host.tv.data.tmdb.MediaDetails
import com.stremioshell.host.tv.data.tmdb.MediaItem
import com.stremioshell.host.tv.data.tmdb.MediaType
import com.stremioshell.host.tv.data.tmdb.SearchResults
import com.stremioshell.host.tv.data.tmdb.SeasonList
import com.stremioshell.host.tv.data.tmdb.SeasonSummary
import com.stremioshell.host.tv.data.tmdb.TrailerPick
import com.stremioshell.host.tv.data.tmdb.VideoRef
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirDateTest {
  private val today = LocalDate.of(2026, 7, 27)

  @Test
  fun `parses an iso date`() {
    assertEquals(LocalDate.of(2026, 3, 12), AirDate.parse("2026-03-12"))
  }

  @Test
  fun `treats blank and malformed dates as absent`() {
    assertNull(AirDate.parse(null))
    assertNull(AirDate.parse(""))
    assertNull(AirDate.parse("   "))
    assertNull(AirDate.parse("soon"))
    assertNull(AirDate.parse("2026-13-45"))
  }

  @Test
  fun `labels a date in the requested locale`() {
    assertEquals("Mar 12, 2026", AirDate.label("2026-03-12", Locale.US))
    assertEquals("12 Mar 2026", AirDate.label("2026-03-12", Locale.UK))
    assertNull(AirDate.label(null))
  }

  @Test
  fun `year tolerates a year-only value`() {
    assertEquals("2026", AirDate.year("2026-03-12"))
    assertEquals("2026", AirDate.year("2026"))
    assertNull(AirDate.year(""))
    assertNull(AirDate.year("soon"))
  }

  @Test
  fun `only a later date is upcoming`() {
    assertTrue(AirDate.isUpcoming("2026-07-28", today))
    assertFalse(AirDate.isUpcoming("2026-07-27", today))
    assertFalse(AirDate.isUpcoming("2026-07-26", today))
  }

  @Test
  fun `a missing date is not upcoming`() {
    // Old catalog entries routinely have no air date; blocking playback on them would break
    // titles that work today.
    assertFalse(AirDate.isUpcoming(null, today))
    assertFalse(AirDate.isUpcoming("", today))
    assertFalse(AirDate.isUpcoming("garbage", today))
  }
}

class ContentRatingTest {
  @Test
  fun `prefers the us certification`() {
    val picked = ContentRating.pick(listOf("DE" to "16", "US" to "TV-MA", "GB" to "18"))
    assertEquals("TV-MA", picked)
  }

  @Test
  fun `falls back to gb when there is no us rating`() {
    assertEquals("15", ContentRating.pick(listOf("FR" to "12", "GB" to "15")))
  }

  @Test
  fun `ignores blank certifications`() {
    // TMDB lists a country with an empty certification more often than it omits the country.
    assertEquals("15", ContentRating.pick(listOf("US" to "", "GB" to "15")))
  }

  @Test
  fun `returns null rather than an unreadable foreign scale`() {
    assertNull(ContentRating.pick(listOf("DE" to "16", "JP" to "G")))
    assertNull(ContentRating.pick(emptyList()))
  }

  @Test
  fun `matches country codes case-insensitively`() {
    assertEquals("R", ContentRating.pick(listOf("us" to "R")))
  }
}

class SeasonListTest {
  private fun season(number: Int, episodes: Int = 10) =
    SeasonSummary(number, "Season $number", episodes)

  @Test
  fun `labels season zero as specials`() {
    assertEquals("Specials", SeasonList.label(0))
    assertEquals("Season 3", SeasonList.label(3))
  }

  @Test
  fun `summary exposes the same label`() {
    assertEquals("Specials", SeasonSummary(0, "Season 0", 4).label)
  }

  @Test
  fun `specials sort last so the show still opens on season one`() {
    val ordered = SeasonList.order(listOf(season(0), season(2), season(1)))
    assertEquals(listOf(1, 2, 0), ordered.map { it.seasonNumber })
  }

  @Test
  fun `drops announced-but-empty seasons`() {
    val ordered = SeasonList.order(listOf(season(1), season(2, episodes = 0)))
    assertEquals(listOf(1), ordered.map { it.seasonNumber })
  }

  @Test
  fun `keeps specials when they are the only thing with episodes`() {
    val ordered = SeasonList.order(listOf(season(0, episodes = 3), season(1, episodes = 0)))
    assertEquals(listOf(0), ordered.map { it.seasonNumber })
  }
}

class TrailerPickTest {
  @Test
  fun `prefers an official trailer`() {
    val key = TrailerPick.bestYoutubeKey(
      listOf(
        VideoRef("teaser", "YouTube", "Teaser", official = true),
        VideoRef("fan", "YouTube", "Trailer", official = false),
        VideoRef("real", "YouTube", "Trailer", official = true),
      ),
    )
    assertEquals("real", key)
  }

  @Test
  fun `falls back to an unofficial trailer then a teaser`() {
    assertEquals(
      "fan",
      TrailerPick.bestYoutubeKey(
        listOf(
          VideoRef("t", "YouTube", "Teaser", official = true),
          VideoRef("fan", "YouTube", "Trailer", official = false),
        ),
      ),
    )
    assertEquals(
      "t",
      TrailerPick.bestYoutubeKey(listOf(VideoRef("t", "YouTube", "Teaser", official = false))),
    )
  }

  @Test
  fun `ignores other sites, other types and empty keys`() {
    assertNull(
      TrailerPick.bestYoutubeKey(
        listOf(
          VideoRef("v", "Vimeo", "Trailer", official = true),
          VideoRef("", "YouTube", "Trailer", official = true),
          VideoRef("c", "YouTube", "Clip", official = true),
        ),
      ),
    )
    assertNull(TrailerPick.bestYoutubeKey(emptyList()))
  }
}

class DetailsMetadataTest {
  private fun details(
    type: MediaType = MediaType.Movie,
    year: String? = "2019",
    endYear: String? = null,
    ongoing: Boolean = false,
    runtime: Int? = null,
    genres: List<String> = emptyList(),
    contentRating: String? = null,
    rating: Double? = null,
    seasons: List<SeasonSummary> = emptyList(),
  ) = MediaDetails(
    item = MediaItem(
      tmdbId = 1,
      type = type,
      title = "T",
      posterUrl = null,
      backdropUrl = null,
      overview = "",
      year = year,
      rating = rating,
    ),
    imdbId = null,
    runtimeMinutes = runtime,
    genres = genres,
    seasons = seasons,
    endYear = endYear,
    ongoing = ongoing,
    contentRating = contentRating,
  )

  @Test
  fun `year range closes, opens, or collapses`() {
    assertEquals("2019", DetailsMetadata.yearRange("2019", null, ongoing = false))
    assertEquals("2019", DetailsMetadata.yearRange("2019", "2019", ongoing = false))
    assertEquals("2019 - 2023", DetailsMetadata.yearRange("2019", "2023", ongoing = false))
    assertEquals("2019 -", DetailsMetadata.yearRange("2019", "2023", ongoing = true))
    assertNull(DetailsMetadata.yearRange(null, "2023", ongoing = false))
    assertNull(DetailsMetadata.yearRange("  ", null, ongoing = false))
  }

  @Test
  fun `runtime label splits hours and marks per-episode lengths`() {
    assertEquals("1h 52m", DetailsMetadata.runtimeLabel(112, perEpisode = false))
    assertEquals("2h", DetailsMetadata.runtimeLabel(120, perEpisode = false))
    assertEquals("47m", DetailsMetadata.runtimeLabel(47, perEpisode = false))
    assertEquals("~47m", DetailsMetadata.runtimeLabel(47, perEpisode = true))
    assertNull(DetailsMetadata.runtimeLabel(0, perEpisode = false))
    assertNull(DetailsMetadata.runtimeLabel(null, perEpisode = false))
  }

  @Test
  fun `seasons label pluralises`() {
    assertNull(DetailsMetadata.seasonsLabel(0))
    assertEquals("1 season", DetailsMetadata.seasonsLabel(1))
    assertEquals("4 seasons", DetailsMetadata.seasonsLabel(4))
  }

  @Test
  fun `score hides an unvoted title`() {
    assertEquals("7.5 / 10", DetailsMetadata.scoreLabel(7.5))
    assertNull(DetailsMetadata.scoreLabel(0.0))
    assertNull(DetailsMetadata.scoreLabel(null))
  }

  @Test
  fun `movie line reads year, runtime, certification, score, genres`() {
    val line = DetailsMetadata.of(
      details(
        runtime = 112,
        contentRating = "R",
        rating = 7.5,
        genres = listOf("Drama", "Thriller"),
      ),
    )
    assertEquals("2019  •  1h 52m  •  R  •  7.5 / 10  •  Drama, Thriller", line)
  }

  @Test
  fun `show line adds a season count and marks the runtime per-episode`() {
    val line = DetailsMetadata.of(
      details(
        type = MediaType.Show,
        endYear = "2023",
        runtime = 50,
        contentRating = "TV-MA",
        seasons = listOf(
          SeasonSummary(1, "Season 1", 10),
          SeasonSummary(2, "Season 2", 10),
          // Specials are extras, not a season of the show.
          SeasonSummary(0, "Specials", 3),
        ),
      ),
    )
    assertEquals("2019 - 2023  •  2 seasons  •  ~50m  •  TV-MA", line)
  }

  @Test
  fun `separators collapse when fields are missing`() {
    assertEquals("2019", DetailsMetadata.of(details()))
    assertEquals("", DetailsMetadata.of(details(year = null)))
  }

  @Test
  fun `genres are capped so the line does not wrap`() {
    val line = DetailsMetadata.of(
      details(year = null, genres = listOf("A", "B", "C", "D")),
    )
    assertEquals("A, B, C", line)
  }

  @Test
  fun `a movie never shows a season count or an open-ended year`() {
    // in_production is set on movies too (anything unreleased), and "2019 -" under a film is wrong.
    val line = DetailsMetadata.of(
      details(ongoing = true, seasons = listOf(SeasonSummary(1, "Season 1", 3))),
    )
    assertEquals("2019", line)
  }

  @Test
  fun `the billboard line names the medium a catalog entry cannot describe`() {
    assertEquals(
      "2019  •  Movie  •  7.5 / 10",
      DetailsMetadata.ofItem(details(rating = 7.5).item),
    )
    assertEquals(
      "2019  •  Series",
      DetailsMetadata.ofItem(details(type = MediaType.Show).item),
    )
  }

  @Test
  fun `the billboard line collapses around a missing year or score`() {
    assertEquals("Movie", DetailsMetadata.ofItem(details(year = null).item))
    assertEquals("2019  •  Movie", DetailsMetadata.ofItem(details(rating = 0.0).item))
  }

  @Test
  fun `the billboard names a medium in the same words Search does`() {
    // These drifted apart once already - Details said "Film" where Search said "Movie" for the
    // same title - so the two are pinned to each other rather than to two literals.
    MediaType.values().forEach { type ->
      assertEquals(
        SearchResults.typeLabel(type),
        DetailsMetadata.ofItem(details(type = type, year = null).item),
      )
    }
  }
}
