package com.stremioshell.host.tv.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHistoryStoreTest {
  @Test
  fun `records normalized submissions newest first and deduplicates case insensitively`() =
    runBlocking {
      val store = SearchHistoryStore(MemoryPreferencesStore())

      store.record("  Dune\nPart Two  ")
      store.record("Alien")
      store.record("dune part two")

      assertEquals(listOf("dune part two", "Alien"), store.entries.first())
    }

  @Test
  fun `history is capped to the four newest submissions`() = runBlocking {
    val store = SearchHistoryStore(MemoryPreferencesStore())

    (1..8).forEach { store.record("Title $it") }

    assertEquals(
      listOf("Title 8", "Title 7", "Title 6", "Title 5"),
      store.entries.first(),
    )
  }

  @Test
  fun `blank submission does not touch storage`() = runBlocking {
    val preferences = MemoryPreferencesStore()
    val store = SearchHistoryStore(preferences)

    store.record(" \n\t\u0000 ")

    assertEquals(0, preferences.mutations)
    assertEquals(emptyList<String>(), store.entries.first())
  }

  @Test
  fun `remove is case insensitive and clear is explicit`() = runBlocking {
    val store = SearchHistoryStore(MemoryPreferencesStore())
    store.record("Alien")
    store.record("Arrival")

    store.remove("aLiEn")
    assertEquals(listOf("Arrival"), store.entries.first())

    store.clear()
    assertEquals(emptyList<String>(), store.entries.first())
  }

  @Test
  fun `presentation sanitizes old entries before they return to the field`() {
    val overlong = "x".repeat(200)

    assertEquals(
      listOf("Blade Runner", "x".repeat(120)),
      SearchHistoryPolicy.presented(
        listOf("  Blade\nRunner ", "blade runner", "\u0000", overlong),
      ),
    )
  }

  @Test
  fun `removal compares normalized legacy values`() {
    assertEquals(
      listOf("Arrival"),
      SearchHistoryPolicy.removed(listOf("  ALIEN\n", "Arrival"), "alien"),
    )
  }

  @Test
  fun `a delayed clear cannot be overtaken by a later submission`() = runBlocking {
    val store = SearchHistoryStore(MemoryPreferencesStore())
    store.record("Old query")
    val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val queue = SearchHistoryWriteQueue(queueScope)
    val clearStarted = CompletableDeferred<Unit>()
    val releaseClear = CompletableDeferred<Unit>()

    try {
      val clear = queue.enqueue {
        clearStarted.complete(Unit)
        releaseClear.await()
        store.clear()
      }
      clearStarted.await()
      val submit = queue.enqueue { store.record("New query") }

      // The later write is ready to run, but the FIFO holds it behind the earlier remote press.
      assertFalse(submit.isCompleted)
      releaseClear.complete(Unit)

      assertTrue(clear.await().isSuccess)
      assertTrue(submit.await().isSuccess)
      assertEquals(listOf("New query"), store.entries.first())
    } finally {
      queueScope.cancel()
    }
  }

  private class MemoryPreferencesStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    var mutations = 0
      private set

    override val data: Flow<Preferences> = state

    override suspend fun updateData(
      transform: suspend (t: Preferences) -> Preferences,
    ): Preferences {
      mutations++
      return transform(state.value).also { state.value = it }
    }
  }
}
