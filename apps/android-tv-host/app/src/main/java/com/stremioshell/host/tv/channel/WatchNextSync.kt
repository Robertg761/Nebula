package com.stremioshell.host.tv.channel

import android.content.Context
import com.stremioshell.host.tv.data.WatchStateStore
import com.stremioshell.host.tv.data.persistenceScope
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one way anything in the app asks for the TV home's Watch Next rows to be
 * brought up to date.
 *
 * Fire and forget on [persistenceScope] for the same reason the watch-state
 * writes use it: the calls come from a player that is finishing, and a
 * lifecycle-scoped publish would be cancelled before it reached the provider.
 * Nothing here ever blocks or throws back at the caller.
 */
object WatchNextSync {
  private val lastPublishAtMs = AtomicLong(0L)

  /**
   * Serialises publishes so two overlapping triggers cannot both read the same
   * "no rows yet" snapshot and insert the same program twice.
   */
  private val publishMutex = Mutex()

  /**
   * @param force skips the throttle, for the saves that end a session: a finished
   *   episode has to leave the row now, not up to a minute later.
   */
  fun publish(context: Context, force: Boolean = false) {
    val nowMs = System.currentTimeMillis()
    if (!force && !WatchNextThrottle.shouldPublish(lastPublishAtMs.get(), nowMs)) return
    lastPublishAtMs.set(nowMs)
    val appContext = context.applicationContext
    persistenceScope.launch {
      runCatching {
        publishMutex.withLock {
          val entries = WatchStateStore(appContext).entries.first()
          WatchNextPublisher(appContext).publish(WatchNextMapper.programsFor(entries))
        }
      }
    }
  }
}
