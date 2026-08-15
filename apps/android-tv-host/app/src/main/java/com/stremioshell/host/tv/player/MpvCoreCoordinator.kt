package com.stremioshell.host.tv.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * Process-wide ownership gate for libmpv's singleton native core.
 *
 * An activity owns a generation lease from before `MPVLib.create()` until its matching destroy
 * completes. A replacement therefore cannot slip through a "no destroy pending" snapshot while
 * the old activity still owns a live core, and a late old lifecycle callback can never retire a
 * newer generation.
 */
internal object MpvCoreCoordinator {
  /** Fair so a destroy already waiting behind a property read wins before a new player creation. */
  val lock = ReentrantLock(true)

  /**
   * Injectable boundary around thread construction and start.
   *
   * Tests can fail or delay this handoff without exhausting real process threads. A launcher that
   * throws after scheduling [task] is also safe: that attempt either claims the lease before the
   * rollback or becomes inert, so it cannot race a later retry.
   */
  internal fun interface DestroyThreadLauncher {
    fun launch(threadName: String, task: Runnable)
  }

  private val defaultDestroyThreadLauncher = DestroyThreadLauncher { threadName, task ->
    Thread(task, threadName).start()
  }

  internal class Lease internal constructor(
    internal val generation: Long,
    internal val released: CountDownLatch = CountDownLatch(1),
  ) {
    /** Guarded by [stateMonitor]. */
    internal var retiring: Boolean = false

    /** Identifies one launch attempt so a delayed failed launcher cannot run after a retry. */
    internal var retirementAttempt: Any? = null

    /** A throwing native destroy is retried before the next generation can be claimed. */
    internal var pendingDestroy: (() -> Unit)? = null

    internal var pendingFailureHandler: ((Throwable) -> Unit)? = null
  }

  private val stateMonitor = Object()
  private val generations = AtomicLong()

  @Volatile
  private var owner: Lease? = null

  /**
   * Waits for the current generation to retire, then claims the next one.
   *
   * A still-active old activity may not have reached `onDestroy()` yet; ownership, rather than a
   * nullable pending-destroy latch, keeps the replacement from creating a second singleton core.
   */
  fun acquire(timeoutMs: Long): Lease? {
    val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
    val deadline = System.nanoTime() + timeoutNanos
    while (true) {
      var retry: (() -> Unit)? = null
      var retryFailureHandler: ((Throwable) -> Unit)? = null
      var retryLease: Lease? = null
      val waitFor = synchronized(stateMonitor) {
        val current = owner
        if (current == null) {
          return Lease(generations.incrementAndGet()).also { owner = it }
        }
        if (!current.retiring && current.pendingDestroy != null) {
          retryLease = current
          retry = current.pendingDestroy
          retryFailureHandler = current.pendingFailureHandler
        }
        current.released
      }
      val leaseToRetry = retryLease
      val destroyToRetry = retry
      if (leaseToRetry != null && destroyToRetry != null) {
        destroyAsync(
          lease = leaseToRetry,
          threadName = "mpv-destroy-retry-${leaseToRetry.generation}",
          onFailure = retryFailureHandler ?: {},
          destroy = destroyToRetry,
        )
      }
      val remaining = deadline - System.nanoTime()
      if (remaining <= 0L) return null
      try {
        if (!waitFor.await(remaining, TimeUnit.NANOSECONDS)) return null
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return null
      }
    }
  }

  /** Releases a lease that never reached native-core creation. */
  fun abandon(lease: Lease): Boolean = synchronized(stateMonitor) {
    if (owner !== lease || lease.retiring) return@synchronized false
    owner = null
    lease.released.countDown()
    true
  }

