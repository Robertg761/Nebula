package com.stremioshell.host.tv.ui

import kotlin.math.roundToInt

/**
 * What a screen reader announces for the nodes a D-pad can land on.
 *
 * TalkBack follows input focus, and the focusable node on these screens is the card - not the
 * caption drawn under it - so a poster with nothing of its own announces as "unlabeled". Kept as
 * plain string building rather than wording buried in composables, so the phrasing is testable.
 */
object A11yLabels {
  /** Added to cards whose long press manages a row, which nothing about a poster hints at. */
  private const val OPTIONS_HINT = "Press and hold for options"

  /**
   * Bullets and the double-space dashes the detail lines use are visual separators: spoken, they
   * are either the word "bullet" or nothing at all, which runs two facts into one phrase. Commas
   * are what TalkBack turns into a pause.
   */
  private val VISUAL_SEPARATORS = Regex("""\s*•\s*|\s{2,}-\s{2,}""")

  fun spoken(line: String): String =
    line.split(VISUAL_SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")

  private fun join(vararg parts: String?): String =
    parts.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")

  /**
   * A poster card: its title, then the caption the card itself shows.
   *
   * @param manageable a long press manages the row this card sits in (My List, Continue Watching).
   */
  fun card(title: String, caption: String? = null, manageable: Boolean = false): String {
    val label = join(title, caption?.let(::spoken))
    return if (manageable) "$label. $OPTIONS_HINT" else label
  }

  /** A Continue Watching card, where the progress bar is the only sign of how far in it is. */
  fun continueWatching(title: String, season: Int?, episode: Int?, progress: Float): String {
    val label = join(title, episodeCode(season, episode), progressLabel(progress))
    return "$label. $OPTIONS_HINT"
  }

  /** "season 2, episode 5": "S2E5" is announced one letter and digit at a time. */
  fun episodeCode(season: Int?, episode: Int?): String? =
    if (season == null || episode == null) null else "season $season, episode $episode"

  /** Null below 1%: a title that was opened and abandoned has no progress worth reporting. */
  fun progressLabel(progress: Float): String? {
    val percent = (progress.coerceIn(0f, 1f) * 100).roundToInt()
    return when {
      percent <= 0 -> null
      percent >= 100 -> "finished"
      else -> "$percent% watched"
    }
  }

  /**
   * An episode row. [marker] is the same trailing line the row shows - aired date, watched, time
   * left - which is where the reason an unaired episode does nothing on OK lives.
   */
  fun episodeRow(number: Int, name: String, marker: String): String =
    join("Episode $number", name, spoken(marker).ifEmpty { null })

  fun castMember(name: String, character: String): String =
    if (character.isBlank()) name else "$name as ${character.trim()}"

  /** The billboard is a single focusable banner, so it announces as a single sentence. */
  fun hero(title: String, metadata: String): String =
    join("Featured: $title", spoken(metadata).ifEmpty { null }, "View details")

  /** The button's own text is a tick and a dash, neither of which says what pressing it does. */
  fun watchlistButton(title: String, inList: Boolean): String =
    if (inList) "Remove $title from My List" else "Add $title to My List"
}
