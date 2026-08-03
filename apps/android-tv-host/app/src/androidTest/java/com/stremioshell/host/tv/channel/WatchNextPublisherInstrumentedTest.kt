package com.stremioshell.host.tv.channel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tvprovider.media.tv.TvContractCompat
import com.stremioshell.host.tv.PhysicalTvInstrumentationGuard
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-backed coverage for the ContentValues semantics used by in-place Watch Next updates. */
@RunWith(AndroidJUnit4::class)
class WatchNextPublisherInstrumentedTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun nullableFieldsAreWrittenAsNullSoAnUpdateClearsThePreviousRow() {
    PhysicalTvInstrumentationGuard.requireExternalBackupOnPhysicalDevice()
    val values = WatchNextPublisher(context).contentValuesFor(
      WatchNextProgramData(
        internalProviderId = "movie:550",
        title = "Fight Club",
        type = WatchNextProgramType.Movie,
        kind = WatchNextKind.Next,
        posterArtUri = null,
        lastEngagementTimeUtcMillis = 1_700_000_000_000L,
        lastPlaybackPositionMillis = null,
        durationMillis = null,
        seasonNumber = null,
        episodeNumber = null,
        deepLinkUri = "stremio-tv://watch-next?type=movie&tmdb=550",
      ),
    )

    val nullableColumns = listOf(
      TvContractCompat.WatchNextPrograms.COLUMN_POSTER_ART_URI,
      TvContractCompat.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
      TvContractCompat.WatchNextPrograms.COLUMN_DURATION_MILLIS,
      TvContractCompat.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
      TvContractCompat.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
    )
    nullableColumns.forEach { column ->
      assertTrue("missing explicit null for $column", values.containsKey(column))
      assertNull("$column retained a stale value", values[column])
    }
  }
}
