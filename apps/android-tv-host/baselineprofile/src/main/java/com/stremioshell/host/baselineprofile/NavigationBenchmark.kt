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
      assertTrue(
        "Home did not expose a content-ready rail; configure the documented benchmark fixture",
        device.wait(Until.hasObject(By.text("Trending Movies")), UI_TIMEOUT_MS),
      )
      repeat(12) { device.pressDPadRight() }
      repeat(5) {
        device.pressDPadDown()
        repeat(6) { device.pressDPadRight() }
      }
      repeat(4) { device.pressDPadLeft() }
      device.waitForIdle()
    }
  }

  private companion object {
    const val PACKAGE_NAME = "com.stremioshell.host.tv"
    const val ITERATIONS = 5
    const val UI_TIMEOUT_MS = 10_000L
  }
}
