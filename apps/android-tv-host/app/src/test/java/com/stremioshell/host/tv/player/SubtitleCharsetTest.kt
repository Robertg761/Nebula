package com.stremioshell.host.tv.player

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversion the shipped libmpv cannot do for itself: it is built without iconv and without
 * uchardet, so anything that is not UTF-8 by the time `sub-add` sees it is mojibake on screen.
 */
class SubtitleCharsetTest {
  @Test
  fun `plain ascii is handed through untouched`() {
    val bytes = "1\n00:00:01,000 --> 00:00:02,000\nHello.\n".toByteArray(StandardCharsets.UTF_8)

    // The same array, not an equal one: the common case must not copy a subtitle file around.
    assertSame(bytes, SubtitleCharset.toUtf8(bytes, "eng"))
  }

  @Test
  fun `real multi-byte utf-8 is recognised and left alone`() {
    // The language tag would send this to a single-byte code page if the bytes were not checked
    // first, which would turn a correct file into a broken one.
    val bytes = "Привет, мир! — ещё\nこんにちは 🎬".toByteArray(StandardCharsets.UTF_8)

    assertArrayEquals(bytes, SubtitleCharset.toUtf8(bytes, "ru"))
  }

  @Test
  fun `a utf-8 byte order mark is believed and then removed`() {
    // mpv's demuxers key off the first characters of the file, and a BOM in front of them has
    // been enough on its own to stop a subtitle parsing at all.
    val text = "1\nПривет"
    val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
      text.toByteArray(StandardCharsets.UTF_8)

    assertEquals(text, decoded(SubtitleCharset.toUtf8(bytes, "ru")))
  }

  @Test
  fun `utf-16 is transcoded rather than mistaken for utf-8`() {
    // A UTF-16 file is mostly ASCII padded with NUL, and NUL is valid UTF-8, so a validity check
    // alone would pass it through to be drawn with a space between every letter.
    val text = "Zażółć gęślą jaźń"
    val littleEndian = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
      text.toByteArray(StandardCharsets.UTF_16LE)
    val bigEndian = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
      text.toByteArray(StandardCharsets.UTF_16BE)

    assertEquals(text, decoded(SubtitleCharset.toUtf8(littleEndian, "pol")))
    assertEquals(text, decoded(SubtitleCharset.toUtf8(bigEndian, "pol")))
  }

  @Test
  fun `cyrillic in the code page russian subtitles are actually written in survives`() {
    val text = "Привет, мир! Как дела?"
    val bytes = text.toByteArray(charset("windows-1251"))

    // If the code page were ignored these bytes would reach mpv as they are and draw as "Ïðèâåò".
    assertEquals(text, decoded(SubtitleCharset.toUtf8(bytes, "ru")))
    // The addon's tag is whichever ISO form it felt like using.
    assertEquals(text, decoded(SubtitleCharset.toUtf8(bytes, "rus")))
  }

  @Test
  fun `central european accents follow the language, not the default code page`() {
    val text = "Zażółć gęślą jaźń — ĄĆĘŁŃÓŚŹŻ"
    val bytes = text.toByteArray(charset("windows-1250"))

    assertEquals(text, decoded(SubtitleCharset.toUtf8(bytes, "pl")))
    // The same bytes read as the Western European default would say something else entirely.
    assertTrue(decoded(SubtitleCharset.toUtf8(bytes, "fr")) != text)
  }

  @Test
  fun `legacy japanese shift jis is converted before reaching mpv`() {
    val text = "日本語の字幕です。映画を楽しんでください。"
    val bytes = text.toByteArray(charset("windows-31j"))

    assertEquals(text, decoded(SubtitleCharset.toUtf8(bytes, "jpn")))
    assertEquals(text, decoded(SubtitleCharset.toUtf8(bytes, "ja-JP")))
  }

  @Test
  fun `legacy chinese chooses gb18030 or big5 from script and catalog tags`() {
    val simplified = "这是简体中文字幕。"
    val traditional = "這是繁體中文字幕。"

    assertEquals(
      simplified,
      decoded(SubtitleCharset.toUtf8(simplified.toByteArray(charset("GB18030")), "zho")),
    )
    assertEquals(
      simplified,
      decoded(SubtitleCharset.toUtf8(simplified.toByteArray(charset("GB18030")), "zhs")),
    )
    assertEquals(
      traditional,
      decoded(SubtitleCharset.toUtf8(traditional.toByteArray(charset("Big5")), "zh-Hant")),
    )
    assertEquals(
      traditional,
      decoded(SubtitleCharset.toUtf8(traditional.toByteArray(charset("Big5")), "zht")),
    )
  }

