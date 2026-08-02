package com.stremioshell.host.tv.data

/** A value that was already loaded, and whether it is old enough to refresh behind the viewer. */
data class CachedValue<V>(val value: V, val stale: Boolean)

/** Pure ownership rules for account-keyed metadata caches and the Home hero request. */
internal object MetadataCacheOwnership {
  fun credential(value: String?): String? = value?.takeIf { it.isNotBlank() }

  fun changed(previous: String?, current: String?): Boolean =
    credential(previous) != credential(current)

  fun canReuseHero(
    sameHero: Boolean,
    credentialChanged: Boolean,
    cachedFresh: Boolean,
    requestActive: Boolean,
  ): Boolean = sameHero && !credentialChanged && (cachedFresh || requestActive)

  /** Both the cache and the live setting must still belong to the job that is completing. */
  fun isCurrent(
    owner: String?,
    cacheOwner: String?,
    liveCredential: String?,
  ): Boolean = owner != null &&
    owner == credential(cacheOwner) &&
    owner == credential(liveCredential)
}

/**
 * Metadata a screen has already shown, kept so returning to it is instant.
 *
 * Home -> Details -> BACK -> the same Details used to refetch details, credits and similar every
 * time, putting a spinner over content the viewer had been reading seconds earlier; season episode
 * lists did the same on every tab back and forth. The rule here is the rails' rule one screen down
 * (see [StalenessPolicy]): a hit is served immediately, and an aged-out hit is *still* served
 * immediately, with the refresh happening in place underneath it.
 *
 * Bounded, because each entry is a whole TMDB payload. Eviction is least-recently-*used* rather
 * than least-recently-loaded: bouncing between two titles keeps both alive no matter how much is
 * opened around them, which is the pattern this cache exists for.
 *
 * Deliberately not used for search results or stream lists. Search is retyped rather than
 * revisited, and a debrid stream URL is signed and short-lived - replaying one hands the player a
 * dead link.
 */
class MetadataCache<K : Any, V : Any>(
  private val maxEntries: Int,
  private val staleness: StalenessPolicy = StalenessPolicy(),
) {
  init {
    require(maxEntries > 0) { "maxEntries must be positive" }
  }

  private class Entry<V>(
    val value: V,
    val loadedAtMillis: Long,
    val forcedStale: Boolean,
  )

  // accessOrder=true is what makes reads count as use; removeEldestEntry is the eviction.
  private val entries = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>): Boolean =
      size > maxEntries
  }

  /**
   * The stored value for [key], or null if there is none.
   *
   * Synchronized only because a read mutates the LRU order: callers are all on the main thread
   * today, and a cache that corrupts its own map the first time that stops being true is not worth
   * the saved nanoseconds.
   */
  @Synchronized
  fun get(key: K, nowMillis: Long): CachedValue<V>? {
    val entry = entries[key] ?: return null
    return CachedValue(
      entry.value,
      entry.forcedStale || staleness.isStale(entry.loadedAtMillis, nowMillis),
    )
  }

  /** Stores [value] and marks it freshly loaded, which is what ends a background refresh. */
  @Synchronized
  fun put(key: K, value: V, loadedAtMillis: Long) {
    entries[key] = Entry(value, loadedAtMillis, forcedStale = false)
  }

  /**
   * Stores a usable stale-fallback value without pretending the network refreshed it.
   *
   * An explicit flag is safer than a sentinel timestamp: TV clocks can start wrong and jump in
   * either direction, while this entry must remain refreshable until a genuinely fresh load wins.
   */
  @Synchronized
  fun putStale(key: K, value: V) {
    entries[key] = Entry(value, loadedAtMillis = 0L, forcedStale = true)
  }

  /** Everything cached belongs to one TMDB key; a new key is a different account's data. */
  @Synchronized
  fun clear() {
    entries.clear()
  }

  @get:Synchronized
  val size: Int get() = entries.size
}
