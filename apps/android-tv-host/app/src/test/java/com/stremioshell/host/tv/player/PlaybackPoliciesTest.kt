package com.stremioshell.host.tv.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPoliciesTest {
  @Test
  fun `fresh resume is a per-file load option`() {
    assertArrayEquals(
      arrayOf("loadfile", "https://example.test/video", "replace", "-1", "start=20"),
      PlaybackLoadCommand.build("https://example.test/video", replace = false, resumeMs = 20_000),
    )
  }

  @Test
  fun `short resume does not add start option`() {
    assertArrayEquals(
      arrayOf("loadfile", "video", "replace"),
      PlaybackLoadCommand.build("video", replace = true, resumeMs = 3_000),
    )
  }

  @Test
  fun `replacement resume uses playlist index before options`() {
    assertArrayEquals(
      arrayOf("loadfile", "video", "replace", "-1", "start=91.234"),
      PlaybackLoadCommand.build("video", replace = true, resumeMs = 91_234),
    )
  }

  @Test
  fun `http root cause outranks later youtube-dl fallback`() {
    val errors = PlaybackErrorAccumulator()
    errors.record("ffmpeg", "https: HTTP error 403 Forbidden")
    errors.record("stream", "Failed to open https://token@example.test/path?secret=yes")
    errors.record("ytdl_hook", "youtube-dl failed: not found or not enough permissions")

    assertEquals(
      "The stream link was rejected or has expired (HTTP 403).",
      errors.messageOr("fallback"),
    )
  }

  @Test
  fun `diagnostic URLs are always redacted`() {
    val errors = PlaybackErrorAccumulator()
    errors.record("stream", "Failed to open https://example.test/path?token=super-secret")

    val message = errors.messageOr("fallback")
    assertFalse(message.contains("super-secret"))
    assertTrue(message.contains("<stream URL>"))
  }

  @Test
  fun `reset starts a clean file diagnostic scope`() {
    val errors = PlaybackErrorAccumulator()
    errors.record("ffmpeg", "HTTP error 404")
    errors.reset()

    assertEquals("new default", errors.messageOr("new default"))
  }

  @Test
  fun `late diagnostics from a replaced file cannot poison the new generation`() {
    val errors = PlaybackErrorAccumulator()
    errors.reset(generation = 1)
    errors.record("ffmpeg", "HTTP error 404", generation = 1)

    errors.reset(generation = 2)
    errors.record("ffmpeg", "HTTP error 403", generation = 1)

    assertEquals("new default", errors.messageOr("new default"))
  }

  @Test
  fun `native playback receives only non-secret negotiation headers`() {
    val value = StreamRequestHeaders.mpvValue(
      linkedMapOf(
        "Referer" to "https://example.test/a,b",
        "Host" to "attacker.test",
        "Bad\nName" to "value",
        "Authorization" to "Bearer token",
        "Cookie" to "session=secret",
        "X-Api-Key" to "secret",
        "User-Agent" to "Nebula,TV",
        "Accept" to "video/*",
      ),
    )

    assertEquals(
      "User-Agent: Nebula\\,TV,Accept: video/*",
      value,
    )
  }

  @Test
  fun `all control characters and oversized header input are rejected`() {
    val headers = linkedMapOf(
      "LeadingTab" to "\tBearer secret",
      "UnitSeparator" to "value\u001fhidden",
      "Delete" to "value\u007fhidden",
      "X-${"n".repeat(128)}" to "too long a name",
      "Huge" to "x".repeat(8 * 1024 + 1),
      "Safe" to "ordinary value",
    )

    assertEquals(mapOf("Safe" to "ordinary value"), StreamRequestHeaders.sanitize(headers))
  }

  @Test
  fun `request header count is bounded and names are case insensitive`() {
    val headers = linkedMapOf<String, String>()
    headers["Authorization"] = "first"
    headers["authorization"] = "second"
    repeat(40) { headers["X-Test-$it"] = "value" }

    val safe = StreamRequestHeaders.sanitize(headers)

    assertEquals(32, safe.size)
    assertEquals("first", safe["Authorization"])
    assertFalse(safe.containsKey("authorization"))
  }

  @Test
  fun `subtitle inherits credentials only on the exact stream origin`() {
    val headers = mapOf("Authorization" to "Bearer secret", "Cookie" to "session=secret")

    assertEquals(
      headers,
      StreamRequestHeaders.forSameOrigin(
        "https://video.example:8443/file",
        "https://video.example:8443/subtitles/en.srt",
        headers,
      ),
    )
    assertTrue(
      StreamRequestHeaders.forSameOrigin(
        "https://video.example:8443/file",
        "https://subtitles.example:8443/en.srt",
        headers,
      ).isEmpty(),
    )
    assertTrue(
      StreamRequestHeaders.forSameOrigin(
        "https://video.example:8443/file",
        "http://video.example:8443/en.srt",
        headers,
      ).isEmpty(),
    )
    assertTrue(
      StreamRequestHeaders.forSameOrigin(
        "https://video.example:8443/file",
        "https://video.example/en.srt",
        headers,
      ).isEmpty(),
    )
  }

  @Test
  fun `later equal-priority HTTP verdict replaces a transient one`() {
    val errors = PlaybackErrorAccumulator()
    errors.record("ffmpeg", "HTTP error 503")
    errors.record("ffmpeg", "HTTP error 403")

    assertEquals(
      "The stream link was rejected or has expired (HTTP 403).",
      errors.messageOr("fallback"),
    )
  }

  @Test
  fun `effective display vote follows playback speed`() {
    assertEquals(47.952f, PlaybackFrameRate.effective(23.976f, 2.0), 0.001f)
    assertEquals(0f, PlaybackFrameRate.effective(0f, 2.0), 0f)
  }

  @Test
  fun `countdown progress drains smoothly and clamps`() {
    assertEquals(1f, PlaybackFrameRate.progressRemaining(0, 15_000), 0f)
    assertEquals(0.5f, PlaybackFrameRate.progressRemaining(7_500, 15_000), 0f)
    assertEquals(0f, PlaybackFrameRate.progressRemaining(20_000, 15_000), 0f)
  }

  @Test
  fun `unknown frame rate starts once per load generation`() {
    assertTrue(PlaybackFrameRate.shouldStartDiscovery(0f, null, loadGeneration = 7))
    assertFalse(PlaybackFrameRate.shouldStartDiscovery(0f, 7, loadGeneration = 7))
    assertTrue(PlaybackFrameRate.shouldStartDiscovery(0f, 6, loadGeneration = 7))
    assertFalse(PlaybackFrameRate.shouldStartDiscovery(23.976f, null, loadGeneration = 7))
  }

  @Test
  fun `failed frame rate read releases only its matching discovery generation`() {
    assertNull(
      PlaybackFrameRate.afterDiscoveryFailure(
        discoveryGeneration = 7,
        failedGeneration = 7,
      ),
    )
    assertEquals(
      8L,
      PlaybackFrameRate.afterDiscoveryFailure(
        discoveryGeneration = 8,
        failedGeneration = 7,
      ),
    )
  }

  @Test
  fun `restored resume reset state wins over stale launch intent`() {
    assertTrue(
      PlaybackResumePolicy.mergeResetRequest(
        launchRequested = true,
        restoredRequested = null,
      ),
    )
    assertTrue(
      PlaybackResumePolicy.mergeResetRequest(
        launchRequested = false,
        restoredRequested = true,
      ),
    )
    assertFalse(
      PlaybackResumePolicy.mergeResetRequest(
        launchRequested = true,
        restoredRequested = false,
      ),
    )
  }

  @Test
  fun `explicit start over persists below normal resume threshold`() {
    assertEquals(
      ResumeSaveAction.Reset,
      PlaybackResumePolicy.saveAction(
        finished = false,
        positionMs = 0,
        minimumSaveMs = 10_000,
        resetRequested = true,
      ),
    )
    assertEquals(
      ResumeSaveAction.Ignore,
      PlaybackResumePolicy.saveAction(
        finished = false,
        positionMs = 0,
        minimumSaveMs = 10_000,
        resetRequested = false,
      ),
    )
    assertEquals(
      ResumeSaveAction.Finished,
      PlaybackResumePolicy.saveAction(
        finished = true,
        positionMs = 0,
        minimumSaveMs = 10_000,
        resetRequested = true,
      ),
    )
  }
}
