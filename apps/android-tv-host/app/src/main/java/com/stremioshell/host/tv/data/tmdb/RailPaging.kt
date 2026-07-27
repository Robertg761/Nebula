package com.stremioshell.host.tv.data.tmdb

/**
 * How far a rail has paged into its catalog.
 *
 * @param nextPage the TMDB page a further fetch would ask for.
 * @param loading a fetch is in flight, so the rail must not start a second one.
 * @param endReached nothing more will be asked for: the catalog ran out, the cap was hit, or a
 *   fetch failed.
 */
data class RailPageState(
  val nextPage: Int = 2,
  val loading: Boolean = false,
  val endReached: Boolean = false,
)

/**
 * When a rail fetches its next page, and how that page joins the items already on screen.
 *
 * Pure so the two rules that are easy to get wrong are testable without a device: TMDB repeats
 * entries across pages of the same endpoint (popularity shifts between requests), and an append
 * must leave every item already in the row at the index it was at, or the focused card moves under
 * the user's thumb.
 */
object RailPaging {
  /** Beyond this a row stops being browsable with a D-pad; it is minutes of held-right as it is. */
  const val MAX_ITEMS = 100

  /** Matches [MAX_ITEMS] at TMDB's 20 per page, and bounds the requests one rail can ever make. */
  const val MAX_PAGES = 5

  /**
   * Cards from the end at which the next page starts loading. Roughly one screen's worth ahead of
   * the focused card, so the page has landed before the user can reach the gap.
   */
  const val PREFETCH_DISTANCE = 6

  /**
   * True when [lastVisibleIndex] has come close enough to the end of a rail of [itemCount] items to
   * start the next page.
   *
   * Driven by the last *visible* index rather than the focused one: holding right scrolls faster
   * than focus events arrive, and a rail that waited for focus to reach the edge would show the gap
   * before it filled it.
   */
  fun shouldFetchNext(state: RailPageState, itemCount: Int, lastVisibleIndex: Int): Boolean {
    if (state.loading || state.endReached) return false
    // An empty rail has nothing to page from; its first page is the rails load's business.
    if (itemCount == 0) return false
    if (itemCount >= MAX_ITEMS || state.nextPage > MAX_PAGES) return false
    return lastVisibleIndex >= itemCount - PREFETCH_DISTANCE
  }

  /**
   * [head] followed by whatever of [tail] it does not already contain, capped at [MAX_ITEMS].
   *
   * Serves both directions a rail grows in. Appending a page passes the row as the head and the new
   * page as the tail, so existing cards keep their indices. A refresh passes the new first page as
   * the head and the row it is replacing as the tail, which is what stops a background refresh from
   * shortening a rail the user has already paged into - their scroll position is restored across
   * visits, and dropping items 21..100 would strand them at the end of a row they were mid-way
   * through.
   *
   * Returns [head] itself when nothing was added, so an all-duplicate page costs no recomposition.
   */
  fun merge(head: List<MediaItem>, tail: List<MediaItem>): List<MediaItem> {
    val seen = HashSet<String>(head.size + tail.size)
    val merged = ArrayList<MediaItem>(minOf(MAX_ITEMS, head.size + tail.size))
    for (item in head) {
      if (merged.size >= MAX_ITEMS) break
      if (seen.add(item.key)) merged += item
    }
    val fromHead = merged.size
    for (item in tail) {
      if (merged.size >= MAX_ITEMS) break
      if (seen.add(item.key)) merged += item
    }
    return if (merged.size == fromHead && fromHead == head.size) head else merged
  }

  /**
   * Paging state for a rail that has just loaded its first page.
   *
   * @param carried the state from before a refresh. A refresh keeps the rail's existing depth (see
   *   [merge]), so it has to keep its place in TMDB's pagination too rather than offering page 2 a
   *   second time.
   */
  fun afterFirstPage(page: MediaPage, itemCount: Int, carried: RailPageState? = null): RailPageState {
    val nextPage = maxOf(2, carried?.nextPage ?: 2)
    return RailPageState(
      nextPage = nextPage,
      loading = false,
      endReached = exhausted(nextPage, itemCount, page.totalPages),
    )
  }

  /** @param itemCount the rail's size after [page] was merged in. */
  fun afterNextPage(state: RailPageState, page: MediaPage, itemCount: Int): RailPageState {
    val nextPage = state.nextPage + 1
    return RailPageState(
      nextPage = nextPage,
      loading = false,
      // An empty page means TMDB's counters were optimistic; stop rather than walk to the cap.
      endReached = page.items.isEmpty() || exhausted(nextPage, itemCount, page.totalPages),
    )
  }

  /**
   * A rail whose next page could not be fetched.
   *
   * Paging stops for this rail rather than retrying: the user still has everything that did load,
   * and a row that refires on every scroll event would hammer a network that is already failing.
   * The next full rails load - a refresh, or a return with stale rails - starts it over.
   */
  fun failed(state: RailPageState): RailPageState =
    state.copy(loading = false, endReached = true)

  private fun exhausted(nextPage: Int, itemCount: Int, totalPages: Int): Boolean =
    nextPage > MAX_PAGES || nextPage > totalPages || itemCount >= MAX_ITEMS
}
