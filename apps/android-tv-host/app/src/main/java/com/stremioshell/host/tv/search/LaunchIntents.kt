package com.stremioshell.host.tv.search

import com.stremioshell.host.tv.channel.WatchNextDeepLink
import com.stremioshell.host.tv.channel.WatchNextTarget

/** A request to open Search; [query] is empty when only the destination was asked for. */
data class SearchLaunch(val query: String)

/** One normalization policy for text fields, launch intents, ViewModel state, and TMDB requests. */
object SearchQuery {
  /** Long enough for a title plus qualifiers, while bounding field state and request URLs. */
  const val MAX_CHARS = 120

  /**
   * Sanitizes text as it enters the field without removing its trailing space. Trimming that space
   * on every keystroke makes it impossible to type a second word with an ordinary IME.
   */
  fun forField(raw: String?): String = normalize(raw, trimEdges = false)

  /** Sanitizes and trims the value used as a request identity and TMDB query parameter. */
  fun forRequest(raw: String?): String = normalize(raw, trimEdges = true)

  private fun normalize(raw: String?, trimEdges: Boolean): String {
    if (raw.isNullOrEmpty()) return ""
    val flattened = buildString(minOf(raw.length, MAX_CHARS * 2)) {
      var index = 0
      while (index < raw.length) {
        val codePoint = Character.codePointAt(raw, index)
        val width = Character.charCount(codePoint)
        index += width
        val space = Character.isWhitespace(codePoint) ||
          Character.isSpaceChar(codePoint) ||
          Character.isISOControl(codePoint)
        if (space) {
          if (isNotEmpty() && last() != ' ' && length < MAX_CHARS) append(' ')
        } else {
          // Refuse the whole code point if it would split a surrogate pair at the UTF-16 cap.
          if (length + width > MAX_CHARS) break
          appendCodePoint(codePoint)
        }
      }
    }
    return if (trimEdges) flattened.trim() else flattened
  }
}

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

  /** Kept for intent callers and tests; [SearchQuery] owns the policy. */
  const val MAX_QUERY_CHARS = SearchQuery.MAX_CHARS

  fun isSearchAction(action: String?): Boolean = when (action) {
    ACTION_SEARCH, ACTION_MEDIA_PLAY_FROM_SEARCH, ACTION_GMS_SEARCH -> true
    else -> false
  }

  /**
   * Chooses the viewer's original speech over a surface-rewritten query, after independently
   * sanitizing both. An unusable USER_QUERY must not hide a usable QUERY fallback.
   */
  fun preferredQuery(userQuery: String?, rewrittenQuery: String?): String? {
    val spoken = sanitize(userQuery)
    if (spoken.isNotEmpty()) return spoken
    return sanitize(rewrittenQuery).ifEmpty { null }
  }

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
   *
   * Walks code points rather than chars. The cap counts UTF-16 units, because that is what the
   * field, the ViewModel and the URL builder downstream all measure - but a title with an emoji or
   * any other astral character in it carries that character as *two* of them, and a cut between the
   * pair leaves a lone surrogate: not a character at all, and something a URL encoder is free to
   * mangle or reject. So a code point that would straddle the cap ends the query instead of being
   * half-admitted.
   */
  fun sanitize(raw: String?): String = SearchQuery.forRequest(raw)
}
