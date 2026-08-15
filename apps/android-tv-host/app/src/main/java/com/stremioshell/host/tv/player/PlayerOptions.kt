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
    if (!speed.isFinite() || speed <= 0.0) return STEPS.indexOf(DEFAULT)
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
    val safe = speed.takeIf { it.isFinite() && it > 0.0 } ?: DEFAULT
    val text = String.format(Locale.ROOT, "%.2f", safe).trimEnd('0').trimEnd('.')
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
 * What separates the glyphs from the picture behind them, as the mpv properties
 * libass draws them with.
 *
 * [Outline] is mpv's own defaults — a border of 3 and no drop shadow — which is
 * what the player has always shipped, so an existing viewer's subtitles do not
 * change shape under them.
 *
 * The ladder runs from nothing around the letters to the most ink around them.
 * [None] is for a clean encode on a dark picture, where an outline is only
 * thickening text that was already legible; [HighContrast] is what keeps white
 * subtitles readable over snow, sand and a credit roll.
 */
enum class SubtitleEdge(
  val storageName: String,
  val label: String,
  /** `sub-border-size`, in the same scaled pixels as `sub-font-size`. */
  val borderSize: Int,
  /** `sub-shadow-offset`; 0 draws no drop shadow at all. */
  val shadowOffset: Int,
) {
  None("none", "None", 0, 0),
  Outline("outline", "Outline", 3, 0),
  Shadow("shadow", "Shadow", 1, 3),
  HighContrast("high-contrast", "High contrast", 5, 0),
  ;

  /**
   * This step as mpv property names and values.
   *
   * The names live here and nowhere else. The player writes them at three points
   * — as options before `init`, as properties when the stored value arrives, and
   * again on every press of the row — and a name that had drifted between two of
   * those sites would fail silently, because mpv reports an unknown property by
   * returning an error nothing on this path reads.
   */
  val mpvOptions: List<Pair<String, String>>
    get() = listOf(
      "sub-border-size" to borderSize.toString(),
      "sub-shadow-offset" to shadowOffset.toString(),
      // Written by the steps with no shadow too, so that [Shadow] draws against a
      // value this ladder states rather than one an mpv release is free to move.
      "sub-shadow-color" to SHADOW_COLOR,
    )

  companion object {
    val DEFAULT = Outline

    /** Opaque black: a shadow that is not darker than the picture is not a shadow. */
    private const val SHADOW_COLOR = "#000000"

    /** The stored name, falling back to [DEFAULT] for anything unrecognised. */
    fun fromStorage(name: String?): SubtitleEdge {
      val key = name?.trim()?.lowercase(Locale.ROOT).orEmpty()
      return entries.firstOrNull { it.storageName == key } ?: DEFAULT
    }

    /** [steps] places along the ladder, stopping at either end, as [SubtitleSize]. */
    fun stepped(current: SubtitleEdge, steps: Int): SubtitleEdge {
      val index = (current.ordinal + steps).coerceIn(0, entries.lastIndex)
      return entries[index]
    }
  }
}

/** mpv's own default border style: an outline around each letter, and no box. */
private const val OUTLINE_AND_SHADOW = "outline-and-shadow"

/** A box the size of the text, drawn in `sub-back-color` including its alpha. */
private const val BACKGROUND_BOX = "background-box"

/**
 * A box behind the subtitles, as mpv's `sub-border-style` and `sub-back-color`
 * together.
 *
 * [Off] is mpv's default and therefore the shipped look, for the same reason
 * [SubtitleEdge.Outline] is: a viewer who never opens these rows keeps the
 * subtitles this player has always drawn.
 *
 * The colour alone draws nothing. Since mpv 0.38 — which is what the bundled
 * libmpv is — the border style is its own option, and `sub-back-color` is only
 * consulted for the two box styles; under the default `outline-and-shadow` the
 * value is set and then ignored, which is precisely what this row used to do.
 * Every step therefore names both properties.
 *
 * The box style is `background-box`, whose box is the size of the text, rather
 * than `opaque-box`, whose box is the size of the border: with the latter the
 * edge row would silently become a padding control, and [SubtitleEdge.None]
 * would leave the letters with a box that touches them. `background-box` also
 * draws the box in the colour's alpha, which is the whole of [Dim].
 *
 * The two rows therefore stay independent settings — the edge draws around each
 * letter either way, and this one decides what is behind them.
 */
