package com.stremioshell.host.tv.data.addon

import com.stremioshell.host.tv.data.HttpFetcher
import com.stremioshell.host.tv.data.OkHttpFetcher
import com.stremioshell.host.tv.data.PlaybackUrlPolicy
import com.stremioshell.host.tv.data.decodeJsonOffMain
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
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
      // The merged picker sorts and caps rows across every addon. Keeping an unplayable row until
      // the player validates the selected item lets one broken addon fill that cap with high-ranked
      // `file:`, cleartext or private-network URLs and crowd every valid row from healthy addons out
      // of the picker. Apply the same pure policy here, before merge ordering, and retain its
      // canonical spelling so the URL eventually handed to native mpv is the one already audited.
      .mapNotNull { stream ->
        PlaybackUrlPolicy.allowedUrlOrNull(stream.url.orEmpty())
          ?.let { allowedUrl -> stream.copy(url = allowedUrl).boundedForApp() }
      }
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

/**
 * Network decoding retains at most [MAX_ADDON_STREAM_ROWS] protocol rows from one addon.
 *
 * The HTTP body already has a byte ceiling, but a small JSON row can still expand into tens of
 * thousands of Kotlin objects. The custom serializer continues decoding excess rows so the JSON
 * remains validated, while discarding each one immediately instead of keeping the whole list.
 */
@Serializable(with = AddonStreamsResponseSerializer::class)
data class AddonStreamsResponse(val streams: List<AddonStream> = emptyList())

internal object AddonStreamsResponseSerializer : KSerializer<AddonStreamsResponse> {
  private val streamsSerializer = BoundedListSerializer(
    elementSerializer = AddonStream.serializer(),
    maxSize = MAX_ADDON_STREAM_ROWS,
  )

  override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
    "com.stremioshell.host.tv.data.addon.AddonStreamsResponse",
  ) {
    element("streams", streamsSerializer.descriptor, isOptional = true)
  }

  override fun deserialize(decoder: Decoder): AddonStreamsResponse =
    decoder.decodeStructure(descriptor) {
      var streams = emptyList<AddonStream>()
      while (true) {
        when (val index = decodeElementIndex(descriptor)) {
          CompositeDecoder.DECODE_DONE -> break
          0 -> streams = decodeSerializableElement(descriptor, index, streamsSerializer)
          else -> throw SerializationException("Unexpected addon response field index $index")
        }
      }
      AddonStreamsResponse(streams)
    }

  override fun serialize(encoder: Encoder, value: AddonStreamsResponse) {
    encoder.encodeStructure(descriptor) {
      encodeSerializableElement(descriptor, 0, streamsSerializer, value.streams)
    }
  }
}

private class BoundedListSerializer<T>(
  private val elementSerializer: KSerializer<T>,
  private val maxSize: Int,
) : KSerializer<List<T>> {
  private val delegate = ListSerializer(elementSerializer)
  override val descriptor: SerialDescriptor = delegate.descriptor

  override fun deserialize(decoder: Decoder): List<T> = decoder.decodeStructure(descriptor) {
    val retained = ArrayList<T>(maxSize)
    while (true) {
      val index = decodeElementIndex(descriptor)
      if (index == CompositeDecoder.DECODE_DONE) break
      val value = decodeSerializableElement(descriptor, index, elementSerializer)
      if (retained.size < maxSize) retained += value
    }
    retained
  }

  override fun serialize(encoder: Encoder, value: List<T>) = delegate.serialize(encoder, value)
}

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

