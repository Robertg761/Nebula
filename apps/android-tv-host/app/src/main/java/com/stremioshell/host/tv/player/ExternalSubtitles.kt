package com.stremioshell.host.tv.player

import com.stremioshell.host.tv.data.PlaybackUrlPolicy
import com.stremioshell.host.tv.data.addon.AddonStreamSubtitle
import com.stremioshell.host.tv.data.subtitles.AddonSubtitle
import java.util.Locale
import okhttp3.HttpUrl

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
  /**
   * Structured presentation metadata lets the Android UI localize app-authored copy without
   * putting a Context into this pure filtering policy. Embedded options constructed by the player
   * keep their existing caller-supplied detail and title through the default.
   */
  val source: ExternalSubtitleSource = ExternalSubtitleSource.Embedded,
  val ordinal: Int = 1,
  val total: Int = 1,
)

enum class ExternalSubtitleSource {
  Embedded,
  Online,
}

/**
 * Bounds subtitle rows supplied inside an untrusted stream response.
 *
 * Applied before Intent extras are built as well as when they are read: Binder
 * payload, parsing work and the D-pad menu all stay bounded even if a caller
 * bypasses the normal intent factory.
 */
object EmbeddedSubtitles {
  const val MAX_OPTIONS = 60
  const val MAX_URL_LENGTH = 4_096
  const val MAX_ID_LENGTH = 120
  const val MAX_LANGUAGE_LENGTH = 64
  const val MAX_CANDIDATES = MAX_OPTIONS * 4

  fun sanitize(subtitles: List<AddonStreamSubtitle>): List<AddonStreamSubtitle> {
    val seenUrls = HashSet<String>()
    return subtitles.asSequence()
      .take(MAX_CANDIDATES)
      .mapNotNull { subtitle ->
        val url = SubtitleUrlPolicy.allowedUrlOrNull(subtitle.url) ?: return@mapNotNull null
        if (!seenUrls.add(url)) return@mapNotNull null
        subtitle.copy(
          url = url,
          id = subtitle.id?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_ID_LENGTH),
          lang = subtitle.lang?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LANGUAGE_LENGTH),
        )
      }
      .take(MAX_OPTIONS)
      .toList()
  }

}

/**
 * One policy for every addon-controlled subtitle URL: menu ingestion, direct download and each
 * redirect hop all audit and then use the same canonical public HTTPS address.
 */
internal object SubtitleUrlPolicy {
  fun allowedUrlOrNull(rawUrl: String): String? {
    val url = rawUrl.trim()
    if (url.isEmpty() || url.length > EmbeddedSubtitles.MAX_URL_LENGTH) return null
    return PlaybackUrlPolicy.allowedUrlOrNull(url)
  }

  fun redirectUrlOrNull(from: HttpUrl, location: String?): String? {
    val resolved = location?.let(from::resolve) ?: return null
    return allowedUrlOrNull(resolved.toString())
  }
}

/**
 * Whether the Subtitles tab has anything to offer beyond the file's own tracks,
 * and how far the asking has got.
 */
sealed interface ExternalSubtitlesState {
  /** Nothing to list and no id to ask an addon with, so the section is not offered at all. */
  data object Unavailable : ExternalSubtitlesState

  /** An id to ask with, nothing listed yet and nothing asked for yet. */
  data object Idle : ExternalSubtitlesState

  /**
   * A search is out. The rows already on the menu ride along rather than vanishing for the
   * duration: the stream's own files (and any earlier search's results) are still perfectly
   * pickable while an addon takes its seconds to answer, and a list that blinks out under the
   * D-pad drops focus out of the section.
   */
  data class Loading(
    val options: List<ExternalSubtitleOption> = emptyList(),
  ) : ExternalSubtitlesState

  /**
   * Everything the section is currently offering, which may legitimately be nothing.
   *
   * One list rather than one per source. The files a stream response named and the files a
   * subtitles addon found are the same decision to a viewer, and keeping them in one place is
   * what stops a search from being able to lose the stream's own list — see
   * [ExternalSubtitles.merge].
   */
  data class Ready(
    val options: List<ExternalSubtitleOption>,
    /**
     * Whether a search can be run at all. False for a stream that named subtitle files but
     * carries no IMDb id: there is a list worth showing and no way to add to it, and a search row
     * that is offered but cannot run is the worst of both.
     */
    val searchable: Boolean = true,
  ) : ExternalSubtitlesState

  data object Failed : ExternalSubtitlesState
}

/**
 * The one row that starts a search, and reports how the last one went. Present and focusable in
 * every state that offers the section at all: it is the row focus is sitting on when the search is
 * started, and a row that stopped being focusable underneath a viewer would drop focus out of a
 * panel the player has taken the D-pad for.
 */
