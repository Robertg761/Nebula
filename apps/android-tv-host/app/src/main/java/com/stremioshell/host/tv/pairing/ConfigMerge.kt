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
     *
     * On the pairing path the dropping and capping never actually fire - [addonInputError] has
     * already refused a box with an unusable line, a duplicate, or more than [AddonList.MAX_ADDONS]
     * of them - so a box that passed validation comes back here with one URL per submitted line
     * and nothing missing. That is the invariant the server's confirmation page depends on to be
     * able to report a count truthfully.
     */
    fun addonUrlsIn(raw: String?): List<String> =
      AddonList.sanitized(raw.orEmpty().split(LINE_BREAK))

    /**
     * Validates the phone form before [of] normalises it.
     *
     * [AddonList.sanitized] deliberately drops malformed entries, collapses duplicates and caps
     * the stored list, which is convenient for migrating old preferences but unsafe for an
     * interactive form: reporting success after silently discarding one line makes the viewer
     * debug the wrong device. Pairing therefore accepts every non-blank line or rejects the entire
     * submission.
     *
     * Duplicates are part of that promise and used to escape it. Lines were checked one at a time
     * while [addonUrlsIn] deduplicated the list as a whole, so two lines that normalise to the
     * same URL - `comet.example` and `https://comet.example/manifest.json` are the same addon, and
     * a viewer correcting a line by pasting it again below produces exactly that - passed
     * validation and then arrived as one, with the confirmation reporting fewer saved than were
     * submitted. Rejecting is the honest half of the contract above: the viewer is told which
     * mistake to fix rather than left to notice a missing row.
     */
    fun addonInputError(raw: String?): String? {
      if (raw.isNullOrBlank()) return null
      val lines = raw.split(LINE_BREAK).map(String::trim).filter(String::isNotEmpty)
      if (lines.size > AddonList.MAX_ADDONS) {
        return "Enter no more than ${AddonList.MAX_ADDONS} addon links."
      }
      val normalized = lines.map { AddonList.sanitized(listOf(it)).firstOrNull() }
      val usable = normalized.filterNotNull()
      if (usable.isEmpty()) {
        return "No usable addon link in that box. Paste the manifest URL."
      }
      if (usable.size != lines.size) {
        return "Every addon line must be a usable manifest link."
      }
      if (usable.distinct().size != usable.size) {
        return "Two of those addon links are the same addon. Enter each one once."
      }
      return null
    }
  }
}

/** What the server may truthfully report after handing a validated submission to storage. */
sealed interface PairingApplyResult {
  data class Saved(val receipt: PairingReceipt) : PairingApplyResult
  data class Failed(val message: String) : PairingApplyResult
}

/** Non-sensitive facts the cleartext confirmation page is allowed to show. */
data class PairingReceipt(
  val tmdbKeyChanged: Boolean,
  val addonUrlsChanged: Boolean,
  val hasTmdbKey: Boolean,
  val addonCount: Int,
)

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
