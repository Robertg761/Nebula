package com.stremioshell.host.tv.player

import com.stremioshell.host.tv.data.PlaybackUrlPolicy

/**
 * Text ceilings for the runtime playback descriptor carried through an Intent or saved state.
 *
 * Binder has one process-wide transaction buffer. Keeping one player descriptor below this text
 * budget leaves substantial room for Parcel framing and unrelated lifecycle state while still
 * accommodating signed playback URLs, request credentials and embedded subtitle choices.
 */
internal object PlayerPayloadBounds {
  const val MAX_TITLE_CHARS = 512
  const val MAX_WATCH_KEY_CHARS = 512
  const val MAX_MEDIA_TYPE_CHARS = 32
  const val MAX_POSTER_URL_CHARS = 2 * 1024
  const val MAX_IMDB_ID_CHARS = 64
  const val MAX_BINGE_GROUP_CHARS = 512
  const val MAX_VIDEO_HASH_CHARS = 256
  const val MAX_FILENAME_CHARS = 1024

  const val MAX_TOTAL_TEXT_CHARS = PlaybackUrlPolicy.MAX_URL_CHARS +
    MAX_TITLE_CHARS +
    MAX_WATCH_KEY_CHARS +
    MAX_MEDIA_TYPE_CHARS +
    MAX_POSTER_URL_CHARS +
    MAX_IMDB_ID_CHARS +
    MAX_BINGE_GROUP_CHARS +
    MAX_VIDEO_HASH_CHARS +
    MAX_FILENAME_CHARS +
    StreamRequestHeaders.MAX_TOTAL_CHARS +
    EmbeddedSubtitles.MAX_TOTAL_CHARS

  fun required(value: String, maxChars: Int): String = truncateUtf16(value, maxChars)

  /** Truncates without leaving the high half of a surrogate pair at the end. */
  fun truncateUtf16(value: String, maxChars: Int): String {
    if (value.length <= maxChars) return value
    var end = maxChars.coerceAtLeast(0)
    if (end > 0 && value[end - 1].isHighSurrogate()) end -= 1
    return value.substring(0, end)
  }

  /** Drops an oversized identity or URL instead of silently changing what it identifies. */
  fun optional(value: String?, maxChars: Int): String? = value
    ?.trim()
    ?.takeIf { it.isNotEmpty() && it.length <= maxChars }

  fun optionalText(value: String?, maxChars: Int): String? = value
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { truncateUtf16(it, maxChars) }
}
