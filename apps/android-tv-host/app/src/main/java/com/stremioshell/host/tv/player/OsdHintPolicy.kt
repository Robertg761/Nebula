package com.stremioshell.host.tv.player

/**
 * Whether the transport panel still spells out what the D-pad does.
 *
 * The panel used to print a key map ("OK play/pause | LEFT/RIGHT 10s | UP for controls | DOWN
 * hides") on every single open, for the life of the install. No premium player does that, and the
 * reason is not squeamishness about instructions - it is that a legend which never goes away is an
 * admission the controls are not legible on their own, and after the tenth film it is just noise
 * sitting under the scrub bar.
 *
 * Deleting it outright was the wrong fix, though. The remote this app is built for - a Google TV
 * Streamer - has no MENU key, no CAPTIONS key and no transport keys at all, so "UP for controls" is
 * genuinely undiscoverable: there is no affordance on screen for a key that does not exist on the
 * remote until you press it. A viewer who never learns it never finds the track menu.
 *
 * So it teaches, then gets out of the way. The legend shows for the first few times the panel is
 * opened on a given install and then stops for good, which is the same bargain a first-run tooltip
 * makes.
 */
object OsdHintPolicy {
  /**
   * Opens that still carry the legend.
   *
   * Five rather than one or two: the first couple of opens are usually accidental - a viewer
   * pressing a direction to see what happens - and are not read. Five is enough that at least one
   * lands while they are actually looking for something.
   */
  const val OPENS_WITH_HINT = 5

  /** @param opensSoFar how many times the panel has been opened before this one. */
  fun showsHint(opensSoFar: Int): Boolean = opensSoFar < OPENS_WITH_HINT

  /**
   * The stored counter after an open, saturating at the limit.
   *
   * Saturates rather than counting forever so the stored value stays meaningful and a viewer who
   * watches a thousand films does not carry a five-digit preference around.
   *
   * The early return is not redundant: `(opensSoFar + 1).coerceAtMost(limit)` overflows to
   * Int.MIN_VALUE at Int.MAX_VALUE, and a stored value that wrapped negative would put the legend
   * back on screen for good. Reached only via a corrupted or hand-edited preference, which is
   * exactly the sort of input a saturating counter should survive.
   */
  fun advance(opensSoFar: Int): Int =
    if (opensSoFar >= OPENS_WITH_HINT) OPENS_WITH_HINT else (opensSoFar + 1).coerceAtLeast(0)
}
