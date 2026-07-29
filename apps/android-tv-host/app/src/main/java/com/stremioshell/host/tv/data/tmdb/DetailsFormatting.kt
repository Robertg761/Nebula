package com.stremioshell.host.tv.data.tmdb

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * TMDB air dates, which arrive as bare "YYYY-MM-DD" strings.
 *
 * Split out of the UI because two decisions ride on them: how a date is written under an episode,
 * and whether that episode has aired at all. An unaired episode has no streams anywhere, so
 * offering it as playable only ever produces an empty stream list.
 */
object AirDate {

  /** The date, or null when TMDB sent nothing usable (it sends `null` and `""` interchangeably). */
  fun parse(raw: String?): LocalDate? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    return runCatching { LocalDate.parse(text) }.getOrNull()
  }

  /** Device-locale display form. Null when there is no date to show. */
  fun label(raw: String?, locale: Locale = Locale.getDefault()): String? = parse(raw)?.format(
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale),
  )

  /**
   * The four-digit year.
   *
   * Deliberately more tolerant than [parse]: TMDB occasionally carries a year-only value for old
   * catalog entries, and a full-date parse would throw that away.
   */
  fun year(raw: String?): String? =
    raw?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }

  /**
   * True only for a date we could read that is still ahead of [today]. An episode with no date at
   * all counts as aired: missing dates are common on older catalog entries, and blocking playback
   * on them would break titles that work today.
   */
  fun isUpcoming(raw: String?, today: LocalDate): Boolean {
    val date = parse(raw) ?: return false
    return date.isAfter(today)
  }
}

/**
 * Picks the certification to print in the metadata line from TMDB's per-country list.
 *
 * TMDB returns every country it knows, unordered and mostly unhelpful: a bare "16" from the German
 * board next to an English synopsis tells a viewer nothing. So only ratings whose scale an
 * English-speaking audience can read are used, and everything else is dropped rather than guessed.
 */
object ContentRating {
  private val PREFERRED_COUNTRIES = listOf("US", "GB")

  /** @param byCountry ISO-3166-1 code to certification, in TMDB's own order. */
  fun pick(byCountry: List<Pair<String, String>>): String? {
    val usable = byCountry.mapNotNull { (country, rating) ->
      val trimmed = rating.trim()
      if (trimmed.isEmpty()) null else country.trim().uppercase(Locale.ROOT) to trimmed
    }
    for (country in PREFERRED_COUNTRIES) {
      usable.firstOrNull { it.first == country }?.let { return it.second }
    }
    return null
  }
}

/** A season heading, and the order seasons are offered in. */
object SeasonList {
  /**
   * TMDB names season 0 inconsistently ("Specials", "Season 0", or the show's own wording), and
   * "Season 0" is meaningless on a remote.
   */
  fun label(seasonNumber: Int): String =
    if (seasonNumber == 0) "Specials" else "Season $seasonNumber"

  /**
   * Seasons worth offering, specials last.
   *
   * Specials are extras, so a show that has them must still open on season 1 - which is exactly
   * what putting season 0 first would break, since the first season in the list is the one the
   * screen selects by default. Empty seasons are dropped: TMDB announces a season as soon as it is
   * ordered, long before it has a single episode to list.
   */
  fun order(seasons: List<SeasonSummary>): List<SeasonSummary> =
    seasons
      .filter { it.seasonNumber >= 0 && it.episodeCount > 0 }
      .sortedBy { if (it.seasonNumber == 0) Int.MAX_VALUE else it.seasonNumber }
}

/** A video TMDB attached to a title, reduced to the fields that decide whether it is the trailer. */
data class VideoRef(
  val key: String,
  val site: String,
  val type: String,
  val official: Boolean,
)

/**
 * Picks one trailer out of the pile TMDB returns (teasers, clips, featurettes, fan uploads, often
 * a dozen of them). YouTube only, because that is the one site whose key we could ever resolve.
 */
object TrailerPick {
  fun bestYoutubeKey(videos: List<VideoRef>): String? {
    val youtube = videos.filter {
      it.site.equals("YouTube", ignoreCase = true) && it.key.isNotBlank()
    }
    // Order of preference, best first: an official trailer beats a fan-uploaded one, and a trailer
    // beats a teaser that shows almost nothing.
    return youtube.firstOrNull { it.official && it.type.equals("Trailer", ignoreCase = true) }?.key
      ?: youtube.firstOrNull { it.type.equals("Trailer", ignoreCase = true) }?.key
      ?: youtube.firstOrNull { it.type.equals("Teaser", ignoreCase = true) }?.key
  }
}

/** One logo candidate, reduced to the fields that decide whether it is the one to show. */
data class LogoRef(
  val filePath: String,
  /** TMDB's ISO-639-1 code. Null on the textless files it stores under no language at all. */
  val language: String?,
  val voteAverage: Double,
  val width: Int,
)

