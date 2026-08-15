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
  fun `a trailing backslash cannot swallow the comma that separates two headers`() {
    // mpv drops one backslash off an escaped separator and has no rule for a
    // doubled one, so escaping by doubling handed the value the joining comma:
    // `User-Agent: evil\,Accept: video/*` arrives at mpv as a single header whose
    // value is `evil,Accept: video/*`. The backslash is removed instead.
    val value = StreamRequestHeaders.mpvValue(
      linkedMapOf(
        "User-Agent" to "evil\\",
        "Accept" to "video/*",
      ),
    )

    assertEquals("User-Agent: evil,Accept: video/*", value)
  }

  @Test
  fun `backslashes are stripped from values rather than escaped`() {
    // None of the three allowlisted headers legitimately carries one, and any
    // count of them that survived would leave the meaning of the following comma
    // depending on that count.
    val value = StreamRequestHeaders.mpvValue(
      linkedMapOf("Accept-Language" to "en\\\\,de\\x"),
    )

    // What mpv parses back out of this is the one header `en,dex`.
    assertEquals("Accept-Language: en\\,dex", value)
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
  fun `request header aggregate is bounded for intent and saved state payloads`() {
    val headers = (0 until 32).associate { index ->
      "X-Token-$index" to "x".repeat(8 * 1024)
    }

    val safe = StreamRequestHeaders.sanitize(headers)
    val totalChars = safe.entries.sumOf { (name, value) -> name.length + value.length + 2 }

    assertTrue(totalChars <= StreamRequestHeaders.MAX_TOTAL_CHARS)
  }

  @Test
  fun `irrelevant addon headers cannot consume mpv's playback allowlist budget`() {
    val headers = linkedMapOf<String, String>()
    repeat(40) { headers["X-Noise-$it"] = "value" }
    headers["User-Agent"] = "Nebula"

    assertEquals("User-Agent: Nebula", StreamRequestHeaders.mpvValue(headers))
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
  fun `the name the countdown tick calls is the up-next timer's own arithmetic`() {
    // One implementation, two names: the activity's tick calls this one, and the
    // card's seconds and due-check come from UpNextPolicy. They used to be two
    // copies that agreed by luck, so the boundaries are asserted as equality with
    // the surviving implementation rather than as literals.
    listOf(
      -5_000L to 15_000L,
      0L to 15_000L,
      7_500L to 15_000L,
      15_000L to 15_000L,
      20_000L to 15_000L,
      0L to 0L,
      0L to -1L,
      0L to Long.MAX_VALUE,
    ).forEach { (elapsedMs, totalMs) ->
      assertEquals(
        UpNextPolicy.progressRemaining(elapsedMs, totalMs),
        PlaybackFrameRate.progressRemaining(elapsedMs, totalMs),
        0f,
      )
    }
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

  @Test
  fun `a paused or backgrounded cache stall cannot become a playback failure`() {
    assertTrue(
      PlaybackStallPolicy.shouldReportFailure(
        generationMatches = true,
        buffering = true,
        paused = false,
        activityStarted = true,
        hasPlaybackError = false,
      ),
    )
    assertFalse(
      PlaybackStallPolicy.shouldReportFailure(
        generationMatches = true,
        buffering = true,
        paused = true,
        activityStarted = true,
        hasPlaybackError = false,
      ),
    )
    assertFalse(
      PlaybackStallPolicy.shouldReportFailure(
        generationMatches = true,
        buffering = true,
        paused = false,
        activityStarted = false,
        hasPlaybackError = false,
      ),
    )
  }

  @Test
  fun `common restoration load preserves pause while explicit play paths clear it`() {
    assertTrue(
      PlaybackLoadPausePolicy.pauseRequested(
        current = true,
        reason = PlaybackLoadReason.Common,
      ),
    )
    listOf(
      PlaybackLoadReason.ExplicitSelection,
      PlaybackLoadReason.Retry,
      PlaybackLoadReason.NextEpisode,
    ).forEach { reason ->
      assertFalse(PlaybackLoadPausePolicy.pauseRequested(current = true, reason = reason))
    }
    assertFalse(
      PlaybackLoadPausePolicy.pauseRequested(
        current = false,
        reason = PlaybackLoadReason.Common,
      ),
    )
  }

  @Test
  fun `sleep expiry rejects answers from both kinds of earlier resolver`() {
    assertTrue(PlaybackResolutionPolicy.acceptsResult(4, 4))
    assertFalse(PlaybackResolutionPolicy.acceptsResult(4, 5))
  }

  @Test
  fun `media session seek transport is blocked for every up next state`() {
    assertTrue(
      PlaybackTransportPolicy.seekAllowed(
        transportAllowed = true,
        upNextVisible = false,
        nextEpisodeResolving = false,
      ),
    )
    assertFalse(
      PlaybackTransportPolicy.seekAllowed(
        transportAllowed = true,
        upNextVisible = true,
        nextEpisodeResolving = false,
      ),
    )
    assertFalse(
      PlaybackTransportPolicy.seekAllowed(
        transportAllowed = true,
        upNextVisible = false,
        nextEpisodeResolving = true,
      ),
    )
  }

  @Test
  fun `manual next marks credits watched but keeps a halfway episode resumable`() {
    assertTrue(PlaybackNextPolicy.marksCurrentWatched(positionSec = 91.0, durationSec = 100.0))
    assertFalse(PlaybackNextPolicy.marksCurrentWatched(positionSec = 50.0, durationSec = 100.0))
  }
}
