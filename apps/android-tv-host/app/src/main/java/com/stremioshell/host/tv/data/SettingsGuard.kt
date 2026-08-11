package com.stremioshell.host.tv.data

import com.stremioshell.host.tv.data.addon.AddonList
import com.stremioshell.host.tv.data.subtitles.SubtitlesClient

/** What the Settings screen is asking to persist. */
data class SettingsDraft(
  val tmdbKey: String,
  val addonUrls: List<String>,
  /** Blank means "use the built-in default", which is how the field is labelled. */
  val subtitlesBaseUrl: String,
)

/**
 * What is on disk right now, for the guard below to fall back to.
 *
 * [readable] is load-bearing and defaults to true only so that callers with a genuine read - tests
 * included - stay unchanged. When it is false the other two fields are not knowledge, they are a
 * failed read's stand-in, and the guard must not let a Save act on them; see
 * [SettingsStore.storedSnapshot], which is the one place that can tell the difference.
 */
data class StoredSettings(
  val tmdbKey: String,
  val addonUrls: List<String>,
  val readable: Boolean = true,
)

/**
 * The values to write, and which of them the draft did not actually supply.
 *
 * A kept value means "do not write this key at all", not "write the stored one back". The two are
 * the same thing when [storedUnreadable] is false and different when it is true - which is the
 * point of carrying it here rather than resolving it away.
 */
data class ResolvedSettings(
  val tmdbKey: String,
  val addonUrls: List<String>,
  val subtitlesBaseUrl: String,
  val keptTmdbKey: Boolean,
  val keptAddonUrls: Boolean,
  /** The stored values could not be read, so nothing they imply may be treated as an instruction. */
  val storedUnreadable: Boolean = false,
  /** The draft offered addon URLs and every one of them was rejected as unusable. */
  val addonInputRejected: Boolean = false,
)

/**
 * Stops a Save from erasing a working configuration.
 *
 * A blank field on this screen is almost never an instruction. The fields are
 * seeded from storage, so blank means the seed had not arrived yet, or a leanback
 * IME session ended badly, or the viewer selected-all and pressed something -
 * and the cost of guessing wrong is an app that cannot load a single rail until
 * a key is retyped on a remote. So blank keeps what is stored, and the screen
 * says so rather than pretending it saved.
 *
 * Clearing on purpose is still possible, just not by accident: the screen carries
 * an explicit Clear affordance per value, which writes the empty value directly
 * and never comes through here.
 *
 * The same reasoning covers the case where the guard cannot see what is stored at all. A failed
 * preferences read hands it blank values that look exactly like an unconfigured TV, and acting on
 * that would turn a transient disk error into a wiped TMDB key and an emptied addon list. So an
 * unreadable snapshot keeps everything the draft did not positively supply, and says so.
 */
object SettingsSaveGuard {
  fun resolve(draft: SettingsDraft, stored: StoredSettings): ResolvedSettings {
    val tmdbKey = draft.tmdbKey.trim()
    // With an unreadable snapshot the stored value is unknown rather than absent, and unknown has
    // to be treated as "there is something there": keeping a key that turns out not to exist costs
    // nothing, overwriting one that does costs the viewer their rails until they retype it.
    val keepTmdb = tmdbKey.isEmpty() && (!stored.readable || stored.tmdbKey.isNotBlank())
    val addonUrls = AddonList.sanitized(draft.addonUrls)
    val keepAddons = addonUrls.isEmpty() && (!stored.readable || stored.addonUrls.isNotEmpty())
    return ResolvedSettings(
      tmdbKey = if (keepTmdb) stored.tmdbKey else tmdbKey,
      addonUrls = if (keepAddons) AddonList.sanitized(stored.addonUrls) else addonUrls,
      subtitlesBaseUrl = normalizeSubtitlesBase(draft.subtitlesBaseUrl),
      keptTmdbKey = keepTmdb,
      keptAddonUrls = keepAddons,
      storedUnreadable = !stored.readable,
      addonInputRejected = AddonList.allRejected(draft.addonUrls),
    )
  }

  /**
   * The subtitles addon's root, with the manifest path taken back off: this is a
   * base other resources hang from, and a viewer pastes whichever of the two forms
   * their addon's page showed them. Blank resets to the built-in default.
   */
  fun normalizeSubtitlesBase(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return SubtitlesClient.OPENSUBTITLES_V3_BASE
    return AddonList.baseUrl(trimmed).ifEmpty { SubtitlesClient.OPENSUBTITLES_V3_BASE }
  }

  /**
   * What to tell the viewer when the guard held a value back; null when it did not.
   *
   * The three reasons a value is held back read differently on purpose. "Blank" is the viewer's own
   * doing and only needs pointing at Clear. "Rejected" is the one they can act on - an address that
   * looked fine on a remote keyboard was not a usable addon URL, and saying "the list was empty"
   * there taught them nothing. "Unreadable" is ours, and the only honest thing to say is that
   * nothing was touched and the Save is worth repeating.
   */
  fun keptNotice(resolved: ResolvedSettings): String? {
    if (resolved.storedUnreadable && (resolved.keptTmdbKey || resolved.keptAddonUrls)) {
      return "Couldn't read your saved settings just now, so your TMDB key and addons were left " +
        "as they are. Try Save again in a moment."
    }
    return when {
      resolved.keptTmdbKey && resolved.keptAddonUrls && resolved.addonInputRejected ->
        "Kept your saved TMDB key and addons - the key was blank and none of the addon URLs " +
          "were usable."
      resolved.keptTmdbKey && resolved.keptAddonUrls ->
        "Kept your saved TMDB key and addons - both were blank. Use Clear to remove them."
      resolved.keptTmdbKey ->
        "Kept your saved TMDB key - the field was blank. Use Clear to remove it."
      resolved.keptAddonUrls && resolved.addonInputRejected ->
        "Kept your saved addons - none of the addon URLs were usable. Each one needs an https " +
          "address."
      resolved.keptAddonUrls -> "Kept your saved addons - the list was empty."
      resolved.addonInputRejected ->
        "No addons saved - none of the addon URLs were usable. Each one needs an https address."
      else -> null
    }
  }
}

