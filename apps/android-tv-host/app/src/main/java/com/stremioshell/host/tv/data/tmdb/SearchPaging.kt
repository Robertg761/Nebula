package com.stremioshell.host.tv.data.tmdb

/** Paging state kept beside search results so loading another page never blanks the grid. */
data class SearchPageState(
  val nextPage: Int = 2,
  val totalPages: Int = 1,
  val loading: Boolean = false,
  val endReached: Boolean = true,
  val error: String? = null,
)

/** Pure search-page policy shared by the ViewModel and its JVM tests. */
object SearchPaging {
  const val MAX_RESULTS = 100
  const val MAX_PAGES = 5
  const val PREFETCH_DISTANCE = 8

  fun afterFirstPage(page: MediaPage): SearchPageState {
    val nextPage = page.page.coerceAtLeast(1) + 1
    return SearchPageState(
      nextPage = nextPage,
      totalPages = page.totalPages.coerceAtLeast(page.page),
      // Mixed search filters people out. A page with no movie/show rows is not proof that later
      // TMDB pages have none, so the server's counters remain authoritative.
      endReached = nextPage > page.totalPages ||
        nextPage > MAX_PAGES ||
        page.items.size >= MAX_RESULTS,
    )
  }

  fun shouldPrefetch(
    state: SearchPageState,
    itemCount: Int,
    lastVisibleIndex: Int,
  ): Boolean {
    if (!canLoad(state) || itemCount == 0) return false
    return lastVisibleIndex >= itemCount - PREFETCH_DISTANCE
  }

  fun begin(state: SearchPageState): SearchPageState =
    if (canLoad(state)) state.copy(loading = true, error = null) else state

  fun afterPage(
    state: SearchPageState,
    page: MediaPage,
    mergedCount: Int,
  ): SearchPageState {
    val nextPage = maxOf(state.nextPage + 1, page.page + 1)
    return SearchPageState(
      nextPage = nextPage,
      totalPages = page.totalPages.coerceAtLeast(page.page),
      loading = false,
      endReached = nextPage > page.totalPages ||
        nextPage > MAX_PAGES ||
        mergedCount >= MAX_RESULTS,
    )
  }

  fun failed(state: SearchPageState, message: String): SearchPageState =
    state.copy(loading = false, error = message)

  fun retry(state: SearchPageState): SearchPageState = state.copy(error = null)

  fun canLoad(state: SearchPageState): Boolean =
    !state.loading &&
      !state.endReached &&
      state.error == null &&
      state.nextPage <= state.totalPages &&
      state.nextPage <= MAX_PAGES

  /** Preserves every existing index while removing TMDB duplicates from appended pages. */
  fun merge(existing: List<MediaItem>, page: List<MediaItem>): List<MediaItem> {
    val seen = existing.mapTo(HashSet(existing.size + page.size)) { it.key }
    val additions = page.filter { seen.add(it.key) }
    if (additions.isEmpty()) return existing
    return (existing + additions).take(MAX_RESULTS)
  }
}
