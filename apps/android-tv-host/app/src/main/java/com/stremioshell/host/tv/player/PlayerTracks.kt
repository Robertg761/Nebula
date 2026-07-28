package com.stremioshell.host.tv.player

import org.json.JSONArray
import java.util.Locale

/**
 * [Audio] and [Subtitle] are the kinds the player offers a choice of. [Video] is
 * kept apart from [Other] not to be offered — a file's video track is not a
 * decision a viewer makes — but because what it is encoded as decides whether the
 * picture will look right on this screen; see [DolbyVisionNotice].
 */
enum class TrackKind {
  Audio,
  Subtitle,
  Video,
  Other,
  ;

  companion object {
    fun fromMpv(type: String?): TrackKind = when (type) {
      "audio" -> Audio
      "sub" -> Subtitle
      "video" -> Video
      else -> Other
    }
  }
}

/**
 * One entry of mpv's `track-list`. Only the fields the menu and the OSD need are
 * kept: mpv reports a couple of dozen per track and none of the rest change what
 * a viewer is choosing between.
 */
data class MpvTrack(
  val id: Int,
  val kind: TrackKind,
  val lang: String = "",
  val title: String = "",
  val codec: String = "",
  /**
   * mpv's `codec-profile`, which is where a Dolby Vision stream names itself on
   * builds whose track list carries no dedicated field ("dvhe.05.06").
   */
  val codecProfile: String = "",
  /**
   * mpv's `dolby-vision-profile`, present only on builds new enough to report it.
   * Null means "the list did not say", never "not Dolby Vision".
   */
  val dolbyVisionProfile: Int? = null,
  val selected: Boolean = false,
  val isDefault: Boolean = false,
  val forced: Boolean = false,
  val external: Boolean = false,
) {
  /**
   * What the track is called in the menu. The language comes first because that
   * is what a viewer is picking by; the title is what tells two tracks in the
   * same language apart ("English - Commentary"), and is dropped when it only
   * repeats the language. A track tagged with neither is identified by its id,
   * which at least stays stable across the list.
   */
  val displayName: String
    get() {
      val language = LanguageNames.display(lang)
      val name = title.trim()
      val parts = listOfNotNull(
        language.ifBlank { null },
        name.ifBlank { null }?.takeIf { !it.equals(language, ignoreCase = true) },
      )
      return parts.joinToString(" - ").ifBlank { "Track $id" }
    }

  /** The technical second line: codec plus the container's own flags. */
  val detail: String
    get() = listOfNotNull(
      codec.trim().ifBlank { null }?.uppercase(Locale.ROOT),
      "Default".takeIf { isDefault },
      "Forced".takeIf { forced },
      "External".takeIf { external },
    ).joinToString("   |   ")

  /** The compact form the OSD's one-line summary uses. */
  val osdLabel: String
    get() {
      val codecNote = codec.trim().ifBlank { null }?.uppercase(Locale.ROOT)
      return if (codecNote == null) displayName else "$displayName ($codecNote)"
    }

  /**
   * Whether this is a Dolby Vision video track.
   *
   * Three sources because no one of them is present everywhere: the dedicated
   * `dolby-vision-profile` field on recent mpv builds, the DV codec tags a
   * container carries (`dvhe`/`dvh1` for HEVC, `dav1` for AV1), and the profile
   * string ffmpeg builds out of them.
   *
   * "dv" is matched only as a word of its own: it is inside "dvvideo", which is
   * the DV *camcorder* codec and nothing to do with Dolby Vision, and warning a
   * viewer about the colours of a home video would be pure noise.
   */
  val isDolbyVision: Boolean
    get() {
      if (dolbyVisionProfile != null) return true
      val text = "${codec.lowercase(Locale.ROOT)} ${codecProfile.lowercase(Locale.ROOT)}"
      if (DV_TAGS.any { text.contains(it) }) return true
      return DV_TOKEN.containsMatchIn(text)
    }

  private companion object {
    val DV_TAGS = listOf("dvhe", "dvh1", "dav1", "dolby vision", "dolbyvision", "dovi")
    val DV_TOKEN = Regex("(?<![a-z0-9])dv(?![a-z0-9])")
  }
}

/**
 * A row of the menu's Audio or Subtitles section. [trackId] is null for the
 * "Off" row, which is what `sid=no` is offered as.
 */
