package com.stremioshell.host.tv

import com.stremioshell.host.tv.channel.WatchNextTarget
import com.stremioshell.host.tv.search.LaunchRequest
import com.stremioshell.host.tv.search.SearchLaunch

/** A launch destination that has been parsed but not yet consumed by [ui.TvApp]. */
internal sealed interface PendingLaunch {
  data class WatchNext(val target: WatchNextTarget) : PendingLaunch
  data class Search(val launch: SearchLaunch) : PendingLaunch
  data object Settings : PendingLaunch
}

/** Identity stays distinct even when two consecutive requests carry equal payloads. */
internal data class PendingLaunchEvent(val id: Long, val request: PendingLaunch)

internal sealed interface PendingLaunchSeed {
  data class Fresh(val request: PendingLaunch) : PendingLaunchSeed
  data class Restored(val event: PendingLaunchEvent) : PendingLaunchSeed
}

/** Owns monotonic request identity and rejects completion from an older Compose effect. */
internal class PendingLaunchTracker {
  private var nextId = 0L

  var current: PendingLaunchEvent? = null
    private set

  fun enqueue(request: PendingLaunch): PendingLaunchEvent {
    val event = PendingLaunchEvent(++nextId, request)
    current = event
    return event
  }

  fun restore(event: PendingLaunchEvent) {
    nextId = maxOf(nextId, event.id)
    current = event
  }

  fun consume(id: Long): PendingLaunch? {
    val event = current?.takeIf { it.id == id } ?: return null
    current = null
    return event.request
  }
}

/**
 * Separates a fresh launch from restoration of an Activity Android retained in the task.
 *
 * Android restores both the saved Compose back stack and the Activity's last Intent. Re-parsing that
 * Intent after restoration would make an already-consumed Watch Next/search/settings request root
 * the restored stack again. Only a request explicitly saved as still pending is replayed.
 */
internal object PendingLaunchPolicy {
  fun from(request: LaunchRequest): PendingLaunch? = when (request) {
    is LaunchRequest.OpenWatchNext -> PendingLaunch.WatchNext(request.target)
    is LaunchRequest.OpenSearch -> PendingLaunch.Search(request.launch)
    LaunchRequest.OpenSettings -> PendingLaunch.Settings
    LaunchRequest.Launch -> null
  }

  fun onCreate(
    restoringState: Boolean,
    restoredPending: PendingLaunchEvent?,
    retainedIntentRequest: LaunchRequest,
  ): PendingLaunchSeed? = if (restoringState) {
    restoredPending?.let { PendingLaunchSeed.Restored(it) }
  } else {
    from(retainedIntentRequest)?.let { PendingLaunchSeed.Fresh(it) }
  }
}
