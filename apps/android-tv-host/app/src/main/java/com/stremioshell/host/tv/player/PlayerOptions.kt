package com.stremioshell.host.tv.player

import java.util.Locale
import kotlin.math.abs

/**
 * The playback speeds the menu offers, and the stepping between them.
 *
 * Deliberately a fixed ladder rather than free adjustment: every step has to be
 * reachable with a handful of D-pad presses, and speeds mpv can hold without
 * audio artefacts are a short list anyway.
 */
object PlaybackSpeeds {
  val STEPS: List<Double> = listOf(0.75, 1.0, 1.25, 1.5, 2.0)
  const val DEFAULT = 1.0

  /**
   * Index of the step [speed] is at. mpv reports the speed as a double it has
   * round-tripped through its own parser, so the nearest step is the only safe
   * reading of it — and a file that somehow starts at a speed off the ladder
   * still highlights something.
   */
  fun nearestIndex(speed: Double): Int {
    var best = 0
    var bestErr = Double.MAX_VALUE
    STEPS.forEachIndexed { index, step ->
      val err = abs(step - speed)
      if (err < bestErr) {
        bestErr = err
        best = index
      }
    }
    return best
  }

  fun nearest(speed: Double): Double = STEPS[nearestIndex(speed)]

  /**
   * [steps] places along the ladder from [speed], stopping at either end. Held
   * rather than wrapped on purpose: a viewer nudging towards 2x must not land
   * back at 0.75x on the press that overshoots.
   */
  fun stepped(speed: Double, steps: Int): Double {
    val index = (nearestIndex(speed) + steps).coerceIn(0, STEPS.lastIndex)
    return STEPS[index]
  }

  /** "1x", "1.25x" — no trailing zeroes, because it is read at three metres. */
  fun label(speed: Double): String {
    val text = String.format(Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.')
    return "${text}x"
  }
}

/**
 * Subtitle sizes, as mpv `sub-font-size` values. [Medium] is 44, which is what
 * the player used to hardcode for everyone, so an existing viewer's subtitles do
 * not change size under them.
 *
 * The spread is wide on purpose: this is the accessibility control, and the
 * difference between 32 and 74 is the difference between a viewer being able to
 * read the subtitles from their sofa and not.
 */
enum class SubtitleSize(val storageName: String, val label: String, val fontSize: Int) {
  Small("small", "Small", 32),
  Medium("medium", "Medium", 44),
  Large("large", "Large", 58),
  Huge("huge", "Huge", 74),
  ;

  companion object {
    val DEFAULT = Medium

    /** The stored name, falling back to [DEFAULT] for anything unrecognised. */
    fun fromStorage(name: String?): SubtitleSize {
      val key = name?.trim()?.lowercase(Locale.ROOT).orEmpty()
      return entries.firstOrNull { it.storageName == key } ?: DEFAULT
    }

    /** [steps] places up or down the ladder, stopping at either end. */
    fun stepped(current: SubtitleSize, steps: Int): SubtitleSize {
      val index = (current.ordinal + steps).coerceIn(0, entries.lastIndex)
      return entries[index]
    }
  }
}

/**
 * Audio and subtitle delay stepping, for the lip-sync drift a Bluetooth speaker
 * or a soundbar's own processing introduces.
 *
 * Per-file and per-session by design: the offset belongs to one release's
 * muxing and one room's audio path, and carrying a correction into a file that
 * does not need it would break sync that was fine.
 */
object DelaySteps {
  /** 25ms: the smallest step that is audible on a step-by-step press. */
  const val STEP_SEC = 0.025

  /** Beyond this something other than delay is wrong, and it is a long walk back. */
  const val LIMIT_SEC = 10.0

  /**
   * [steps] steps from [currentSec], clamped to ±[LIMIT_SEC] and quantised onto
   * the step grid so a long run of presses cannot accumulate binary drift into
   * the label.
   */
  fun stepped(currentSec: Double, steps: Int): Double {
    val grid = Math.round(currentSec / STEP_SEC) + steps
    val next = (grid * STEP_SEC).coerceIn(-LIMIT_SEC, LIMIT_SEC)
    return Math.round(next * 1000.0) / 1000.0
  }

  /** "+150 ms" / "0 ms" / "-25 ms" — signed, because the sign is the whole point. */
  fun label(sec: Double): String {
    val ms = Math.round(sec * 1000.0)
    return when {
      ms > 0 -> "+$ms ms"
      ms < 0 -> "$ms ms"
      else -> "0 ms"
    }
  }
}
