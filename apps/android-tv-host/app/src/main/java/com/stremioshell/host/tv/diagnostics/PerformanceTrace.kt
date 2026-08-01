package com.stremioshell.host.tv.diagnostics

import android.annotation.SuppressLint
import android.os.Build
import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

/**
 * Small, dependency-free trace boundary for the TV critical journeys.
 *
 * Sections are deliberately short and named with a stable `Nebula.` prefix so a Perfetto capture
 * can separate app work from Compose, Coil, OkHttp, and libmpv without turning normal builds into a
 * logging stream. `Trace` is a no-op when tracing is disabled, which keeps these markers safe on
 * every supported API level.
 */
object PerformanceTrace {
  private const val PREFIX = "Nebula."
  private const val MAX_SECTION_NAME_LENGTH = 127

  @SuppressLint("UnclosedTrace")
  fun begin(name: String) {
    // The Android framework stub used by JVM unit tests throws for Trace methods. A trace marker is
    // diagnostic only, so that environment must remain a clean no-op rather than changing the
    // behavior of a network/parser test.
    runCatching { Trace.beginSection(sectionName(name)) }
  }

  fun end() {
    runCatching { Trace.endSection() }
  }

  inline fun <T> section(name: String, block: () -> T): T {
    begin(name)
    return try {
      block()
    } finally {
      end()
    }
  }

  suspend fun <T> suspendSection(name: String, block: suspend () -> T): T =
    asyncSection(name) { block() }

  /** Marks a route or focus transition from the current thread through its next rendered frame. */
  suspend fun suspendUntilNextFrame(name: String, awaitFrame: suspend () -> Unit) {
    asyncSection(name) { awaitFrame() }
  }

  /**
   * A span that is allowed to cross a suspension point.
   *
   * `beginSection`/`endSection` nest per *thread*, so a span drawn with them around a suspending
   * call is closed by whichever coroutine happens to resume on that thread next - which put
   * unrelated work inside `Nebula.details.load` and left the rail fetches overlapping each other in
   * a capture. An async section is matched by name and cookie instead, so the slice belongs to the
   * one operation wherever it resumes.
   *
   * The async trace API arrived in API 29 and this app supports 26, so older devices keep the
   * thread-local markers: imprecise across a suspension point, but better than losing the span.
   */
  private suspend fun <T> asyncSection(name: String, block: suspend () -> T): T {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      begin(name)
      return try {
        block()
      } finally {
        end()
      }
    }
    val cookie = cookies.incrementAndGet()
    beginAsync(name, cookie)
    return try {
      block()
    } finally {
      endAsync(name, cookie)
    }
  }

  @SuppressLint("UnclosedTrace")
  private fun beginAsync(name: String, cookie: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    runCatching { Trace.beginAsyncSection(sectionName(name), cookie) }
  }

  private fun endAsync(name: String, cookie: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    runCatching { Trace.endAsyncSection(sectionName(name), cookie) }
  }

  private fun sectionName(name: String): String =
    (PREFIX + name).take(MAX_SECTION_NAME_LENGTH)

  /** Only has to be unique among the spans open at once, so a wrapping counter is enough. */
  private val cookies = AtomicInteger()
}
