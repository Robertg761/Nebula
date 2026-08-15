package com.stremioshell.host.tv.player

import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stremioshell.host.tv.data.PlaybackUrlPolicy
import com.stremioshell.host.tv.data.addon.AddonStreamSubtitle
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerPayloadParcelInstrumentedTest {
  @Test
  fun worstCaseRestorableStateStaysBelowConservativeBinderBudget() {
    val urlPrefix = "https://video.example/"
    val headers = (0 until 8).associate { index ->
      "X-Token-$index" to "h".repeat(4_000)
    }
    val subtitles = (0 until 60).map { index ->
      AddonStreamSubtitle(
        id = "i".repeat(EmbeddedSubtitles.MAX_ID_LENGTH),
        url = "https://subs.example/$index/${"s".repeat(3_900)}.srt",
        lang = "l".repeat(EmbeddedSubtitles.MAX_LANGUAGE_LENGTH),
      )
    }
    val state = PlayerRestorableState.create(
      url = urlPrefix + "v".repeat(PlaybackUrlPolicy.MAX_URL_CHARS - urlPrefix.length),
      title = "t".repeat(PlayerPayloadBounds.MAX_TITLE_CHARS),
      watchKey = "w".repeat(PlayerPayloadBounds.MAX_WATCH_KEY_CHARS),
      tmdbId = 1,
      mediaType = "m".repeat(PlayerPayloadBounds.MAX_MEDIA_TYPE_CHARS),
      posterUrl = "p".repeat(PlayerPayloadBounds.MAX_POSTER_URL_CHARS),
      season = 1,
      episode = 1,
      imdbId = "i".repeat(PlayerPayloadBounds.MAX_IMDB_ID_CHARS),
      bingeGroup = "b".repeat(PlayerPayloadBounds.MAX_BINGE_GROUP_CHARS),
      requestHeaders = headers,
      embeddedSubtitles = subtitles,
      streamVideoHash = "v".repeat(PlayerPayloadBounds.MAX_VIDEO_HASH_CHARS),
      streamFilename = "f".repeat(PlayerPayloadBounds.MAX_FILENAME_CHARS),
      streamVideoSize = Long.MAX_VALUE,
      positionMs = Long.MAX_VALUE,
      resumeResetRequested = true,
      pauseRequested = true,
    )

    val parcel = Parcel.obtain()
    try {
      parcel.writeParcelable(state, 0)
      assertTrue(
        "Player state used ${parcel.dataSize()} bytes",
        parcel.dataSize() < MAX_PARCEL_BYTES,
      )
    } finally {
      parcel.recycle()
    }
  }

  private companion object {
    const val MAX_PARCEL_BYTES = 256 * 1024
  }
}
