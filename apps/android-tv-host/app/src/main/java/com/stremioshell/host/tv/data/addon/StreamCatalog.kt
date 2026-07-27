package com.stremioshell.host.tv.data.addon

import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** One title's streams from every configured addon, folded into the single list callers use. */
data class StreamFetch(
  val merged: MergedStreams,
  /** What the addons that did not answer threw, for the caller's own error message. */
  val failures: List<Throwable>,
) {
  val streams: List<AddonStream> get() = merged.streams
}

/**
 * Asks every configured addon for a title at once and merges the answers.
 *
 * Shared rather than reimplemented per caller: the picker and the player's binge loop both need
 * exactly this, the player has no ViewModel to reach it through, and while they had separate code
 * the binge loop only ever queried addon #1 - so autoplay silently gave up on any episode whose
 * release lived on the viewer's second addon and handed it to the picker as if nothing had it.
 *
 * Parallel with a per-addon budget, because the list is only as fast as its slowest member
 * otherwise: one addon whose debrid backend is wedged would hold the caller for OkHttp's full 40s
 * call timeout while three healthy ones sat finished. A partial answer beats a complete one that
 * arrives after the viewer has given up, so a timed-out addon is reported as a failure alongside
 * the rows that did land.
 */
class StreamCatalog(
  private val client: AddonClient = AddonClient(),
  private val perAddonTimeoutMillis: Long = ADDON_STREAM_TIMEOUT_MS,
) {
  /** [season] and [episode] are both null for a movie, and must both be set for an episode. */
  suspend fun fetch(
    addonUrls: List<String>,
    imdbId: String,
    season: Int? = null,
    episode: Int? = null,
  ): StreamFetch = coroutineScope {
    // Named by label, never by URL: an addon's configured path can carry a debrid key, and both
    // exception messages and the on-screen notice get read (and logged) by someone else.
    val labels = AddonList.labels(addonUrls)
    val outcomes = addonUrls.mapIndexed { index, url ->
      async {
        // withTimeoutOrNull around the catch, not inside it: the timeout arrives as a
        // CancellationException, which catchingFailure deliberately rethrows.
        val result = withTimeoutOrNull(perAddonTimeoutMillis) {
          catchingFailure { request(url, imdbId, season, episode) }
        }
        val label = labels[index]
        val error = if (result == null) {
          SocketTimeoutException("$label timed out")
        } else {
          result.exceptionOrNull()
        }
        // Collected as return values rather than into a shared list: unlike the ViewModel's old
        // copy of this loop, these branches can run on several IO threads at once.
        Outcome(AddonFetch(label, result?.getOrNull()), error)
      }
    }.awaitAll()
    StreamFetch(
      merged = StreamMerge.merge(outcomes.map { it.fetch }),
      failures = outcomes.mapNotNull { it.error },
    )
  }

  private suspend fun request(
    url: String,
    imdbId: String,
    season: Int?,
    episode: Int?,
  ): List<AddonStream> =
    if (season != null && episode != null) {
      client.episodeStreams(url, imdbId, season, episode)
    } else {
      client.movieStreams(url, imdbId)
    }

  private data class Outcome(val fetch: AddonFetch, val error: Throwable?)

  companion object {
    /**
     * Per-addon budget for a stream request. Well under OkHttp's 40s call timeout, because that
     * ceiling is for one request and this is a race between several.
     */
    const val ADDON_STREAM_TIMEOUT_MS = 20_000L
  }
}

/**
 * [runCatching] would swallow CancellationException too, turning a cancelled fetch into a failed
 * one - and, here, turning the per-addon timeout into an unnamed error instead of a named one.
 */
private suspend fun <T> catchingFailure(block: suspend () -> T): Result<T> =
  try {
    Result.success(block())
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (error: Throwable) {
    Result.failure(error)
  }
