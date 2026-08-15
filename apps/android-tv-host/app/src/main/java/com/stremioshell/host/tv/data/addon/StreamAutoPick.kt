package com.stremioshell.host.tv.data.addon

import com.stremioshell.host.tv.data.PersistenceMutationClock
import com.stremioshell.host.tv.data.PersistenceMutationToken
import java.util.Locale
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * What a viewer last chose for one series, kept so the next episode does not ask
 * the same question again.
 *
 * Both fields are recorded because a bingeGroup is exact but perishable - a
 * release that only covers season 3 has nothing to offer season 4 - while the
 * resolution tier still says something useful about every episode of the show.
 */
@Serializable(with = StreamSelectionSerializer::class)
data class StreamSelection(
  /** The series' IMDb id, which is the only id both the picker and the player hold. */
  val seriesId: String,
  val bingeGroup: String? = null,
  val resolutionHeight: Int? = null,
  /**
   * Nullable for selections persisted before playback-format compatibility was
   * recorded. A known false is different from legacy "not recorded".
   */
  val hdr: Boolean? = null,
  val dolbyVision: Boolean? = null,
  /** The row's own label, for showing the viewer what was remembered. */
  val label: String? = null,
  val updatedAtMs: Long,
  /** Monotonic persistence order; zero denotes a selection written by an older build. */
  val mutationOrder: Long = 0L,
  /** Captured before the persistence coroutine starts; omitted from the stored JSON. */
  val pendingMutation: PersistenceMutationToken = PersistenceMutationClock.next(),
)

@Serializable
private data class PersistedStreamSelection(
  val seriesId: String,
  val bingeGroup: String? = null,
  val resolutionHeight: Int? = null,
  val hdr: Boolean? = null,
  val dolbyVision: Boolean? = null,
  val label: String? = null,
  val updatedAtMs: Long,
  val mutationOrder: Long = 0L,
)

internal object StreamSelectionSerializer : KSerializer<StreamSelection> {
  private val delegate = PersistedStreamSelection.serializer()
  override val descriptor: SerialDescriptor = delegate.descriptor

  override fun serialize(encoder: Encoder, value: StreamSelection) {
    delegate.serialize(
      encoder,
      PersistedStreamSelection(
        seriesId = value.seriesId,
        bingeGroup = value.bingeGroup,
        resolutionHeight = value.resolutionHeight,
        hdr = value.hdr,
        dolbyVision = value.dolbyVision,
        label = value.label,
        updatedAtMs = value.updatedAtMs,
        mutationOrder = value.mutationOrder,
      ),
    )
  }

  override fun deserialize(decoder: Decoder): StreamSelection {
    val value = delegate.deserialize(decoder)
    return StreamSelection(
      seriesId = value.seriesId,
      bingeGroup = value.bingeGroup,
      resolutionHeight = value.resolutionHeight,
      hdr = value.hdr,
      dolbyVision = value.dolbyVision,
      label = value.label,
      updatedAtMs = value.updatedAtMs,
      mutationOrder = value.mutationOrder,
      pendingMutation = PersistenceMutationToken.Unassigned,
    )
  }
}

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
    val wanted = bingeGroup?.trim()?.lowercase(Locale.ROOT)?.ifBlank { null } ?: return null
    return streams.firstOrNull {
      it.bingeGroup?.trim()?.lowercase(Locale.ROOT) == wanted
    }
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
    BingeGroupMatcher.match(bingeGroup, streams)
      ?.takeIf { formatCompatible(it, remembered) }
      ?.let { return it }
    val memory = remembered ?: return null
    BingeGroupMatcher.match(memory.bingeGroup, streams)
      ?.takeIf { formatCompatible(it, memory) }
      ?.let { return it }
    val height = memory.resolutionHeight ?: return null
    return StreamOrder.byQuality(streams)
      .firstOrNull { stream ->
        val quality = StreamQuality.parse(stream)
        quality.resolutionHeight == height &&
          formatCompatible(stream, memory)
      }
  }

  /**
   * A binge-group is addon-provided rather than a cryptographic release identity. Preserve the
   * exact-match preference, but never let a reused/mistagged group cross the format boundary the
   * viewer already chose; that can turn working SDR playback into an unsupported DV stream.
   */
  private fun formatCompatible(stream: AddonStream, memory: StreamSelection?): Boolean {
    if (memory == null) return true
    val quality = StreamQuality.parse(stream)
    return (memory.hdr == null || quality.hdr == memory.hdr) &&
      (memory.dolbyVision == null || quality.dolbyVision == memory.dolbyVision)
  }
}
