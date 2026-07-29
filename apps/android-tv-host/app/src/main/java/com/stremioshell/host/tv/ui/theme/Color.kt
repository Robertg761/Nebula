package com.stremioshell.host.tv.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Nebula's palette: a deep-space near-black with a violet-to-cyan accent.
 *
 * Tuned for a dark room and an LED/OLED panel, which is the only way this app is ever seen. The
 * backgrounds sit a few points off true black rather than on it: banding across a large flat
 * gradient is far more visible on a TV than on a phone, and a hair of blue in the black gives the
 * dithering something to work with.
 *
 * Accent is used for exactly two things - focus and play - so that on any screen the violet is
 * always "where the remote is pointing" or "the thing you came here to press". Everything else is
 * greyscale, which is what keeps a wall of posters from fighting the UI.
 *
 * The ratios quoted below are measured WCAG contrast against the surface each colour is actually
 * used on, not estimates. That matters more here than on a phone: the viewer is three metres from
 * the panel, and a TV's own picture processing crushes shadow detail before it ever reaches the
 * screen. Anything a viewer has to *read* clears 4.5:1 on every surface it appears on.
 */
object NebulaPalette {
  /** App background. Not #000: see the banding note above. */
  val Void = Color(0xFF06060F)

  /**
   * Cards, dialogs, the nav rail - one step up from the background.
   *
   * Deliberately only 1.10:1 from [Void]: a card is not supposed to announce itself, and lifting
   * this is what makes a dark-room UI go grey. The steps that had to move are the ones above,
   * which carry *state* rather than structure.
   */
  val Surface = Color(0xFF12122A)

  /**
   * Chips, inactive tracks, artwork placeholders, secondary buttons.
   *
   * Raised from #1E1E3C, which sat 1.14:1 from [Surface] - below the threshold a step is
   * perceptible at three metres, which meant every component that signalled focus by moving
   * Surface->SurfaceVariant was signalling nothing at all. Now 1.245:1.
   */
  val SurfaceVariant = Color(0xFF24244A)

  /**
   * The step above: a focused list row, the player menu's active plate.
   *
   * The ramp had no fourth stop, so the player invented its own plates rather than reach for one.
   * A row moving [Surface] -> here is a 1.45:1 lift, which reads across a room.
   */
  val SurfaceRaised = Color(0xFF2E2E5C)

  /** Hairlines and unfocused card outlines. Barely there on purpose. */
  val Outline = Color(0xFF2C2C52)

  /**
   * The edge of anything that has to be told apart from the page behind it: a dialog over a wall
   * of posters, a panel floating on live video, the unplayed half of a progress bar.
   *
   * [Outline] is right for a card hairline and too faint for those three jobs - at 1.39:1 on
   * [Surface] a dialog had no edge at all, and a scrub bar's remainder could not be seen, so the
   * viewer could tell how much film they had watched but not how much was left.
   */
  val OutlineStrong = Color(0xFF3A3A66)

  /** The accent. Focus rings, progress fills, primary buttons. */
  val Violet = Color(0xFF8B6CFF)

  /** The lighter end of the accent, for the focus ring itself so it reads as "lit". */
  val VioletBright = Color(0xFFA78BFF)

  /** The cool end of the accent gradient. Never used alone as a fill. */
  val Cyan = Color(0xFF4CC9F0)

  /**
   * Ink for anything sitting on an accent fill.
   *
   * Near-black rather than white: white on [Violet] is 3.69:1, which fails AA for the 15sp label a
   * button actually carries, and white on [VioletBright] is 2.70:1. This is 5.13:1 and 7.02:1 on
   * those same two fills. It was previously a raw literal declared eight times across six files,
   * three of them carrying a comment asserting it matched the others - which is a token asking to
   * be born.
   */
  val OnAccent = Color(0xFF120A2E)

  /** The second line of a focused row: readable on the accent without shouting. */
  val OnAccentMuted = Color(0xCC120A2E)

  /** Primary copy. Slightly violet-tinted white so it belongs to the palette. */
  val TextHigh = Color(0xFFF2F0FF)

  /** Secondary copy: metadata lines, captions, hints. */
  val TextMuted = Color(0xFFA9A4C7)

  /**
   * The quietest tone a viewer still has to read: key legends, timestamps, section eyebrows.
   *
   * Raised from #6F6A93, which measured 4.00:1 on the page and 3.18:1 on a chip. Its doc called it
   * decorative; every actual call site was load-bearing copy - the player's only key legend, the
   * pairing instructions, the up-next countdown. Now 5.88:1 / 5.34:1 / 4.29:1 on Void / Surface /
   * SurfaceVariant, still an obvious step below [TextMuted].
   */
  val TextFaint = Color(0xFF8B86AE)

  /** Genuinely inactive text. The only tone in the app not meant to be read. */
  val TextDisabled = Color(0xFF6F6A93)

  /** Failures. Desaturated toward pink so it does not vibrate against the violet. */
  val Danger = Color(0xFFFF6B7A)

  /** Success ticks - watched markers, "connected" in Settings. */
  val Success = Color(0xFF4ADE9B)

  /**
   * Caution that is not failure: "no subtitles", "may not play", a paused film.
   *
   * Amber, because a warning badge used to borrow [Danger] - so a paused film wore the same colour
   * as a dead stream and told the viewer something had gone wrong when nothing had.
   */
  val Caution = Color(0xFFFFC46B)

  /**
   * The tinted plate that means "selected" or "active".
   *
   * Was invented per call site at six different alphas - 0.14, 0.18, 0.20, 0.22, 0.22 and 0.30 -
   * three of them adjacent inside the player menu, so one idea read at three brightnesses on a
   * single screen. Baked opaque (Violet at 0.22 over [Void]) so it is one colour wherever it lands
   * and costs no per-frame alpha composite. Carries [VioletBright] at 5.89:1.
   */
  val AccentPlate = Color(0xFF231C44)

  /** The same plate, louder - the player menu's active tab. Carries [VioletBright] at 5.35:1. */
  val AccentPlateStrong = Color(0xFF2C2251)

  /** The unfilled half of a progress or scrub bar. See [OutlineStrong] for why it is not Outline. */
  val TrackInactive = OutlineStrong

  /** Scrims over artwork. Carries the background's blue so backdrops fade into the page. */
  val Scrim = Color(0xE60A0A18)
}
