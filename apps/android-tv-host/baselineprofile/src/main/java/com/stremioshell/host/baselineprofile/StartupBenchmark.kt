package com.stremioshell.host.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Measures the same cold launch with and without the committed profile. */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
  @get:Rule
  val benchmarkRule = MacrobenchmarkRule()

  @Test
  fun coldStartupWithoutCompilation() = measure(CompilationMode.None())

  @Test
  fun coldStartupWithBaselineProfile() = measure(
    CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
  )

  private fun measure(compilationMode: CompilationMode) {
    benchmarkRule.measureRepeated(
      packageName = PACKAGE_NAME,
      metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
      compilationMode = compilationMode,
      startupMode = StartupMode.COLD,
      iterations = ITERATIONS,
      setupBlock = { pressHome() },
    ) {
      startActivityAndWait()
      assertTrue(
        "Cold launch never exposed Home",
        device.wait(Until.hasObject(By.text("Home")), UI_TIMEOUT_MS),
      )
    }
  }

  private companion object {
    const val PACKAGE_NAME = "com.stremioshell.host.tv"
    const val ITERATIONS = 10
    const val UI_TIMEOUT_MS = 10_000L
  }
}
