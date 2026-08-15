package com.stremioshell.host.tv.player

import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderedTaskQueueTest {
  @Test
  fun `a later user choice cannot complete before an earlier slow write`() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val firstStarted = CompletableDeferred<Unit>()
    val releaseFirst = CompletableDeferred<Unit>()
    val bothFinished = CompletableDeferred<Unit>()
    val completed = Collections.synchronizedList(mutableListOf<String>())
    val queue = OrderedTaskQueue(scope)

    assertTrue(queue.enqueue {
      firstStarted.complete(Unit)
      releaseFirst.await()
      completed += "first"
    })
    assertTrue(queue.enqueue {
      completed += "second"
      bothFinished.complete(Unit)
    })

    withTimeout(2_000) { firstStarted.await() }
    assertEquals(emptyList<String>(), completed)
    releaseFirst.complete(Unit)
    withTimeout(2_000) { bothFinished.await() }
    assertEquals(listOf("first", "second"), completed)
    scope.cancel()
  }

  @Test
  fun `one failed write is reported without stopping later choices`() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val failure = CompletableDeferred<Exception>()
    val finished = CompletableDeferred<Unit>()
    val queue = OrderedTaskQueue(scope, onFailure = { failure.complete(it) })

    queue.enqueue { throw IllegalStateException("disk full") }
    queue.enqueue { finished.complete(Unit) }

    assertEquals("disk full", withTimeout(2_000) { failure.await() }.message)
    withTimeout(2_000) { finished.await() }
    scope.cancel()
  }

  @Test
  fun `awaitable settings write shares order with an older player write`() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val firstStarted = CompletableDeferred<Unit>()
    val releaseFirst = CompletableDeferred<Unit>()
    val completed = Collections.synchronizedList(mutableListOf<String>())
    val queue = OrderedTaskQueue(scope)

    queue.enqueue {
      firstStarted.complete(Unit)
      releaseFirst.await()
      completed += "player"
    }
    val settingsResult = queue.enqueueResult {
      completed += "settings"
      "durable"
    }

    withTimeout(2_000) { firstStarted.await() }
    assertEquals(emptyList<String>(), completed)
    releaseFirst.complete(Unit)
    assertEquals("durable", withTimeout(2_000) { settingsResult.await() }.getOrThrow())
    assertEquals(listOf("player", "settings"), completed)
    scope.cancel()
  }

  @Test
  fun `persistence boundary contains a disk failure`() = runBlocking {
    var observed: Exception? = null

    val succeeded = PlayerPersistenceBoundary.run(onFailure = { observed = it }) {
      throw java.io.IOException("disk unavailable")
    }

    assertEquals(false, succeeded)
    assertEquals("disk unavailable", observed?.message)
  }
}
