package com.stremioshell.host.tv.data

/**
 * How much watch state is kept.
 *
 * Watched records and resume points share one stored list but not one budget: a
 * single binge writes a watched record per episode, and under a shared cap a
 * weekend on a long-running show would evict the half-watched film someone else
 * in the house left in Continue Watching. Separate caps mean the row a viewer can
 * see never loses entries to history they cannot.
 */
object WatchStateRetention {
  /** Continue Watching is a row on a TV; nobody scrolls fifty cards, let alone more. */
  const val MAX_RESUMABLE = 50

  /**
   * Enough for a couple of long series plus a year of films. The whole list is
   * re-encoded on every progress write, so this is a size budget as much as a
   * history one - roughly 60KB of JSON at the cap.
   */
  const val MAX_WATCHED = 300

  /**
   * Newest first, each kind capped independently. Sorting here rather than at
   * read time is what makes the caps mean "most recent", whatever order the
   * caller assembled.
   */
  fun prune(entries: List<WatchEntry>): List<WatchEntry> {
    val newestFirst = entries.sortedWith(watchEntryNewestFirst)
    val watched = newestFirst.filter { it.watched }.take(MAX_WATCHED)
    val resumable = newestFirst.filterNot { it.watched }.take(MAX_RESUMABLE)
    return (watched + resumable).sortedWith(watchEntryNewestFirst)
  }
}
