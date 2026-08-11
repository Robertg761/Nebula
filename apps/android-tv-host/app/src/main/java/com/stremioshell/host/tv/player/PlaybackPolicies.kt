package com.stremioshell.host.tv.player

import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Builds the one `loadfile` command used by fresh playback, retry and episode replacement.
 *
 * `start` is deliberately file-local. Setting it before `MPVLib.init()` looks plausible but mpv
 * does not carry that value into the later file load on Android; keeping it on the command also
 * prevents a retry position leaking into the following episode.
 */
object PlaybackLoadCommand {
  fun build(url: String, replace: Boolean, resumeMs: Long): Array<String> {
    val command = mutableListOf("loadfile", url)
    if (replace) command += "replace"
    val startSec = resumeMs.takeIf { it > MIN_RESUME_MS }?.div(1000.0)
    if (startSec != null) {
      // Since mpv 0.38 the third loadfile argument is the playlist index. It has to be present
      // before the fourth, per-file option argument even when no playlist index is requested.
      if (!replace) command += "replace"
      command += "-1"
      command += "start=${formatSeconds(startSec)}"
    }
    return command.toTypedArray()
  }

  private fun formatSeconds(value: Double): String =
    String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')

  private const val MIN_RESUME_MS = 3_000L
}

/**
 * A safe subset of request headers for mpv's `http-header-fields` string-list option.
 *
 * Protocol add-ons are untrusted input. Header names and values containing control characters are
 * rejected rather than allowed to create a second header. Hop-by-hop and routing headers are also
 * rejected: the player, not an add-on, owns the target host and connection framing.
 */
object StreamRequestHeaders {
  private val NAME = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")
  private val NATIVE_PLAYBACK_HEADERS = setOf(
    "accept",
    "accept-language",
    "user-agent",
  )
  private val FORBIDDEN = setOf(
    "connection",
    "content-length",
    "host",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
  )

  fun sanitize(headers: Map<String, String>): Map<String, String> {
    val safe = linkedMapOf<String, String>()
    val seenNames = hashSetOf<String>()
    var totalChars = 0
    headers.forEach { (rawName, rawValue) ->
      // Inspect before trim: trimming first used to turn a leading CR, tab or other control into a
      // valid-looking field. Header names cannot contain whitespace at all; values may contain
      // ordinary spaces but no C0 control or DEL character.
      if (rawName.any(::isControl) || rawName.any(Char::isWhitespace)) return@forEach
      if (rawValue.any(::isControl)) return@forEach
      val name = rawName.trim()
      val value = rawValue.trim()
      val normalizedName = name.lowercase(Locale.ROOT)
      if (
        name.length !in 1..MAX_NAME_CHARS ||
        value.length !in 1..MAX_VALUE_CHARS ||
        !NAME.matches(name) ||
        normalizedName in FORBIDDEN ||
        normalizedName in seenNames ||
        safe.size >= MAX_HEADERS
      ) {
        return@forEach
      }
      val addedChars = name.length + value.length + 2
      if (totalChars + addedChars > MAX_TOTAL_CHARS) return@forEach
      safe[name] = value
      seenNames += normalizedName
      totalChars += addedChars
    }
    return safe
  }

  private fun isControl(char: Char): Boolean = char.code < 0x20 || char.code == 0x7f

  /**
   * mpv parses this option as a comma-separated string list, so a value carrying a comma has to be
   * escaped or it becomes a second header — the whole reason this function exists.
   *
   * mpv's list splitter (`get_nextsep`) has exactly one rule: a separator directly preceded by a
   * backslash is not a separator, and that one backslash is removed. There is no `\\` → `\` rule,
   * which is why doubling backslashes — what this used to do — is not an escape but an injection.
   * `a\\` was emitted as `a\\\\`, mpv dropped one backslash off the joining comma, ate the comma
   * and merged the header with the one after it. Backslashes are therefore removed from the value
   * outright before the comma is escaped: none of the three allowlisted headers legitimately
   * carries one, so there is nothing to preserve and stripping leaves no sequence whose meaning
   * depends on how many backslashes precede it. Header names cannot reach either metacharacter —
   * [NAME] admits neither.
   *
   * Native mpv owns redirects and DNS after the initial URL validation. Until playback is routed
   * through an app-controlled transport, never give that redirect chain credentials, referrers or
   * arbitrary addon headers. The small allowlist is limited to content negotiation and client
   * identification; same-origin subtitle downloads may still use the complete sanitized set.
   */
  fun mpvValue(headers: Map<String, String>): String =
    sanitize(headers)
      .filterKeys { it.lowercase(Locale.ROOT) in NATIVE_PLAYBACK_HEADERS }
      .entries
      .joinToString(",") { (name, value) ->
        "$name: ${value.replace("\\", "").replace(",", "\\,")}"
      }

  /**
   * Returns stream credentials only when [resourceUrl] has the exact same origin as [streamUrl].
   *
   * Subtitle URLs are controlled by addons and commonly point at a CDN unrelated to the playing
   * stream. Forwarding Authorization or Cookie there would disclose a debrid account token. An
   * invalid, relative, downgraded, cross-host or cross-port URL therefore receives no inherited
   * headers at all.
   */
  fun forSameOrigin(
    streamUrl: String,
    resourceUrl: String,
    headers: Map<String, String>,
  ): Map<String, String> {
    val stream = streamUrl.toHttpUrlOrNull() ?: return emptyMap()
    val resource = resourceUrl.toHttpUrlOrNull() ?: return emptyMap()
    if (
      stream.scheme != resource.scheme ||
      stream.host != resource.host ||
      stream.port != resource.port
    ) {
      return emptyMap()
    }
    return sanitize(headers)
  }