  /**
   * Retires exactly [lease]'s generation on a dedicated thread.
   *
   * `MPVLib.destroy()` is vendor/native code with no useful upper latency bound. Running it from
   * Activity.onDestroy would make BACK capable of blocking the main thread indefinitely.
   */
  fun destroyAsync(
    lease: Lease,
    threadName: String = "mpv-destroy-${lease.generation}",
    launcher: DestroyThreadLauncher = defaultDestroyThreadLauncher,
    onFailure: (Throwable) -> Unit = {},
    destroy: () -> Unit,
  ): Boolean {
    val attempt = Any()
    synchronized(stateMonitor) {
      if (owner !== lease || lease.retiring) return false
      lease.retiring = true
      lease.retirementAttempt = attempt
      lease.pendingDestroy = null
      lease.pendingFailureHandler = null
    }

    /** Guarded by [stateMonitor], including the launch-failure check below. */
    var executionStarted = false
    val task = Runnable {
      val ownsAttempt = synchronized(stateMonitor) {
        if (
          owner !== lease ||
          !lease.retiring ||
          lease.retirementAttempt !== attempt ||
          executionStarted
        ) {
          false
        } else {
          executionStarted = true
          true
        }
      }
      if (!ownsAttempt) return@Runnable

      lock.lock()
      var destroyed = false
      var destroyFailure: Throwable? = null
      try {
        try {
          destroy()
          destroyed = true
        } catch (error: Throwable) {
          destroyFailure = error
        }
      } finally {
        lock.unlock()
        synchronized(stateMonitor) {
          if (lease.retirementAttempt === attempt) {
            lease.retiring = false
            lease.retirementAttempt = null
          }
          // A throwing native destroy is not proof that the singleton is gone. Retain ownership
          // so a replacement cannot create over a possibly-live core; the same lease may be
          // retried deliberately.
          if (destroyed && owner === lease) {
            owner = null
            lease.released.countDown()
          } else if (!destroyed && owner === lease) {
            lease.pendingDestroy = destroy
            lease.pendingFailureHandler = onFailure
          }
        }
      }
      destroyFailure?.let { failure -> runCatching { onFailure(failure) } }
    }

    return try {
      launcher.launch(threadName, task)
      true
    } catch (_: Throwable) {
      synchronized(stateMonitor) {
        if (executionStarted) {
          // The task owns this attempt and will release the lease in its finally block.
          true
        } else {
          if (owner === lease && lease.retirementAttempt === attempt) {
            lease.retiring = false
            lease.retirementAttempt = null
            lease.pendingDestroy = destroy
            lease.pendingFailureHandler = onFailure
          }
          false
        }
      }
    }
  }

  /**
   * Last-resort retirement when the process cannot start a destroy thread.
   *
   * Normal Activity teardown always uses [destroyAsync]. This exists only for its launch-failure
   * path: retaining an undestroyed singleton would poison every later player, while inventing a
   * new unmanaged thread cannot improve a process-level thread-creation failure. A thrown destroy
   * deliberately retains the lease, matching [destroyAsync].
   */
  fun destroyBlocking(
    lease: Lease,
    onFailure: (Throwable) -> Unit = {},
    destroy: () -> Unit,
  ): Boolean {
    val attempt = Any()
    synchronized(stateMonitor) {
      if (owner !== lease || lease.retiring) return false
      lease.retiring = true
      lease.retirementAttempt = attempt
      lease.pendingDestroy = null
      lease.pendingFailureHandler = null
    }

    lock.lock()
    var destroyed = false
    var destroyFailure: Throwable? = null
    try {
      try {
        destroy()
        destroyed = true
      } catch (error: Throwable) {
        destroyFailure = error
      }
    } finally {
      lock.unlock()
      synchronized(stateMonitor) {
        if (lease.retirementAttempt === attempt) {
          lease.retiring = false
          lease.retirementAttempt = null
        }
        if (destroyed && owner === lease) {
          owner = null
          lease.released.countDown()
        } else if (!destroyed && owner === lease) {
          lease.pendingDestroy = destroy
          lease.pendingFailureHandler = onFailure
        }
      }
    }
    destroyFailure?.let { failure -> runCatching { onFailure(failure) } }
    return destroyed
  }
}
