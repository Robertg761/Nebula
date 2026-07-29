package com.stremioshell.host.tv.pairing

/** One non-sensitive result the TV may show after checking phone-submitted configuration. */
data class PairingConnectionCheck(
  val label: String,
  val connected: Boolean,
)

data class PairingValidation(
  val hasTmdbKey: Boolean,
  val tmdbConnected: Boolean,
  val addons: List<PairingConnectionCheck>,
) {
  val complete: Boolean
    get() = hasTmdbKey && tmdbConnected && addons.isNotEmpty() && addons.all { it.connected }
}

/**
 * User-facing validation copy that names only short source labels, never credential-bearing URLs
 * or the TMDB key.
 */
object PairingValidationPolicy {
  fun failureMessage(validation: PairingValidation): String {
    val issues = buildList {
      when {
        !validation.hasTmdbKey -> add("Enter a TMDB API key.")
        !validation.tmdbConnected -> add("The TMDB key did not connect; check it and try again.")
      }
      if (validation.addons.isEmpty()) {
        add("Enter at least one stream addon manifest URL.")
      } else {
        val failed = validation.addons.filterNot { it.connected }.joinToString(", ") { it.label }
        if (failed.isNotEmpty()) add("$failed did not connect; check those addon URLs.")
      }
    }
    return issues.joinToString(" ")
      .ifBlank { "The connection checks did not complete. Please try again." }
  }
}
