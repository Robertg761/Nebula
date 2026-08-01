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

/**
 * Records startup and the TV's critical user journey on a provisioned device.
 *
 * This deliberately fails instead of emitting a startup-only profile when the
 * test TMDB/addon fixture is missing. A short but green generator would give a
 * false sense that Details, Streams and playback were represented.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
  @get:Rule
  val baselineProfileRule = BaselineProfileRule()

  @Test
  fun generate() = baselineProfileRule.collect(
    packageName = PACKAGE_NAME,
    includeInStartupProfile = true,
  ) {
    pressHome()
    startActivityAndWait()
    waitForText("Home")
    waitForText("Trending Movies")

    // Exercise sustained home rail focus/scroll work before leaving the route. The repeat count is
    // intentional: three presses only prove that the first card can move, while the reported
    // regression appears when several lazy rows have been composed and artwork is being replaced.
    repeat(12) { device.pressDPadRight() }
    repeat(5) {
      device.pressDPadDown()
      repeat(6) { device.pressDPadRight() }
    }
    repeat(4) { device.pressDPadLeft() }
    repeat(3) { device.pressDPadUp() }

    device.pressKeyCode(KeyEvent.KEYCODE_SEARCH)
    waitForText("Search")
    device.pressBack()
    waitForText("Home")

    openByText("Settings")
    waitForText("Settings")
    device.pressBack()
    waitForText("Home")

    // The fixture movie is stable, but the credentials stay only on the
    // dedicated profiling device. See docs/baseline-profile.md.
    device.executeShellCommand(
      "am start -W -a android.intent.action.VIEW " +
        "-d '$PROFILE_MOVIE_DEEP_LINK' $ACTIVITY_COMPONENT",
    )
    openByText("Play", DETAILS_TIMEOUT_MS)
    waitForTextContaining("release", STREAMS_TIMEOUT_MS)

    // Streams requests initial focus on its first playable release.
    device.pressDPadCenter()
    SystemClock.sleep(PLAYBACK_SAMPLE_MS)
    device.pressBack()
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

  private fun MacrobenchmarkScope.openByText(text: String, timeoutMs: Long = UI_TIMEOUT_MS) {
    val node = device.wait(Until.findObject(By.text(text)), timeoutMs)
      ?: throw AssertionError(
        "Baseline-profile fixture did not expose '$text' within ${timeoutMs}ms",
      )
    node.click()
    device.waitForIdle()
  }

  private companion object {
    const val PACKAGE_NAME = "com.stremioshell.host.tv"
    const val ACTIVITY_COMPONENT =
      "com.stremioshell.host.tv/com.stremioshell.host.tv.TvAppActivity"
    const val PROFILE_MOVIE_DEEP_LINK =
      "stremio-tv://watch-next?type=movie&tmdb=550"
    const val UI_TIMEOUT_MS = 10_000L
    const val DETAILS_TIMEOUT_MS = 20_000L
    const val STREAMS_TIMEOUT_MS = 30_000L
    const val PLAYBACK_SAMPLE_MS = 15_000L
  }
}
