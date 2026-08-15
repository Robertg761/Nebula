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
 * Phone-facing validation copy. It names only categories, never stored source labels,
 * credential-bearing URLs, or the TMDB key; the TV renders its local checklist separately.
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
      } else if (validation.addons.any { !it.connected }) {
        // A blank phone field means "keep what is stored", so these labels may describe a saved
        // credential-bearing addon. The cleartext phone response remains write-only by reporting
        // only the category; the TV's local checklist can still show the short labels.
        add("One or more stream addons did not connect; check the addon URLs.")
      }
    }
    return issues.joinToString(" ")
      .ifBlank { "The connection checks did not complete. Please try again." }
  }
}