data class TrackRow(
  val trackId: Int?,
  val label: String,
  val detail: String,
  val selected: Boolean,
)

/**
 * Reading mpv's track list into something the UI can render, kept out of the
 * activity so the awkward shapes — an absent list, a track tagged with nothing,
 * a file whose subtitles are switched off — are testable without a device.
 */
object MpvTracks {
  /**
   * Parses `track-list`. A malformed or absent document yields no tracks rather
   * than throwing: the menu showing "None" is a far better failure than taking
   * playback down over a property read.
   */
  fun parse(json: String?): List<MpvTrack> {
    if (json.isNullOrBlank()) return emptyList()
    val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
    val tracks = ArrayList<MpvTrack>(array.length())
    for (i in 0 until array.length()) {
      val entry = array.optJSONObject(i) ?: continue
      tracks += MpvTrack(
        id = entry.optInt("id"),
        kind = TrackKind.fromMpv(entry.optString("type")),
        lang = entry.optString("lang"),
        title = entry.optString("title"),
        codec = entry.optString("codec"),
        codecProfile = entry.optString("codec-profile"),
        // Zero is mpv's "no Dolby Vision", and an absent field reads as zero too,
        // so both come back as null rather than as a profile number.
        dolbyVisionProfile = entry.optInt("dolby-vision-profile").takeIf { it > 0 },
        selected = entry.optBoolean("selected"),
        isDefault = entry.optBoolean("default"),
        forced = entry.optBoolean("forced"),
        external = entry.optBoolean("external"),
      )
    }
    return tracks
  }

  fun of(tracks: List<MpvTrack>, kind: TrackKind): List<MpvTrack> = tracks.filter { it.kind == kind }

  fun selected(tracks: List<MpvTrack>, kind: TrackKind): MpvTrack? =
    tracks.firstOrNull { it.kind == kind && it.selected }

  /**
   * The video track being played. Falls back to the first one listed: a file's
   * video track is selected before anything reads this list, but a track list read
   * mid-open can arrive with nothing flagged yet, and the first video track is
   * what mpv will be playing in every file this player opens.
   */
  fun selectedVideo(tracks: List<MpvTrack>): MpvTrack? =
    selected(tracks, TrackKind.Video) ?: tracks.firstOrNull { it.kind == TrackKind.Video }

  /** Whether the picture on screen is Dolby Vision; see [MpvTrack.isDolbyVision]. */
  fun isDolbyVision(tracks: List<MpvTrack>): Boolean =
    selectedVideo(tracks)?.isDolbyVision == true

  fun audioRows(tracks: List<MpvTrack>): List<TrackRow> =
    of(tracks, TrackKind.Audio).map { it.toRow() }

  /**
   * The subtitle rows, with "Off" first. It is always offered and it is always
   * the selected row when nothing else is: mpv having no subtitle track loaded
   * and the viewer having switched them off look identical from here, and both
   * mean the same thing to someone reading the list.
   */
  fun subtitleRows(tracks: List<MpvTrack>): List<TrackRow> {
    val subs = of(tracks, TrackKind.Subtitle)
    val off = TrackRow(
      trackId = null,
      label = "Off",
      detail = "",
      selected = subs.none { it.selected },
    )
    return listOf(off) + subs.map { it.toRow() }
  }

  /**
   * Where focus goes when a section opens: the row that is already selected, so
   * the viewer starts from what they are listening to or reading rather than
   * from the top of a fifteen-entry list.
   */
  fun initialFocusIndex(rows: List<TrackRow>): Int =
    rows.indexOfFirst { it.selected }.takeIf { it >= 0 } ?: 0

  /**
   * The OSD's frame-rate chip, e.g. "23.976 fps" or "25 fps", or null when mpv has
   * not reported one yet - the chip is dropped rather than showing "0 fps".
   *
   * Trailing zeroes go because the three decimals only exist for 23.976 and 29.97;
   * every whole rate would otherwise read as "25.000 fps".
   */
  fun fpsLabel(fps: Float): String? {
    if (fps <= 0f) return null
    return String.format(Locale.ROOT, "%.3f", fps).trimEnd('0').trimEnd('.') + " fps"
  }

  private fun MpvTrack.toRow() = TrackRow(
    trackId = id,
    label = displayName,
    detail = detail,
    selected = selected,
  )
}