/** One addon's answer to the Save-time reachability check; [name] is null when it failed. */
data class AddonProbe(val label: String, val name: String?)

/**
 * What the Save-time TMDB check actually learned.
 *
 * A boolean could not tell "TMDB says that key is not valid" from "this TV has no working network
 * right now", and the status line reported both as "check the key" - sending a viewer off to
 * retype a perfectly good key on a remote because their router had rebooted.
 */
sealed interface TmdbProbeResult {
  /** Nothing to check: the resolved configuration has no key in it. */
  data object NoKey : TmdbProbeResult

  data object Ok : TmdbProbeResult

  /** TMDB answered, and its answer was that the key is not acceptable. */
  data object BadCredentials : TmdbProbeResult

  /** TMDB was never reached, so the key is neither confirmed nor blamed. */
  data class NetworkFailure(val error: Throwable) : TmdbProbeResult

  /** True only for a live confirmed key; what the pairing screen's checklist still reads. */
  val connected: Boolean get() = this is Ok

  companion object {
    /**
     * Classifies one probe attempt. [error] is null when the probe returned normally.
     *
     * Only the two statuses that are a statement about the credential itself count as bad
     * credentials. Everything else - no DNS, a timeout, a TLS failure, a 500, an unparseable body -
     * says something about the trip, not about the key.
     */
    fun of(key: String, error: Throwable?): TmdbProbeResult = when {
      key.isBlank() -> NoKey
      error == null -> Ok
      isCredentialRejection(error) -> BadCredentials
      else -> NetworkFailure(error)
    }

    private fun isCredentialRejection(error: Throwable): Boolean {
      var current: Throwable? = error
      var depth = 0
      while (current != null && depth < MAX_CAUSE_DEPTH) {
        val status = current as? HttpStatusException
        if (status != null) return status.code == 401 || status.code == 403
        current = current.cause
        depth++
      }
      return false
    }

    private const val MAX_CAUSE_DEPTH = 5
  }
}

/** The one-line summaries the Settings screen prints after a Save. */
object SettingsStatus {
  /**
   * The probe-shaped status line. [NetworkErrorMessage] writes the failure half, so an outage says
   * the same thing here as it does on Home instead of inventing a second vocabulary for it.
   */
  fun tmdbStatus(key: String, result: TmdbProbeResult): String {
    if (key.isBlank()) return "TMDB: no key"
    return when (result) {
      TmdbProbeResult.NoKey -> "TMDB: no key"
      TmdbProbeResult.Ok -> "TMDB: connected"
      TmdbProbeResult.BadCredentials -> "TMDB: failed (check the key)"
      is TmdbProbeResult.NetworkFailure ->
        "TMDB: not checked - " + NetworkErrorMessage.forThrowable(NetworkSource.Tmdb, result.error)
    }
  }

  /**
   * The boolean form, kept for the pairing checklist, which has only ever had a boolean to report.
   * Settings goes through the overload above.
   */
  fun tmdbStatus(key: String, connected: Boolean?): String = when {
    key.isBlank() -> "TMDB: no key"
    connected == true -> "TMDB: connected"
    else -> "TMDB: failed (check the key)"
  }

  fun addonStatus(probes: List<AddonProbe>): String {
    if (probes.isEmpty()) return "Addons: none configured"
    val ok = probes.filter { it.name != null }
    val failed = probes.filter { it.name == null }
    return when {
      // A single addon keeps the wording it had before the list existed, manifest
      // name and all: that name is the only confirmation the URL points where the
      // viewer thinks it does.
      probes.size == 1 && ok.size == 1 -> "Addon: connected (${safeManifestName(ok[0].name)})"
      probes.size == 1 -> "Addon: failed (check the URL)"
      ok.isEmpty() -> "Addons: none connected (check the URLs)"
      failed.isEmpty() -> "Addons: ${ok.size} connected"
      else -> "Addons: ${ok.size} of ${probes.size} connected (" +
        failed.joinToString(", ") { it.label } + " failed)"
    }
  }

  /**
   * Manifest names are remote input. Keep their useful text while preventing control/bidi
   * characters or a megabyte-long name from taking over Settings' live status region.
   */
  private fun safeManifestName(raw: String?): String {
    val safe = raw.orEmpty()
      .filter { char ->
        char.code >= 0x20 &&
          char.code != 0x7f &&
          Character.getType(char) != Character.FORMAT.toInt()
      }
      .trim()
      .take(MAX_MANIFEST_NAME_CHARS)
    return safe.ifBlank { "addon" }
  }

  private const val MAX_MANIFEST_NAME_CHARS = 80
}