data class ExternalSubtitlesAction(
  val label: String,
  val detail: String,
  /**
   * False when pressing would do nothing: while a search is already running, and for a list that
   * has no search behind it to run.
   */
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

  /** Enough alternatives to cover twenty languages without creating an unwalkable TV menu. */
  const val MAX_OPTIONS = 60

  /**
   * How much of an addon's answer is even looked at, the same bound and for the same reason as
   * [EmbeddedSubtitles.MAX_CANDIDATES]: an addon is a third party, its response length is its own
   * choice, and normalizing and grouping tens of thousands of rows to then show sixty of them is
   * work done on the main thread of a set-top box.
   */
  const val MAX_CANDIDATES = MAX_OPTIONS * 4

  private const val UNKNOWN_LABEL = "Unknown language"

  fun options(
    subtitles: List<AddonSubtitle>,
    preferredLanguage: String?,
    /**
     * What the menu will actually put on screen for a language code. Defaults to the same
     * `Locale` path the Android layer renders through, because the list is sorted by it: sorting
     * by the English name while rendering the viewer's own would leave a French TV showing a list
     * that is alphabetical in no language on screen. Injectable so the ordering can be tested
     * without a device's locale data.
     */
    displayLabel: (String) -> String = ::menuLabel,
  ): List<ExternalSubtitleOption> {
    val seenUrls = HashSet<String>()
    // Insertion-ordered, so the cap below keeps the addon's own ranking within a
    // language rather than an arbitrary three of them.
    val byLanguage = LinkedHashMap<String, MutableList<String>>()
    for (subtitle in subtitles.asSequence().take(MAX_CANDIDATES)) {
      val url = SubtitleUrlPolicy.allowedUrlOrNull(subtitle.url) ?: continue
      if (!seenUrls.add(url)) continue
      val group = byLanguage.getOrPut(LanguageCodes.normalize(subtitle.lang)) { mutableListOf() }
      if (group.size < PER_LANGUAGE) group += url
    }
    val preferred = preferredCode(preferredLanguage)
    // Resolved once per language rather than once per row: it is the sort key as well as the
    // label, and on Android it reaches into the platform's locale data to get there.
    val labels = byLanguage.keys.associateWith(displayLabel)
    val ordered = byLanguage.entries.sortedWith(
      compareBy(
        { rank(it.key, preferred) },
        { labels.getValue(it.key).lowercase(Locale.ROOT) },
      ),
    )
    val options = ArrayList<ExternalSubtitleOption>(minOf(MAX_OPTIONS, seenUrls.size))
    for ((code, urls) in ordered) {
      // Whole languages, never part of one. Each row says "2 of 3", and a cap applied to the
      // flattened list cuts a group mid-way and leaves two rows both claiming there is a third.
      if (options.size + urls.size > MAX_OPTIONS) break
      urls.forEachIndexed { index, url ->
        options += option(code, labels.getValue(code), url, index + 1, urls.size)
      }
    }
    return options
  }

  /**
   * One list out of the files a stream response named and the files a search found, the stream's
   * own first.
   *
   * The stream's list is the one that cannot be got back: it arrives with the launch intent and is
   * never fetched again, so a search result that replaced it would take it away for the rest of
   * the session. Deduplicated by URL, since a stream and an addon quoting the same OpenSubtitles
   * file is routine, and the copy that keeps its "Included with the stream" wording is the one
   * that was there first.
   */
  fun merge(
    embedded: List<ExternalSubtitleOption>,
    online: List<ExternalSubtitleOption>,
  ): List<ExternalSubtitleOption> {
    val seenUrls = HashSet<String>()
    val merged = ArrayList<ExternalSubtitleOption>(embedded.size + online.size)
    for (option in embedded) if (seenUrls.add(option.url)) merged += option
    for (option in online) if (seenUrls.add(option.url)) merged += option
    return merged
  }

  /**
   * How the search row reads in [state], or null when there is nothing to ask and
   * the section is not offered at all.
   */
  fun action(state: ExternalSubtitlesState): ExternalSubtitlesAction? = when (state) {
    ExternalSubtitlesState.Unavailable -> null
    ExternalSubtitlesState.Idle -> ExternalSubtitlesAction("Search online subtitles", SOURCE, true)
    is ExternalSubtitlesState.Loading -> ExternalSubtitlesAction("Searching...", "", false)
    ExternalSubtitlesState.Failed ->
      ExternalSubtitlesAction("Couldn't load subtitles", "OK tries again", true)
    is ExternalSubtitlesState.Ready -> when {
      // Nothing listed and nothing to search: the section has nothing to be.
      !state.searchable && state.options.isEmpty() -> null
      // A list with no search behind it. The row still exists because the section's rows hang off
      // it, but pressing it can only do nothing, so it says so by being unpressable.
      !state.searchable -> ExternalSubtitlesAction("Included subtitles", NO_SEARCH, false)
      state.options.isEmpty() ->
        ExternalSubtitlesAction("No subtitles found", "OK searches again", true)
      else -> ExternalSubtitlesAction("Search again", SOURCE, true)
    }
  }

  /**
   * The name the menu puts on a language, resolved the way the Android layer resolves it: the
   * platform's own name for the code in the viewer's locale, falling back to the English name
   * when the platform has nothing (which is what a three-letter code gets from a plain JVM) and
   * to the code itself when even that table has never heard of it.
   */
  fun menuLabel(code: String): String {
    val canonical = LanguageCodes.normalize(code)
    if (canonical.isBlank()) return UNKNOWN_LABEL
    val localized = Locale.forLanguageTag(canonical).getDisplayLanguage(Locale.getDefault()).trim()
    return localized.takeUnless {
      it.isBlank() || it.equals(canonical, ignoreCase = true)
    } ?: label(canonical)
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

  private fun option(
    code: String,
    label: String,
    url: String,
    ordinal: Int,
    total: Int,
  ) = ExternalSubtitleOption(
    url = url,
    lang = code,
    label = label,
    detail = if (total == 1) SOURCE_LABEL else "$SOURCE_LABEL $ordinal of $total",
    trackTitle = if (total == 1) TRACK_LABEL else "$TRACK_LABEL $ordinal",
    source = ExternalSubtitleSource.Online,
    ordinal = ordinal,
    total = total,
  )

  private const val SOURCE_LABEL = "Online subtitle"
  private const val TRACK_LABEL = "Online"

  /** Why the row is there without being pressable; see [action]. */
  private const val NO_SEARCH = "Included with the stream"

  /** Named because the default addon is the one every Stremio client offers. */
  private const val SOURCE = "OpenSubtitles"
}