enum class SubtitleBackground(
  val storageName: String,
  val label: String,
  /** `sub-back-color`, as mpv's `#AARRGGBB`, where alpha `FF` is opaque. */
  val backColor: String,
  /** `sub-border-style`: which of mpv's border styles draws (or does not draw) the box. */
  val borderStyle: String,
) {
  Off("off", "Off", "#00000000", OUTLINE_AND_SHADOW),
  // Half alpha: enough that the text sits on something, little enough that the
  // part of the picture it covers is still being watched through it.
  Dim("dim", "Dim", "#80000000", BACKGROUND_BOX),
  Solid("solid", "Solid", "#FF000000", BACKGROUND_BOX),
  ;

  /**
   * As [SubtitleEdge.mpvOptions]: the property names live here and nowhere else.
   *
   * [Off] states `outline-and-shadow` rather than leaving the property alone, so
   * that the player can keep applying these pairs as plain name/value writes.
   * A step that named only the properties it needed would leave the box style
   * from the step before it standing, and stepping back to [Off] would clear the
   * colour while the box remained.
   */
  val mpvOptions: List<Pair<String, String>>
    get() = listOf(
      "sub-border-style" to borderStyle,
      "sub-back-color" to backColor,
    )

  companion object {
    val DEFAULT = Off

    /** The stored name, falling back to [DEFAULT] for anything unrecognised. */
    fun fromStorage(name: String?): SubtitleBackground {
      val key = name?.trim()?.lowercase(Locale.ROOT).orEmpty()
      return entries.firstOrNull { it.storageName == key } ?: DEFAULT
    }

    /** [steps] places along the ladder, stopping at either end, as [SubtitleSize]. */
    fun stepped(current: SubtitleBackground, steps: Int): SubtitleBackground {
      val index = (current.ordinal + steps).coerceIn(0, entries.lastIndex)
      return entries[index]
    }
  }
}

/**
 * Where the soundtrack is decoded: here, or by whatever is on the other end of
 * the HDMI cable.
 *
 * [Decode] is mpv doing the work and handing the sink PCM, which every TV,
 * soundbar and pair of headphones can take — and which is why it is the default.
 * [Passthrough] asks Android to hand supported compressed bitstreams to the sink
 * for its own decoder. It can preserve a receiver's surround/Atmos path, but
 * only when the Android device, active route and sink all advertise the codec;
 * an unsupported candidate can be silence.
 *
 * [spdifCodecs] is therefore a candidate list, not proof of support: it is what
 * this player is willing to ask for, and never what it asks for. The value that
 * reaches mpv is always [spdifCodecsFor] resolved against the codecs the active
 * route reports, and passthrough is declined for the session when that returns
 * null. Nothing else may write `audio-spdif`.
 */
enum class AudioOutputMode(
  val storageName: String,
  val label: String,
  /** The value for mpv's `audio-spdif`; empty is "decode everything". */
  val spdifCodecs: String,
) {
  Decode("decode", "Decode", ""),
  Passthrough("passthrough", "Passthrough", "ac3,eac3,dts,dts-hd,truehd"),
  ;

  /**
   * The OSD line for a mid-film switch. Passthrough's names the failure it can
   * cause: a sink that will not take the bitstream plays nothing at all, and
   * silence with no explanation reads as a broken player rather than as a setting
   * to put back.
   */
  val osdMessage: String
    get() = when (this) {
      Decode -> "Audio output: Decode"
      Passthrough ->
        "Passthrough requested - device support is not verified; choose Decode if audio is silent"
    }

  /**
   * The mpv codec list that is safe to request for a known active sink — the
   * value the player writes to `audio-spdif`, once it has resolved [sinkCodecs]
   * from the route Android actually selected for media.
   *
   * The contract the call site depends on:
   * - Names on both sides use mpv's `audio-spdif` spelling, not Android's
   *   encoding constants. Folding `E_AC3_JOC` in with `E_AC3` into one `eac3`
   *   is the caller's job, because only the caller can see the encodings.
   * - The result keeps the order of [spdifCodecs], not the iteration order of
   *   [sinkCodecs]: mpv's list is a preference order, and a set built from an
   *   Android query has no meaningful order to inherit.
   * - Names the ladder does not offer are ignored rather than passed through, so
   *   nothing this player never validated can reach mpv.
   * - Null means support is unknown (no set at all) or nothing survived the
   *   intersection, so the conservative action is to remain in [Decode] — a
   *   caller writing the property directly wants `?: ""`, which is what Decode
   *   itself always resolves to.
   *
   * A sink that reports more than one route must be intersected before it gets
   * here: a union would make the least-capable route silent as soon as a richer
   * AVR happened to be selected beside it.
   */
  fun spdifCodecsFor(sinkCodecs: Set<String>?): String? {
    if (this == Decode) return ""
    val supported = sinkCodecs
      ?.asSequence()
      ?.map { it.trim().lowercase(Locale.ROOT) }
      ?.filter { it.isNotBlank() }
      ?.toSet()
      ?: return null
    return spdifCodecs
      .split(',')
      .filter { it in supported }
      .joinToString(",")
      .ifBlank { null }
  }

  companion object {
    val DEFAULT = Decode

    /** The stored name, falling back to [DEFAULT] for anything unrecognised. */
    fun fromStorage(name: String?): AudioOutputMode {
      val key = name?.trim()?.lowercase(Locale.ROOT).orEmpty()
      return entries.firstOrNull { it.storageName == key } ?: DEFAULT
    }

    /** [steps] places along the list, stopping at either end, as [SubtitleSize]. */
    fun stepped(current: AudioOutputMode, steps: Int): AudioOutputMode {
      val index = (current.ordinal + steps).coerceIn(0, entries.lastIndex)
      return entries[index]
    }
  }
}

