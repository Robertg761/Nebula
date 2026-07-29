package com.stremioshell.host.tv.ui

import com.stremioshell.host.tv.data.addon.AddonStream
import com.stremioshell.host.tv.data.addon.StreamQuality

private const val FILTER_GIB = 1024L * 1024L * 1024L

enum class StreamViewMode(val label: String) {
  Recommended("Recommended"),
  All("All"),
}

enum class StreamAvailability(val label: String) {
  Any("Any"),
  Instant("Instant"),
}

enum class StreamDynamicRange(val label: String) {
  Any("Any"),
  Sdr("SDR"),
  Hdr("HDR"),
  DolbyVision("DV"),
}

enum class StreamResolution(val label: String) {
  Any("Any"),
  Uhd("4K"),
  FullHd("1080p"),
  Hd("720p"),
  Sd("SD"),
  Unknown("Other"),
}

enum class StreamSizeLimit(val label: String, val maxBytes: Long?) {
  Any("Any", null),
  Under5Gb("≤ 5 GB", 5L * FILTER_GIB),
  Under15Gb("≤ 15 GB", 15L * FILTER_GIB),
  Under30Gb("≤ 30 GB", 30L * FILTER_GIB),
  ;
}

/**
 * The stream picker's six independent decisions.
 *
 * [source] is the short collision-safe label StreamMerge already attached to rows, never a
 * manifest URL. That keeps a Real-Debrid token out of saved state, semantics and screenshots.
 */
data class StreamFilters(
  val viewMode: StreamViewMode = StreamViewMode.Recommended,
  val availability: StreamAvailability = StreamAvailability.Any,
  val dynamicRange: StreamDynamicRange = StreamDynamicRange.Any,
  val resolution: StreamResolution = StreamResolution.Any,
  val source: String? = null,
  val sizeLimit: StreamSizeLimit = StreamSizeLimit.Any,
) {
  companion object {
    val SHOW_ALL = StreamFilters(viewMode = StreamViewMode.All)
  }
}

/**
 * Pure filtering policy so a TV does not have to manufacture addon responses to test it.
 *
 * Recommended is deliberately conservative but never a dead end: it prefers instant releases,
 * avoids Dolby Vision when another release exists, and drops known files over 30 GiB when a
 * friendlier candidate exists. Each narrowing step is conditional, so an all-DV or all-large title
 * still has something to play. "All" bypasses that preset, and [StreamFilters.SHOW_ALL] clears
 * every explicit constraint, keeping the exact raw merged list one press away.
 */
object StreamFilterPolicy {
  private const val RECOMMENDED_MAX_BYTES = 30L * 1024L * 1024L * 1024L

  fun apply(streams: List<AddonStream>, filters: StreamFilters): List<AddonStream> {
    var candidates = streams
    if (filters.availability == StreamAvailability.Instant) {
      candidates = candidates.filter(StreamPresentation::isCached)
    }
    if (filters.dynamicRange != StreamDynamicRange.Any) {
      candidates = candidates.filter { stream ->
        val quality = StreamQuality.parse(stream)
        when (filters.dynamicRange) {
          StreamDynamicRange.Any -> true
          StreamDynamicRange.Sdr -> !quality.hdr && !quality.dolbyVision
          StreamDynamicRange.Hdr -> quality.hdr && !quality.dolbyVision
          StreamDynamicRange.DolbyVision -> quality.dolbyVision
        }
      }
    }
    if (filters.resolution != StreamResolution.Any) {
      candidates = candidates.filter { stream ->
        resolutionOf(StreamQuality.parse(stream).resolutionHeight) == filters.resolution
      }
    }
    filters.source?.let { source ->
      candidates = candidates.filter { it.source == source }
    }
    filters.sizeLimit.maxBytes?.let { limit ->
      candidates = candidates.filter { (StreamQuality.parse(it).sizeBytes ?: Long.MAX_VALUE) <= limit }
    }
    // Apply the soft preset last. An explicit "DV" or source selection must not be emptied because
    // a different class/source happened to look friendlier before the viewer narrowed the list.
    return if (filters.viewMode == StreamViewMode.Recommended) {
      recommended(candidates)
    } else {
      candidates
    }
  }

  fun sources(streams: List<AddonStream>): List<String> =
    streams.mapNotNull(AddonStream::source).distinct()

  private fun recommended(streams: List<AddonStream>): List<AddonStream> {
    if (streams.isEmpty()) return streams
    var candidates = streams
    candidates.prefer { StreamPresentation.isCached(it) }?.let { candidates = it }
    candidates.prefer { !StreamQuality.parse(it).dolbyVision }?.let { candidates = it }
    candidates.prefer {
      val bytes = StreamQuality.parse(it).sizeBytes
      bytes == null || bytes <= RECOMMENDED_MAX_BYTES
    }?.let { candidates = it }
    return candidates
  }

  private fun resolutionOf(height: Int?): StreamResolution = when {
    height == null -> StreamResolution.Unknown
    height >= 2160 -> StreamResolution.Uhd
    height >= 1080 -> StreamResolution.FullHd
    height >= 720 -> StreamResolution.Hd
    else -> StreamResolution.Sd
  }

  /** A preference narrows only when at least one row survives it. */
  private fun List<AddonStream>.prefer(
    predicate: (AddonStream) -> Boolean,
  ): List<AddonStream>? = filter(predicate).takeIf(List<AddonStream>::isNotEmpty)
}
