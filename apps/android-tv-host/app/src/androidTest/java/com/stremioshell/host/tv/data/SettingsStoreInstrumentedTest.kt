package com.stremioshell.host.tv.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Device-backed proof that configuration commits atomically and survives a new store instance. */
@RunWith(AndroidJUnit4::class)
class SettingsStoreInstrumentedTest {
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun configurationRoundTripUsesSanitizedCredentialFreeFixtures() = runBlocking {
    val first = SettingsStore(context)
    val fixtureKey = "instrumentation-key-not-a-secret"
    val fixtureAddon = "https://example.invalid/manifest.json"
    val fixtureSubtitles = "https://subtitles.example.invalid/manifest.json"

    try {
      first.setConfiguration(
        tmdbKey = "  $fixtureKey  ",
        addonUrls = listOf(fixtureAddon),
        subtitlesBaseUrl = "  $fixtureSubtitles  ",
      )

      val reopened = SettingsStore(context)
      assertEquals(fixtureKey, reopened.tmdbApiKey.first())
      assertEquals(listOf(fixtureAddon), reopened.addonManifestUrls.first())
      assertEquals(fixtureSubtitles, reopened.subtitlesBaseUrl.first())
    } finally {
      first.setConfiguration(
        tmdbKey = "",
        addonUrls = emptyList(),
        subtitlesBaseUrl = "",
      )
    }
  }
}
