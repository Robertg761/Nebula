package com.stremioshell.host.tv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stremioshell.host.tv.search.SearchQuery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val SEARCH_HISTORY_STORE_NAME = "search_history"

private val Context.searchHistoryDataStore by preferencesDataStore(
  name = SEARCH_HISTORY_STORE_NAME,
  corruptionHandler = preferencesCorruptionHandler(SEARCH_HISTORY_STORE_NAME),
)

/**
 * The complete policy for the small, device-local recent-search list.
 *
 * Keeping this separate from the UI means every entry, including data left by an older build, goes
 * through the same 120-character request policy before it can be shown or submitted again.
 */
object SearchHistoryPolicy {
  /** Enough to be useful on a TV without turning the idle screen into another catalog. */
  const val MAX_ENTRIES = 4

  /** Normalizes, de-duplicates newest-first input and enforces the on-screen bound. */
  fun presented(entries: List<String>): List<String> {
    val result = ArrayList<String>(minOf(entries.size, MAX_ENTRIES))
    for (entry in entries) {
      val normalized = SearchQuery.forRequest(entry)
      if (
        normalized.isNotEmpty() &&
        result.none { it.equals(normalized, ignoreCase = true) }
      ) {
        result += normalized
        if (result.size == MAX_ENTRIES) break
      }
    }
    return result
  }

  /** Moves one deliberate submission to the front, preserving its most recent spelling/case. */
  fun recorded(entries: List<String>, query: String): List<String> {
    val normalized = SearchQuery.forRequest(query)
    if (normalized.isEmpty()) return presented(entries)
    return presented(
      buildList(entries.size + 1) {
        add(normalized)
        addAll(entries.filterNot {
          SearchQuery.forRequest(it).equals(normalized, ignoreCase = true)
        })
      },
    )
  }

  /** Removes every case variant defensively, including malformed legacy duplicates. */
  fun removed(entries: List<String>, query: String): List<String> {
    val normalized = SearchQuery.forRequest(query)
    if (normalized.isEmpty()) return presented(entries)
    return presented(entries.filterNot {
      SearchQuery.forRequest(it).equals(normalized, ignoreCase = true)
    })
  }
}

/**
 * A private Preferences DataStore for search history only.
 *
 * Nothing here leaves the device, joins settings/pairing payloads, or reaches diagnostics. The app
 * manifest also disables Android backup, so these viewer queries remain local to this install.
 */
class SearchHistoryStore internal constructor(
  private val store: DataStore<Preferences>,
) {
  constructor(context: Context) : this(context.searchHistoryDataStore)

  private val json = Json
  private val data = store.recoveringData(SEARCH_HISTORY_STORE_NAME)

  val entries: Flow<List<String>> = data
    .map { it[KEY_ENTRIES] }
    .distinctUntilChanged()
    .map(::decode)
    .map(SearchHistoryPolicy::presented)
    .distinctUntilChanged()

  /** Records only a complete submitted request. Blank/control-only values do not touch storage. */
  suspend fun record(query: String) {
    val normalized = SearchQuery.forRequest(query)
    if (normalized.isEmpty()) return
    store.edit { preferences ->
      val next = SearchHistoryPolicy.recorded(decode(preferences[KEY_ENTRIES]), normalized)
      preferences[KEY_ENTRIES] = json.encodeToString(next)
    }
  }

  suspend fun remove(query: String) {
    val normalized = SearchQuery.forRequest(query)
    if (normalized.isEmpty()) return
    store.edit { preferences ->
      val next = SearchHistoryPolicy.removed(decode(preferences[KEY_ENTRIES]), normalized)
      if (next.isEmpty()) preferences.remove(KEY_ENTRIES)
      else preferences[KEY_ENTRIES] = json.encodeToString(next)
    }
  }

  suspend fun clear() {
    store.edit { it.remove(KEY_ENTRIES) }
  }

  /** Search history is disposable; malformed older bytes become an empty list, never a crash. */
  private fun decode(raw: String?): List<String> = when {
    raw.isNullOrBlank() -> emptyList()
    else -> runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
  }

  private companion object {
    val KEY_ENTRIES = stringPreferencesKey("recent_searches")
  }
}

/**
 * A single-consumer boundary for history presses.
 *
 * DataStore makes each edit atomic, but independent coroutines can reach it in a different order
 * from the remote presses that launched them. Enqueueing synchronously preserves input order even
 * when an earlier filesystem operation is slower than a later one.
 */
internal class SearchHistoryWriteQueue(scope: CoroutineScope) {
  private data class Write(
    val operation: suspend () -> Unit,
    val result: CompletableDeferred<Result<Unit>>,
  )

  private val writes = Channel<Write>(Channel.UNLIMITED)

  init {
    scope.launch {
      for (write in writes) {
        write.result.complete(runCatching { write.operation() })
      }
    }
  }

  fun enqueue(operation: suspend () -> Unit): Deferred<Result<Unit>> {
    val result = CompletableDeferred<Result<Unit>>()
    if (writes.trySend(Write(operation, result)).isFailure) {
      result.complete(Result.failure(IllegalStateException("search history queue unavailable")))
    }
    return result
  }
}

/** Process-wide so a retiring Activity cannot let its last press overtake the next Activity's. */
internal object SearchHistoryWrites {
  private val queue = SearchHistoryWriteQueue(persistenceScope)

  fun enqueue(operation: suspend () -> Unit) {
    queue.enqueue(operation)
  }
}
