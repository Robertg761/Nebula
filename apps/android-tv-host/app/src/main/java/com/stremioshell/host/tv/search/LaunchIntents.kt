package com.stremioshell.host.tv.search

import com.stremioshell.host.tv.channel.WatchNextDeepLink
import com.stremioshell.host.tv.channel.WatchNextTarget

/** A request to open Search; [query] is empty when only the destination was asked for. */
data class SearchLaunch(val query: String)

/** What the intent that started (or re-entered) the app is asking for. */
sealed interface LaunchRequest {
  /** A launcher tap, or an intent nothing here recognises: Home, as before. */
  data object Launch : LaunchRequest

  data class OpenSearch(val launch: SearchLaunch) : LaunchRequest

  data class OpenWatchNext(val target: WatchNextTarget) : LaunchRequest

  /** `stremio-tv://settings`: launcher shortcuts, Assistant, and the baseline-profile generator. */
  data object OpenSettings : LaunchRequest
}

/**
 * Turns a launch intent into one of three destinations.
 *
 * Plain strings rather than Intent so the whole decision is a JVM test, the same way
 * [WatchNextDeepLink] keeps its URI handling testable. The activity these arrive at is
 * exported and named in a searchable declaration, so every value here comes from a
 * caller we do not control: an unrecognised action, a foreign URI or a junk query has
 * to read as "just open the app" rather than as a half-understood instruction.
 */
object LaunchIntents {
  /** Intent.ACTION_SEARCH, as sent by the Assistant for an app-scoped query. */
  const val ACTION_SEARCH = "android.intent.action.SEARCH"

  /**
   * MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH. "Play <title> on <app>" asks to
   * start playback outright; we cannot know which release the viewer wants, so it lands
   * on the search results for the title instead of guessing a stream.
   */
  const val ACTION_MEDIA_PLAY_FROM_SEARCH = "android.media.action.MEDIA_PLAY_FROM_SEARCH"

  /** The Google-app search action, still what some Assistant surfaces send. */
  const val ACTION_GMS_SEARCH = "com.google.android.gms.actions.SEARCH_ACTION"

  /** SearchManager.QUERY. */
  const val EXTRA_QUERY = "query"

  /**
   * SearchManager.USER_QUERY: what the viewer actually said, when the surface rewrote
   * [EXTRA_QUERY] into something it thought we would like better.
   */
  const val EXTRA_USER_QUERY = "user_query"

  /**
   * Long enough for any title plus a qualifier ("the thing 1982"), short enough that a
   * pasted-in megabyte of text never reaches the field, the ViewModel or TMDB.
   */
  const val MAX_QUERY_CHARS = 120

  fun isSearchAction(action: String?): Boolean = when (action) {
    ACTION_SEARCH, ACTION_MEDIA_PLAY_FROM_SEARCH, ACTION_GMS_SEARCH -> true
    else -> false
  }

  /**
   * @param query the QUERY extra, already read off the intent (see [EXTRA_QUERY]); null
   *   when it was absent or was not a string.
   */
  /**
   * `stremio-tv://settings`, exactly: a startsWith would let `settings.evil.example` paths
   * through, and a destination-only link has no parameters to carry. Trailing slash included
   * because Android's Uri normalisation is free to add one.
   */
  private fun isSettingsDeepLink(dataString: String?): Boolean =
    dataString == "stremio-tv://settings" || dataString == "stremio-tv://settings/"

  fun route(action: String?, dataString: String?, query: String?): LaunchRequest {
    // The URI forms are unambiguous - they only ever come from surfaces this app published -
    // so they win over an action, which anyone can send with anything attached.
    WatchNextDeepLink.parse(dataString)?.let { return LaunchRequest.OpenWatchNext(it) }
    if (isSettingsDeepLink(dataString)) return LaunchRequest.OpenSettings
    if (!isSearchAction(action)) return LaunchRequest.Launch
    // A search action with nothing usable attached still means "the viewer asked for
    // search": the destination is the answer, they can type the rest.
    return LaunchRequest.OpenSearch(SearchLaunch(sanitize(query)))
  }

  /**
   * Collapses a spoken or typed query into something a single-line field and a URL query
   * parameter can both carry: no control characters, no line breaks, no runs of spaces,
   * and never longer than [MAX_QUERY_CHARS].
   */
  fun sanitize(raw: String?): String {
    if (raw.isNullOrEmpty()) return ""
    val flattened = buildString(minOf(raw.length, MAX_QUERY_CHARS * 2)) {
      for (ch in raw) {
        // Tabs, newlines and the C0/C1 ranges all become plain gaps rather than being
        // dropped, so "dune\npart two" stays two words.
        val space = ch.isWhitespace() || ch.isISOControl()
        if (space) {
          if (isNotEmpty() && last() != ' ') append(' ')
        } else {
          append(ch)
        }
        // One char past the cap is enough to know a trailing trim is all that is left.
        if (length > MAX_QUERY_CHARS) break
      }
    }
    return flattened.take(MAX_QUERY_CHARS).trim()
  }
}
