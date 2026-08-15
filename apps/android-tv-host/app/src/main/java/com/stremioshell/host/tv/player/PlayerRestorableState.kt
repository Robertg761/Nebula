package com.stremioshell.host.tv.player

import android.os.Parcelable
import com.stremioshell.host.tv.data.PlaybackUrlPolicy
import com.stremioshell.host.tv.data.addon.AddonStreamSubtitle
import kotlinx.parcelize.Parcelize

/**
 * Everything needed to recreate the file this activity is actually playing.
 *
 * The launch [android.content.Intent] is only the first file. Retry can replace its signed URL and
 * the binge loop can move the same activity through many episodes, so restoring that launch after
 * process death pairs the current position with the wrong stream and watch key. This snapshot is
 * deliberately made from the mutable runtime descriptor instead.
 *
 * Headers and subtitles are flattened into String lists because those are stable Parcel types. The
 * player sanitizes them again when restoring, so a damaged or older parcel cannot bypass the same
 * boundaries an ordinary launch has to cross.
 */
@Parcelize
internal data class PlayerRestorableState(
  val url: String,
  val title: String,
  val watchKey: String,
  val tmdbId: Int,
  val mediaType: String,
  val posterUrl: String?,
  val season: Int?,
  val episode: Int?,
  val imdbId: String?,
  val bingeGroup: String?,
  val requestHeaderNames: List<String>,
  val requestHeaderValues: List<String>,
  val subtitleUrls: List<String>,
  val subtitleLanguages: List<String>,
  val subtitleIds: List<String>,
  val streamVideoHash: String?,
  val streamFilename: String?,
  val streamVideoSize: Long?,
  val positionMs: Long,
  val resumeResetRequested: Boolean,
  val pauseRequested: Boolean,
) : Parcelable {
  fun requestHeaders(): Map<String, String> = buildMap {
    requestHeaderNames.forEachIndexed { index, name ->
      requestHeaderValues.getOrNull(index)?.let { value -> put(name, value) }
    }
  }

  fun embeddedSubtitles(): List<AddonStreamSubtitle> = subtitleUrls.mapIndexed { index, url ->
    AddonStreamSubtitle(
      id = subtitleIds.getOrNull(index)?.takeIf(String::isNotBlank),
      url = PlaybackUrlPolicy.allowedUrlOrNull(url).orEmpty(),
      lang = subtitleLanguages.getOrNull(index)?.takeIf(String::isNotBlank),
    )
  }

  companion object {
    fun create(
      url: String,
      title: String,
      watchKey: String,
      tmdbId: Int,
      mediaType: String,
      posterUrl: String?,
      season: Int?,
      episode: Int?,
      imdbId: String?,
      bingeGroup: String?,
      requestHeaders: Map<String, String>,
      embeddedSubtitles: List<AddonStreamSubtitle>,
      streamVideoHash: String?,
      streamFilename: String?,
      streamVideoSize: Long?,
      positionMs: Long,
      resumeResetRequested: Boolean,
      pauseRequested: Boolean,
    ): PlayerRestorableState {
      val boundedHeaders = StreamRequestHeaders.sanitize(requestHeaders)
      val boundedSubtitles = EmbeddedSubtitles.sanitize(embeddedSubtitles)
      return PlayerRestorableState(
        url = PlaybackUrlPolicy.allowedUrlOrNull(url).orEmpty(),
        title = PlayerPayloadBounds.required(title, PlayerPayloadBounds.MAX_TITLE_CHARS),
        watchKey = PlayerPayloadBounds.required(watchKey, PlayerPayloadBounds.MAX_WATCH_KEY_CHARS),
        tmdbId = tmdbId,
        mediaType = PlayerPayloadBounds.required(mediaType, PlayerPayloadBounds.MAX_MEDIA_TYPE_CHARS),
        posterUrl = PlayerPayloadBounds.optional(
          posterUrl,
          PlayerPayloadBounds.MAX_POSTER_URL_CHARS,
        ),
        season = season,
        episode = episode,
        imdbId = PlayerPayloadBounds.optional(imdbId, PlayerPayloadBounds.MAX_IMDB_ID_CHARS),
        bingeGroup = PlayerPayloadBounds.optional(
          bingeGroup,
          PlayerPayloadBounds.MAX_BINGE_GROUP_CHARS,
        ),
        requestHeaderNames = boundedHeaders.keys.toList(),
        requestHeaderValues = boundedHeaders.values.toList(),
        subtitleUrls = boundedSubtitles.map { it.url },
        subtitleLanguages = boundedSubtitles.map { it.lang.orEmpty() },
        subtitleIds = boundedSubtitles.map { it.id.orEmpty() },
        streamVideoHash = PlayerPayloadBounds.optional(
          streamVideoHash,
          PlayerPayloadBounds.MAX_VIDEO_HASH_CHARS,
        ),
        streamFilename = PlayerPayloadBounds.optionalText(
          streamFilename,
          PlayerPayloadBounds.MAX_FILENAME_CHARS,
        ),
        streamVideoSize = streamVideoSize,
        positionMs = positionMs,
        resumeResetRequested = resumeResetRequested,
        pauseRequested = pauseRequested,
      )
    }
  }
}
