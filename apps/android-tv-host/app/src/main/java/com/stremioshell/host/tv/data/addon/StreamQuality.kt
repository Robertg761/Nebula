package com.stremioshell.host.tv.data.addon

import java.util.Locale
/**
 * What a stream row's own text says about the release.
 *
 * The addon protocol has no field for any of this: resolution, dynamic range and
 * size live in free text that each addon formats its own way ("[RD+] Comet 4K",
 * "Show.S01E02.2160p.DV.HDR10+.WEB-DL", "💾 12.4 GB"), so this reads whatever is
 * there and says nothing where it is not. A null resolution means "the row did
 * not say", never "SD" - guessing would sort an unlabelled remux below a cam.
 */
data class StreamQuality(
  val resolutionHeight: Int? = null,
  val hdr: Boolean = false,
  val dolbyVision: Boolean = false,
  val sizeBytes: Long? = null,
) {
  // Deliberately no combined badge list: the picker row colours the badges by whether they are a
  // reason to pick the release or just a fact about it, and a flat list of strings cannot say
  // which is which. The row builds its own from the two labels below.

  fun resolutionLabel(): String? = when (val height = resolutionHeight) {
    null -> null
    // What every store, panel and addon calls 2160p, so the badge says it too.
    2160 -> "4K"
    else -> "${height}p"
  }

  fun formattedSize(): String? {
    val bytes = sizeBytes?.takeIf { it > 0 } ?: return null
    return if (bytes >= GIB) "%.1f GB".format(bytes.toDouble() / GIB) else "${bytes / MIB} MB"
  }

  companion object {
    private const val MIB = 1024L * 1024L
    private const val GIB = MIB * 1024L

    /** Every line an addon might have hidden the release details in. */
    fun parse(stream: AddonStream): StreamQuality = of(
      text = listOfNotNull(
        stream.name,
        stream.title,
        stream.description,
        stream.behaviorHints?.filename,
      ).joinToString(" "),
      sizeHintBytes = stream.behaviorHints?.videoSize,
    )

    fun of(text: String, sizeHintBytes: Long? = null): StreamQuality {
      val lower = text.lowercase(Locale.ROOT)
      return StreamQuality(
        resolutionHeight = resolution(lower),
        // "hdr10", "hdr10+" and "hdr" all mean the same thing to a badge; PQ and
        // HLG are how a remux names the transfer function rather than the format.
        // Word-boundary matched like every other marker here, because a substring
        // test badged "HDRip" and "UHDRip" - an SD-era DVD rip, and about as far
        // from HDR as a release gets - as HDR, which then poisoned both the HDR
        // filter and the remembered-selection auto-pick for the next episode.
        // "hdr10" earns a token of its own so that "hdr10" and "hdr10+" still
        // match once "hdr" alone stops matching inside them.
        hdr = lower.containsToken("hdr", "hdr10", "hdr10plus", "pq10", "pq", "hlg"),
        dolbyVision = lower.containsAny("dolby vision", "dolbyvision", "dovi") ||
          lower.containsToken("dv"),
        sizeBytes = sizeHintBytes?.takeIf { it > 0 } ?: size(lower),
      )
    }

    /**
     * The first valid `<n>p` in the row. Text is assembled in display-priority
     * order (name, title, description, filename), so this is the delivered tier
     * the addon puts in its label. Taking the highest number silently promoted
     * "1080p transcode from a 2160p source" into a 4K stream, distorting both the
     * picker order and next-episode compatibility.
     */
    private fun resolution(lower: String): Int? {
      val numeric = PIXEL_HEIGHT.findAll(lower)
        .mapNotNull { it.groupValues[1].toIntOrNull() }
        .filter { it in MIN_HEIGHT..MAX_HEIGHT }
        .firstOrNull()
      if (numeric != null) return numeric
      return when {
        lower.containsAny("2160", "uhd") || lower.containsToken("4k") -> 2160
        lower.containsAny("fullhd", "full hd") || lower.containsToken("fhd") -> 1080
        lower.containsToken("hd") -> 720
        lower.containsToken("sd") -> 480
        else -> null
      }
    }

    /** "12.4 GB", "1,5 GiB", "700MB" - the size an addon puts in its detail line. */
    private fun size(lower: String): Long? {
      val match = SIZE.find(lower) ?: return null
      val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
      val unit = if (match.groupValues[2].startsWith("g")) GIB else MIB
      return (amount * unit).toLong().takeIf { it > 0 }
    }

    private fun String.containsAny(vararg needles: String): Boolean =
      needles.any { contains(it) }

    /**
     * Matches a marker only as a word of its own: "dv" is inside "advanced",
     * "hd" is inside "hdr", and either false positive puts a stream in the wrong
     * tier.
     */
    private fun String.containsToken(vararg tokens: String): Boolean =
      tokens.any { token -> TOKENS.getValue(token).containsMatchIn(this) }

    /**
     * Every word-boundary marker this file asks about, compiled once.
     *
     * Built per call, these were the most expensive thing on the stream picker by a wide margin:
     * a parse runs seven of them, the filter parsed each row several times over, and eighty rows
     * meant thousands of fresh Regex compilations on the main thread for one press of a filter
     * chip. Same discipline as StreamPresentation's own token table.
     */
    private val TOKENS = listOf(
      "hdr",
      "hdr10",
      "hdr10plus",
      "pq10",
      "pq",
      "hlg",
      "dv",
      "4k",
      "fhd",
      "hd",
      "sd",
    )
      .associateWith { Regex("(?<![a-z0-9])$it(?![a-z0-9])") }

    /**
     * `<n>p`, optionally carrying a bit depth.
     *
     * The trailing lookahead is what keeps "1080price" and "720px" out, but on its own it also
     * threw away "1080p10" and "2160p10bit" - the ordinary way a 10-bit encode is spelled - which
     * left those rows ranked unknown, sorted below every labelled row and skipped by auto-pick.
     * Only the three real bit depths or a two/three-digit frame-rate suffix are accepted after the
     * `p`. The suffix is discarded; it merely lets ordinary names such as `1080p60` reach the
     * height while the final lookahead still rejects `1080price` and `720px`.
     */
    private val PIXEL_HEIGHT = Regex(
      "(?<!\\d)(\\d{3,4})p(?:(?:8|10|12)(?:bit)?|\\d{2,3}(?:fps)?)?(?![a-z0-9])",
    )
    private val SIZE = Regex("(\\d+(?:[.,]\\d+)?)\\s*(gib|gb|mib|mb)(?![a-z])")
    private const val MIN_HEIGHT = 240
    private const val MAX_HEIGHT = 4320
  }
}

/**
 * Puts the best-looking streams first while leaving the addon's own ordering
 * intact inside a tier: an addon that has already sorted by cached-ness, seeders
 * or debrid status knows things this parser cannot see, so the only thing worth
 * overruling is a 720p row sitting above a 4K one.
 */
object StreamOrder {
  /**
   * A row that never said its resolution sorts below every row that did, but
   * above nothing else: it is usually an unlabelled or odd release, and burying
   * it further would hide the only stream some titles have.
   */
  private const val UNKNOWN_RANK = 0

  fun byQuality(streams: List<AddonStream>): List<AddonStream> = streams
    // Parsed once per stream rather than inside the comparator, which would
    // re-run the regexes O(n log n) times for a list of eighty streams.
    .map { it to StreamQuality.parse(it) }
    .sortedByDescending { (_, quality) -> quality.resolutionHeight ?: UNKNOWN_RANK }
    .map { (stream, _) -> stream }
}
