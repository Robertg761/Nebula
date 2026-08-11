package com.stremioshell.host.tv.data.addon

import com.stremioshell.host.tv.data.HttpFetcher
import com.stremioshell.host.tv.data.OkHttpFetcher
import com.stremioshell.host.tv.data.decodeJsonOffMain
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

/**
 * Client for the open Stremio addon protocol, as implemented by Comet and
 * other debrid resolvers: `<base>/manifest.json`, `<base>/stream/{type}/{id}.json`.
 *
 * Uses plain [HttpFetcher.get], which sends `no-store` and so is neither answered from the shared
 * disk cache nor written into it: debrid stream URLs are signed for an hour or less, and replaying
 * a cached one hands the player a dead link.
 */
class AddonClient(
  private val fetcher: HttpFetcher = OkHttpFetcher,
) {
  /**
   * [coerceInputValues] because addons send explicit nulls where the protocol implies a default:
   * `"streams": null` for a title they have nothing for, `"subtitles": null`, `"countryWhitelist":
   * null`. Without it kotlinx throws and one null costs the whole response - every stream from that
   * addon - rather than one field.
   */
  private val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
  }

  suspend fun manifest(manifestUrl: String): AddonManifest {
    val body = fetcher.get(manifestUrl.trim())
    return decodeJsonOffMain { json.decodeFromString<AddonManifest>(body) }
  }

  suspend fun movieStreams(manifestUrl: String, imdbId: String): List<AddonStream> {
    return streams(manifestUrl, "movie", imdbId)
  }

  suspend fun episodeStreams(
    manifestUrl: String,
    imdbId: String,
    season: Int,
    episode: Int,
  ): List<AddonStream> {
    return streams(manifestUrl, "series", "$imdbId:$season:$episode")
  }

  private suspend fun streams(manifestUrl: String, type: String, id: String): List<AddonStream> {
    val body = fetcher.get(streamUrl(manifestUrl, type, id))
    return decodeJsonOffMain { json.decodeFromString<AddonStreamsResponse>(body) }.streams
      .filter { !it.url.isNullOrBlank() }
  }

  companion object {
    /** `<...>/manifest.json` -> `<...>/stream/{type}/{id}.json` */
    fun streamUrl(manifestUrl: String, type: String, id: String): String =
      AddonList.resourceUrl(manifestUrl, "stream", type, "$id.json")
  }
}

@Serializable
data class AddonManifest(
  val id: String = "",
  val name: String = "",
  val version: String = "",
  val description: String = "",
)

@Serializable
data class AddonStreamsResponse(val streams: List<AddonStream> = emptyList())

@Serializable
data class AddonStream(
  /** Short label, e.g. "[RD+] Comet 4K". */
  val name: String? = null,
  /** Longer description: file name, size, seeders. */
  val title: String? = null,
  val description: String? = null,
  val url: String? = null,
  /**
   * The torrent this release lives in, when the addon says. Two addons pointed at
   * the same debrid account hand back the same file under different signed URLs,
   * and this is the only field that gives them away as one release; see
   * [StreamMerge].
   */
  @SerialName("infoHash") val infoHash: String? = null,
  /** Which file inside a pack [infoHash] refers to. */
  @SerialName("fileIdx") val fileIdx: Int? = null,
  /**
   * Subtitle tracks supplied with this exact stream. These are distinct from a
   * later search against the public subtitle addon: signed or release-specific
   * tracks can only be preserved here.
   */
  @SerialName("subtitles") val subtitles: List<AddonStreamSubtitle> = emptyList(),
  @SerialName("behaviorHints") val behaviorHints: AddonBehaviorHints? = null,
  /**
   * Which configured addon produced this row, filled in by [StreamMerge] once
   * there is more than one and left null when there is not.
   *
   * Transient because it is ours, not the protocol's: it must never be read from
   * an addon's response, and nothing that persists a stream has any use for it.
   */
  @Transient val source: String? = null,
) {
  val label: String get() = name ?: "Stream"
  val detail: String get() = (description ?: title).orEmpty()

  /** The release this stream belongs to; see [BingeGroupMatcher]. */
  val bingeGroup: String? get() = behaviorHints?.bingeGroup
}

@Serializable
data class AddonBehaviorHints(
  @SerialName("bingeGroup") val bingeGroup: String? = null,
  @SerialName("filename") val filename: String? = null,
  @SerialName("videoSize") val videoSize: Long? = null,
  @SerialName("videoHash") val videoHash: String? = null,
  @SerialName("proxyHeaders") val proxyHeaders: AddonProxyHeaders? = null,
  @SerialName("notWebReady") val notWebReady: Boolean? = null,
  @SerialName("countryWhitelist") val countryWhitelist: List<String> = emptyList(),
)

/** One subtitle attached directly to a Stremio stream response. */
@Serializable
data class AddonStreamSubtitle(
  @SerialName("id") val id: String? = null,
  @SerialName("url") val url: String = "",
  @SerialName("lang") val lang: String? = null,
)

/**
 * Headers the addon requires around playback. Request and response maps are
 * both retained even though the player currently consumes only [request].
 */
@Serializable
data class AddonProxyHeaders(
  @SerialName("request") val request: Map<String, String> = emptyMap(),
  @SerialName("response") val response: Map<String, String> = emptyMap(),
)
