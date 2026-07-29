package com.stremioshell.host.tv.data.tmdb

import java.util.Locale

/**
 * The type filter above the search grid.
 *
 * Multi-search answers with movies and shows in one response, so a chip narrows the results already
 * in hand rather than asking TMDB again: switching is instant, costs no request, and cannot lose the
 * query the viewer typed.
 */
enum class SearchFilter(val label: String) {
  All("All"),
  Movies("Movies"),
  Shows("Shows"),
  ;

  fun accepts(item: MediaItem): Boolean = when (this) {
    All -> true
    Movies -> item.type == MediaType.Movie
    Shows -> item.type == MediaType.Show
  }

  companion object {
    /**
     * The filter called [name], or [All] for anything else. The screen saves the chip by name
     * because an enum is not one of the types `rememberSaveable` can put in a Bundle on its own,
     * and a restore must not be able to crash Search over a value it no longer recognises.
     */
    fun of(name: String?): SearchFilter = values().firstOrNull { it.name == name } ?: All
  }
}

/**
 * What the search grid shows, out of TMDB's multi-search answer.
 *
 * Pure because these are the rules a device cannot easily be made to reproduce on demand: TMDB
 * orders search by popularity, so the exact title a viewer typed regularly lands below a franchise's
 * sequels, and its index carries stub entries with no artwork at all, which render as bare grey
 * rectangles in a grid of posters.
 */
object SearchResults {
  /**
   * @param query the query these [items] came back for - not what is in the field right now, which
   *   runs ahead of the results while the debounce waits.
   */
  fun present(query: String, items: List<MediaItem>, filter: SearchFilter): List<MediaItem> {
    // Multi-search can carry the same title twice when a franchise exists as both a film and a
    // series; the grid keys on [MediaItem.key], which duplicates would collide on.
    val ofType = items.distinctBy { it.key }.filter { filter.accepts(it) }
    // Artless entries are dropped - unless they are all TMDB has for this query, because an empty
    // grid answers the viewer worse than a row of placeholder cards does.
    val visible = ofType.filter { it.posterUrl != null || it.backdropUrl != null }.ifEmpty { ofType }
    val target = normalized(query)
    if (target.isEmpty()) return visible
    // partition keeps TMDB's own order inside each half, so only the exact hits move.
    val (exact, rest) = visible.partition { normalized(it.title) == target }
    return if (exact.isEmpty()) visible else exact + rest
  }

  /** The line under a result card: what tells two identically named titles apart at ten feet. */
  fun caption(item: MediaItem): String = listOfNotNull(
    item.year?.trim()?.ifBlank { null },
    typeLabel(item.type),
  ).joinToString(" • ")

  fun typeLabel(type: MediaType): String = if (type == MediaType.Show) "Series" else "Movie"

  /** Reserves the line above the grid whether or not a newer query is on the way. */
  fun countLabel(count: Int): String = if (count == 1) "1 result" else "$count results"

  /** Headline for a search that matched nothing, echoing what was actually searched for. */
  fun emptyTitle(query: String, filter: SearchFilter): String {
    val shown = query.trim()
    return when (filter) {
      SearchFilter.All -> "No results for \"$shown\""
      SearchFilter.Movies -> "No movies match \"$shown\""
      SearchFilter.Shows -> "No shows match \"$shown\""
    }
  }

  /**
   * @param otherTypesMatch the query did match something, just not of the filtered type. That is a
   *   different problem from a query nothing matched, and it has a different fix.
   */
  fun emptyHint(filter: SearchFilter, otherTypesMatch: Boolean): String =
    if (filter != SearchFilter.All && otherTypesMatch) {
      "There are matches of another type - try All."
    } else {
      "Try fewer words, or check the spelling."
    }

  private val WHITESPACE = Regex("\\s+")

  // Case and spacing only. Anything cleverer (dropping punctuation, articles) would start calling
  // near-misses exact, and the reordering below is only defensible while "exact" means exact.
  private fun normalized(text: String): String =
    text.trim().lowercase(Locale.ROOT).replace(WHITESPACE, " ")
}
