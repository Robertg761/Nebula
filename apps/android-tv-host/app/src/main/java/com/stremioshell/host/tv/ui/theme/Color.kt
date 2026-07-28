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
 */
object NebulaPalette {
  /** App background. Not #000: see the banding note above. */
  val Void = Color(0xFF06060F)

  /** Cards, dialogs, the nav rail - one step up from the background. */
  val Surface = Color(0xFF12122A)

  /** Chips, inactive tracks, artwork placeholders - one step up again. */
  val SurfaceVariant = Color(0xFF1E1E3C)

  /** Hairlines and unfocused card outlines. Barely there on purpose. */
  val Outline = Color(0xFF2C2C52)

  /** The accent. Focus rings, progress fills, primary buttons. */
  val Violet = Color(0xFF8B6CFF)

  /** The lighter end of the accent, for the focus ring itself so it reads as "lit". */
  val VioletBright = Color(0xFFA78BFF)

  /** The cool end of the accent gradient. Never used alone as a fill. */
  val Cyan = Color(0xFF4CC9F0)

  /** Primary copy. Slightly violet-tinted white so it belongs to the palette. */
  val TextHigh = Color(0xFFF2F0FF)

  /** Secondary copy: metadata lines, captions, hints. */
  val TextMuted = Color(0xFFA9A4C7)

  /** Disabled and decorative text. Below body-copy contrast on purpose. */
  val TextFaint = Color(0xFF6F6A93)

  /** Failures. Desaturated toward pink so it does not vibrate against the violet. */
  val Danger = Color(0xFFFF6B7A)

  /** Success ticks - watched markers, "connected" in Settings. */
  val Success = Color(0xFF4ADE9B)

  /** Scrims over artwork. Carries the background's blue so backdrops fade into the page. */
  val Scrim = Color(0xE60A0A18)
}