enum class AudioRouteAction { None, RefreshPassthrough, Decode }

/**
 * What a live route change must do to the audio chain.
 *
 * A non-empty codec list is not by itself unchanged: moving from a full AVR to an AC-3-only sink
 * still requires rewriting `audio-spdif` and reopening the selected audio track.
 */
object AudioRoutePolicy {
  fun action(
    mode: AudioOutputMode,
    appliedSpdifCodecs: String,
    supportedSpdifCodecs: String,
  ): AudioRouteAction = when {
    mode != AudioOutputMode.Passthrough -> AudioRouteAction.None
    supportedSpdifCodecs.isEmpty() -> AudioRouteAction.Decode
    supportedSpdifCodecs != appliedSpdifCodecs -> AudioRouteAction.RefreshPassthrough
    else -> AudioRouteAction.None
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
    val safeCurrent = currentSec
      .takeIf(Double::isFinite)
      ?.coerceIn(-LIMIT_SEC, LIMIT_SEC)
      ?: 0.0
    val grid = Math.round(safeCurrent / STEP_SEC) + steps.toLong()
    val next = (grid * STEP_SEC).coerceIn(-LIMIT_SEC, LIMIT_SEC)
    return Math.round(next * 1000.0) / 1000.0
  }

  /** "+150 ms" / "0 ms" / "-25 ms" — signed, because the sign is the whole point. */
  fun label(sec: Double): String {
    val safe = sec.takeIf(Double::isFinite)?.coerceIn(-LIMIT_SEC, LIMIT_SEC) ?: 0.0
    val ms = Math.round(safe * 1000.0)
    return when {
      ms > 0 -> "+$ms ms"
      ms < 0 -> "$ms ms"
      else -> "0 ms"
    }
  }
}

/**
 * How much longer the player keeps going before it stops itself.
 *
 * Session state and deliberately never persisted: a timer is a statement about
 * this evening, and one that came back on the next launch would pause a film
 * nobody asked it to. It does survive an episode transition, because "sleep in
 * thirty minutes" is thirty minutes of television rather than thirty minutes of
 * whichever episode happened to be playing when it was set.
 *
 * [AfterEpisode] is the same statement without a clock. It has no duration to
 * schedule: the ending is the deadline, and what it changes is that the ending
 * offers the next episode without starting it.
 *
 * A short ladder for the reason [PlaybackSpeeds] is one: every value has to be a
 * couple of D-pad presses from either end of it.
 */
enum class SleepTimer(val durationMs: Long) {
  Off(0L),
  Minutes15(15L * 60_000L),
  Minutes30(30L * 60_000L),
  Minutes60(60L * 60_000L),
  Minutes90(90L * 60_000L),
  AfterEpisode(0L),
  ;

  /** Whether arming this one means a deadline the player has to schedule. */
  val isTimed: Boolean get() = durationMs > 0L

  /** The whole minutes on the face of the option, for the menu's label. */
  val minutes: Int get() = (durationMs / 60_000L).toInt()

  companion object {
    val DEFAULT = Off

    /** [steps] places along the ladder, stopping at either end, as [SubtitleSize]. */
    fun stepped(current: SleepTimer, steps: Int): SleepTimer {
      val index = (current.ordinal + steps).coerceIn(0, entries.lastIndex)
      return entries[index]
    }

    /**
     * Whole minutes left on an armed timer, rounded up so that the last part
     * minute still reads as one: a label that said "0 min" would be reporting an
     * evening that has not ended yet. Zero only once the deadline itself has
     * passed, which is the moment the timer is about to fire.
     */
    fun minutesLeft(remainingMs: Long): Int {
      if (remainingMs <= 0L) return 0
      val roundedUp = remainingMs / 60_000L + if (remainingMs % 60_000L == 0L) 0L else 1L
      return roundedUp.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Whether an ending under [current] may start the next episode on its own.
     *
     * The only thing [AfterEpisode] changes about the end of an episode: the card
     * still appears, and the next episode is still one press away. Suppressing
     * the offer as well would leave a viewer who is awake after all with nothing
     * on screen but a stopped player.
     */
    fun autoPlaysNext(current: SleepTimer): Boolean = current != AfterEpisode

    /**
     * The timer an ending leaves behind. [AfterEpisode] is spent on the ending it
     * was armed for, whether or not there was anything to play next.
     *
     * One-shot on purpose: left armed it would also stop the episode after the
     * one the viewer asked about, which is a different request from the one the
     * row makes and one nothing on screen would explain the second time.
     */
    fun afterEnding(current: SleepTimer): SleepTimer =
      if (current == AfterEpisode) DEFAULT else current
  }
}
