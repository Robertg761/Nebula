package com.stremioshell.host.tv.data

/**
 * Ordering, de-duplication and the size cap for "My List".
 *
 * A saved title is a snapshot rather than a reference, so the row renders with no
 * network - which means the whole list is re-encoded on every add and removal, and
 * the cap is a size budget as much as a hoarding one.
 */
object WatchlistRetention {
  /**
   * Roughly 100KB of JSON at the cap. Far past the point where a viewer would scroll
   * to the end of a TV row, and the oldest saves are the ones nobody comes back for.
   */
  const val MAX_ENTRIES = 200

  /**
   * Newest first, one entry per title, capped.
   *
   * Applied on read as well as on write: a stored list from an older build (or a
   * half-written one) must not be able to put a duplicate key into a lazy row, which
   * crashes it outright.
   */
  fun ordered(entries: List<WatchlistEntry>): List<WatchlistEntry> =
    entries.sortedByDescending { it.addedAtMs }.distinctBy { it.key }.take(MAX_ENTRIES)

  /**
   * Saves [entry], replacing any earlier save of the same title.
   *
   * [entry] leads the input so that a stable sort keeps it in front of anything stored
   * in the same millisecond: a re-save must never appear to land behind the copy it
   * just replaced.
   */
  fun add(entries: List<WatchlistEntry>, entry: WatchlistEntry): List<WatchlistEntry> =
    ordered(listOf(entry) + entries.filterNot { it.key == entry.key })

  fun remove(entries: List<WatchlistEntry>, key: String): List<WatchlistEntry> =
    ordered(entries.filterNot { it.key == key })

  fun contains(entries: List<WatchlistEntry>, key: String): Boolean = entries.any { it.key == key }

  /**
   * What one press of the Details toggle produces. Kept here rather than in the screen
   * so the button reads the list it is about to change exactly once, inside the same
   * write - a decision made from a StateFlow could act on a value the store had already
   * moved past.
   */
  fun toggled(entries: List<WatchlistEntry>, entry: WatchlistEntry): List<WatchlistEntry> =
    if (contains(entries, entry.key)) remove(entries, entry.key) else add(entries, entry)
}
