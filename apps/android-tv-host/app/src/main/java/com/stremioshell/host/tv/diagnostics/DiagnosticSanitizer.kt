package com.stremioshell.host.tv.diagnostics

import com.stremioshell.host.tv.data.redactSecrets

/**
 * Keeps diagnostics useful without turning a support report into a credential export.
 *
 * URLs are reduced to the same origin/resource form used by network logging. The second pass
 * catches secrets in non-URL text, and the final bound prevents a remote response or stack trace
 * from growing the on-device ring buffer without limit.
 */
internal object DiagnosticSanitizer {
  private const val MAX_DETAIL_CHARS = 400

  /**
   * `stremio://` rides along with http(s) because it is the same credential-bearing addon URL in
   * the one form a viewer can paste from an install link, and diagnostics quote what was pasted.
   */
  private val url = Regex("""(https?|stremio)://[^\s<>"']+""", RegexOption.IGNORE_CASE)

  /**
   * A named secret in prose, a log line or a JSON body.
   *
   * The keyword may be quoted (`"token": "SECRET"`) and it may be plural (`api_keys=...`): both
   * are what an addon's own error payload actually looks like, and both used to walk straight
   * through a pattern that demanded the separator immediately after the bare keyword. Group 1 is
   * everything up to and including the separator so the key stays readable, group 2 is the value's
   * opening quote if it had one, so the quoting is put back rather than left unbalanced.
   *
   * The value stops at the delimiters that end a field - whitespace, `,`, `;`, `"`, `}`, `]` - so
   * one secret in the middle of a JSON object cannot swallow the rest of the object with it. The
   * one exception is a leading `Bearer `, which is a scheme name rather than part of the value:
   * without it `Authorization: Bearer <jwt>` would have "Bearer" redacted as the whole value and
   * leave the token itself standing alone in the report.
   */
  private val namedSecret = Regex(
    """(?i)(\b(?:api[_-]?keys?|tokens?|authorization|passwords?|secrets?)"?\s*[=:]\s*)("?)(?:bearer\s+)?[^\s,;"}\]]+"?""",
  )
  private val bearer = Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/\-=]+""")

  fun sanitize(value: String): String {
    val oneLine = value
      .replace('\n', ' ')
      .replace('\r', ' ')
      .replace('\t', ' ')
      .trim()
    val redactedUrls = url.replace(oneLine) { match ->
      redactUrl(match.value.trimEnd('.', ')', ']', '}'), scheme = match.groupValues[1])
    }
    val redactedNamed = namedSecret.replace(redactedUrls) { match ->
      val quote = match.groupValues[2]
      "${match.groupValues[1]}$quote<redacted>$quote"
    }
    // Last, for the credential that arrives without a keyword in front of it - a bare
    // `Bearer <jwt>` quoted out of a request log.
    return bearer
      .replace(redactedNamed, "Bearer <redacted>")
      .take(MAX_DETAIL_CHARS)
      .ifBlank { "<empty>" }
  }

  /**
   * [redactSecrets] only parses http(s), and anything it cannot parse it replaces wholesale. A
   * `stremio://` link is the same URL under a scheme no HTTP client can fetch, so it is redacted
   * as its https equivalent and re-labelled - which keeps the host visible in the report instead
   * of collapsing every install link to one indistinguishable `<redacted-url>`.
   */
  private fun redactUrl(raw: String, scheme: String): String {
    if (!scheme.equals("stremio", ignoreCase = true)) return redactSecrets(raw)
    val redacted = redactSecrets(HTTPS + raw.substring(scheme.length + SCHEME_SEPARATOR.length))
    return if (redacted.startsWith(HTTPS)) "stremio://" + redacted.removePrefix(HTTPS) else redacted
  }

  private const val HTTPS = "https://"
  private const val SCHEME_SEPARATOR = "://"
}
