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
