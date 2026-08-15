package com.stremioshell.host.tv.data

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

private const val PERSISTENCE_TAG = "TvPersistence"

/** One tag for everything that writes to, or declines to write to, the preferences file. */
internal fun logPersistence(message: String) {
  Log.w(PERSISTENCE_TAG, message)
}

/** The same tag for the failures that come with a throwable worth keeping. */
internal fun logPersistenceFailure(message: String, error: Throwable) {
  Log.e(PERSISTENCE_TAG, message, error)
}

/** Logs an unhandled detached write without turning cancellation into an application failure. */
internal fun reportPersistenceScopeFailure(
  error: Throwable,
  log: (String, Throwable) -> Unit = ::logPersistenceFailure,
) {
  if (error is CancellationException) return
  log("Detached persistence work failed", error)
}

/**
 * Preferences protobuf corruption should reset that store to its documented defaults rather than
 * crash every collector on every launch. Write failures are intentionally not swallowed: callers
 * that promise pairing/settings success must still see and report them.
 */
internal fun preferencesCorruptionHandler(
  storeName: String,
  log: (String, Throwable) -> Unit = ::logPersistenceFailure,
): ReplaceFileCorruptionHandler<Preferences> =
  ReplaceFileCorruptionHandler { error ->
    log("$storeName preferences were corrupt; resetting", error)
    emptyPreferences()
  }

/**
 * A preferences read, and whether it is the real thing.
 *
 * [degraded] is the distinction that [recoveringData] alone cannot make: the empty preferences it
 * emits after an I/O failure look exactly like a first run. For a rail that is fine - both mean
 * "nothing to draw". For anything that treats absence as an instruction it is not: a Save that
 * reads a degraded snapshot as "the viewer has no TMDB key" goes on to write that emptiness back
 * over a working one.
 */
internal data class PreferencesSnapshot(
  val preferences: Preferences,
  val degraded: Boolean,
)

/**
 * A transient filesystem read error should not take down Home or the player. DataStore's next
 * collection gets another chance; this collection receives safe defaults, tagged as such. Non-I/O
 * programmer and cancellation failures still propagate.
 *
 * The backoff grows all the way out rather than settling at a few seconds. A read failure that has
 * survived a dozen retries is not transient - the storage is gone, or the file is locked by
 * something that is not going to let go - and re-reading it every four seconds forever costs
 * wakeups and I/O on a device that is often left on for days, for an answer that does not change.
 * The log follows the same shape: the first few failures are worth a line each, after which the
 * retry continues quietly rather than filling Logcat with the same sentence for the rest of the
 * session.
 */
internal fun DataStore<Preferences>.recoveringSnapshots(
  storeName: String,
  log: (String, Throwable) -> Unit = ::logPersistenceFailure,
): Flow<PreferencesSnapshot> =
  data.map { PreferencesSnapshot(it, degraded = false) }
    .retryWhen { error, attempt ->
      if (error !is IOException) return@retryWhen false
      val backoffMillis = preferencesReadRetryBackoffMillis(attempt)
      if (attempt < MAX_LOGGED_READ_FAILURES) {
        log(
          "$storeName preferences could not be read (attempt ${attempt + 1}); using defaults, " +
            "retrying in ${backoffMillis}ms",
          error,
        )
      }
      emit(PreferencesSnapshot(emptyPreferences(), degraded = true))
      // Eager StateFlow collectors otherwise complete forever after one transient read failure.
      // Re-subscribe with a bounded backoff while one-shot reads can still return the safe default.
      delay(backoffMillis)
      true
    }

/** The [PreferencesSnapshot.preferences] half, for the readers that cannot act on the other one. */
internal fun DataStore<Preferences>.recoveringData(
  storeName: String,
  log: (String, Throwable) -> Unit = ::logPersistenceFailure,
): Flow<Preferences> = recoveringSnapshots(storeName, log).map { it.preferences }

/** 250ms doubling to a five-minute ceiling: prompt about a hiccup, quiet about a dead disk. */
internal fun preferencesReadRetryBackoffMillis(attempt: Long): Long =
  (250L shl attempt.coerceAtMost(MAX_BACKOFF_SHIFT).toInt())
    .coerceAtMost(MAX_READ_RETRY_BACKOFF_MILLIS)