/** Keeps one untrusted stream row small before it enters picker or player state. */
private fun AddonStream.boundedForApp(): AddonStream = copy(
  name = name?.truncateUtf16(MAX_STREAM_LABEL_CHARS),
  title = title?.truncateUtf16(MAX_STREAM_DETAIL_CHARS),
  description = description?.truncateUtf16(MAX_STREAM_DETAIL_CHARS),
  infoHash = infoHash.boundedIdentity(MAX_INFO_HASH_CHARS),
  subtitles = subtitles.asSequence()
    .take(MAX_SUBTITLE_CANDIDATES)
    .mapNotNull { subtitle ->
      subtitle.url.takeIf { it.length <= MAX_SUBTITLE_URL_CHARS }?.let { url ->
        subtitle.copy(
          url = url,
          id = subtitle.id.boundedText(MAX_SUBTITLE_ID_CHARS),
          lang = subtitle.lang.boundedText(MAX_SUBTITLE_LANGUAGE_CHARS),
        )
      }
    }
    .toList(),
  behaviorHints = behaviorHints?.let { hints ->
    hints.copy(
      bingeGroup = hints.bingeGroup.boundedIdentity(MAX_BINGE_GROUP_CHARS),
      filename = hints.filename.boundedText(MAX_FILENAME_CHARS),
      videoHash = hints.videoHash.boundedIdentity(MAX_VIDEO_HASH_CHARS),
      proxyHeaders = hints.proxyHeaders?.let { headers ->
        AddonProxyHeaders(
          request = headers.request.boundedHeaders(),
          response = headers.response.boundedHeaders(),
        )
      },
      countryWhitelist = hints.countryWhitelist.asSequence()
        .take(MAX_COUNTRIES)
        .mapNotNull { it.boundedIdentity(MAX_COUNTRY_CHARS) }
        .toList(),
    )
  },
)

private fun String?.boundedIdentity(maxChars: Int): String? = this
  ?.trim()
  ?.takeIf { it.isNotEmpty() && it.length <= maxChars }

private fun String?.boundedText(maxChars: Int): String? = this
  ?.trim()
  ?.takeIf { it.isNotEmpty() }
  ?.truncateUtf16(maxChars)

private fun String.truncateUtf16(maxChars: Int): String {
  if (length <= maxChars) return this
  var end = maxChars.coerceAtLeast(0)
  if (end > 0 && this[end - 1].isHighSurrogate()) end -= 1
  return substring(0, end)
}

private fun Map<String, String>.boundedHeaders(): Map<String, String> {
  val bounded = linkedMapOf<String, String>()
  var totalChars = 0
  for ((name, value) in this) {
    if (
      bounded.size >= MAX_PROXY_HEADERS ||
      name.length !in 1..MAX_HEADER_NAME_CHARS ||
      value.length > MAX_HEADER_VALUE_CHARS
    ) {
      continue
    }
    val addedChars = name.length + value.length
    if (totalChars + addedChars > MAX_PROXY_HEADER_CHARS) continue
    bounded[name] = value
    totalChars += addedChars
  }
  return bounded
}

private const val MAX_STREAM_LABEL_CHARS = 256
private const val MAX_STREAM_DETAIL_CHARS = 2 * 1024
private const val MAX_INFO_HASH_CHARS = 128
private const val MAX_BINGE_GROUP_CHARS = 512
private const val MAX_FILENAME_CHARS = 1024
private const val MAX_VIDEO_HASH_CHARS = 256
private const val MAX_SUBTITLE_CANDIDATES = 240
private const val MAX_SUBTITLE_URL_CHARS = 4 * 1024
private const val MAX_SUBTITLE_ID_CHARS = 120
private const val MAX_SUBTITLE_LANGUAGE_CHARS = 64
private const val MAX_COUNTRIES = 32
private const val MAX_COUNTRY_CHARS = 8
private const val MAX_PROXY_HEADERS = 32
private const val MAX_HEADER_NAME_CHARS = 128
private const val MAX_HEADER_VALUE_CHARS = 8 * 1024
private const val MAX_PROXY_HEADER_CHARS = 16 * 1024

/** Twice the final merged-picker cap leaves filtering headroom without an unbounded object graph. */
internal const val MAX_ADDON_STREAM_ROWS = StreamMerge.MAX_MERGED_STREAMS * 2
