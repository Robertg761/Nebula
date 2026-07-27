package com.stremioshell.host.tv.ui

import com.stremioshell.host.tv.LoadState
import com.stremioshell.host.tv.data.tmdb.MediaItem
import com.stremioshell.host.tv.data.tmdb.SearchFilter
import com.stremioshell.host.tv.data.tmdb.SearchResults

/** What the area below the query field is showing. */
sealed interface SearchUi {
  /** Nothing typed yet. */
  data object Idle : SearchUi

  data object Searching : SearchUi

  /**
   * @param refreshing these belong to an earlier query and a newer one is on its way. Kept on
   *   screen rather than blanked, because they are the results for a prefix of what is being typed
   *   and a grid that flashes empty on every keystroke is unreadable.
   */
  data class Results(val items: List<MediaItem>, val refreshing: Boolean) : SearchUi

  data class Empty(val title: String, val hint: String) : SearchUi

  data class Failed(val message: String) : SearchUi
}

/**
 * Maps the search field, the debounce, and the in-flight request onto one state.
 *
 * Pure because the interesting part is what happens *between* keystrokes: the field runs ahead of
 * the results by up to a debounce, so every branch has to ask whether the state it is looking at is
 * still about the query the viewer can see. Getting that wrong is what produces the two staleness
 * bugs that are invisible in a screenshot - "No results for 'bat'" flashing up while "batman" is
 * still being typed, and the previous query's failure sitting under a query that has not run yet.
 */
object SearchPresentation {
  /**
   * @param typed what is in the field right now.
   * @param requested the query [state] belongs to; the ViewModel sets it when a fetch starts.
   */
  fun resolve(
    typed: String,
    requested: String,
    state: LoadState<List<MediaItem>>,
    filter: SearchFilter,
  ): SearchUi {
    val query = typed.trim()
    if (query.isEmpty()) return SearchUi.Idle
    val pending = query != requested.trim()
    return when (state) {
      is LoadState.Loading -> SearchUi.Searching
      // A failure for a query that has already been edited says nothing about the new one.
      is LoadState.Failed -> if (pending) SearchUi.Searching else SearchUi.Failed(state.message)
      is LoadState.Ready -> {
        val items = SearchResults.present(requested, state.value, filter)
        when {
          items.isNotEmpty() -> SearchUi.Results(items, refreshing = pending)
          pending -> SearchUi.Searching
          else -> SearchUi.Empty(
            title = SearchResults.emptyTitle(query, filter),
            // The filter, not the query, is why the grid is empty when TMDB did return something.
            hint = SearchResults.emptyHint(filter, otherTypesMatch = state.value.isNotEmpty()),
          )
        }
      }
    }
  }
}