  private const val MAX_HEADERS = 32
  private const val MAX_NAME_CHARS = 128
  private const val MAX_VALUE_CHARS = 8 * 1024
  private const val MAX_TOTAL_CHARS = 64 * 1024
}

/**
 * Keeps the most useful mpv diagnostic for one file and turns it into safe viewer-facing copy.
 *
 * mpv often reports one failure several ways. In particular, a direct HTTP 403 is followed by the
 * ytdl hook trying three absent executables. Last-line-wins therefore blames youtube-dl instead of
 * the expired stream. Prefix-aware priorities keep the root cause, and URLs are removed before any
 * text can reach the television or logs.
 */
class PlaybackErrorAccumulator {
  private var best: Candidate? = null
  private var generation: Long = 0L

  @Synchronized
  fun reset(generation: Long = this.generation) {
    this.generation = generation
    best = null
  }

  @Synchronized
  fun record(prefix: String, text: String, generation: Long = this.generation) {
    if (generation != this.generation) return
    val sanitized = redact(text.trim())
    if (sanitized.isBlank()) return
    val candidate = Candidate(
      priority = priority(prefix, sanitized),
      message = viewerMessage(sanitized),
    )
    // Later diagnostics of equal specificity are closer to mpv's terminal verdict. This matters
    // when a reconnect reports 503 first and the final signed-link rejection is 403.
    if (candidate.priority >= (best?.priority ?: Int.MIN_VALUE)) best = candidate
  }

  @Synchronized
  fun messageOr(default: String): String = best?.message ?: default

  private fun priority(prefix: String, message: String): Int {
    val source = prefix.lowercase(Locale.ROOT)
    val lower = message.lowercase(Locale.ROOT)
    return when {
      HTTP_STATUS.containsMatchIn(lower) -> 100
      source.startsWith("stream") && ("failed" in lower || "error" in lower) -> 90
      source.startsWith("ffmpeg") && ("tls" in lower || "network" in lower) -> 85
      source.startsWith("ad") || source.startsWith("vd") || source.startsWith("demux") -> 75
      source.startsWith("ytdl") || "youtube-dl" in lower || "yt-dlp" in lower -> 10
      else -> 50
    }
  }

  private fun viewerMessage(message: String): String {
    val status = HTTP_STATUS.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
    return when (status) {
      401, 403 -> "The stream link was rejected or has expired (HTTP $status)."
      404, 410 -> "The stream link is no longer available (HTTP $status)."
      in 500..599 -> "The stream host failed (HTTP $status). Try again in a moment."
      null -> message.take(MAX_ERROR_CHARS)
      else -> "The stream request failed (HTTP $status)."
    }
  }

  private data class Candidate(val priority: Int, val message: String)

  companion object {
    private val HTTP_STATUS = Regex("""(?i)\bHTTP(?:\s+error)?\s+(\d{3})\b""")
    private val URL = Regex("""(?i)\bhttps?://[^\s"'<>]+""")
    private const val MAX_ERROR_CHARS = 160

    fun redact(text: String): String = URL.replace(text, "<stream URL>")
  }
}

/** Pure arithmetic shared by the display vote and its JVM tests. */
object PlaybackFrameRate {
  fun effective(contentFps: Float, speed: Double): Float {
    if (!contentFps.isFinite() || contentFps <= 0f || !speed.isFinite() || speed <= 0.0) return 0f
    return (contentFps * speed).toFloat().takeIf { it.isFinite() } ?: 0f
  }

  /**
   * The up-next countdown bar's fraction, which is not frame-rate arithmetic at all: it lives here
   * only because the activity's countdown tick calls it by this name. There used to be a second
   * copy of the same arithmetic in [UpNextPolicy], and the two agreeing at the negative, zero,
   * exact-end and overrun boundaries was luck rather than structure — production ran this one while
   * the tests exercised that one. [UpNextPolicy.progressRemaining] is now the only implementation,
   * beside the [UpNextPolicy.secondsLeft] and [UpNextPolicy.isDue] it has to agree with.
   */
  fun progressRemaining(elapsedMs: Long, totalMs: Long): Float =
    UpNextPolicy.progressRemaining(elapsedMs, totalMs)

  /**
   * Starts one bounded discovery cycle per file generation while the content rate is unknown.
   * A stale generation is not allowed to suppress discovery for the replacement file.
   */
  fun shouldStartDiscovery(
    contentFps: Float,
    discoveryGeneration: Long?,
    loadGeneration: Long,
  ): Boolean =
    (!contentFps.isFinite() || contentFps <= 0f) && discoveryGeneration != loadGeneration

  /** A failed read releases only the discovery cycle that issued it. */
  fun afterDiscoveryFailure(
    discoveryGeneration: Long?,
    failedGeneration: Long,
  ): Long? = discoveryGeneration.takeUnless { it == failedGeneration }
}

enum class ResumeSaveAction { Finished, Position, Reset, Ignore }

/** Pure launch/restoration and persistence decisions for an explicit Start Over request. */
object PlaybackResumePolicy {
  /** Restored state wins, including `false`, so recreation cannot resurrect a consumed intent. */
  fun mergeResetRequest(launchRequested: Boolean, restoredRequested: Boolean?): Boolean =
    restoredRequested ?: launchRequested

  fun saveAction(
    finished: Boolean,
    positionMs: Long,
    minimumSaveMs: Long,
    resetRequested: Boolean,
  ): ResumeSaveAction = when {
    finished -> ResumeSaveAction.Finished
    positionMs > minimumSaveMs -> ResumeSaveAction.Position
    resetRequested -> ResumeSaveAction.Reset
    else -> ResumeSaveAction.Ignore
  }
}
