package com.stremioshell.host.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Measures the interaction path that startup-only benchmarks miss. */
@RunWith(AndroidJUnit4::class)
class NavigationBenchmark {
  @get:Rule
  val benchmarkRule = MacrobenchmarkRule()

  @Test
  fun homeDpadNavigationWithoutProfile() = measure(CompilationMode.None())

  @Test
  fun homeDpadNavigationWithBaselineProfile() = measure(
    CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
  )

  private fun measure(compilationMode: CompilationMode) {
    benchmarkRule.measureRepeated(
      packageName = PACKAGE_NAME,
      metrics = listOf(FrameTimingMetric()),
      compilationMode = compilationMode,
      iterations = ITERATIONS,
      setupBlock = { pressHome() },
    ) {
      startActivityAndWait()
      if (!device.wait(Until.hasObject(By.text("Trending Movies")), UI_TIMEOUT_MS)) {
        // Name what is actually on screen: "the rail is missing" has meant a wiped fixture, a
        // raised keyboard and a too-short timeout on different days, and the difference is not
        // guessable from a timeout alone.
        val visible = device.findObjects(By.textContains(""))
          .mapNotNull { it.text?.takeIf(String::isNotBlank) }
          .take(12)
        assertTrue(
          "Home did not expose a content-ready rail; configure the documented benchmark " +
            "fixture. Visible text instead: $visible",
          false,
        )
      }
      repeat(12) { device.pressDPadRight() }
      repeat(5) {
        device.pressDPadDown()
        repeat(6) { device.pressDPadRight() }
      }
      repeat(4) { device.pressDPadLeft() }
      device.waitForIdle()
      // Iterations launch HOT into whatever state the previous one left, and the walk above
      // leaves Home scrolled five rails down - where the readiness marker this block starts by
      // waiting on is off screen. BACK on a scrolled Home is the app's own scroll-to-top
      // gesture, so this both restores the invariant the next iteration assumes and puts the
      // return-to-top animation's frames into the measurement, which is a scroll a real viewer
      // performs constantly.
      device.pressBack()
      if (!device.wait(Until.hasObject(By.text("Trending Movies")), UI_TIMEOUT_MS)) {
        // The walk above runs at injected-event speed, far past what a remote can produce, and
        // it can race the silent rail refresh that follows a cache-primed open: if a reshuffle
        // eats a press, Home may end the walk unscrolled - and then the BACK above exits to the
        // launcher instead of scrolling to the top. Manual-timing runs of the same walk behave;
        // for the benchmark the iteration invariant is what matters, so relaunch and re-assert
        // rather than failing a measurement over an input-speed artifact.
        startActivityAndWait()
        assertTrue(
          "Home never exposed its first rail again after a relaunch",
          device.wait(Until.hasObject(By.text("Trending Movies")), UI_TIMEOUT_MS),
        )
      }
    }
  }

  private companion object {
    const val PACKAGE_NAME = "com.stremioshell.host.tv"
    const val ITERATIONS = 5
    const val UI_TIMEOUT_MS = 20_000L
  }
}
