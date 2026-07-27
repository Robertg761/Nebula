package com.stremioshell.host.tv.player

import java.util.Locale

/**
 * The language codes containers actually carry, and what to call them on screen.
 *
 * Matroska/MP4 tag tracks with either ISO 639-1 ("en") or 639-2 ("eng"), and
 * sometimes with a region ("pt-BR"), so both forms of the same language have to
 * be recognised as one: a preference stored from an "eng" remux must still match
 * the "en" track of the next episode's web-dl.
 */
object LanguageCodes {
  /** ISO 639-2/B code, ISO 639-1 code, English name. */
  private val TABLE = listOf(
    Triple("eng", "en", "English"),
    Triple("spa", "es", "Spanish"),
    Triple("fra", "fr", "French"),
    Triple("deu", "de", "German"),
    Triple("ita", "it", "Italian"),
    Triple("por", "pt", "Portuguese"),
    Triple("nld", "nl", "Dutch"),
    Triple("swe", "sv", "Swedish"),
    Triple("nor", "no", "Norwegian"),
    Triple("dan", "da", "Danish"),
    Triple("fin", "fi", "Finnish"),
    Triple("isl", "is", "Icelandic"),
    Triple("pol", "pl", "Polish"),
    Triple("ces", "cs", "Czech"),
    Triple("slk", "sk", "Slovak"),
    Triple("hun", "hu", "Hungarian"),
    Triple("ron", "ro", "Romanian"),
    Triple("bul", "bg", "Bulgarian"),
    Triple("hrv", "hr", "Croatian"),
    Triple("srp", "sr", "Serbian"),
    Triple("slv", "sl", "Slovenian"),
    Triple("ell", "el", "Greek"),
    Triple("tur", "tr", "Turkish"),
    Triple("rus", "ru", "Russian"),
    Triple("ukr", "uk", "Ukrainian"),
    Triple("jpn", "ja", "Japanese"),
    Triple("kor", "ko", "Korean"),
    Triple("zho", "zh", "Chinese"),
    Triple("tha", "th", "Thai"),
    Triple("vie", "vi", "Vietnamese"),
    Triple("hin", "hi", "Hindi"),
    Triple("tam", "ta", "Tamil"),
    Triple("tel", "te", "Telugu"),
    Triple("ara", "ar", "Arabic"),
    Triple("heb", "he", "Hebrew"),
    Triple("fas", "fa", "Persian"),
    Triple("ind", "id", "Indonesian"),
    Triple("msa", "ms", "Malay"),
    Triple("fil", "tl", "Filipino"),
    Triple("cat", "ca", "Catalan"),
  )

  /** The 639-2/B code the alternates below are keyed on, per known alias. */
  private val CANONICAL: Map<String, String> = buildMap {
    for ((three, two, _) in TABLE) {
      put(three, three)
      put(two, three)
    }
    // Bibliographic/terminological pairs and legacy codes seen in the wild.
    put("fre", "fra")
    put("ger", "deu")
    put("dut", "nld")
    put("gre", "ell")
    put("chi", "zho")
    put("cze", "ces")
    put("slo", "slk")
    put("rum", "ron")
    put("per", "fas")
    put("ice", "isl")
    put("may", "msa")
    put("scr", "hrv")
    put("scc", "srp")
    put("tgl", "fil")
    put("iw", "heb")
    put("in", "ind")
  }

  private val NAMES: Map<String, String> = TABLE.associate { (three, _, name) -> three to name }

  private val SHORT: Map<String, String> = TABLE.associate { (three, two, _) -> three to two }

  /**
   * The stored form of a raw container tag: lowercased, region stripped, and
   * folded onto one code per language. Blank for an untagged track, which is the
   * signal that there is no preference to be learned from it.
   */
  fun normalize(raw: String?): String {
    val code = raw?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_')
    if (code.isNullOrBlank() || code == "und" || code == "unknown") return ""
    return CANONICAL[code] ?: code
  }

