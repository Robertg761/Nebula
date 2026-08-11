package com.stremioshell.host.tv.channel

import android.content.Context
import com.stremioshell.host.tv.data.WatchStateStore
import com.stremioshell.host.tv.data.persistenceScope
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.first
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
    // Claimed on the caller's thread so the throttled progress ticks - all but one of them - cost
    // a compare-and-set and nothing else.
    val claim = window.claim(force = force, nowMs = System.currentTimeMillis()) ?: return
    val appContext = context.applicationContext
    persistenceScope.launch {
      val published = runCatching {
        publishMutex.withLock {
          val entries = WatchStateStore(appContext).entries.first()
          WatchNextPublisher(appContext).publish(WatchNextMapper.programsFor(entries))
        }
      }.getOrDefault(false)
      // A publish that never happened must not silence the next minute of updates: the claim goes
      // back so the following trigger - which may be the one that ends the session - is let
      // through immediately. The publisher reports its own no-ops (no TV provider on this image,
      // a row query that failed) as false, so those hand the window back too.
      if (!published) window.release(claim)
    }
  }
}

/**
 * The throttle's claim on the next publish slot.
 *
 * Split out from [WatchNextSync] so the ordering rules - which of two overlapping callers wins,
 * and what a failed publish leaves behind - can be tested without a Context or a TV provider.
 */
internal class WatchNextPublishWindow {
  private val lastPublishAtMs = AtomicLong(0L)

  /** One caller's reservation: the stamp it wrote, and the stamp to put back if it comes to nothing. */
  internal data class Claim(val atMs: Long, val previousMs: Long)

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
      val previous = lastPublishAtMs.get()
      if (!force && !WatchNextThrottle.shouldPublish(previous, nowMs)) return null
      if (lastPublishAtMs.compareAndSet(previous, nowMs)) return Claim(nowMs, previous)
      if (!force) return null
    }
  }

  /**
   * Undoes a claim whose publish came to nothing.
   *
   * Conditional on purpose: a later claim that has already moved the stamp on is the more recent
   * truth, and restoring an older value over it would re-open a window that caller is using.
   */
  fun release(claim: Claim) {
    lastPublishAtMs.compareAndSet(claim.atMs, claim.previousMs)
  }
}
