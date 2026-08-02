package com.stremioshell.host.tv

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.stremioshell.host.tv.data.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Credential-free TV smoke coverage for the Android lifecycle and real Compose
 * accessibility tree. Playback, HDR, audio routing and CEC remain physical-TV
 * release gates; see docs/tv-qa-matrix.md.
 */
@RunWith(AndroidJUnit4::class)
class TvNavigationInstrumentedTest {
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val settings = SettingsStore(context)
  private lateinit var device: UiDevice
  private var previousKey = ""
  private var previousAddons = emptyList<String>()
  private var previousSubtitles = ""

  @Before
  fun resetConfiguration() {
    device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    // A TV left alone long enough goes to sleep, and an activity launched into a dark display
    // renders nothing for UiAutomator to find - every wait below then times out with the app
    // working perfectly.
    device.wakeUp()
    runBlocking {
      // Snapshotted so the blank state these tests need is not what the device is left in: this
      // suite also runs on personal boxes, and blanking here once erased a configured TMDB key
      // and addon list for good.
      previousKey = settings.tmdbApiKey.first()
      previousAddons = settings.addonManifestUrls.first()
      previousSubtitles = settings.subtitlesBaseUrl.first()
      settings.setConfiguration(
        tmdbKey = "",
        addonUrls = emptyList(),
        subtitlesBaseUrl = "",
      )
    }
  }

  @After
  fun restoreConfiguration() {
    runBlocking {
      settings.setConfiguration(
        tmdbKey = previousKey,
        addonUrls = previousAddons,
        subtitlesBaseUrl = previousSubtitles,
      )
    }
  }

  @Test
  fun coldLaunchHasFocusableSetupAndSettingsBackReturnsHome() {
    launch(Intent(context, TvAppActivity::class.java)).use {
      waitForText("NEBULA")
      waitForFocusedText("Set up with phone")

      device.pressDPadRight()
      waitForFocusedText("Enter manually")
      device.pressDPadCenter()

      waitForText("Settings")
      waitForFocusedText("Set up with phone")
      device.pressBack()

      waitForText("NEBULA")
      waitForFocusedText("Set up with phone")
    }
  }

  @Test
  fun searchIntentRoutesQueryAndBackReturnsHome() {
    val intent = Intent(context, TvAppActivity::class.java)
      .setAction(Intent.ACTION_SEARCH)
      .putExtra(SearchManager.QUERY, "Dune Part Two")

    launch(intent).use {
      waitForText("Search")
      waitForText("Dune Part Two")
      device.pressBack()
      waitForText("NEBULA")
    }
  }

  @Test
  fun watchNextDeepLinkRoutesToDetailsAndBackReturnsHome() {
    val intent = Intent(
      Intent.ACTION_VIEW,
      Uri.parse("stremio-tv://watch-next?type=movie&tmdb=550&position=60000"),
      context,
      TvAppActivity::class.java,
    )

    launch(intent).use {
      // The blank test configuration is a real, initialized value, not an endless load. The
      // recovery action proves both that the URI reached Details and that the cold DataStore
      // sentinel was allowed to resolve before the screen decided what to render.
      waitForText("Add a TMDB API key in Settings to load this title.")
      waitForFocusedText("Open Settings")
      device.pressBack()
      waitForText("NEBULA")
    }
  }

  private fun launch(intent: Intent): ActivityScenario<TvAppActivity> {
    return ActivityScenario.launch<TvAppActivity>(intent)
  }

  private fun waitForText(text: String): UiObject2 {
    return requireNotNull(device.wait(Until.findObject(By.text(text)), TIMEOUT_MS)) {
      "Timed out waiting for text: $text"
    }
  }

  private fun waitForFocusedText(text: String): UiObject2 {
    val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
    while (SystemClock.uptimeMillis() < deadline) {
      val label = device.findObject(By.text(text))
      var node = label
      while (node != null) {
        // Compose exposes focus on the button semantics node and its label as a
        // child accessibility node. Checking the ancestor chain proves the
        // same user-visible control has focus without assuming they are merged.
        if (node.isFocused) return requireNotNull(label)
        node = node.parent
      }
      SystemClock.sleep(POLL_INTERVAL_MS)
    }
    throw IllegalArgumentException("Timed out waiting for focused text: $text")
  }

  private companion object {
    const val TIMEOUT_MS = 15_000L
    const val POLL_INTERVAL_MS = 100L
  }
}
