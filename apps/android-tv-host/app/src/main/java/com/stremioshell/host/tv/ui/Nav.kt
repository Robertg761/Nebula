package com.stremioshell.host.tv.ui

import android.os.Parcelable
import com.stremioshell.host.tv.data.tmdb.MediaType
import kotlinx.parcelize.Parcelize

/**
 * Minimal back-stack navigation for the TV app.
 *
 * Parcelable so the whole back stack can ride out an activity recreation:
 * matching the display refresh rate to the content frame rate switches display
 * mode mid-playback and recreates the TvAppActivity sitting behind the player,
 * and process death can do the same at any point.
 */
sealed interface Screen : Parcelable {
  @Parcelize data object Home : Screen
  @Parcelize data object Search : Screen
  @Parcelize data object Settings : Screen
  @Parcelize data object Pair : Screen

  /**
   * @param initialSeason the season to open on, and [initialEpisode] the episode to mark and
   *   focus, when the screen was opened from Continue Watching. Null for a plain browse arrival,
   *   which opens on the first season as before.
   */
  @Parcelize
  data class Details(
    val type: MediaType,
    val tmdbId: Int,
    val initialSeason: Int? = null,
    val initialEpisode: Int? = null,
  ) : Screen

  /**
   * @param startOver plays from 0:00 even when a resume point exists, which is what
   *   the "Start over" action on a part-watched title asks for.
   */
  @Parcelize
  data class Streams(
    val imdbId: String,
    val title: String,
    val tmdbId: Int,
    val mediaType: MediaType,
    val posterUrl: String?,
    val season: Int? = null,
    val episode: Int? = null,
    val startOver: Boolean = false,
  ) : Screen
}
