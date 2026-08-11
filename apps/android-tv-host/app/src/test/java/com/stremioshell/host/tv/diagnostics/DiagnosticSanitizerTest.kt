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
  fun `redacts quoted json secrets, plural keys and spaced bearer headers`() {
    // What an addon's own error payload looks like when it is quoted verbatim into a report.
    val sanitized = DiagnosticSanitizer.sanitize(
      """{"token": "SECRET-A", "api_key":"SECRET-B", "api_keys": "SECRET-C", """ +
        """"passwords":"SECRET-D"} Authorization: Bearer SECRET-E""",
    )

    listOf("SECRET-A", "SECRET-B", "SECRET-C", "SECRET-D", "SECRET-E").forEach { secret ->
      assertFalse(sanitized, sanitized.contains(secret))
    }
    // The key stays readable and its quoting is put back, so the line is still legible JSON-ish.
    assertTrue(sanitized, sanitized.contains(""""token": "<redacted>""""))
    assertTrue(sanitized, sanitized.contains(""""api_key":"<redacted>""""))
    // "Bearer" is the scheme, not the value: the whole header value goes, rather than the word
    // being redacted as the value and the token after it surviving on its own.
    assertTrue(sanitized, sanitized.contains("Authorization: <redacted>"))
  }

  @Test
  fun `a stremio install link is redacted like the addon url it is`() {
    val sanitized = DiagnosticSanitizer.sanitize(
      "install stremio://comet.example/rd-secret-key/manifest.json failed",
    )

    assertFalse(sanitized, sanitized.contains("rd-secret-key"))
    // Re-labelled rather than collapsed: the host is the useful half of a support report.
    assertTrue(sanitized, sanitized.contains("stremio://comet.example/<redacted>/manifest.json"))
  }

  @Test
  fun `redaction leaves ordinary diagnostic prose alone`() {
    val sanitized = DiagnosticSanitizer.sanitize(
      "HTTP 502 from comet.example after 3 retries: timeout=connect",
    )

    assertEquals("HTTP 502 from comet.example after 3 retries: timeout=connect", sanitized)
  }

  @Test
  fun `normalizes whitespace and bounds event details`() {
    val sanitized = DiagnosticSanitizer.sanitize("line one\nline two\t${"x".repeat(500)}")

    assertFalse(sanitized.contains('\n'))
    assertFalse(sanitized.contains('\t'))
    assertEquals(400, sanitized.length)
  }
}
