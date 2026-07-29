package com.stremioshell.host.tv.data

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen

private const val PERSISTENCE_TAG = "TvPersistence"

/**
 * Preferences protobuf corruption should reset that store to its documented defaults rather than
 * crash every collector on every launch. Write failures are intentionally not swallowed: callers
 * that promise pairing/settings success must still see and report them.
 */
internal fun preferencesCorruptionHandler(
  storeName: String,
  log: (String, Throwable) -> Unit = { message, error ->
    Log.e(PERSISTENCE_TAG, message, error)
  },
): ReplaceFileCorruptionHandler<Preferences> =
  ReplaceFileCorruptionHandler { error ->
    log("$storeName preferences were corrupt; resetting", error)
    emptyPreferences()
  }

/**
 * A transient filesystem read error should not take down Home or the player. DataStore's next
 * collection gets another chance; this collection receives safe defaults. Non-I/O programmer and
 * cancellation failures still propagate.
 */
internal fun DataStore<Preferences>.recoveringData(
  storeName: String,
  log: (String, Throwable) -> Unit = { message, error ->
    Log.e(PERSISTENCE_TAG, message, error)
  },
): Flow<Preferences> =
  data.retryWhen { error, attempt ->
    if (error !is IOException) return@retryWhen false
    log("$storeName preferences could not be read; using defaults", error)
    emit(emptyPreferences())
    // Eager StateFlow collectors otherwise complete forever after one transient read failure.
    // Re-subscribe with a bounded backoff while one-shot reads can still return the safe default.
    delay((250L shl attempt.coerceAtMost(4).toInt()).coerceAtMost(4_000L))
    true
  }

/** Timestamp ordering used to stop a delayed persistence coroutine undoing newer watch state. */
internal object PersistenceOrdering {
  fun accepts(existingUpdatedAtMs: Long?, incomingUpdatedAtMs: Long): Boolean =
    existingUpdatedAtMs == null || incomingUpdatedAtMs >= existingUpdatedAtMs

  /** A remove wins a timestamp tie so an in-flight save cannot immediately resurrect the row. */
  fun acceptsAfterRemoval(removedAtMs: Long?, incomingUpdatedAtMs: Long): Boolean =
    removedAtMs == null || incomingUpdatedAtMs > removedAtMs

  /** Delayed duplicate removals must not weaken a newer deletion marker. */
  fun latestRemoval(existingRemovedAtMs: Long?, incomingRemovedAtMs: Long): Long =
    maxOf(existingRemovedAtMs ?: Long.MIN_VALUE, incomingRemovedAtMs)

  /** Monotonic counters must not move backwards when two queued writes finish out of order. */
  fun monotonicCounter(existing: Int?, incoming: Int): Int =
    maxOf(existing ?: 0, incoming.coerceAtLeast(0))
}
