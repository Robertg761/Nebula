package com.stremioshell.host.tv.player

import com.stremioshell.host.tv.data.persistenceScope
import com.stremioshell.host.tv.diagnostics.NebulaDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal object PlayerPersistenceBoundary {
  suspend fun run(
    onFailure: (Exception) -> Unit,
    write: suspend () -> Unit,
  ): Boolean = try {
    write()
    true
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (error: Exception) {
    onFailure(error)
    false
  }
}

/** A single-consumer queue for user choices whose durable order must match input order. */
internal class OrderedTaskQueue(
  scope: CoroutineScope,
  private val onFailure: (Exception) -> Unit = {},
) {
  private val tasks = Channel<suspend () -> Unit>(Channel.UNLIMITED)

  init {
    scope.launch {
      for (task in tasks) {
        PlayerPersistenceBoundary.run(onFailure, task)
      }
    }
  }

  fun enqueue(task: suspend () -> Unit): Boolean = tasks.trySend(task).isSuccess

  /**
   * Enqueues work immediately, before a caller can be rescheduled, and exposes its eventual result.
   * This is what lets Settings share the player's input-order boundary while still publishing a
   * durable success/failure receipt after the write completes.
   */
  fun <T> enqueueResult(task: suspend () -> T): Deferred<Result<T>> {
    val result = CompletableDeferred<Result<T>>()
    val accepted = enqueue {
      try {
        result.complete(Result.success(task()))
      } catch (cancellation: CancellationException) {
        // A canceled individual write must not cancel the process-wide queue and strand every
        // choice behind it. Report the cancellation to this caller and keep consuming tasks.
        result.complete(Result.failure(cancellation))
        onFailure(cancellation)
      } catch (error: Exception) {
        result.complete(Result.failure(error))
        throw error
      }
    }
    if (!accepted) {
      result.complete(Result.failure(IllegalStateException("persistence queue unavailable")))
    }
    return result
  }
}

/** Process-wide so writes from a retiring player cannot overtake a new player's first choice. */
internal object PlayerPreferenceWrites {
  private val queue = OrderedTaskQueue(persistenceScope) { error ->
    NebulaDiagnostics.record(
      "player",
      "preference write failed: ${error.javaClass.simpleName}",
    )
  }

  fun enqueue(task: suspend () -> Unit) {
    if (!queue.enqueue(task)) {
      NebulaDiagnostics.record("player", "preference write dropped: queue unavailable")
    }
  }

  fun <T> enqueueResult(task: suspend () -> T): Deferred<Result<T>> = queue.enqueueResult(task)
}
