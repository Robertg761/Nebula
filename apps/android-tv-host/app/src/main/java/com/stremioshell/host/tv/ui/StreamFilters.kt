package com.stremioshell.host.tv.ui

import androidx.compose.runtime.Immutable
import com.stremioshell.host.tv.data.addon.AddonStream
import com.stremioshell.host.tv.data.addon.StreamQuality

private const val FILTER_GIB = 1024L * 1024L * 1024L

/**
 * One release and what its own text says about it, read once.
 *
 * The parse is regex work over four free-text fields, and everything the picker does needs the
 * answer: three of the six filters, the recommended preset's two narrowing passes, the tier
 * headings and the row's own badges. Read at each of those points it ran eight or nine times per
 * stream for every press of a filter chip, on the main thread, inside composition. Read once here
 * it runs exactly as many times as there are streams, and only when the list itself changes.
 */
@Immutable
data class RatedStream(
  val stream: AddonStream,
  val quality: StreamQuality,
  /**
   * Which repeat of its identity (URL, or label when there is none) this row is, counted over the
   * *unfiltered* list. It exists for the lazy-list key: debrid addons hand back the same resolved
   * URL under several labels, so the URL alone collides. Counted here rather than over the
   * filtered rows because a filter that removes the first duplicate must not renumber the
   * survivors - a renumbered key means the list reuses a node for a different stream and focus
   * tracks position instead of identity.
   */
  val occurrence: Int = 0,
)

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

  /** The whole list, read once. Everything below filters over the result rather than the raw rows. */
  fun rate(streams: List<AddonStream>): List<RatedStream> {
    val seen = mutableMapOf<String, Int>()
    return streams.map { stream ->
      val identity = stream.url ?: stream.label
      val occurrence = seen[identity] ?: 0
      seen[identity] = occurrence + 1
      RatedStream(stream, StreamQuality.parse(stream), occurrence)
    }
  }

  /** Filters a raw list end to end, reading it once on the way through. */
  fun apply(streams: List<AddonStream>, filters: StreamFilters): List<AddonStream> =
    applyRated(rate(streams), filters).map(RatedStream::stream)

  /** The same policy over rows already read, which is what the picker holds. */
  fun applyRated(streams: List<RatedStream>, filters: StreamFilters): List<RatedStream> {
    var candidates = streams
    if (filters.availability == StreamAvailability.Instant) {
      candidates = candidates.filter { StreamPresentation.isCached(it.stream) }
    }
    if (filters.dynamicRange != StreamDynamicRange.Any) {
      candidates = candidates.filter { (_, quality) ->
        when (filters.dynamicRange) {
          StreamDynamicRange.Any -> true
          StreamDynamicRange.Sdr -> !quality.hdr && !quality.dolbyVision
          StreamDynamicRange.Hdr -> quality.hdr && !quality.dolbyVision
          StreamDynamicRange.DolbyVision -> quality.dolbyVision
        }
      }
    }
    if (filters.resolution != StreamResolution.Any) {
      candidates = candidates.filter { (_, quality) ->
        resolutionOf(quality.resolutionHeight) == filters.resolution
      }
    }
    filters.source?.let { source ->
      candidates = candidates.filter { it.stream.source == source }
    }
    filters.sizeLimit.maxBytes?.let { limit ->
      candidates = candidates.filter { (_, quality) ->
        (quality.sizeBytes ?: Long.MAX_VALUE) <= limit
      }
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

  private fun recommended(streams: List<RatedStream>): List<RatedStream> {
    if (streams.isEmpty()) return streams
    var candidates = streams
    candidates.prefer { StreamPresentation.isCached(it.stream) }?.let { candidates = it }
    candidates.prefer { !it.quality.dolbyVision }?.let { candidates = it }
    candidates.prefer {
      val bytes = it.quality.sizeBytes
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
  private fun List<RatedStream>.prefer(
    predicate: (RatedStream) -> Boolean,
  ): List<RatedStream>? = filter(predicate).takeIf(List<RatedStream>::isNotEmpty)
}
