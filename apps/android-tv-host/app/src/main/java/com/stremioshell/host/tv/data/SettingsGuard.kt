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

/** What is on disk right now, for the guard below to fall back to. */
data class StoredSettings(
  val tmdbKey: String,
  val addonUrls: List<String>,
)

/** The values to write, and which of them the draft did not actually supply. */
data class ResolvedSettings(
  val tmdbKey: String,
  val addonUrls: List<String>,
  val subtitlesBaseUrl: String,
  val keptTmdbKey: Boolean,
  val keptAddonUrls: Boolean,
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
 */
object SettingsSaveGuard {
  fun resolve(draft: SettingsDraft, stored: StoredSettings): ResolvedSettings {
    val tmdbKey = draft.tmdbKey.trim()
    val keepTmdb = tmdbKey.isEmpty() && stored.tmdbKey.isNotBlank()
    val addonUrls = AddonList.sanitized(draft.addonUrls)
    val keepAddons = addonUrls.isEmpty() && stored.addonUrls.isNotEmpty()
    return ResolvedSettings(
      tmdbKey = if (keepTmdb) stored.tmdbKey else tmdbKey,
      addonUrls = if (keepAddons) AddonList.sanitized(stored.addonUrls) else addonUrls,
      subtitlesBaseUrl = normalizeSubtitlesBase(draft.subtitlesBaseUrl),
      keptTmdbKey = keepTmdb,
      keptAddonUrls = keepAddons,
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

  /** What to tell the viewer when the guard held a value back; null when it did not. */
  fun keptNotice(resolved: ResolvedSettings): String? = when {
    resolved.keptTmdbKey && resolved.keptAddonUrls ->
      "Kept your saved TMDB key and addons - both were blank. Use Clear to remove them."
    resolved.keptTmdbKey -> "Kept your saved TMDB key - the field was blank. Use Clear to remove it."
    resolved.keptAddonUrls -> "Kept your saved addons - the list was empty."
    else -> null
  }
}

/** One addon's answer to the Save-time reachability check; [name] is null when it failed. */
data class AddonProbe(val label: String, val name: String?)

/** The one-line summaries the Settings screen prints after a Save. */
object SettingsStatus {
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
