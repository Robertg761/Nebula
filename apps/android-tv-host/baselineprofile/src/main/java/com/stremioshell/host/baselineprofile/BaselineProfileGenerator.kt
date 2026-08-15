package com.stremioshell.host.baselineprofile

import android.os.SystemClock
import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Records launch-only startup rules and the wider TV journey on a provisioned device. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
  @get:Rule
  val baselineProfileRule = BaselineProfileRule()

  @Test
  fun collectStartup() = baselineProfileRule.collect(
    packageName = PACKAGE_NAME,
    includeInStartupProfile = true,
  ) {
    launchHome()
  }

  /**
   * Records the full baseline journey without putting it in the startup profile.
   *
   * This deliberately fails when the test TMDB/addon fixture is missing. A short but green
   * generator would give a false sense that Details, Streams and playback were represented.
   */
  @Test
  fun collectCriticalUserJourney() = baselineProfileRule.collect(
    packageName = PACKAGE_NAME,
    includeInStartupProfile = false,
  ) {
    launchHome()

    // The two side routes run before the rail scroll, and that ordering is load-bearing: the
    // marker this journey returns to is Home's *first* rail heading, and scrolling puts it off
    // screen. Coming back to a scrolled Home then looks identical to never having come back at
    // all. Scroll last, when nothing needs to recognise Home again.
    //
    // Settings goes by deep link because the drawer's "Settings" label only exists while the
    // drawer is open - there is nothing on a settled screen to click. "TMDB" is the first
    // section heading, which is what proves the form composed.
    startByDeepLink(SETTINGS_DEEP_LINK)
    waitForText("TMDB")
    backUntilText("Trending Movies", HOME_TIMEOUT_MS)

    // Search raises the IME, and while the keyboard window is up it owns the accessibility tree
    // UiAutomator searches - a wait made before it is dismissed times out on text that is plainly
    // on screen. backUntilText absorbs the press that closes it.
    device.pressKeyCode(KeyEvent.KEYCODE_SEARCH)
    waitForText("Search")
    backUntilText("Trending Movies", HOME_TIMEOUT_MS)

    // Exercise sustained home rail focus/scroll work. The repeat count is intentional: three
    // presses only prove that the first card can move, while the reported regression appears when
    // several lazy rows have been composed and artwork is being replaced.
    repeat(12) { device.pressDPadRight() }
    repeat(5) {
      device.pressDPadDown()
      repeat(6) { device.pressDPadRight() }
    }
    repeat(4) { device.pressDPadLeft() }
    repeat(3) { device.pressDPadUp() }

    // The fixture movie is stable, but the credentials stay only on the
    // dedicated profiling device. See docs/baseline-profile.md.
    startByDeepLink(PROFILE_MOVIE_DEEP_LINK)
    // "Cast" proves Details composed; the primary button ("Play", or "Resume" when the
    // profiling device has watch history for the fixture) holds initial focus, so CENTER is
    // the press that opens the picker regardless of which label it carries.
    waitForText("Cast", DETAILS_TIMEOUT_MS)
    device.pressDPadCenter()
    waitForTextContaining("release", STREAMS_TIMEOUT_MS)

    // Streams requests initial focus on its first playable release.
    device.pressDPadCenter()
    SystemClock.sleep(PLAYBACK_SAMPLE_MS)
    device.pressBack()
    device.waitForIdle()
  }

  private fun MacrobenchmarkScope.launchHome() {
    pressHome()
    startActivityAndWait()
    // The collapsed nav rail is icons-only, so the literal text "Home" exists nowhere on a
    // settled Home screen; the first rail's heading is what proves the fixture is browsable.
    // Generous timeout: this is a cold TMDB fetch over the profiling device's Wi-Fi.
    waitForText("Trending Movies", HOME_TIMEOUT_MS)
  }

  /**
   * Routes the app by URI.
   *
   * Deliberately unquoted, and that is the whole point of this helper: UiDevice's
   * `executeShellCommand` runs its argument through `Runtime.exec`, which tokenises on
   * whitespace and runs the binary
   * directly - there is no shell, so a quoted `-d '<uri>'` reaches `am` with the quote characters
   * still attached and the URI never parses. The app then reads it as an unrecognised link and
   * stays where it was, which is a deep link that silently does nothing rather than one that
   * fails. No shell also means the `&` between query parameters needs no protection.
   */
  private fun MacrobenchmarkScope.startByDeepLink(uri: String) {
    val output = device.executeShellCommand(
      "am start -W -a android.intent.action.VIEW -d $uri $ACTIVITY_COMPONENT",
    )
    if (output.contains("Error", ignoreCase = true)) {
      fail("Deep link $uri was not accepted by am: ${output.trim()}")
    }
    device.waitForIdle()
  }

  /**
   * BACK until [text] is on screen, rather than a fixed number of presses.
   *
   * A single BACK is not reliably one step of navigation here: the nav drawer takes the first
   * one to close itself whenever it holds focus (which the rail-scroll leg above can leave it
   * doing), and a raised keyboard takes one to dismiss. Counting presses instead either strands
   * the journey a screen short or walks it out of the app entirely - and an over-press that
   * leaves the launcher on screen is the more expensive mistake, because every later leg then
   * fails against a screen the app does not own.
   */
  private fun MacrobenchmarkScope.backUntilText(text: String, timeoutMs: Long = UI_TIMEOUT_MS) {
    repeat(MAX_BACK_PRESSES) {
      if (device.wait(Until.hasObject(By.text(text)), timeoutMs / MAX_BACK_PRESSES)) {
        device.waitForIdle()
        return
      }
      device.pressBack()
      device.waitForIdle()
    }
    if (!device.wait(Until.hasObject(By.text(text)), timeoutMs)) {
      fail("Baseline-profile fixture never came back to '$text' after $MAX_BACK_PRESSES BACK presses")
    }
    device.waitForIdle()
  }

  private fun MacrobenchmarkScope.waitForText(text: String, timeoutMs: Long = UI_TIMEOUT_MS) {
    if (!device.wait(Until.hasObject(By.text(text)), timeoutMs)) {
      fail("Baseline-profile fixture did not expose '$text' within ${timeoutMs}ms")
    }
    device.waitForIdle()
  }

  private fun MacrobenchmarkScope.waitForTextContaining(
    text: String,
    timeoutMs: Long = UI_TIMEOUT_MS,
  ) {
    if (!device.wait(Until.hasObject(By.textContains(text)), timeoutMs)) {
      fail("Baseline-profile fixture did not expose text containing '$text' within ${timeoutMs}ms")
    }
    device.waitForIdle()
  }

  private companion object {
    const val PACKAGE_NAME = "com.stremioshell.host.tv"
    const val ACTIVITY_COMPONENT =
      "com.stremioshell.host.tv/com.stremioshell.host.tv.TvAppActivity"
    const val PROFILE_MOVIE_DEEP_LINK =
      "stremio-tv://watch-next?type=movie&tmdb=550"
    const val SETTINGS_DEEP_LINK = "stremio-tv://settings"
    const val UI_TIMEOUT_MS = 10_000L

    /** Home is ready when its first rail heading is: a cold TMDB fetch, not a local compose. */
    const val HOME_TIMEOUT_MS = 20_000L

    /**
     * Enough for the deepest leg (picker over details over home) plus one press absorbed by the
     * drawer or the keyboard, and short of walking out of the app from anywhere the journey goes.
     */
    const val MAX_BACK_PRESSES = 4
    const val DETAILS_TIMEOUT_MS = 20_000L
    const val STREAMS_TIMEOUT_MS = 30_000L
    const val PLAYBACK_SAMPLE_MS = 15_000L
  }
}
