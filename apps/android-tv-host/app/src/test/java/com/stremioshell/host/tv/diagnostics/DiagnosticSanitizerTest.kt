package com.stremioshell.host.tv.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSanitizerTest {
  @Test
  fun `redacts credential-bearing urls and named secrets`() {
    val sanitized = DiagnosticSanitizer.sanitize(
      "GET https://user:pass@comet.example/opaque-token/stream/movie/tt1.json?token=abc " +
        "api_key=tmdb-secret Authorization:BearerSecret Bearer ey.secret.token",
    )

    assertFalse(sanitized.contains("opaque-token"))
    assertFalse(sanitized.contains("tmdb-secret"))
    assertFalse(sanitized.contains("ey.secret.token"))
    assertTrue(sanitized.contains("https://comet.example/<redacted>/stream/movie/tt1.json?<redacted>"))
    assertTrue(sanitized.contains("api_key=<redacted>"))
    assertTrue(sanitized.contains("Bearer <redacted>"))
  }

  @Test
  fun `normalizes whitespace and bounds event details`() {
    val sanitized = DiagnosticSanitizer.sanitize("line one\nline two\t${"x".repeat(500)}")

    assertFalse(sanitized.contains('\n'))
    assertFalse(sanitized.contains('\t'))
    assertEquals(400, sanitized.length)
  }
}
