package com.stremioshell.host.tv.channel

import android.content.Context
import com.stremioshell.host.tv.data.WatchStateStore
import com.stremioshell.host.tv.data.persistenceScope
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one way anything in the app asks for the TV home's Watch Next rows to be
 * brought up to date.
 *
 * Fire and forget on [persistenceScope] for the same reason the watch-state
 * writes use it: the calls come from a player that is finishing, and a
 * lifecycle-scoped publish would be cancelled before it reached the provider.
 * Nothing here ever blocks or throws back at the caller.
 */
object WatchNextSync {
  private val window = WatchNextPublishWindow()
  private val retrySlot = WatchNextRetrySlot()

  /**
   * Serialises publishes so two overlapping triggers cannot both read the same
   * "no rows yet" snapshot and insert the same program twice.
   */
  private val publishMutex = Mutex()

  /**
   * @param force skips the throttle, for the saves that end a session: a finished
   *   episode has to leave the row now, not up to a minute later.
   */
  fun publish(context: Context, force: Boolean = false) {
    launchPublish(context.applicationContext, force = force, retryOnFailure = true)
  }

  private fun launchPublish(context: Context, force: Boolean, retryOnFailure: Boolean) {
    // Claimed on the caller's thread so the throttled progress ticks - all but one of them - cost
    // a compare-and-set and nothing else.
    val claim = window.claim(force = force, nowMs = System.currentTimeMillis()) ?: return
    val appContext = context.applicationContext
    persistenceScope.launch {
      val result = runCatching { reconcileNow(appContext) }
        .getOrDefault(WatchNextPublishResult.Failed)
      // A publish that never happened must not silence the next minute of updates: the claim goes
      // back so the following trigger - which may be the one that ends the session - is let
      // through immediately. A real provider/query/mutation failure also receives one coalesced
      // delayed retry; an image with no TV provider merely releases the window.
      if (result == WatchNextPublishResult.Published) {
        window.commit(claim)
      } else {
        window.release(claim)
      }
      if (result == WatchNextPublishResult.Failed && retryOnFailure) {
        scheduleRetry(appContext)
      }
    }
  }

  /**
   * Runs one authoritative reconciliation behind the same mutex as every queued publish.
   *
   * The dismissal receiver awaits this directly after writing its tombstone. If an older publish
   * already holds the mutex, this runs after it and removes anything it reinserted. If that publish
   * has not read DataStore yet, it sees the tombstone itself. Either ordering prevents the launcher's
   * removal broadcast from becoming an immediate delete/reinsert loop.
   */
  internal suspend fun reconcileNow(context: Context): WatchNextPublishResult =
    publishMutex.withLock {
      val appContext = context.applicationContext
      val entries = WatchStateStore(appContext).watchNextEntriesOrNull()
        ?: return@withLock WatchNextPublishResult.Failed
      WatchNextPublisher(appContext).publish(WatchNextMapper.programsFor(entries))
    }

  /** One bounded retry: persistent provider failure then waits for the next ordinary trigger. */
  private fun scheduleRetry(context: Context) {
    if (!retrySlot.claim()) return
    persistenceScope.launch {
      try {
        delay(RETRY_DELAY_MS)
        launchPublish(context, force = true, retryOnFailure = false)
      } finally {
        retrySlot.release()
      }
    }
  }

  private const val RETRY_DELAY_MS = 5_000L
}

/** Coalesces overlapping failures into one delayed reconciliation. */
internal class WatchNextRetrySlot {
  private val scheduled = AtomicBoolean(false)
  fun claim(): Boolean = scheduled.compareAndSet(false, true)
  fun release() { scheduled.set(false) }
}

/**
 * The throttle's claim on the next publish slot.
 *
 * Split out from [WatchNextSync] so the ordering rules - which of two overlapping callers wins,
 * and what a failed publish leaves behind - can be tested without a Context or a TV provider.
 */
internal class WatchNextPublishWindow {
  private val lastClaim = AtomicReference<Claim?>(null)

  /** One caller's identity, not merely its timestamp, and the claim it superseded. */
  internal class Claim internal constructor(
    val atMs: Long,
    previous: Claim?,
  ) {
    internal val released = AtomicBoolean(false)
    private val predecessor = AtomicReference(previous)
    val previousMs: Long get() = predecessor.get()?.atMs ?: 0L

    internal fun previous(): Claim? = predecessor.get()
    internal fun prunePredecessors() { predecessor.set(null) }
  }

  /**
   * Takes the window, or returns null when someone else holds it.
   *
   * Read-then-write used to be two separate operations, so two progress ticks arriving together
   * both saw the same stale stamp, both passed the throttle, and both published. The claim is a
   * compare-and-set on the stamp itself: exactly one caller can move it, and the loser is
   * throttled rather than allowed through on a value that has already been superseded.
   *
   * [force] never comes back null: it is the save that ends a session, so it re-reads and claims
   * again rather than dropping the row update that the viewer is about to look for.
   */
  fun claim(force: Boolean, nowMs: Long): Claim? {
    while (true) {
      val previous = lastClaim.get()
      val validPrevious = previous.latestValid()
      if (validPrevious !== previous) {
        // A releaser may have marked the head just before another caller arrived. Help collapse it
        // before applying the throttle so even that narrow interleaving cannot spend a failed slot.
        lastClaim.compareAndSet(previous, validPrevious)
        continue
      }
      if (!force && !WatchNextThrottle.shouldPublish(previous?.atMs ?: 0L, nowMs)) return null
      val claim = Claim(nowMs, previous)
      if (lastClaim.compareAndSet(previous, claim)) return claim
      if (!force) return null
    }
  }

  /**
   * Undoes a claim whose publish came to nothing.
   *
   * A later claim that has already moved the stamp on remains the head, but this claim is marked so
   * it can never be resurrected as that later claim's predecessor. When the head itself is released,
   * every released predecessor is collapsed in one CAS; the newest successful claim, if any, wins.
   */
  fun release(claim: Claim) {
    claim.released.set(true)
    while (true) {
      val head = lastClaim.get()
      val validHead = head.latestValid()
      if (validHead === head || lastClaim.compareAndSet(head, validHead)) return
    }
  }

  /** Commits a successful claim and severs history that can no longer be restored. */
  fun commit(claim: Claim) {
    // A newer in-flight claim may still point at this one and must be able to restore it if that
    // newer publish fails. Only this claim's own older history is obsolete after its success.
    claim.prunePredecessors()
  }

  private fun Claim?.latestValid(): Claim? {
    var current = this
    while (current?.released?.get() == true) current = current.previous()
    return current
  }
}