/**
 * Picks the title's logotype out of the pile TMDB returns.
 *
 * A typeset title is the clearest tell that a browse UI is generic: every premium service leads
 * with the title's own logo, and TMDB has one for most things. It ships several per title though -
 * per language, per revision, and in two formats - so the choice is not "the first one".
 *
 * SVG is excluded outright, and that is the load-bearing rule rather than a preference: TMDB files
 * a good share of logos as `.svg`, and Coil cannot decode SVG without an extra decoder artifact
 * this app does not ship. Picking one would render an empty box where the title should be, which
 * is worse than the typeset fallback in every case.
 */
object LogoPick {
  fun best(logos: List<LogoRef>, preferredLanguage: String = "en"): String? {
    val usable = logos.filter {
      it.filePath.isNotBlank() && !it.filePath.endsWith(".svg", ignoreCase = true)
    }
    if (usable.isEmpty()) return null
    val preferred = preferredLanguage.lowercase(Locale.ROOT)
    // Device language first, then English, then a language-neutral emblem, then any foreign
    // wordmark. Within a language TMDB's vote and width decide, as before.
    return usable
      .sortedWith(
        compareBy<LogoRef> { logo ->
          when (logo.language?.lowercase(Locale.ROOT)) {
            preferred -> 0
            "en" -> 1
            null -> 2
            else -> 3
          }
        }
          .thenByDescending { it.voteAverage }
          .thenByDescending { it.width },
      )
      .first()
      .filePath
  }
}

/**
 * The single line of facts under the title: years, length, certification, score, genres.
 *
 * Assembled here rather than in the composable so the omission rules are testable - every field is
 * optional on some title, and the separators have to collapse cleanly when they are missing rather
 * than leaving "2026  •    •  Drama".
 */
object DetailsMetadata {
  private const val SEPARATOR = "  •  "

  /** How many genres fit before the line starts wrapping on a 10-foot layout. */
  private const val MAX_GENRES = 3

  /**
   * "2019", "2019 - 2023", or "2019 -" for a show that is still running.
   *
   * The open-ended form matters: without it a returning series reads as having ended in whatever
   * year its most recent episode aired.
   */
  fun yearRange(startYear: String?, endYear: String?, ongoing: Boolean): String? {
    val start = startYear?.trim()?.ifBlank { null } ?: return null
    if (ongoing) return "$start -"
    val end = endYear?.trim()?.ifBlank { null }
    return if (end == null || end == start) start else "$start - $end"
  }

  /**
   * "1h 52m", or "~52m" when [perEpisode] - a show's runtime is one episode's, and printing it
   * unqualified next to "4 seasons" reads as the length of the whole series.
   */
  fun runtimeLabel(minutes: Int?, perEpisode: Boolean): String? {
    if (minutes == null || minutes <= 0) return null
    val hours = minutes / 60
    val rest = minutes % 60
    val body = when {
      hours == 0 -> "${rest}m"
      rest == 0 -> "${hours}h"
      else -> "${hours}h ${rest}m"
    }
    return if (perEpisode) "~$body" else body
  }

  fun seasonsLabel(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 season"
    else -> "$count seasons"
  }

  fun scoreLabel(voteAverage: Double?): String? {
    // TMDB reports 0.0 for anything nobody has voted on, and "0.0 / 10" reads as a verdict.
    if (voteAverage == null || voteAverage <= 0.0) return null
    return String.format(Locale.getDefault(), "%.1f / 10", voteAverage)
  }

  /**
   * The same line for a surface that only has a catalog entry, not a full details response - Home's
   * billboard renders before any title has been opened, so runtime, certification and genres simply
   * do not exist yet. The kind of title stands in for them: it is the one fact a viewer needs before
   * pressing OK, and without it the line can collapse to a bare year.
   *
   * Borrows Search's wording rather than spelling it out again: the two surfaces describe the same
   * media types, and this line said "Film" against Search's "Movie" for exactly as long as they
   * were two independent strings.
   */
  fun ofItem(item: MediaItem): String = listOfNotNull(
    item.year?.trim()?.ifBlank { null },
    SearchResults.typeLabel(item.type),
    scoreLabel(item.rating),
  ).joinToString(SEPARATOR)

  fun of(details: MediaDetails): String {
    val isShow = details.item.type == MediaType.Show
    // Specials are excluded from the count for the same reason they sort last: "3 seasons" for a
    // two-season show with a Christmas special is wrong.
    val realSeasons = details.seasons.count { it.seasonNumber > 0 }
    return listOfNotNull(
      yearRange(details.item.year, details.endYear, details.ongoing && isShow),
      if (isShow) seasonsLabel(realSeasons) else null,
      runtimeLabel(details.runtimeMinutes, perEpisode = isShow),
      details.contentRating?.trim()?.ifBlank { null },
      scoreLabel(details.item.rating),
      details.genres.take(MAX_GENRES).joinToString(", ").ifBlank { null },
    ).joinToString(SEPARATOR)
  }
}