  @Test
  fun `legacy korean windows 949 is converted before reaching mpv`() {
    val text = "한국어 자막입니다. 영화를 즐기세요."
    val bytes = text.toByteArray(charset("x-windows-949"))

    assertEquals(text, decoded(SubtitleCharset.toUtf8(bytes, "kor")))
    assertEquals(text, decoded(SubtitleCharset.toUtf8(bytes, "ko-KR")))
  }

  @Test
  fun `greek, turkish, hebrew, arabic, thai and the baltic all get their own page`() {
    assertEquals("windows-1253", SubtitleCharset.fallbackCharset("el").name())
    assertEquals("windows-1254", SubtitleCharset.fallbackCharset("tr").name())
    assertEquals("windows-1254", SubtitleCharset.fallbackCharset("az").name())
    assertEquals("windows-1255", SubtitleCharset.fallbackCharset("he").name())
    assertEquals("windows-1256", SubtitleCharset.fallbackCharset("fa").name())
    assertEquals("windows-1256", SubtitleCharset.fallbackCharset("ur").name())
    assertEquals("windows-1258", SubtitleCharset.fallbackCharset("vi").name())
    assertEquals("windows-1257", SubtitleCharset.fallbackCharset("lt").name())
    assertEquals("windows-1251", SubtitleCharset.fallbackCharset("uk").name())
    assertEquals("windows-1250", SubtitleCharset.fallbackCharset("hr").name())
    // Thai is the one page the JDK and Android disagree about the name of.
    assertTrue(SubtitleCharset.fallbackCharset("th").name().endsWith("874"))
  }

  @Test
  fun `an unknown or missing language falls back to western european`() {
    assertEquals("windows-1252", SubtitleCharset.fallbackCharset(null).name())
    assertEquals("windows-1252", SubtitleCharset.fallbackCharset("").name())
    assertEquals("windows-1252", SubtitleCharset.fallbackCharset("qaa").name())
    assertEquals("windows-1252", SubtitleCharset.fallbackCharset("eng").name())

    val text = "Le café d'à côté — déjà vu"
    val bytes = text.toByteArray(charset("windows-1252"))

    assertEquals(text, decoded(SubtitleCharset.toUtf8(bytes, "qaa")))
    assertEquals(text, decoded(SubtitleCharset.toUtf8(bytes, null)))
  }

  @Test
  fun `a regional tag lands on the same page as the language`() {
    assertEquals("windows-1250", SubtitleCharset.fallbackCharset("sr-Latn").name())
    assertEquals("windows-1251", SubtitleCharset.fallbackCharset("sr-Cyrl").name())
    assertEquals("windows-1251", SubtitleCharset.fallbackCharset("RU").name())
    assertEquals("windows-1251", SubtitleCharset.fallbackCharset("mac").name())
    assertEquals("windows-1250", SubtitleCharset.fallbackCharset("mne").name())
  }

  @Test
  fun `no byte sequence can make the conversion throw`() {
    val random = Random(20260810)
    val languages = listOf(null, "", "ru", "pl", "th", "he", "zzz", "vi")
    repeat(400) { attempt ->
      val bytes = ByteArray(random.nextInt(0, 64)) { random.nextInt().toByte() }
      val language = languages[attempt % languages.size]

      val converted = SubtitleCharset.toUtf8(bytes, language)

      // Whatever came out has to be well-formed UTF-8, or mpv is no better off than before:
      // decoding and re-encoding it is only lossless when every byte was part of a valid sequence.
      assertArrayEquals(bytes.size.toString(), converted, utf8(decoded(converted)))
    }
  }

  @Test
  fun `an empty body converts to an empty file rather than failing`() {
    val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    assertEquals(0, SubtitleCharset.toUtf8(ByteArray(0), "ru").size)
    assertEquals(0, SubtitleCharset.toUtf8(bom, "ru").size)
  }

  private fun utf8(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)

  private fun decoded(bytes: ByteArray): String = String(bytes, StandardCharsets.UTF_8)

  private fun charset(name: String): Charset = Charset.forName(name)
}
