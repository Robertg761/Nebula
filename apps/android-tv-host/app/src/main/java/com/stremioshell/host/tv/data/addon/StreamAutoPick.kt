package com.stremioshell.host.tv.data.addon

import kotlinx.serialization.Serializable

/**
 * What a viewer last chose for one series, kept so the next episode does not ask
 * the same question again.
 *
 * Both fields are recorded because a bingeGroup is exact but perishable - a
 * release that only covers season 3 has nothing to offer season 4 - while the
 * resolution tier still says something useful about every episode of the show.
 */
@Serializable
data class StreamSelection(
  /** The series' IMDb id, which is the only id both the picker and the player hold. */
  val seriesId: String,
  val bingeGroup: String? = null,
  val resolutionHeight: Int? = null,
  /** The row's own label, for showing the viewer what was remembered. */
  val label: String? = null,
  val updatedAtMs: Long,
)

/**
 * Matches a bingeGroup across episodes.
 *
 * Addons that support binge watching tag every episode of one release with the
 * same group string ("comet|1080p|WEB-DL|GROUP"), which is the only signal in the
 * protocol that says "this is the same source you were just watching" - same
 * encode, same audio tracks, same subtitle timing.
 */
object BingeGroupMatcher {
  fun match(bingeGroup: String?, streams: List<AddonStream>): AddonStream? {
    val wanted = bingeGroup?.trim()?.lowercase()?.ifBlank { null } ?: return null
    return streams.firstOrNull { it.bingeGroup?.trim()?.lowercase() == wanted }
  }
}

/**
 * Which stream the next episode should start on without asking.
 *
 * Only ever picks something the viewer's own last choice points at, in order of
 * how much that choice guarantees: the exact release they are mid-way through,
 * then the release they picked for this series, then the best row in the
 * resolution tier they picked. Anything less certain returns null, which is what
 * lands them on the stream list instead of playing a random 480p cam.
 */
object StreamAutoPick {
  fun pick(
    streams: List<AddonStream>,
    bingeGroup: String? = null,
    remembered: StreamSelection? = null,
  ): AddonStream? {
    BingeGroupMatcher.match(bingeGroup, streams)?.let { return it }
    val memory = remembered ?: return null
    BingeGroupMatcher.match(memory.bingeGroup, streams)?.let { return it }
    val height = memory.resolutionHeight ?: return null
    return StreamOrder.byQuality(streams)
      .firstOrNull { StreamQuality.parse(it).resolutionHeight == height }
  }
}
