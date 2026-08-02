package com.stremioshell.host.tv.data

/**
 * Decides when data that is already on screen is old enough to refresh behind the user's back.
 *
 * Home is revisited far more often than TMDB's catalogs change, so rails are kept until they age
 * out rather than refetched on every visit; the refresh then happens in place, with no spinner
 * over content the user is already browsing.
 */
class StalenessPolicy(private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS) {
  init {
    require(maxAgeMillis > 0) { "maxAgeMillis must be positive" }
  }

  /** @param loadedAtMillis when the data was last loaded, or null if it never fully loaded. */
  fun isStale(loadedAtMillis: Long?, nowMillis: Long): Boolean {
    if (loadedAtMillis == null) return true
    val age = nowMillis - loadedAtMillis
    // A clock that jumped backwards (a TV boots with a bogus date, then NTP corrects it) would
    // otherwise pin the data as fresh for hours.
    if (age < 0) return true
    return age >= maxAgeMillis
  }

  companion object {
    /** Long enough that browsing never refetches, short enough that "today's trending" is today's. */
    const val DEFAULT_MAX_AGE_MILLIS: Long = 4L * 60 * 60 * 1000
  }
}

/** Decides whether a completed refresh may advance a longer-lived in-memory freshness clock. */
object RefreshCompletionPolicy {
  fun loadedAtMillis(
    nowMillis: Long,
    hasFailures: Boolean,
    usedStaleFallback: Boolean,
  ): Long? = nowMillis.takeUnless { hasFailures || usedStaleFallback }
}
