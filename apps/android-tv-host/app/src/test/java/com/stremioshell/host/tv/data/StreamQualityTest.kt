package com.stremioshell.host.tv.data

import com.stremioshell.host.tv.data.addon.AddonBehaviorHints
import com.stremioshell.host.tv.data.addon.AddonStream
import com.stremioshell.host.tv.data.addon.StreamOrder
import com.stremioshell.host.tv.data.addon.StreamQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamQualityTest {
  @Test
  fun `resolution comes from the pixel height wherever it is written`() {
    assertEquals(2160, StreamQuality.of("Show.S01E01.2160p.WEB-DL").resolutionHeight)
    assertEquals(1080, StreamQuality.of("[RD+] Comet 1080p").resolutionHeight)
    assertEquals(720, StreamQuality.of("show.s01e01.720p.hdtv.x264").resolutionHeight)
    assertEquals(480, StreamQuality.of("Old.Release.480p").resolutionHeight)
  }

  @Test
  fun `the highest resolution in the row wins`() {
    // Addons label a transcode with both the source and the delivered height.
    assertEquals(2160, StreamQuality.of("1080p transcode of 2160p source").resolutionHeight)
  }

  @Test
  fun `resolution words stand in for a missing pixel height`() {
    assertEquals(2160, StreamQuality.of("[RD] Comet 4K Remux").resolutionHeight)
    assertEquals(2160, StreamQuality.of("Movie UHD BluRay").resolutionHeight)
    assertEquals(1080, StreamQuality.of("Movie FullHD").resolutionHeight)
    assertEquals(720, StreamQuality.of("Movie HD WEB").resolutionHeight)
  }

  @Test
  fun `an unlabelled row admits it knows nothing`() {
    assertNull(StreamQuality.of("[RD+] Comet").resolutionHeight)
  }

  @Test
  fun `hdr is not read out of a resolution or a codec`() {
    assertFalse(StreamQuality.of("Movie UHD BluRay x265").hdr)
    assertTrue(StreamQuality.of("Movie 2160p HDR10+ WEB-DL").hdr)
    assertTrue(StreamQuality.of("Movie 2160p hdr").hdr)
  }

  @Test
  fun `dolby vision is only read as a marker of its own`() {
    assertTrue(StreamQuality.of("Movie 2160p DV HDR WEB-DL").dolbyVision)
    assertTrue(StreamQuality.of("Movie 2160p DoVi").dolbyVision)
    // "dv" inside a word is not a format claim.
    assertFalse(StreamQuality.of("Advanced.Release.1080p").dolbyVision)
  }

  @Test
  fun `size is read from the detail line when the addon gives no hint`() {
    assertEquals(12_884_901_888L, StreamQuality.of("Movie 2160p 💾 12 GB").sizeBytes)
    assertEquals(734_003_200L, StreamQuality.of("Movie 720p 700MB").sizeBytes)
    // Comma decimals are what a European addon writes.
    assertEquals(1_610_612_736L, StreamQuality.of("Movie 1080p 1,5 GiB").sizeBytes)
  }

  @Test
  fun `the addon's own size hint wins over the text`() {
    val stream = AddonStream(
      name = "Comet 1080p",
      title = "Movie 1080p 700MB",
      url = "https://rd.example/v.mkv",
      behaviorHints = AddonBehaviorHints(videoSize = 9_000_000_000L),
    )

    assertEquals(9_000_000_000L, StreamQuality.parse(stream).sizeBytes)
  }

  @Test
  fun `badges read as a viewer would say them`() {
    val quality = StreamQuality.of("Movie 2160p DV HDR10 12 GB")

    assertEquals(listOf("4K", "DV", "HDR", "12.0 GB"), quality.badges)
  }

  @Test
  fun `a row with nothing to say carries no badges`() {
    assertEquals(emptyList<String>(), StreamQuality.of("[RD+] Comet").badges)
  }

  @Test
  fun `sorting puts the best tier first and keeps addon order inside it`() {
    val streams = listOf(
      stream("A 720p"),
      stream("B 1080p"),
      stream("C 2160p"),
      stream("D 1080p"),
      stream("E unlabelled"),
      stream("F 4K"),
    )

    val sorted = StreamOrder.byQuality(streams).map { it.label }

    assertEquals(listOf("C 2160p", "F 4K", "B 1080p", "D 1080p", "A 720p", "E unlabelled"), sorted)
  }

  private fun stream(label: String) = AddonStream(name = label, url = "https://rd.example/v.mkv")
}
