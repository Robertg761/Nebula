package com.stremioshell.host.tv.player

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Getting a downloaded subtitle file into the one encoding mpv can actually read.
 *
 * This exists because of what the shipped player core is: `dev.jdtech.mpv:libmpv` is built with
 * iconv disabled and without uchardet, so `sub-codepage` is inert and mpv treats every subtitle
 * file it is handed as UTF-8. Most of the subtitle files on OpenSubtitles are not UTF-8 — the
 * Russian, Polish, Greek and Turkish uploads in particular are overwhelmingly legacy single-byte
 * Windows code pages, because that is what the desktop tools that produced them wrote — and mpv
 * renders those bytes as mojibake with no error and no clue as to why. Nothing in the player can
 * fix that after the fact, so the file is converted here, on the way to disk, before `sub-add`
 * ever sees it.
 *
 * The order of the guesses is what keeps this safe. A byte-order mark is a statement, so it is
 * believed. Failing that, the whole file is validated as strict UTF-8: real UTF-8 (and plain
 * ASCII, which is a subset of it) is passed through byte-for-byte, and multi-byte sequences make
 * the check decisive rather than probabilistic — arbitrary legacy bytes almost never form valid
 * UTF-8. Only once the file has failed that test is the language guessed from, since a single-byte
 * code page cannot be identified from the bytes alone. That last step is a guess, but it is a much
 * better one than "assume UTF-8 and draw question marks".
 */
object SubtitleCharset {
  /**
   * [bytes] as UTF-8, given a subtitle the addon tagged as [lang].
   *
   * Returns the input array itself when it is already UTF-8, so the common case copies nothing.
   * Never throws: the fallbacks are single-byte code pages, which have a character for every one
   * of the 256 possible bytes, so there is no input this cannot produce *something* readable from.
   */
  fun toUtf8(bytes: ByteArray, lang: String?): ByteArray {
    val bom = byteOrderMark(bytes)
    if (bom != null) {
      // A BOM is the file saying what it is. Strip it either way: mpv's subtitle demuxers key off
      // the first characters of the file ("1" or "WEBVTT" or "[Script Info]"), and a BOM sitting
      // in front of those has been enough to make a file fail to parse at all.
      val body = bytes.copyOfRange(bom.length, bytes.size)
      return if (bom.charset == StandardCharsets.UTF_8) body else transcode(body, bom.charset)
    }
    if (isValidUtf8(bytes)) return bytes
    return transcode(bytes, fallbackCharset(lang))
  }

  /**
   * The single-byte code page a file tagged [lang] is most likely to be in, once it is known not
   * to be UTF-8.
   *
   * One charset per language rather than any attempt at statistical detection: the alternative is
   * a heuristic that is wrong in a way nobody can explain, and the addon's own language tag is
   * both free and right far more often than not. Windows code pages rather than the ISO-8859
   * family because subtitle files are written on Windows, and the Windows pages are supersets of
   * their ISO counterparts over the range that matters.
   */
  fun fallbackCharset(lang: String?): Charset {
    val code = LanguageCodes.normalize(lang)
    val name = CODE_PAGE_BY_LANGUAGE[code] ?: DEFAULT_CODE_PAGE
    return resolved(name)
  }

  private fun transcode(bytes: ByteArray, charset: Charset): ByteArray =
    String(bytes, charset).toByteArray(StandardCharsets.UTF_8)

  private data class ByteOrderMark(val charset: Charset, val length: Int)

  private fun byteOrderMark(bytes: ByteArray): ByteOrderMark? = when {
    bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
      bytes[2] == 0xBF.toByte() -> ByteOrderMark(StandardCharsets.UTF_8, 3)
    // Checked before the UTF-8 validation rather than after: a UTF-16 file is mostly ASCII
    // characters padded with NUL bytes, and NUL is a perfectly valid UTF-8 byte, so a UTF-16 file
    // passes a UTF-8 validity check and then renders as text with a space between every letter.
    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
      ByteOrderMark(StandardCharsets.UTF_16LE, 2)
    bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
      ByteOrderMark(StandardCharsets.UTF_16BE, 2)
    else -> null
  }

