package com.stremioshell.host.tv.player

import org.json.JSONArray
import java.util.Locale

/** The track kinds the player offers a choice of; everything else is [Other]. */
enum class TrackKind {
  Audio,
  Subtitle,
  Other,
  ;

  companion object {
    fun fromMpv(type: String?): TrackKind = when (type) {
      "audio" -> Audio
      "sub" -> Subtitle
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
   * The OSD's one-line summary, e.g.
   * "Audio: English (TRUEHD)   |   Subtitles: off   |   23.976 fps".
   */
  fun osdLine(tracks: List<MpvTrack>, fps: Float): String {
    val audio = selected(tracks, TrackKind.Audio)?.osdLabel ?: "none"
    val sub = selected(tracks, TrackKind.Subtitle)?.osdLabel ?: "off"
    val fpsNote = if (fps > 0f) {
      "   |   ${String.format(Locale.ROOT, "%.3f", fps).trimEnd('0').trimEnd('.')} fps"
    } else {
      ""
    }
    return "Audio: $audio   |   Subtitles: $sub$fpsNote"
  }

  private fun MpvTrack.toRow() = TrackRow(
    trackId = id,
    label = displayName,
    detail = detail,
    selected = selected,
  )
}
