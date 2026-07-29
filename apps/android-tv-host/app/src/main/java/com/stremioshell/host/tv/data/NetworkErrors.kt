package com.stremioshell.host.tv.data

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A non-2xx HTTP response.
 *
 * The message carries only the status code and host on purpose: TMDB request URLs embed
 * `api_key=<secret>` in their query string, and exception messages have a habit of ending up
 * rendered on screen. The full (redacted) URL goes to Logcat instead.
 */
class HttpStatusException(
  val code: Int,
  val host: String,
  /** Parsed server backoff hint, when Retry-After was present and valid. */
  val retryAfterSeconds: Long? = null,
) : IOException("HTTP $code from $host")

/** A successful endpoint returned more JSON than a TV should ever hold in memory. */
class HttpResponseTooLargeException(val maxBytes: Long) :
  IOException("Response exceeded $maxBytes bytes")

/** Which service a failure came from, so the message can point at the right setting. */
enum class NetworkSource(
  /** How the service is named mid-sentence, e.g. "Couldn't reach TMDB". */
  internal val label: String,
  /** What to check in Settings when the service rejects our credentials. */
  internal val credentialHint: String,
) {
  Tmdb("TMDB", "check your API key in Settings"),
  Addon("the addon", "check your addon URL in Settings"),
}

/**
 * Turns a data-layer throwable into a sentence that is safe to show on a TV.
 *
 * Pure and Android-free so it can be unit tested. Two rules it exists to enforce:
 *  - no raw exception text ever reaches the UI (it can contain the TMDB api_key), and
 *  - the user is told what to do next, not what class threw.
 */
object NetworkErrorMessage {
  /** Causes are only worth walking a few levels; anything deeper is noise. */
  private const val MAX_CAUSE_DEPTH = 5

  fun forThrowable(source: NetworkSource, error: Throwable?): String {
    var current = error
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
      recognize(source, current)?.let { return it }
      current = current.cause
      depth++
    }
    return "Something went wrong loading from ${source.label}."
  }

  fun forStatus(source: NetworkSource, code: Int): String = when {
    code == 401 || code == 403 ->
      "Couldn't reach ${source.label} (HTTP $code) - ${source.credentialHint}."
    code == 404 -> "Nothing found on ${source.label} for this request (HTTP 404)."
    code == 429 -> "Hit ${source.label}'s rate limit - try again in a moment."
    code in 500..599 -> "Couldn't reach ${source.label} (HTTP $code) - it may be down. Try again shortly."
    else -> "Couldn't reach ${source.label} (HTTP $code)."
  }

  private fun recognize(source: NetworkSource, error: Throwable): String? = when (error) {
    is HttpStatusException -> forStatus(source, error.code)
    is HttpResponseTooLargeException ->
      "The response from ${source.label} was too large to use safely."
    is UnknownHostException -> "No internet connection. Check your network and try again."
    // OkHttp's callTimeout surfaces as a plain InterruptedIOException, not a SocketTimeoutException.
    is SocketTimeoutException, is InterruptedIOException ->
      "Timed out waiting for ${source.label}. Check your connection and try again."
    is SSLException -> "Secure connection to ${source.label} failed."
    is SerializationException -> "Couldn't read the response from ${source.label}."
    is IOException -> "Couldn't reach ${source.label}. Check your network connection."
    else -> null
  }
}

/**
 * Produces the structurally safe form of a URL for Logcat.
 *
 * Addon credentials are not limited to parameters named `token`: configured
 * Stremio URLs routinely put opaque debrid credentials in userinfo, arbitrary
 * query keys, or a path segment before `manifest.json`. Parsing the URL lets us
 * discard all three without trying to guess secret names. Known public resource
 * suffixes remain so a report can still say which operation failed.
 */
fun redactSecrets(text: String): String {
  val url = text.toHttpUrlOrNull() ?: return "<redacted-url>"
  val origin = url.newBuilder()
    .username("")
    .password("")
    .encodedPath("/")
    .query(null)
    .fragment(null)
    .build()
    .toString()
    .removeSuffix("/")

  val segments = url.encodedPathSegments.filter { it.isNotEmpty() }
  val safePath = if (url.host.equals(TMDB_HOST, ignoreCase = true)) {
    url.encodedPath
  } else {
    val resourceIndex = segments.indexOfFirst {
      it.lowercase(Locale.ROOT) in PUBLIC_ADDON_RESOURCES
    }
    when {
      resourceIndex >= 0 -> {
        val suffix = segments.drop(resourceIndex).joinToString("/")
        if (resourceIndex == 0) "/$suffix" else "/<redacted>/$suffix"
      }
      segments.lastOrNull()?.equals("manifest.json", ignoreCase = true) == true ->
        if (segments.size == 1) "/manifest.json" else "/<redacted>/manifest.json"
      else -> "/<redacted>"
    }
  }
  val queryMarker = if (url.query != null) "?<redacted>" else ""
  return origin + safePath + queryMarker
}

private const val TMDB_HOST = "api.themoviedb.org"
private val PUBLIC_ADDON_RESOURCES = setOf("stream", "subtitles", "catalog", "meta")
