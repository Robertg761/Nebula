package com.stremioshell.host.tv

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.stremioshell.host.tv.data.SettingsStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
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
  private var configurationSnapshotReady = false

  @Before
  fun resetConfiguration() {
    configurationSnapshotReady = false
    PhysicalTvInstrumentationGuard.requireExternalBackupOnPhysicalDevice()
    device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    // A TV left alone long enough goes to sleep, and an activity launched into a dark display
    // renders nothing for UiAutomator to find - every wait below then times out with the app
    // working perfectly.
    device.wakeUp()
    runBlocking {
      // This snapshot limits cross-test churn. It is not the final physical-device protection:
      // process death can skip @After, so the guarded physical wrapper also restores an external
      // DataStore snapshot after Gradle has returned.
      previousKey = settings.tmdbApiKey.first()
      previousAddons = settings.addonManifestUrls.first()
      previousSubtitles = settings.subtitlesBaseUrl.first()
      // JUnit still invokes @After when @Before throws. Arm restoration only after every value is
      // captured, otherwise an intentionally rejected physical run would write the fields' blank
      // defaults over the personal configuration it was meant to protect.
      configurationSnapshotReady = true
      settings.setConfiguration(
        tmdbKey = "",
        addonUrls = emptyList(),
        subtitlesBaseUrl = "",
      )
    }
  }

  @After
  fun restoreConfiguration() {
    if (!configurationSnapshotReady) return
    runBlocking {
      settings.setConfiguration(
        tmdbKey = previousKey,
        addonUrls = previousAddons,
        subtitlesBaseUrl = previousSubtitles,
      )
    }
    configurationSnapshotReady = false
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
  fun settingsTextFieldReleasesVerticalDpadFocus() {
    val intent = Intent(
      Intent.ACTION_VIEW,
      Uri.parse("stremio-tv://settings"),
      context,
      TvAppActivity::class.java,
    )
    launch(intent).use { scenario ->
      waitForFocusedText("Set up with phone")
      device.pressDPadDown()
      waitForFocusedDescription("TMDB API key")
      assertImeStaysHidden(scenario)

      // Navigation focus is not editing: Gboard stays closed and DPAD_DOWN remains with Compose.
      device.pressDPadDown()
      waitForFocusedText("Show key")

      device.pressDPadUp()
      waitForFocusedDescription("TMDB API key")

      // Editing is deliberate. Center opens Gboard; Back hides it without moving focus. The next
      // direction press first returns the field to readOnly, then performs the requested jump.
      device.pressDPadCenter()
      waitForImeVisibility(scenario, visible = true)
      device.pressBack()
      waitForImeVisibility(scenario, visible = false)
      device.pressDPadDown()
      waitForFocusedText("Show key")

      device.pressDPadUp()
      waitForFocusedDescription("TMDB API key")
      device.pressDPadUp()
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

  @Test
  fun consumedWatchNextIntentDoesNotReplaceRestoredHomeAfterRecreation() {
    val detailsRecovery = "Add a TMDB API key in Settings to load this title."
    val intent = Intent(
      Intent.ACTION_VIEW,
      Uri.parse("stremio-tv://watch-next?type=movie&tmdb=550&position=60000"),
      context,
      TvAppActivity::class.java,
    )

    launch(intent).use { scenario ->
      waitForText(detailsRecovery)
      device.pressBack()
      waitForFocusedText("Set up with phone")

      scenario.recreate()

      waitForFocusedText("Set up with phone")
      assertNull(
        "Consumed Watch Next intent replayed over the restored Home back stack",
        device.wait(Until.findObject(By.text(detailsRecovery)), REPLAY_QUIET_PERIOD_MS),
      )
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

  private fun waitForFocusedDescription(description: String): UiObject2 {
    val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
    while (SystemClock.uptimeMillis() < deadline) {
      val described = device.findObject(By.desc(description))
      var node = described
      while (node != null) {
        // Compose exposes the field description on a semantics child of the focusable EditText.
        // Stop at that nearest focus owner: on the API 34 TV image a higher Compose ancestor can
        // report focused while a sibling button still owns D-pad focus, which made this helper
        // return early and the test's next Up act as the first Up rather than the second.
        if (node.isFocusable) {
          if (node.isFocused) return requireNotNull(described)
          break
        }
        node = node.parent
      }
      SystemClock.sleep(POLL_INTERVAL_MS)
    }
    throw IllegalArgumentException("Timed out waiting for focused description: $description")
  }

  private fun assertImeStaysHidden(
    scenario: ActivityScenario<TvAppActivity>,
    durationMs: Long = IME_QUIET_PERIOD_MS,
  ) {
    val deadline = SystemClock.uptimeMillis() + durationMs
    while (SystemClock.uptimeMillis() < deadline) {
      check(!isImeVisible(scenario)) { "Software keyboard opened from D-pad focus alone" }
      SystemClock.sleep(POLL_INTERVAL_MS)
    }
  }

  private fun waitForImeVisibility(
    scenario: ActivityScenario<TvAppActivity>,
    visible: Boolean,
  ) {
    val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
    while (SystemClock.uptimeMillis() < deadline) {
      if (isImeVisible(scenario) == visible) return
      SystemClock.sleep(POLL_INTERVAL_MS)
    }
    throw IllegalArgumentException("Timed out waiting for software keyboard visible=$visible")
  }

  private fun isImeVisible(scenario: ActivityScenario<TvAppActivity>): Boolean {
    val visible = AtomicBoolean(false)
    scenario.onActivity { activity ->
      visible.set(
        ViewCompat.getRootWindowInsets(activity.window.decorView)
          ?.isVisible(WindowInsetsCompat.Type.ime()) == true,
      )
    }
    return visible.get()
  }

  private companion object {
    const val TIMEOUT_MS = 15_000L
    const val POLL_INTERVAL_MS = 100L
    const val REPLAY_QUIET_PERIOD_MS = 2_000L
    const val IME_QUIET_PERIOD_MS = 1_000L
  }
}