  /**
   * Every spelling of [code] worth handing mpv, longest first. mpv matches
   * `alang`/`slang` entries against the container tag, so listing both ISO forms
   * is what makes a preference stick across differently tagged releases.
   */
  fun aliases(code: String): List<String> {
    val canonical = normalize(code)
    if (canonical.isBlank()) return emptyList()
    val extras = CANONICAL.entries
      .filter { it.value == canonical && it.key != canonical }
      .map { it.key }
      .sorted()
    return (listOf(canonical) + listOfNotNull(SHORT[canonical]) + extras).distinct()
  }

  /** English name for a container tag, or the tag itself upper-cased when unknown. */
  fun displayName(raw: String?): String {
    val canonical = normalize(raw)
    if (canonical.isBlank()) return ""
    return NAMES[canonical] ?: canonical.uppercase(Locale.ROOT)
  }
}

/** Thin alias so the track model reads naturally. */
object LanguageNames {
  fun display(raw: String?): String = LanguageCodes.displayName(raw)
}

/**
 * What a stored track preference means to mpv, and what an explicit pick means
 * to the stored preference.
 *
 * Two separate jobs, both pure: turning a saved language into the `alang`/`slang`
 * value set before `loadfile`, and deciding whether the track a viewer just
 * chose should change what the next episode starts with.
 */
object TrackPreferences {
  /** The stored subtitle language that means "no subtitles at all". */
  const val SUBTITLES_OFF = "off"

  /**
   * The `alang` value for a stored preference, or null when there is nothing to
   * set and mpv's own default order should stand.
   */
  fun alangValue(stored: String?): String? = languageList(stored)

  /**
   * The `slang` value for a stored preference. Null both when unset and when
   * subtitles are switched off, where [subtitlesOff] carries the intent instead:
   * `slang` cannot express "none", and a leftover list would have mpv pick a
   * track back up on the next file.
   */
  fun slangValue(stored: String?): String? =
    if (subtitlesOff(stored)) null else languageList(stored)

  fun subtitlesOff(stored: String?): Boolean =
    stored?.trim()?.equals(SUBTITLES_OFF, ignoreCase = true) == true

  private fun languageList(stored: String?): String? {
    val aliases = LanguageCodes.aliases(stored.orEmpty())
    return aliases.takeIf { it.isNotEmpty() }?.joinToString(",")
  }

  /**
   * Whether an explicit pick changes the stored preference. [Unchanged] is not
   * the same as storing a blank: a track carrying no language tag says nothing
   * about what the viewer wants next time, so the preference they set earlier
   * has to survive it.
   */
  sealed interface Update {
    data object Unchanged : Update
    data class Set(val value: String) : Update
  }

  /** The stored audio language after the viewer picked [picked] from the menu. */
  fun audioUpdate(picked: MpvTrack): Update {
    val code = LanguageCodes.normalize(picked.lang)
    return if (code.isBlank()) Update.Unchanged else Update.Set(code)
  }

  /**
   * The stored subtitle language after the viewer picked [picked], null meaning
   * they chose "Off".
   *
   * The untagged case is the interesting one: normally there is nothing to learn
   * from it, but turning subtitles *on* while the stored preference says "off"
   * has to clear that preference, or the next episode starts with them off again
   * and the viewer has to switch them on every single time.
   */
  fun subtitleUpdate(picked: MpvTrack?, stored: String?): Update {
    if (picked == null) return Update.Set(SUBTITLES_OFF)
    return subtitleLanguageUpdate(picked.lang, stored)
  }

  /**
   * The same verdict from a language alone, for a subtitle the viewer picked out
   * of a subtitles addon: an addon's file is as explicit a choice as a track in
   * the container, but there is no track to read a tag off until mpv has fetched
   * it, and the addon's own tag is all there is to go on.
   */
  fun subtitleLanguageUpdate(lang: String?, stored: String?): Update {
    val code = LanguageCodes.normalize(lang)
    if (code.isNotBlank()) return Update.Set(code)
    return if (subtitlesOff(stored)) Update.Set("") else Update.Unchanged
  }
}
