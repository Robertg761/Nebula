package com.stremioshell.host.tv.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataStoreSafetyTest {
  @Test
  fun `protobuf corruption is replaced by empty preferences and recorded`() = runBlocking {
    val logged = mutableListOf<String>()
    val handler = preferencesCorruptionHandler("test-store") { message, _ -> logged += message }

    val replacement = handler.handleCorruption(CorruptionException("broken", null))

    assertTrue(replacement.asMap().isEmpty())
    assertTrue(logged.single().contains("test-store"))
  }

  @Test
  fun `io read failure emits defaults while retaining provenance`() = runBlocking {
    val logged = mutableListOf<String>()
    val recovered = FailingDataStore(IOException("disk unavailable"))
      .recoveringData("test-store") { message, _ -> logged += message }
      .first()

    assertTrue(recovered.asMap().isEmpty())
    assertTrue(logged.single().contains("test-store"))
  }

  @Test
  fun `long lived collector retries after an io read failure`() = runBlocking {
    var collections = 0
    val store = object : DataStore<Preferences> {
      override val data: Flow<Preferences> = flow {
        collections++
        if (collections == 1) throw IOException("temporary")
        emit(androidx.datastore.preferences.core.emptyPreferences())
      }

      override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
      ): Preferences = throw UnsupportedOperationException()
    }

    val emissions = store.recoveringData("test-store", log = { _, _ -> }).take(2).toList()

    assertEquals(2, emissions.size)
    assertEquals(2, collections)
  }

  @Test
  fun `a failed read is tagged so a caller can tell it from a first run`() = runBlocking {
    val degraded = FailingDataStore(IOException("disk unavailable"))
      .recoveringSnapshots("test-store") { _, _ -> }
      .first()

    // The preferences are the same empty ones a brand-new TV has. Only the tag separates "nothing
    // configured" from "nothing readable", and a Save that confuses the two erases credentials.
    assertTrue(degraded.preferences.asMap().isEmpty())
    assertTrue(degraded.degraded)

    val healthy = object : DataStore<Preferences> {
      override val data: Flow<Preferences> = flow {
        emit(androidx.datastore.preferences.core.emptyPreferences())
      }

      override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
      ): Preferences = throw UnsupportedOperationException()
    }.recoveringSnapshots("test-store") { _, _ -> }.first()

    assertTrue(!healthy.degraded)
  }

  @Test
  fun `the read retry backoff grows past a few seconds and then stops growing`() {
    // It used to settle at a four-second floor and stay there for the life of the process, with a
    // Log.e on every attempt - a wakeup every four seconds, for days, for an answer that is not
    // going to change once the storage is genuinely gone.
    assertEquals(250L, preferencesReadRetryBackoffMillis(0))
    assertEquals(4_000L, preferencesReadRetryBackoffMillis(4))
    assertEquals(256_000L, preferencesReadRetryBackoffMillis(10))
    assertEquals(300_000L, preferencesReadRetryBackoffMillis(11))
    // Bounded rather than shifted into nonsense, however long the failure lasts.
    assertEquals(300_000L, preferencesReadRetryBackoffMillis(5_000))
  }

  @Test
  fun `non io read failure still propagates`() {
    val failure = IllegalStateException("programmer error")

    val thrown = runCatching {
      runBlocking {
        FailingDataStore(failure)
          .recoveringData("test-store", log = { _, _ -> })
          .first()
      }
    }.exceptionOrNull()

    assertEquals(failure, thrown)
  }

  private class FailingDataStore(
    private val failure: Throwable,
  ) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw failure }

    override suspend fun updateData(
      transform: suspend (t: Preferences) -> Preferences,
    ): Preferences = throw UnsupportedOperationException()
  }
}