/** 250ms << 11 already exceeds the ceiling; shifting further would only risk an overflow. */
private const val MAX_BACKOFF_SHIFT = 11L
private const val MAX_READ_RETRY_BACKOFF_MILLIS = 5L * 60 * 1_000
private const val MAX_LOGGED_READ_FAILURES = 4L

/** Logical persistence ordering kept separate from user-visible wall-clock timestamps. */
internal object PersistenceOrdering {
  data class Allocation(val order: Long, val sessionBase: Long, val counter: Long)

  /**
   * Allocates after both the persisted counter and any order observed in migrated/restored records.
   * A missing counter is therefore safe, and an NTP/manual clock rollback cannot make a later
   * mutation look older. Long.MAX_VALUE is a harmless saturation point centuries beyond this cap.
   */
  fun nextMutationOrder(storedCounter: Long?, observedOrder: Long = 0L): Long {
    val current = maxOf(storedCounter ?: 0L, observedOrder, 0L)
    return if (current == Long.MAX_VALUE) Long.MAX_VALUE else current + 1L
  }

  /** Rebase this process' action-time token above every order already present on disk. */
  fun allocate(
    storedSession: String?,
    storedSessionBase: Long?,
    storedCounter: Long?,
    observedOrder: Long,
    token: PersistenceMutationToken,
  ): Allocation {
    val current = maxOf(storedCounter ?: 0L, observedOrder, 0L)
    val base = if (storedSession == token.sessionId && storedSessionBase != null) {
      storedSessionBase.coerceAtLeast(0L)
    } else {
      current
    }
    val order = saturatingAdd(base, token.sequence.coerceAtLeast(1L))
    return Allocation(order, base, maxOf(current, order))
  }

  fun acceptsMutation(existingOrder: Long?, incomingOrder: Long): Boolean =
    existingOrder == null || incomingOrder >= existingOrder

  /** A removal wins a tie, though action-time tokens make ties exceptional. */
  fun acceptsAfterRemoval(removedOrder: Long?, incomingOrder: Long): Boolean =
    removedOrder == null || incomingOrder > removedOrder

  private fun saturatingAdd(first: Long, second: Long): Long =
    if (first > Long.MAX_VALUE - second) Long.MAX_VALUE else first + second

  /**
   * New-format records sort by logical order. A touched new-format record is newer than untouched
   * legacy data; two legacy records retain their old timestamp ordering until they are mutated.
   */
  fun compareNewest(
    firstOrder: Long,
    firstTimestampMs: Long,
    secondOrder: Long,
    secondTimestampMs: Long,
  ): Int = when {
    firstOrder > 0L && secondOrder > 0L -> secondOrder.compareTo(firstOrder)
    firstOrder > 0L -> -1
    secondOrder > 0L -> 1
    else -> secondTimestampMs.compareTo(firstTimestampMs)
  }

  /** Monotonic counters must not move backwards when two queued writes finish out of order. */
  fun monotonicCounter(existing: Int?, incoming: Int): Int =
    maxOf(existing ?: 0, incoming.coerceAtLeast(0))
}

/** One mutation's order within this app process, captured before persistence work is launched. */
data class PersistenceMutationToken internal constructor(
  val sessionId: String,
  val sequence: Long,
) {
  val assigned: Boolean get() = sessionId.isNotEmpty() && sequence > 0L

  companion object {
    val Unassigned = PersistenceMutationToken("", 0L)
  }
}

/**
 * Process-local source tokens are rebased against a persisted counter in each store. UUID identity
 * distinguishes process restarts; the sequence distinguishes actions even when the wall clock moves
 * backwards, and is captured in the data object's constructor before a coroutine can be delayed.
 */
object PersistenceMutationClock {
  private val sessionId = UUID.randomUUID().toString()
  private val sequence = AtomicLong(0L)

  fun next(): PersistenceMutationToken {
    val next = sequence.updateAndGet { current ->
      if (current == Long.MAX_VALUE) Long.MAX_VALUE else current + 1L
    }
    return PersistenceMutationToken(sessionId, next)
  }
}
