package com.stremioshell.host.tv.player

import com.stremioshell.host.tv.data.subtitles.AddonSubtitle
import java.util.Locale

/**
 * One external subtitle file as the menu offers it, already reduced to the two
 * things a viewer chooses by: what language it is in, and which of that
 * language's files it is.
 */
data class ExternalSubtitleOption(
  val url: String,
  /** Normalized language code, blank when the addon tagged the file with nothing. */
  val lang: String,
  /** The language, because that is what the choice is actually about. */
  val label: String,
  val detail: String,
  /**
   * What mpv is told to call the track. It is what the regular subtitle list then
   * shows next to the language, so it has to say where the track came from rather
   * than repeat the language back.
   */
  val trackTitle: String,
)

/**
 * Whether the Subtitles tab has anything to offer beyond the file's own tracks,
 * and how far the asking has got.
 */
sealed interface ExternalSubtitlesState {
  /** No id to ask an addon with, so the section is not offered at all. */
  data object Unavailable : ExternalSubtitlesState

  /** Nothing asked for yet. */
  data object Idle : ExternalSubtitlesState

  data object Loading : ExternalSubtitlesState

  /** What the addon had, which may legitimately be nothing. */
  data class Ready(val options: List<ExternalSubtitleOption>) : ExternalSubtitlesState

  data object Failed : ExternalSubtitlesState
}

/**
 * The one row that starts a search, and reports how the last one went. Always
 * present and always focusable, whatever the state: it is the row focus is sitting
 * on when the search is started, and a row that stopped being focusable underneath
 * a viewer would drop focus out of a panel the player has taken the D-pad for.
 */
data class ExternalSubtitlesAction(
  val label: String,
  val detail: String,
  /** False only while a search is running, when pressing again would do nothing. */
  val enabled: Boolean,
)

/**
 * Turning a subtitles addon's answer into a list a viewer can read off a sofa.
 *
 * The raw answer cannot be shown as it arrives: OpenSubtitles has dozens of files
 * per language for anything popular, several of them byte-identical uploads, and
 * they come back in whatever order the addon assembled them. Nothing here can tell
 * a good transcription from a bad one — no ratings or download counts survive the
 * addon protocol — so the only honest reduction is to keep the addon's own order
 * within each language, offer a few of each, and put the language the viewer reads
 * at the top.
 */
object ExternalSubtitles {
  /**
   * How many files per language the menu offers. Three is enough that a mistimed
   * or truncated first pick has an alternative behind it, and few enough that
   * twenty languages still fit a list that is walked with a D-pad.
   */
  const val PER_LANGUAGE = 3

  private const val UNKNOWN_LABEL = "Unknown language"

  fun options(
    subtitles: List<AddonSubtitle>,
    preferredLanguage: String?,
  ): List<ExternalSubtitleOption> {
    val seenUrls = HashSet<String>()
    // Insertion-ordered, so the cap below keeps the addon's own ranking within a
    // language rather than an arbitrary three of them.
    val byLanguage = LinkedHashMap<String, MutableList<String>>()
    for (subtitle in subtitles) {
      val url = subtitle.url.trim()
      if (url.isEmpty() || !seenUrls.add(url)) continue
      val group = byLanguage.getOrPut(LanguageCodes.normalize(subtitle.lang)) { mutableListOf() }
      if (group.size < PER_LANGUAGE) group += url
    }
    val preferred = preferredCode(preferredLanguage)
    return byLanguage.entries
      .sortedWith(
        compareBy(
          { rank(it.key, preferred) },
          { label(it.key).lowercase(Locale.ROOT) },
        ),
      )
      .flatMap { (code, urls) ->
        urls.mapIndexed { index, url -> option(code, url, index + 1, urls.size) }
      }
  }

  /**
   * How the search row reads in [state], or null when there is nothing to ask and
   * the section is not offered at all.
   */
  fun action(state: ExternalSubtitlesState): ExternalSubtitlesAction? = when (state) {
    ExternalSubtitlesState.Unavailable -> null
    ExternalSubtitlesState.Idle -> ExternalSubtitlesAction("Search online subtitles", SOURCE, true)
    ExternalSubtitlesState.Loading -> ExternalSubtitlesAction("Searching...", "", false)
    ExternalSubtitlesState.Failed ->
      ExternalSubtitlesAction("Couldn't load subtitles", "OK tries again", true)
    is ExternalSubtitlesState.Ready -> if (state.options.isEmpty()) {
      ExternalSubtitlesAction("No subtitles found", "OK searches again", true)
    } else {
      ExternalSubtitlesAction("Search again", SOURCE, true)
    }
  }

  /**
   * The language to float to the top. "Off" is not one: a viewer who switched
   * subtitles off and is now asking an addon for some has no stored language to
   * honour, only one they are about to choose.
   */
  private fun preferredCode(stored: String?): String =
    if (TrackPreferences.subtitlesOff(stored)) "" else LanguageCodes.normalize(stored)

  /** Preferred language, then anything tagged, then the untagged files. */
  private fun rank(code: String, preferred: String): Int = when {
    code.isBlank() -> 2
    code == preferred -> 0
    else -> 1
  }

  private fun label(code: String): String =
    LanguageCodes.displayName(code).ifBlank { UNKNOWN_LABEL }

  private fun option(code: String, url: String, ordinal: Int, total: Int) = ExternalSubtitleOption(
    url = url,
    lang = code,
    label = label(code),
    detail = if (total == 1) SOURCE_LABEL else "$SOURCE_LABEL $ordinal of $total",
    trackTitle = if (total == 1) TRACK_LABEL else "$TRACK_LABEL $ordinal",
  )

  private const val SOURCE_LABEL = "Online subtitle"
  private const val TRACK_LABEL = "Online"

  /** Named because the default addon is the one every Stremio client offers. */
  private const val SOURCE = "OpenSubtitles"
}
