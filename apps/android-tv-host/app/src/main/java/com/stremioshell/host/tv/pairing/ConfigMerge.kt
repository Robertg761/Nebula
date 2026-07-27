package com.stremioshell.host.tv.pairing

import com.stremioshell.host.tv.data.addon.AddonList

/**
 * What the phone actually typed into the pairing form.
 *
 * A field left blank is [null], which means "leave whatever the TV already has
 * alone". The form is never pre-filled with the stored values, so blank has to
 * mean "unchanged" rather than "erase" - otherwise fixing one setting would
 * silently wipe the other. That also means the phone has no way to spell "remove
 * every addon"; clearing the list stays a deliberate act on the TV's own Settings
 * screen, where the viewer can see what they are removing.
 */
data class PairingSubmission(val tmdbKey: String?, val addonUrls: List<String>?) {
  val isEmpty: Boolean get() = tmdbKey == null && addonUrls == null

  companion object {
    /** Line breaks only: a comma or a space can appear inside a real manifest URL. */
    private val LINE_BREAK = Regex("""\R""")

    /** Normalises raw form values: trimmed, and blank collapsed to null. */
    fun of(rawTmdbKey: String?, rawAddonUrls: String?): PairingSubmission = PairingSubmission(
      tmdbKey = rawTmdbKey?.trim()?.ifEmpty { null },
      addonUrls = addonUrlsIn(rawAddonUrls).ifEmpty { null },
    )

    /**
     * The addon box read as a list: one URL per line, through [AddonList.sanitized] so what the
     * phone submits is already in the form the TV stores - `stremio://` links resolved, bare hosts
     * completed, duplicates and blank lines dropped, and the list capped.
     *
     * Empty for a box with nothing usable in it, which the caller has to tell apart from a box the
     * viewer deliberately left blank: the first is a typo worth reporting, the second is "keep what
     * you have".
     */
    fun addonUrlsIn(raw: String?): List<String> =
      AddonList.sanitized(raw.orEmpty().split(LINE_BREAK))
  }
}

/** The configuration to persist, plus which halves actually moved. */
data class MergedConfig(
  val tmdbKey: String,
  val addonUrls: List<String>,
  val tmdbKeyChanged: Boolean,
  val addonUrlsChanged: Boolean,
) {
  val changed: Boolean get() = tmdbKeyChanged || addonUrlsChanged
}

/** Folds a [PairingSubmission] onto the values currently stored on the TV. */
object ConfigMerge {
  fun merge(
    submission: PairingSubmission,
    currentTmdbKey: String,
    currentAddonUrls: List<String>,
  ): MergedConfig {
    val tmdbKey = submission.tmdbKey ?: currentTmdbKey
    // Compared in stored form, so a viewer who re-pastes their addon as a `stremio://` link or
    // without the `/manifest.json` tail is re-submitting the same value, not a new one.
    val stored = AddonList.sanitized(currentAddonUrls)
    val addonUrls = submission.addonUrls ?: stored
    return MergedConfig(
      tmdbKey = tmdbKey,
      addonUrls = addonUrls,
      // Re-submitting the value that is already stored is not a change either,
      // so a no-op POST does not churn DataStore.
      tmdbKeyChanged = tmdbKey != currentTmdbKey,
      addonUrlsChanged = addonUrls != stored,
    )
  }
}
