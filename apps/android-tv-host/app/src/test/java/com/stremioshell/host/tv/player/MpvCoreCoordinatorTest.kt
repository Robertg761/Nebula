package com.stremioshell.host.tv.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvCoreCoordinatorTest {
  @Test
  fun `replacement cannot acquire until matching generation is destroyed`() {
    val first = requireNotNull(MpvCoreCoordinator.acquire(100))
    assertNull(MpvCoreCoordinator.acquire(10))

    val destroyEntered = CountDownLatch(1)
    MpvCoreCoordinator.lock.lock()
    try {
      assertTrue(
        MpvCoreCoordinator.destroyAsync(first, threadName = "first-destroy-test") {
          destroyEntered.countDown()
        },
      )
      assertFalse(
        MpvCoreCoordinator.destroyAsync(first, threadName = "duplicate-destroy-test") {
          throw AssertionError("duplicate destroy must not run")
        },
      )
      assertFalse(destroyEntered.await(50, TimeUnit.MILLISECONDS))
      assertNull(MpvCoreCoordinator.acquire(10))
    } finally {
      MpvCoreCoordinator.lock.unlock()
    }

    assertTrue(destroyEntered.await(2, TimeUnit.SECONDS))
    val replacement = requireNotNull(MpvCoreCoordinator.acquire(2_000))
    assertTrue(MpvCoreCoordinator.abandon(replacement))
  }

  @Test
  fun `late old teardown cannot destroy a newer lease`() {
    val old = requireNotNull(MpvCoreCoordinator.acquire(100))
    assertTrue(MpvCoreCoordinator.abandon(old))

    val replacement = requireNotNull(MpvCoreCoordinator.acquire(100))
    assertFalse(
      MpvCoreCoordinator.destroyAsync(old, threadName = "late-old-destroy-test") {
        throw AssertionError("old lease must not destroy replacement")
      },
    )
    assertTrue(MpvCoreCoordinator.abandon(replacement))
  }

  @Test
  fun `failed thread launch rolls retirement back so destroy can be retried`() {
    val lease = requireNotNull(MpvCoreCoordinator.acquire(100))
    val destroyEntered = CountDownLatch(1)
    val failedLauncher = MpvCoreCoordinator.DestroyThreadLauncher { _, _ ->
      throw IllegalStateException("thread start failed")
    }

    assertFalse(
      MpvCoreCoordinator.destroyAsync(lease, launcher = failedLauncher) {
        throw AssertionError("failed launcher must not run destroy")
      },
    )
    assertTrue(
      MpvCoreCoordinator.destroyAsync(lease, threadName = "retry-destroy-test") {
        destroyEntered.countDown()
      },
    )
    assertTrue(destroyEntered.await(2, TimeUnit.SECONDS))

    val replacement = requireNotNull(MpvCoreCoordinator.acquire(2_000))
    assertTrue(MpvCoreCoordinator.abandon(replacement))
  }

  @Test
  fun `blocking launch-failure fallback retires the matching lease`() {
    val lease = requireNotNull(MpvCoreCoordinator.acquire(100))

    assertTrue(MpvCoreCoordinator.destroyBlocking(lease) {})

    val replacement = requireNotNull(MpvCoreCoordinator.acquire(100))
    assertTrue(MpvCoreCoordinator.abandon(replacement))
  }

  @Test
  fun `throwing blocking fallback is contained and retried by the next acquire`() {
    val lease = requireNotNull(MpvCoreCoordinator.acquire(100))
    val attempts = AtomicInteger()
    val observed = AtomicReference<Throwable?>()

    assertFalse(
      MpvCoreCoordinator.destroyBlocking(
        lease,
        onFailure = observed::set,
      ) {
        if (attempts.incrementAndGet() == 1) {
          throw IllegalStateException("native destroy failed")
        }
      },
    )
    assertTrue(observed.get() is IllegalStateException)

    val replacement = requireNotNull(MpvCoreCoordinator.acquire(2_000))
    assertEquals(2, attempts.get())
    assertTrue(MpvCoreCoordinator.abandon(replacement))
  }

  @Test
  fun `throwing async destroy is contained and retried before a replacement is acquired`() {
    val lease = requireNotNull(MpvCoreCoordinator.acquire(100))
    val expected = IllegalStateException("native destroy failed")
    val observed = AtomicReference<Throwable?>()
    val failureObserved = CountDownLatch(1)
    val attempts = AtomicInteger()

    assertTrue(
      MpvCoreCoordinator.destroyAsync(
        lease,
        threadName = "throwing-destroy-test",
        onFailure = { failure ->
          observed.set(failure)
          failureObserved.countDown()
        },
      ) {
        if (attempts.incrementAndGet() == 1) throw expected
      },
    )
    assertTrue(failureObserved.await(2, TimeUnit.SECONDS))
    assertSame(expected, observed.get())
    val replacement = requireNotNull(MpvCoreCoordinator.acquire(2_000))
    assertEquals(2, attempts.get())
    assertTrue(MpvCoreCoordinator.abandon(replacement))
  }

  @Test
  fun `only one waiter owns each released generation`() {
    val first = requireNotNull(MpvCoreCoordinator.acquire(100))
    val ready = CountDownLatch(2)
    val start = CountDownLatch(1)
    val acquired = LinkedBlockingQueue<MpvCoreCoordinator.Lease>()
    val waiters = List(2) { index ->
      Thread({
        ready.countDown()
        start.await()
        MpvCoreCoordinator.acquire(2_000)?.let(acquired::offer)
      }, "mpv-acquire-waiter-$index").also(Thread::start)
    }

    assertTrue(ready.await(2, TimeUnit.SECONDS))
    start.countDown()
    assertTrue(MpvCoreCoordinator.destroyAsync(first) {})

    val winner = requireNotNull(acquired.poll(2, TimeUnit.SECONDS))
    assertNull(acquired.poll(100, TimeUnit.MILLISECONDS))
    assertTrue(MpvCoreCoordinator.abandon(winner))

    val second = requireNotNull(acquired.poll(2, TimeUnit.SECONDS))
    assertTrue(MpvCoreCoordinator.abandon(second))
    waiters.forEach { it.join(2_000) }
    assertTrue(waiters.none(Thread::isAlive))
  }
}
