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
 * The one spacing ramp.
 *
 * Before this existed the UI tree carried 21 distinct dp literals, with 10 and 12 doing the same
 * job in adjacent files, 18 and 20 both meaning "a section break", and 34 and 36 both meaning
 * "dialog padding". Nothing could be nudged consistently because there was nothing to nudge.
 *
 * A 4/8/12/16/24/32/48 ramp. Everything new uses these; the structural sizes that are not really
 * spacing (a poster's width, the hero's height) stay in [NebulaDimens].
 */
object NebulaSpace {
  /** Between a label and the thing it labels. */
  val xxs = 4.dp

  /** Inside a chip; between an icon and its word. */
  val xs = 8.dp

  /** Between controls in a row. */
  val sm = 12.dp

  /** Between stacked controls; a card's inner padding on the tight axis. */
  val md = 16.dp

  /** Between groups inside one block. */
  val lg = 24.dp

  /** Between blocks; a dialog's padding. */
  val xl = 32.dp

  /** Between a screen's edge and its content. */
  val xxl = 48.dp
}

/** Icon sizes, so the same glyph is not 18dp on one screen and 22dp on the next. */
object NebulaIcon {
  /** Inline with text: a tick beside a title, a leading icon in a field. */
  val sm = 20.dp

  /** A button's or a row's own icon. */
  val md = 24.dp

  /** The single glyph on an empty or failed screen. */
  val lg = 48.dp
}

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
  val ScreenEdge = NebulaSpace.xxl

  /**
   * The same protection top and bottom.
   *
   * Smaller than [ScreenEdge] on purpose - a 10-foot layout wants more air at the sides than above -
   * but still more than twice the 3% overscan bite of a 540dp-tall viewport. It exists because the
   * five screens previously opened at 28, 32, 36, 56 and 56dp, so the first line of content jumped
   * as you navigated between them.
   */
  val ScreenEdgeVertical = 36.dp

  /**
   * Vertical gap between major sections - rails on Home, groups in Settings.
   *
   * Tightened from 34dp with [RailHeadingGap]. Measured on a 540dp viewport, Home was showing the
   * billboard and about one and a half rails: the page was airier than a browse UI can afford,
   * because the viewer's question is "what else is there" and the answer was mostly background.
   * The gap is still a clear section break - it is more than twice [CardGap] - but a rail now costs
   * 12dp less, which is most of another row of posters over the length of the page.
   */
  val RailGap = 28.dp

  /**
   * Gap between a rail's heading and its cards.
   *
   * Carries the 8dp that every rail used to spend on vertical `contentPadding`, on the belief that
   * a LazyRow clipped its children and the focused card's ring needed the slack. It does not:
   * foundation's `clipScrollableContainer` clips the *scroll* axis only and deliberately inflates
   * the cross axis by 30dp of elevation headroom, which was confirmed on a device - a focused
   * poster's ring and its 16dp glow both render complete, well outside the row's bounds. Removing
   * that padding gives every rail 16dp of its height back, about a quarter of a viewport across
   * eight rails, and the gap the viewer actually sees is unchanged.
   *
   * Since reduced again alongside a smaller heading: 22dp was tuned under a 24sp title, and a 19sp
   * one needs less air beneath it to read as attached to the row it names rather than floating
   * between two.
   */
  val RailHeadingGap = 16.dp

  /**
   * Between a poster and its caption.
   *
   * Has to clear the focused card's own overhang: 216dp at [FocusScale] grows 7.56dp below its
   * layout box, and the focus ring is a 3dp stroke at inset -2dp, i.e. 3.5dp further out, scaled
   * to 3.75dp - 11.3dp in total. At the old 12dp the clearance was 0.7dp and only the caption's
   * half-leading kept ink off ink, which [NebulaTypography]'s leading trim has now removed.
   */
  val CardCaptionGap = NebulaSpace.md

  /** Horizontal gap between cards in a rail. */
  val CardGap = NebulaSpace.md

  /** Gap between stacked controls in a dialog or form. */
  val ControlGap = 14.dp

  /**
   * How far an accent tick and its gap push the words beside them.
   *
   * Headings hang the tick in the margin by this much so the *text* lands on [ScreenEdge], flush
   * with the posters underneath. Previously the tick started at the content line and the words sat
   * 16dp inside it, so every rail title was indented from its own row.
   */
  val TickInset = NebulaSpace.md

  /** Standard poster: a true 2:3, so TMDB art fills the card instead of being cropped. */
  val PosterWidth = 144.dp
  val PosterHeight = 216.dp

  /** 16:9 still, for the episode rows on Details. */
  val StillWidth = 268.dp
  val StillHeight = 151.dp

  /** Cast portraits: narrower than a poster, so a cast row reads as a different kind of row. */
  val PortraitWidth = 124.dp
  val PortraitHeight = 186.dp

  /** Home's billboard. ~59% of a 540dp viewport, leaving the first rail's top edge visible. */
  val HeroHeight = 320.dp

  /**
   * Collapsed width of the nav rail.
   *
   * Consumed as the derived inset in TvApp's RailInset and as the width content reserves for the
   * rail, rather than applied directly as a width on the drawer itself.
   */
  val NavRailWidth = 62.dp

  /** Both dialogs in the app, one width. They were 540 and 560 for no reason. */
  val DialogWidth = 560.dp
  val DialogPadding = NebulaSpace.xl

  /** Artwork corner. Tighter than the shape scale: posters want to look like posters. */
  val PosterShape = RoundedCornerShape(10.dp)

  /**
   * How far a focused card lifts. Restrained on purpose - a focused poster must not cover its
   * own title, which is a bug this app has already shipped once.
   */
  const val FocusScale = 1.07f

  /** Wider elements (billboards, list rows) scale less, or they would grow past the overscan. */
  const val FocusScaleWide = 1.02f

  /** Buttons and chips, which are smaller than a card and would look frantic at [FocusScale]. */
  const val FocusScaleButton = 1.05f

  /** The bloom under a focused card. */
  val FocusGlow = NebulaSpace.md

  /** The bloom under a focused button, chip or rail item - two steps, not the three we had. */
  val FocusGlowCompact = NebulaSpace.sm
}

