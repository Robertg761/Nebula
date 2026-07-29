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

  private val url = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
  private val namedSecret = Regex(
    """(?i)\b(api[_-]?key|token|authorization|password|secret)\s*([=:])\s*([^\s,;]+)""",
  )
  private val bearer = Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/\-=]+""")

  fun sanitize(value: String): String {
    val oneLine = value
      .replace('\n', ' ')
      .replace('\r', ' ')
      .replace('\t', ' ')
      .trim()
    val redactedUrls = url.replace(oneLine) { match ->
      redactSecrets(match.value.trimEnd('.', ')', ']', '}'))
    }
    val redactedNamed = namedSecret.replace(redactedUrls) { match ->
      "${match.groupValues[1]}${match.groupValues[2]}<redacted>"
    }
    return bearer
      .replace(redactedNamed, "Bearer <redacted>")
      .take(MAX_DETAIL_CHARS)
      .ifBlank { "<empty>" }
  }
}
