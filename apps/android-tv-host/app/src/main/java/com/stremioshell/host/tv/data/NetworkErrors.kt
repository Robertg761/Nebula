package com.stremioshell.host.tv.data

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.serialization.SerializationException

/**
 * A non-2xx HTTP response.
 *
 * The message carries only the status code and host on purpose: TMDB request URLs embed
 * `api_key=<secret>` in their query string, and exception messages have a habit of ending up
 * rendered on screen. The full (redacted) URL goes to Logcat instead.
 */
class HttpStatusException(val code: Int, val host: String) : IOException("HTTP $code from $host")

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

private val SECRET_QUERY_PARAM = Regex(
  "([?&](?:api_key|apikey|api-key|token|access_token|key)=)[^&\\s]*",
  RegexOption.IGNORE_CASE,
)

/**
 * Masks secrets carried in query strings so a URL can be logged. Logcat on a TV is readable by
 * `adb logcat` and pasted into bug reports, so even the technical channel gets the key removed.
 */
fun redactSecrets(text: String): String =
  SECRET_QUERY_PARAM.replace(text) { match -> match.groupValues[1] + "<redacted>" }