  /**
   * Whether every byte of [bytes] is part of a well-formed UTF-8 sequence.
   *
   * Reporting rather than replacing is the whole point: a decoder that silently substitutes
   * U+FFFD would call every file valid and this class would never convert anything. Decoded in
   * chunks through a small reused buffer because the answer is a boolean — a subtitle can be
   * megabytes, and there is no reason to hold its decoded form in memory to throw it away.
   */
  private fun isValidUtf8(bytes: ByteArray): Boolean {
    val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    val input = ByteBuffer.wrap(bytes)
    val output = CharBuffer.allocate(VALIDATION_CHUNK_CHARS)
    while (true) {
      val result = decoder.decode(input, output, true)
      if (result.isError) return false
      if (result.isUnderflow) break
      // Overflow only means the scratch buffer filled up; the input still has more to say.
      output.clear()
    }
    output.clear()
    return !decoder.flush(output).isError
  }

  /**
   * Every name below is an ICU converter Android has had since the beginning and a JDK charset the
   * unit tests run against, but a lookup that threw would turn "this subtitle is in Thai" into "no
   * subtitle at all". Latin-1 is the one charset every runtime is required to carry, and it at
   * least keeps the ASCII half of the file intact.
   */
  private fun resolved(name: String): Charset =
    (listOf(name) + CANDIDATES[name].orEmpty()).firstNotNullOfOrNull { candidate ->
      runCatching { Charset.forName(candidate) }.getOrNull()
    } ?: StandardCharsets.ISO_8859_1

  private const val WINDOWS_1250 = "windows-1250"
  private const val WINDOWS_1251 = "windows-1251"
  private const val WINDOWS_1253 = "windows-1253"
  private const val WINDOWS_1254 = "windows-1254"
  private const val WINDOWS_1255 = "windows-1255"
  private const val WINDOWS_1256 = "windows-1256"
  private const val WINDOWS_1257 = "windows-1257"
  private const val WINDOWS_1258 = "windows-1258"
  private const val WINDOWS_874 = "windows-874"

  /** Western European, and the only honest answer for a language this list has never heard of. */
  private const val DEFAULT_CODE_PAGE = "windows-1252"

  /** Fallback spellings, for the one page runtimes disagree about the name of. */
  private val CANDIDATES: Map<String, List<String>> = mapOf(
    WINDOWS_874 to listOf("x-windows-874", "TIS-620"),
  )

  /**
   * Keyed on [LanguageCodes.normalize] output, so both ISO forms and a regional tag land on the
   * same entry. Languages the code table does not know stay as their raw code, which is why the
   * three-letter and two-letter spellings of those are both listed.
   */
  private val CODE_PAGE_BY_LANGUAGE: Map<String, String> = buildMap {
    fun page(charset: String, vararg codes: String) {
      for (code in codes) put(LanguageCodes.normalize(code), charset)
    }
    page(WINDOWS_1251, "ru", "uk", "bg", "sr", "mk", "mkd", "be", "bel")
    page(
      WINDOWS_1250,
      "pl", "cs", "sk", "hu", "hr", "sl", "ro", "sq", "sqi", "alb", "bs", "bos",
    )
    page(WINDOWS_1253, "el")
    page(WINDOWS_1254, "tr", "az", "aze")
    page(WINDOWS_1255, "he")
    page(WINDOWS_1256, "ar", "fa", "ur", "urd")
    page(WINDOWS_874, "th")
    page(WINDOWS_1258, "vi")
    page(WINDOWS_1257, "lt", "lit", "lv", "lav", "et", "est")
  }

  /** Two thousand characters is a handful of subtitle cues; the buffer is scratch, not a result. */
  private const val VALIDATION_CHUNK_CHARS = 2_048
}
