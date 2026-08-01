package com.stremioshell.host.tv.player

/**
 * Identity for one native playback load.
 *
 * A monotonically increasing generation is the primary identity; the source fingerprint is kept
 * as a cheap diagnostic discriminator without retaining a second copy of a signed URL in logs or
 * reports. The Activity still owns the full URL, while worker callbacks only need this token.
 */
internal data class PlaybackSessionToken(
  val generation: Long,
  val sourceFingerprint: Int,
)

internal class PlaybackSessionGuard {
  private var nextGeneration = 0L
  private var current: PlaybackSessionToken? = null

  @Synchronized
  fun begin(source: String): PlaybackSessionToken {
    val token = PlaybackSessionToken(
      generation = ++nextGeneration,
      sourceFingerprint = source.hashCode(),
    )
    current = token
    return token
  }

  /**
   * Generation 0 is the pre-session state: the activity's `loadGeneration` starts there and stays
   * there until the first `loadfile` begins a session. A mutation queued in that window (a pause
   * from onStop, a menu press while prefs are still loading) carries generation 0 and must run -
   * dropping it silently loses a write mpv genuinely owes the viewer. Once any session has begun,
   * 0 is stale like every other retired generation, including after [invalidate].
   */
  @Synchronized
  fun isCurrent(generation: Long): Boolean = when {
    current != null -> current?.generation == generation
    else -> generation == 0L && nextGeneration == 0L
  }

  @Synchronized
  fun isCurrent(token: PlaybackSessionToken): Boolean = current == token

  @Synchronized
  fun invalidate() {
    current = null
  }
}