/*
 * The gradients below are plain vals, not `get()` accessors.
 *
 * A getter rebuilds its Brush - and the colour-stop list inside it - on every read, and these are
 * read from the draw path of things there are many of: the scrim under every Continue Watching
 * card, the accent tick on every episode row. One instance per gradient, allocated once, is
 * exactly what a token is for.
 */

/**
 * The accent gradient, violet into cyan.
 *
 * Reserved for surfaces that mean "act now" - the primary Play button, the focused nav item, the
 * playback progress fill. Used anywhere else it stops meaning anything.
 */
val NebulaAccentBrush: Brush =
  Brush.horizontalGradient(listOf(NebulaPalette.Violet, NebulaPalette.Cyan))

/**
 * The same ramp down its short axis, for the accent ticks beside headings.
 *
 * Those are 4dp wide and 18-30dp tall, so a *horizontal* gradient across them was four pixels of
 * blend and rendered as one flat mid-blue that is not a colour in the palette - which is how an app
 * documented as "violet-to-cyan" shipped with its cyan effectively invisible. Run along the long
 * axis it is actually a gradient.
 */
val NebulaAccentBrushVertical: Brush =
  Brush.verticalGradient(listOf(NebulaPalette.Violet, NebulaPalette.Cyan))

/**
 * Left-to-right scrim for a billboard: opaque over the copy, clear over the artwork.
 *
 * TMDB backdrops are full-frame stills with no safe area for text, so the readable side has to be
 * manufactured. Three stops rather than two - a linear fade leaves a visible diagonal edge across
 * a flat sky, and the extra stop hides it.
 */
val NebulaHeroScrim: Brush = Brush.horizontalGradient(
  0.00f to NebulaPalette.Void,
  0.35f to NebulaPalette.Void.copy(alpha = 0.92f),
  0.72f to NebulaPalette.Void.copy(alpha = 0.35f),
  1.00f to Color.Transparent,
)

/** Mirrored form of [NebulaHeroScrim] for right-to-left layouts, where copy sits on the right. */
val NebulaHeroScrimRtl: Brush = Brush.horizontalGradient(
  0.00f to Color.Transparent,
  0.28f to NebulaPalette.Void.copy(alpha = 0.35f),
  0.65f to NebulaPalette.Void.copy(alpha = 0.92f),
  1.00f to NebulaPalette.Void,
)

/** Bottom-up scrim, so a billboard or backdrop dissolves into the page rather than ending at a line. */
val NebulaBottomScrim: Brush = Brush.verticalGradient(
  0.00f to Color.Transparent,
  0.55f to NebulaPalette.Void.copy(alpha = 0.65f),
  1.00f to NebulaPalette.Void,
)

/**
 * The scrim under the player OSD.
 *
 * Distinct from [NebulaBottomScrim], which resolves to the page colour because the thing under it
 * is the page. Here the thing under it is live video, and taking it to fully opaque black means the
 * bottom fifth of the picture is deleted whenever the panel is up. Stops at 0.88.
 */
val NebulaOsdScrim: Brush = Brush.verticalGradient(
  0.00f to Color.Transparent,
  0.45f to NebulaPalette.Void.copy(alpha = 0.55f),
  1.00f to NebulaPalette.Void.copy(alpha = 0.88f),
)

/** Full-bleed backdrop wash on Details: keeps the art present without letting it fight the copy. */
val NebulaBackdropScrim: Brush = Brush.verticalGradient(
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
fun nebulaFocusBorder(shape: androidx.compose.ui.graphics.Shape = NebulaDimens.PosterShape): Border =
  if (shape === NebulaDimens.PosterShape) PosterFocusBorder else focusBorder(shape)

/** The ring at the default corner, which is the one every poster and card in the app asks for. */
private val PosterFocusBorder = focusBorder(NebulaDimens.PosterShape)

private fun focusBorder(shape: androidx.compose.ui.graphics.Shape) = Border(
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
 *
 * Built once and handed back, rather than rebuilt per call: every card in every rail asks for this
 * on every composition of the row, and the value it gets is the same one every time.
 */
fun nebulaCardGlow() = CardGlow

private val CardGlow = CardDefaults.glow(
  focusedGlow = Glow(
    elevationColor = NebulaPalette.Violet,
    elevation = NebulaDimens.FocusGlow,
  ),
)

/**
 * The same treatment for buttons, so a focused button and a focused card look related.
 *
 * Same 3dp stroke as [nebulaFocusBorder]: buttons used to get 2dp, which at three metres is about
 * one screen pixel of difference and bought nothing for the inconsistency of a focused chip and a
 * focused button on one screen wearing different rings.
 */
@Composable
fun nebulaButtonBorder(shape: androidx.compose.ui.graphics.Shape = NebulaShapes.large) =
  ButtonDefaults.border(
    focusedBorder = Border(
      border = BorderStroke(3.dp, NebulaPalette.VioletBright),
      inset = (-2).dp,
      shape = shape,
    ),
  )

/** The button's bloom, hoisted for the same reason [nebulaCardGlow] is. */
fun nebulaButtonGlow() = ButtonGlow

private val ButtonGlow = ButtonDefaults.glow(
  focusedGlow = Glow(
    elevationColor = NebulaPalette.Violet,
    elevation = NebulaDimens.FocusGlowCompact,
  ),
)
