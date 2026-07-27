package com.stremioshell.host.tv.data.tmdb

/**
 * Where a Home rail's items come from.
 *
 * Data rather than a suspending lambda so the rail set itself - its order, its titles, which wave
 * it loads in - is inspectable in a unit test; the ViewModel is the only thing that turns a query
 * into a request.
 */
sealed interface CatalogQuery {
  val type: MediaType

  data class Trending(override val type: MediaType) : CatalogQuery
  data class Popular(override val type: MediaType) : CatalogQuery

  /** @param genreId a TMDB genre id, from the movie or the TV list depending on [type]. */
  data class Genre(override val type: MediaType, val genreId: Int) : CatalogQuery
}

/** @param title doubles as the rail's identity: rails are matched by it across reloads. */
data class CatalogRailSpec(val title: String, val query: CatalogQuery)

/**
 * The rails Home offers, in order.
 *
 * Split into two waves rather than one: nine endpoints fired at once would put Home's first paint
 * behind the slowest of them, and OkHttp only runs five per host concurrently anyway. [PRIMARY] is
 * what the user sees on arrival, [SECONDARY] streams in below the fold while they are reading it.
 */
object CatalogRails {
  // TMDB genre ids. Movies and shows use separate lists, and the overlap is only partial - a show
  // has no "Science Fiction", it has "Sci-Fi & Fantasy".
  private const val MOVIE_ACTION = 28
  private const val MOVIE_DOCUMENTARY = 99
  private const val MOVIE_ANIMATION = 16
  private const val SHOW_COMEDY = 35
  private const val SHOW_SCI_FI_FANTASY = 10765

  val PRIMARY: List<CatalogRailSpec> = listOf(
    CatalogRailSpec("Trending Movies", CatalogQuery.Trending(MediaType.Movie)),
    CatalogRailSpec("Trending Shows", CatalogQuery.Trending(MediaType.Show)),
    CatalogRailSpec("Popular Movies", CatalogQuery.Popular(MediaType.Movie)),
    CatalogRailSpec("Popular Shows", CatalogQuery.Popular(MediaType.Show)),
  )

  /**
   * Genre rails, alternating movie and show so scrolling down does not feel like it changed apps
   * halfway.
   */
  val SECONDARY: List<CatalogRailSpec> = listOf(
    CatalogRailSpec("Action", CatalogQuery.Genre(MediaType.Movie, MOVIE_ACTION)),
    CatalogRailSpec("Comedy Shows", CatalogQuery.Genre(MediaType.Show, SHOW_COMEDY)),
    CatalogRailSpec("Sci-Fi & Fantasy", CatalogQuery.Genre(MediaType.Show, SHOW_SCI_FI_FANTASY)),
    CatalogRailSpec("Documentaries", CatalogQuery.Genre(MediaType.Movie, MOVIE_DOCUMENTARY)),
    CatalogRailSpec("Animation", CatalogQuery.Genre(MediaType.Movie, MOVIE_ANIMATION)),
  )

  val ALL: List<CatalogRailSpec> = PRIMARY + SECONDARY

  /** Load waves, in the order they are dispatched. */
  val WAVES: List<List<CatalogRailSpec>> = listOf(PRIMARY, SECONDARY)

  val ORDER: List<String> = ALL.map { it.title }

  fun specFor(title: String): CatalogRailSpec? = ALL.firstOrNull { it.title == title }
}

/**
 * The title Home's billboard leads with.
 *
 * Rails arrive in declared order, so the first rail's first entry is the week's top trending title -
 * but a billboard is mostly its backdrop, and TMDB ships plenty of catalog entries without one. So
 * the first entry that has artwork wins, and a poster-only catalog falls back to the very first
 * entry rather than leaving Home without a header.
 */
object HeroPick {
  fun from(rails: List<List<MediaItem>>): MediaItem? {
    for (items in rails) {
      items.firstOrNull { !it.backdropUrl.isNullOrBlank() }?.let { return it }
    }
    return rails.firstOrNull { it.isNotEmpty() }?.first()
  }
}
