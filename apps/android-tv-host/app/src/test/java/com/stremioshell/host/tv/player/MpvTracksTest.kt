package com.stremioshell.host.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvTracksTest {
  private val remuxTrackList = """
    [
      {"id":1,"type":"video","selected":true,"codec":"hevc"},
      {"id":1,"type":"audio","lang":"eng","title":"Surround 7.1","codec":"truehd",
       "selected":true,"default":true},
      {"id":2,"type":"audio","lang":"jpn","codec":"flac"},
      {"id":1,"type":"sub","lang":"eng","title":"Full","codec":"ass","forced":false},
      {"id":2,"type":"sub","lang":"eng","title":"Signs & Songs","codec":"ass","forced":true},
      {"id":3,"type":"sub","lang":"spa","codec":"subrip","selected":true}
    ]
  """.trimIndent()

  @Test
  fun `parses the fields the menu shows`() {
    val tracks = MpvTracks.parse(remuxTrackList)

    assertEquals(6, tracks.size)
    val audio = MpvTracks.of(tracks, TrackKind.Audio)
    assertEquals(2, audio.size)
    assertEquals(1, audio[0].id)
    assertEquals("eng", audio[0].lang)
    assertEquals("Surround 7.1", audio[0].title)
    assertEquals("truehd", audio[0].codec)
    assertTrue(audio[0].selected)
    assertTrue(audio[0].isDefault)
    assertFalse(audio[1].selected)
  }

  @Test
  fun `video tracks are read but offered as neither audio nor subtitle`() {
    val tracks = MpvTracks.parse(remuxTrackList)

    assertEquals(TrackKind.Video, tracks.first().kind)
    assertEquals(2, MpvTracks.audioRows(tracks).size)
    // Off plus the three subtitle tracks, and nothing from the video track.
    assertEquals(4, MpvTracks.subtitleRows(tracks).size)
  }

  @Test
  fun `a missing or malformed track list yields no tracks rather than throwing`() {
    assertTrue(MpvTracks.parse(null).isEmpty())
    assertTrue(MpvTracks.parse("").isEmpty())
    assertTrue(MpvTracks.parse("not json").isEmpty())
    assertTrue(MpvTracks.parse("""{"id":1}""").isEmpty())
  }

  @Test
  fun `rows label a track by language, then title`() {
    val rows = MpvTracks.audioRows(MpvTracks.parse(remuxTrackList))

    assertEquals("English - Surround 7.1", rows[0].label)
    assertEquals("TRUEHD   |   Default", rows[0].detail)
    assertEquals("Japanese", rows[1].label)
    assertEquals("FLAC", rows[1].detail)
  }

  @Test
  fun `a title that only repeats the language is not shown twice`() {
    val track = MpvTrack(id = 1, kind = TrackKind.Audio, lang = "eng", title = "English")

    assertEquals("English", track.displayName)
  }

  @Test
  fun `an untagged track is identified by its id`() {
    val track = MpvTrack(id = 4, kind = TrackKind.Subtitle)

    assertEquals("Track 4", track.displayName)
    assertEquals("", track.detail)
  }

  @Test
  fun `an unknown language code is shown as the code itself`() {
    val track = MpvTrack(id = 1, kind = TrackKind.Audio, lang = "qaa")

    assertEquals("QAA", track.displayName)
  }

  @Test
  fun `a regional tag reads as its language`() {
    val track = MpvTrack(id = 1, kind = TrackKind.Audio, lang = "pt-BR")

    assertEquals("Portuguese", track.displayName)
  }

  @Test
  fun `forced and external flags reach the row detail`() {
    val rows = MpvTracks.subtitleRows(MpvTracks.parse(remuxTrackList))

    // Row 0 is Off, so the forced signs track is row 2.
    assertEquals("English - Signs & Songs", rows[2].label)
    assertEquals("ASS   |   Forced", rows[2].detail)
  }

  @Test
  fun `subtitle rows offer Off first`() {
    val rows = MpvTracks.subtitleRows(MpvTracks.parse(remuxTrackList))

    assertEquals(4, rows.size)
    assertEquals("Off", rows[0].label)
    assertNull(rows[0].trackId)
    assertFalse(rows[0].selected)
    assertTrue(rows[3].selected)
  }

  @Test
  fun `Off is the selected row when no subtitle track is on`() {
    val tracks = MpvTracks.parse(
      """[{"id":1,"type":"sub","lang":"eng"},{"id":2,"type":"sub","lang":"fra"}]""",
    )

    val rows = MpvTracks.subtitleRows(tracks)

    assertTrue(rows[0].selected)
    assertEquals(0, MpvTracks.initialFocusIndex(rows))
  }

  @Test
  fun `Off is offered even for a file with no subtitles at all`() {
    val rows = MpvTracks.subtitleRows(emptyList())

    assertEquals(1, rows.size)
    assertTrue(rows[0].selected)
  }

  @Test
  fun `focus opens on the selected row`() {
    val tracks = MpvTracks.parse(remuxTrackList)

    assertEquals(0, MpvTracks.initialFocusIndex(MpvTracks.audioRows(tracks)))
    // Off, English full, English forced, Spanish (selected).
    assertEquals(3, MpvTracks.initialFocusIndex(MpvTracks.subtitleRows(tracks)))
  }

  @Test
  fun `focus falls back to the first row when nothing is selected`() {
    val rows = MpvTracks.audioRows(
      MpvTracks.parse("""[{"id":1,"type":"audio","lang":"eng"}]"""),
    )

    assertEquals(0, MpvTracks.initialFocusIndex(rows))
  }

  @Test
  fun `the OSD line names the selected tracks and the frame rate`() {
    val tracks = MpvTracks.parse(remuxTrackList)

    assertEquals(
      "Audio: English - Surround 7.1 (TRUEHD)   |   Subtitles: Spanish (SUBRIP)   |   23.976 fps",
      MpvTracks.osdLine(tracks, 23.976f),
    )
  }

  @Test
  fun `the OSD line says off and none for what is not playing, and drops an unknown fps`() {
    assertEquals("Audio: none   |   Subtitles: off", MpvTracks.osdLine(emptyList(), 0f))
  }

  @Test
  fun `a whole frame rate has no trailing zeroes`() {
    assertTrue(MpvTracks.osdLine(emptyList(), 25f).endsWith("25 fps"))
  }

  @Test
  fun `an ordinary HEVC remux is not Dolby Vision`() {
    assertFalse(MpvTracks.isDolbyVision(MpvTracks.parse(remuxTrackList)))
  }

  @Test
  fun `the dolby-vision-profile field is taken at its word`() {
    val tracks = MpvTracks.parse(
      """[{"id":1,"type":"video","selected":true,"codec":"hevc","dolby-vision-profile":5}]""",
    )

    assertTrue(MpvTracks.isDolbyVision(tracks))
    assertEquals(5, tracks.first().dolbyVisionProfile)
  }

  @Test
  fun `a zero or absent dolby-vision-profile is not a profile`() {
    val tracks = MpvTracks.parse(
      """[{"id":1,"type":"video","selected":true,"codec":"hevc","dolby-vision-profile":0}]""",
    )

    assertNull(tracks.first().dolbyVisionProfile)
    assertFalse(MpvTracks.isDolbyVision(tracks))
  }

  @Test
  fun `the DV codec tags are recognised on builds with no dedicated field`() {
    listOf("dvhe", "dvh1", "dav1").forEach { tag ->
      val tracks = MpvTracks.parse("""[{"id":1,"type":"video","selected":true,"codec":"$tag"}]""")
      assertTrue(tag, MpvTracks.isDolbyVision(tracks))
    }
  }

  @Test
  fun `a DV profile string is enough on its own`() {
    val tracks = MpvTracks.parse(
      """[{"id":1,"type":"video","selected":true,"codec":"hevc","codec-profile":"dvhe.05.06"}]""",
    )

    assertTrue(MpvTracks.isDolbyVision(tracks))
  }

  @Test
  fun `the DV camcorder codec is not Dolby Vision`() {
    // "dvvideo" contains "dv", and a home video has nothing to warn about.
    val tracks = MpvTracks.parse("""[{"id":1,"type":"video","selected":true,"codec":"dvvideo"}]""")

    assertFalse(MpvTracks.isDolbyVision(tracks))
  }

  @Test
  fun `dv as a word of its own counts`() {
    val tracks = MpvTracks.parse(
      """[{"id":1,"type":"video","selected":true,"codec":"hevc","codec-profile":"Main 10 DV"}]""",
    )

    assertTrue(MpvTracks.isDolbyVision(tracks))
  }

  @Test
  fun `a Dolby Vision audio track says nothing about the picture`() {
    // Dolby Digital audio next to plain HEVC video: the warning is about what the
    // panel has to render, so only the video track may raise it.
    val tracks = MpvTracks.parse(
      """
      [
        {"id":1,"type":"video","selected":true,"codec":"hevc"},
        {"id":1,"type":"audio","selected":true,"codec":"dvhe"}
      ]
      """.trimIndent(),
    )

    assertFalse(MpvTracks.isDolbyVision(tracks))
  }

  @Test
  fun `the video track falls back to the first when nothing is flagged selected`() {
    val tracks = MpvTracks.parse("""[{"id":1,"type":"video","codec":"dvh1"}]""")

    assertEquals(1, MpvTracks.selectedVideo(tracks)?.id)
    assertTrue(MpvTracks.isDolbyVision(tracks))
  }

  @Test
  fun `a file with no video track is not Dolby Vision`() {
    assertNull(MpvTracks.selectedVideo(emptyList()))
    assertFalse(MpvTracks.isDolbyVision(emptyList()))
  }
}
