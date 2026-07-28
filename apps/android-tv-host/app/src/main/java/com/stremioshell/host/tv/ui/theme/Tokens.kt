package com.stremioshell.host.tv.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow

/**
 * The measurements every Nebula screen is built from.
 *
 * These exist so that "the gap between rails" is one number rather than nine call sites that
 * drifted apart, which is the single biggest reason the old UI read as unfinished: every screen
 * had its own idea of what a margin was.
 */
object NebulaDimens {
  /**
   * Distance from the screen edge to any content. TVs overscan, and a Google TV Streamer plugged
   * into a set with overscan left on will eat roughly the first 3% of the panel - 48dp at 1080p is
   * comfortably outside that, and it is also just where a 10-foot layout wants its margin.
   */
  val ScreenEdge = 48.dp

  /** Vertical gap between rails on Home. */
  val RailGap = 34.dp

  /** Gap between a rail's heading and its cards. */
  val RailHeadingGap = 14.dp

  /** Horizontal gap between cards in a rail. */
  val CardGap = 16.dp

  /** Gap between stacked controls in a dialog or form. */
  val ControlGap = 14.dp

  /** Standard poster: a true 2:3, so TMDB art fills the card instead of being cropped. */
  val PosterWidth = 144.dp
  val PosterHeight = 216.dp

  /** 16:9 still, for episode rows and the Continue Watching row. */
  val StillWidth = 268.dp
  val StillHeight = 151.dp

  /** Cast portraits: narrower than a poster, so a cast row reads as a different kind of row. */
  val PortraitWidth = 124.dp
  val PortraitHeight = 186.dp

  /** Home's billboard. ~55% of a 1080p viewport, leaving the first rail's top edge visible. */
  val HeroHeight = 320.dp

  /** Collapsed width of the nav rail; it expands on focus. */
  val NavRailWidth = 62.dp

  /** Artwork corner. Tighter than the shape scale: posters want to look like posters. */
  val PosterShape = RoundedCornerShape(10.dp)

  /** How far a focused card lifts. Restrained on purpose - a focused poster must not cover its
   *  own title, which is a bug this app has already shipped once. */
  const val FocusScale = 1.07f

  /** Wider elements (billboards, list rows) scale less, or they would grow past the overscan. */
  const val FocusScaleWide = 1.02f
}

/**
 * The accent gradient, violet into cyan.
 *
 * Reserved for surfaces that mean "act now" - the primary Play button, the focused nav item, the
 * playback progress fill. Used anywhere else it stops meaning anything.
 */
val NebulaAccentBrush: Brush
  get() = Brush.horizontalGradient(listOf(NebulaPalette.Violet, NebulaPalette.Cyan))

/**
 * Left-to-right scrim for a billboard: opaque over the copy, clear over the artwork.
 *
 * TMDB backdrops are full-frame stills with no safe area for text, so the readable side has to be
 * manufactured. Three stops rather than two - a linear fade leaves a visible diagonal edge across
 * a flat sky, and the extra stop hides it.
 */
val NebulaHeroScrim: Brush
  get() = Brush.horizontalGradient(
    0.00f to NebulaPalette.Void,
    0.35f to NebulaPalette.Void.copy(alpha = 0.92f),
    0.72f to NebulaPalette.Void.copy(alpha = 0.35f),
    1.00f to Color.Transparent,
  )

/** Bottom-up scrim, so a billboard or backdrop dissolves into the page rather than ending at a line. */
val NebulaBottomScrim: Brush
  get() = Brush.verticalGradient(
    0.00f to Color.Transparent,
    0.55f to NebulaPalette.Void.copy(alpha = 0.65f),
    1.00f to NebulaPalette.Void,
  )

/** Full-bleed backdrop wash on Details: keeps the art present without letting it fight the copy. */
val NebulaBackdropScrim: Brush
  get() = Brush.verticalGradient(
    0.00f to NebulaPalette.Void.copy(alpha = 0.55f),
    0.45f to NebulaPalette.Void.copy(alpha = 0.88f),
    1.00f to NebulaPalette.Void,
  )

/**
 * The focus ring, as one definition.
 *
 * A 3dp bright-violet stroke sitting slightly outside the card. The inset is negative so the ring
 * hugs the artwork instead of overlapping it - on a poster, a ring drawn inside the bounds reads as
 * a frame printed on the image rather than as a highlight.
 */
@Composable
fun nebulaFocusBorder(shape: androidx.compose.ui.graphics.Shape = NebulaDimens.PosterShape) = Border(
  border = BorderStroke(3.dp, NebulaPalette.VioletBright),
  inset = (-2).dp,
  shape = shape,
)

/** Card focus styling: the ring above plus a violet bloom underneath. */
@Composable
fun nebulaCardBorder(shape: androidx.compose.ui.graphics.Shape = NebulaDimens.PosterShape) =
  CardDefaults.border(focusedBorder = nebulaFocusBorder(shape))

/**
 * The glow that sells the "lit from behind" look.
 *
 * tv-material3 renders this as a coloured elevation shadow, so a focused card genuinely spills
 * violet onto the background instead of just gaining an outline. Cheap - it is the platform
 * shadow renderer - which matters on this hardware.
 */
fun nebulaCardGlow() = CardDefaults.glow(
  focusedGlow = Glow(elevationColor = NebulaPalette.Violet, elevation = 16.dp),
)

/** The same treatment for buttons, so a focused button and a focused card look related. */
@Composable
fun nebulaButtonBorder(shape: androidx.compose.ui.graphics.Shape = NebulaShapes.large) =
  ButtonDefaults.border(
    focusedBorder = Border(
      border = BorderStroke(2.dp, NebulaPalette.VioletBright),
      inset = (-2).dp,
      shape = shape,
    ),
  )

fun nebulaButtonGlow() = ButtonDefaults.glow(
  focusedGlow = Glow(elevationColor = NebulaPalette.Violet, elevation = 12.dp),
)
