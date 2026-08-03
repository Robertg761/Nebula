package com.stremioshell.host.tv.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stremioshell.host.tv.PhysicalTvInstrumentationGuard
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
    PhysicalTvInstrumentationGuard.requireExternalBackupOnPhysicalDevice()
    val first = SettingsStore(context)
    val fixtureKey = "instrumentation-key-not-a-secret"
    val fixtureAddon = "https://example.invalid/manifest.json"
    val fixtureSubtitles = "https://subtitles.example.invalid/manifest.json"

    // This snapshot keeps the case self-contained. The guarded physical wrapper owns final
    // recovery because a disconnect or process death can skip this finally block entirely.
    val previousKey = first.tmdbApiKey.first()
    val previousAddons = first.addonManifestUrls.first()
    val previousSubtitles = first.subtitlesBaseUrl.first()

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
        tmdbKey = previousKey,
        addonUrls = previousAddons,
        subtitlesBaseUrl = previousSubtitles,
      )
    }
  }
}
