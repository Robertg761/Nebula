package com.stremioshell.host.tv.pairing

/**
 * What the phone actually typed into the pairing form.
 *
 * A field left blank is [null], which means "leave whatever the TV already has
 * alone". The form is never pre-filled with the stored values, so blank has to
 * mean "unchanged" rather than "erase" - otherwise fixing one setting would
 * silently wipe the other.
 */
data class PairingSubmission(val tmdbKey: String?, val addonUrl: String?) {
  val isEmpty: Boolean get() = tmdbKey == null && addonUrl == null

  companion object {
    /** Normalises raw form values: trimmed, and blank collapsed to null. */
    fun of(rawTmdbKey: String?, rawAddonUrl: String?): PairingSubmission = PairingSubmission(
      tmdbKey = rawTmdbKey?.trim()?.ifEmpty { null },
      addonUrl = rawAddonUrl?.trim()?.ifEmpty { null },
    )
  }
}

/** The configuration to persist, plus which halves actually moved. */
data class MergedConfig(
  val tmdbKey: String,
  val addonUrl: String,
  val tmdbKeyChanged: Boolean,
  val addonUrlChanged: Boolean,
) {
  val changed: Boolean get() = tmdbKeyChanged || addonUrlChanged
}

/** Folds a [PairingSubmission] onto the values currently stored on the TV. */
object ConfigMerge {
  fun merge(
    submission: PairingSubmission,
    currentTmdbKey: String,
    currentAddonUrl: String,
  ): MergedConfig {
    val tmdbKey = submission.tmdbKey ?: currentTmdbKey
    val addonUrl = submission.addonUrl ?: currentAddonUrl
    return MergedConfig(
      tmdbKey = tmdbKey,
      addonUrl = addonUrl,
      // Re-submitting the value that is already stored is not a change either,
      // so a no-op POST does not churn DataStore.
      tmdbKeyChanged = tmdbKey != currentTmdbKey,
      addonUrlChanged = addonUrl != currentAddonUrl,
    )
  }
}
