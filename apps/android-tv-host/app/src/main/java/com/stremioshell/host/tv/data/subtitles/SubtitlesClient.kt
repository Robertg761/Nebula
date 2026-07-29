package com.stremioshell.host.tv.data.subtitles

import com.stremioshell.host.tv.data.HttpFetcher
import com.stremioshell.host.tv.data.OkHttpFetcher
import com.stremioshell.host.tv.data.addon.AddonList
import com.stremioshell.host.tv.data.decodeJsonOffMain
import java.net.URLEncoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Client for a Stremio subtitles addon, whose one resource is
 * `{base}/subtitles/{type}/{id}/{extra}.json`.
 *
 * Kept apart from [com.stremioshell.host.tv.data.addon.AddonClient] because the
 * two ask different addons for different things: streams come from the viewer's
 * own debrid resolver and are configured, subtitles come from a public catalogue
 * that needs no configuration at all. It is also why this one may be served from
 * the disk cache — a subtitle is a static file, not a signed link with an hour to
 * live.
 */
class SubtitlesClient(
  private val fetcher: HttpFetcher = OkHttpFetcher,
  /**
   * Resolved per request rather than held: the player builds one of these for the
   * whole session, and a viewer who changes the addon in Settings mid-film should
   * not have to restart playback for the next subtitle search to use it.
   */
  private val baseUrl: suspend () -> String = { OPENSUBTITLES_V3_BASE },
) {
  private val json = Json {
    ignoreUnknownKeys = true
    // Addons disagree on whether the entry id is a string or a number, and the
    // whole response is worth nothing if one of them fails to parse.
    isLenient = true
  }

  suspend fun movieSubtitles(
    imdbId: String,
    extra: Map<String, String> = emptyMap(),
  ): List<AddonSubtitle> = subtitles("movie", imdbId, extra)

  suspend fun episodeSubtitles(
    imdbId: String,
    season: Int,
    episode: Int,
    extra: Map<String, String> = emptyMap(),
  ): List<AddonSubtitle> = subtitles("series", "$imdbId:$season:$episode", extra)

  private suspend fun subtitles(
    type: String,
    id: String,
    extra: Map<String, String>,
  ): List<AddonSubtitle> {
    val base = baseUrl().trim().ifBlank { OPENSUBTITLES_V3_BASE }
    val body = fetcher.getAllowingStale(subtitlesUrl(base, type, id, extra))
    return decodeJsonOffMain { json.decodeFromString<SubtitlesResponse>(body) }.subtitles
      .filter { it.url.isNotBlank() }
  }

  companion object {
    /**
     * The community OpenSubtitles v3 addon, which is the subtitles source every
     * Stremio client offers out of the box and needs no account or key.
     */
    const val OPENSUBTITLES_V3_BASE = "https://opensubtitles-v3.strem.io"

    /**
     * `{base}/subtitles/{type}/{id}/{extra}.json`, with [extra] omitted entirely
     * when there is none — an addon route that expects two path segments does not
     * match a trailing empty one.
     *
     * [id] is left as it comes: an IMDb id is `tt` and digits, and the series form
     * spells its season and episode with colons, all of which a path segment
     * carries verbatim. The extra arguments are the part that can hold a file
     * name, so they are the part that is encoded.
     */
    fun subtitlesUrl(
      base: String,
      type: String,
      id: String,
      extra: Map<String, String> = emptyMap(),
    ): String {
      if (extra.isEmpty()) {
        return AddonList.resourceUrl(base, "subtitles", type, "$id.json")
      }
      val args = extra.entries.joinToString("&") { (key, value) ->
        "${encode(key)}=${encode(value)}"
      }
      return AddonList.resourceUrl(
        manifestUrl = base,
        resource = "subtitles",
        type = type,
        id = id,
        encodedExtraPathSegment = "$args.json",
      )
    }

    /**
     * Percent-encoding, not form encoding: this lands in a path segment, where a
     * `+` is a literal plus and not a space.
     */
    private fun encode(value: String): String =
      URLEncoder.encode(value, "UTF-8").replace("+", "%20")
  }
}

@Serializable
data class SubtitlesResponse(val subtitles: List<AddonSubtitle> = emptyList())

/** One subtitle file an addon offers. */
@Serializable
data class AddonSubtitle(
  /** The addon's own id for the file; only ever used to tell two entries apart. */
  val id: String = "",
  val url: String = "",
  /** ISO 639 code as the addon spells it, e.g. "eng", "pt-BR" or nothing at all. */
  val lang: String = "",
)
