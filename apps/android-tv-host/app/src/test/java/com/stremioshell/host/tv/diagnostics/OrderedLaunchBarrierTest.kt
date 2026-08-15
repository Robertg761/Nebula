package com.stremioshell.host.tv.diagnostics

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderedLaunchBarrierTest {
  @Test
  fun `reader waits for every write submitted before the barrier`() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val barrier = OrderedLaunchBarrier(scope)
    val firstStarted = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val barrierFinished = CountDownLatch(1)
    val order = Collections.synchronizedList(mutableListOf<String>())

    try {
      barrier.launch {
        firstStarted.countDown()
        releaseFirst.await(2, TimeUnit.SECONDS)
        order += "first"
      }
      assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
      barrier.launch { order += "second" }

      val waiter = thread(name = "diagnostic-export-barrier") {
        runBlocking { barrier.awaitSubmitted() }
        barrierFinished.countDown()
      }
      assertFalse(barrierFinished.await(100, TimeUnit.MILLISECONDS))

      releaseFirst.countDown()
      assertTrue(barrierFinished.await(2, TimeUnit.SECONDS))
      waiter.join(2_000)
      assertFalse(waiter.isAlive)
      assertEquals(listOf("first", "second"), order.toList())
    } finally {
      releaseFirst.countDown()
      scope.cancel()
    }
  }
}
