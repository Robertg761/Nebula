package com.stremioshell.host.tv.channel

/**
 * How often the Watch Next rows may be rewritten.
 *
 * The player saves progress every 30 seconds and the provider write is a handful
 * of IPC round trips, so an unthrottled publish would spend that on rows whose
 * only change is a progress bar nobody is looking at while the video is on
 * screen. The saves that actually matter - stopped, finished - bypass this.
 */
object WatchNextThrottle {
  const val MIN_INTERVAL_MS = 60_000L

  fun shouldPublish(lastPublishAtMs: Long, nowMs: Long): Boolean {
    if (lastPublishAtMs <= 0L) return true
    val elapsedMs = nowMs - lastPublishAtMs
    // A TV with no battery-backed clock sets the time from the network some way
    // into its boot; without this, a backwards jump locks publishing out until
    // real time catches up to the stale stamp.
    if (elapsedMs < 0L) return true
    return elapsedMs >= MIN_INTERVAL_MS
  }
}
