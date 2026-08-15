package com.stremioshell.host.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.stremioshell.host.tv.ui.theme.NebulaAccentBrushVertical
import com.stremioshell.host.tv.ui.theme.NebulaDimens
import com.stremioshell.host.tv.ui.theme.NebulaIcon
import com.stremioshell.host.tv.ui.theme.NebulaPalette
import com.stremioshell.host.tv.ui.theme.NebulaShapes
import com.stremioshell.host.tv.ui.theme.NebulaSpace
import com.stremioshell.host.tv.ui.theme.nebulaButtonBorder
import com.stremioshell.host.tv.ui.theme.nebulaButtonGlow

/**
 * Nebula's controls: the buttons, headings, badges and bars every screen shares.
 *
 * Split out from [Components.kt] on purpose - that file is the focus and navigation machinery,
 * which is subtle and rarely changes, while this one is pure appearance. Keeping them apart means
 * a restyle never has to touch the code that makes the D-pad work.
 */

/**
 * How much weight a button carries.
 *
 * There is exactly one [Primary] per screen region - the thing the viewer came to press. Everything
 * else is [Secondary] (a real alternative), [Ghost] (an escape hatch: Cancel, Later, Back) or
 * [Danger] (it removes something). Enforcing that by type rather than by convention is what stops a
 * screen growing four equally loud violet buttons, which is how the old Settings screen ended up.
 */
enum class NebulaButtonStyle { Primary, Secondary, Ghost, Danger }

internal object NebulaButtonFocusPolicy {
  fun canFocus(enabled: Boolean, focusableWhenDisabled: Boolean): Boolean =
    enabled || focusableWhenDisabled
}

/**
 * The app's button.
 *
 * Wraps tv-material3's so that focus styling - ring, glow, scale, contrast flip - is defined once.
 * The focused state deliberately *brightens* rather than only outlining: on a TV the viewer is
 * across the room and an outline alone is easy to lose against poster art.
 */
@Composable
fun NebulaButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  style: NebulaButtonStyle = NebulaButtonStyle.Secondary,
  icon: ImageVector? = null,
  enabled: Boolean = true,
  focusableWhenDisabled: Boolean = true,
) {
  val shape = NebulaShapes.large
  val colors = when (style) {
    // Dark ink at rest as well as focused. White on Violet measures 3.69:1, which fails AA at the
    // 15sp label a button carries - so the app's most important control was at its *worst*
    // contrast in the state it spends most of its life in. Holding the ink constant also makes
    // focus read as one object brightening rather than one inverting.
    NebulaButtonStyle.Primary -> ButtonDefaults.colors(
      containerColor = NebulaPalette.Violet,
      contentColor = NebulaPalette.OnAccent,
      focusedContainerColor = NebulaPalette.VioletBright,
      focusedContentColor = NebulaPalette.OnAccent,
      pressedContainerColor = NebulaPalette.Violet,
      pressedContentColor = NebulaPalette.OnAccent,
      disabledContainerColor = NebulaPalette.SurfaceVariant,
      disabledContentColor = NebulaPalette.TextDisabled,
    )
    NebulaButtonStyle.Secondary -> ButtonDefaults.colors(
      containerColor = NebulaPalette.SurfaceVariant,
      contentColor = NebulaPalette.TextHigh,
      focusedContainerColor = NebulaPalette.VioletBright,
      focusedContentColor = NebulaPalette.OnAccent,
      pressedContainerColor = NebulaPalette.Violet,
      pressedContentColor = NebulaPalette.OnAccent,
      disabledContainerColor = NebulaPalette.Surface,
      disabledContentColor = NebulaPalette.TextDisabled,
    )
    // Reads as a label until focused, which is what keeps a dialog's "Cancel" from competing with
    // the action next to it while still being obviously reachable.
    NebulaButtonStyle.Ghost -> ButtonDefaults.colors(
      containerColor = Color.Transparent,
      contentColor = NebulaPalette.TextMuted,
      focusedContainerColor = NebulaPalette.SurfaceVariant,
      focusedContentColor = NebulaPalette.TextHigh,
      pressedContainerColor = NebulaPalette.SurfaceRaised,
      pressedContentColor = NebulaPalette.TextHigh,
      disabledContainerColor = Color.Transparent,
      disabledContentColor = NebulaPalette.TextDisabled,
    )
    // Removal. Not a second Primary - it is quiet until focused, then commits to the failure
    // colour, so a held-OK on a poster cannot delete something behind a button that looks like
    // Play.
    NebulaButtonStyle.Danger -> ButtonDefaults.colors(
      containerColor = NebulaPalette.Surface,
      contentColor = NebulaPalette.Danger,
      focusedContainerColor = NebulaPalette.Danger,
      focusedContentColor = Color(0xFF2A0509),
      pressedContainerColor = NebulaPalette.Danger,
      pressedContentColor = Color(0xFF2A0509),
      disabledContainerColor = NebulaPalette.Surface,
      disabledContentColor = NebulaPalette.TextDisabled,
    )
  }
  Button(
    onClick = onClick,
    enabled = enabled,
    colors = colors,
    shape = ButtonDefaults.shape(shape = shape),
    border = nebulaButtonBorder(shape),
    glow = nebulaButtonGlow(),
    scale = ButtonDefaults.scale(focusedScale = NebulaDimens.FocusScaleButton),
    // Asymmetric when there is an icon: a glyph carries less optical weight at its leading edge
    // than a capital does, so an equal pad on both sides leaves the content group sitting visibly
    // right of centre. tv-material3's own metrics make the same 4dp reduction.
    contentPadding = if (icon != null) {
      PaddingValues(start = 20.dp, top = NebulaSpace.sm, end = NebulaSpace.lg, bottom = NebulaSpace.sm)
    } else {
      PaddingValues(horizontal = NebulaSpace.lg, vertical = NebulaSpace.sm)
    },
    // Most disabled controls retain focus so disabling the pressed node cannot strand the D-pad.
    // Callers with permanently unavailable edge actions can explicitly remove those inert stops.
    modifier = modifier.focusProperties {
      canFocus = NebulaButtonFocusPolicy.canFocus(enabled, focusableWhenDisabled)
    },
  ) {
    if (icon != null) {
      // Decorative: the label beside it already names the action, and a described icon would make
      // every button announce itself twice.
      Icon(icon, contentDescription = null, modifier = Modifier.size(NebulaIcon.sm))
      Spacer(Modifier.width(NebulaSpace.xs))
    }
    Text(
      text,
      style = MaterialTheme.typography.labelLarge,
      maxLines = 1,
      // Without this an over-long label - an addon name on Settings' Remove button, say - is
      // sliced through a letter rather than ellipsised.
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * The accent tick that marks a heading.
 *
 * Hung in the margin rather than sitting on the content line: with the tick and its gap inside the
 * padding, every rail title started 16dp right of the posters it labelled, so Home had two left
 * edges running the full height of the page.
 *
 * The gradient runs *vertically* here. [NebulaAccentBrushVertical] exists because the app-wide
 * horizontal brush compressed a violet-to-cyan ramp into four physical dp on these ticks and
 * rendered as one flat mid-blue that is not a colour in the palette - which is how an app
 * documented as violet-to-cyan shipped with its cyan effectively invisible.
 */
@Composable
private fun AccentTick(height: Dp) {
  Box(
    modifier = Modifier
      .size(width = 4.dp, height = height)
      .background(NebulaAccentBrushVertical, RoundedCornerShape(2.dp))
      .clearAndSetSemantics {},
  )
}

/**
 * A rail's heading, with the accent tick that marks it as one.
 *
 * The tick is what makes a vertical stack of rails scannable: at a glance the viewer sees where
 * each section starts without having to read the words.
 */
@Composable
fun RailHeading(title: String, modifier: Modifier = Modifier) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier.padding(
      start = NebulaDimens.ScreenEdge - NebulaDimens.TickInset,
      bottom = NebulaDimens.RailHeadingGap,
    ),
  ) {
    // Cap height rather than the em box, so the tick brackets the letters instead of overshooting
    // them top and bottom.
    AccentTick(height = 15.dp)
    Text(
      text = title,
      // titleMedium, not titleLarge. At 24sp SemiBold against 14sp captions a row heading was
      // nearly twice the size of the titles it labelled, and eight of them down a page read as
      // chrome shouting over the artwork - which is the thing the greyscale rule exists to
      // prevent. Every premium browse UI sets its row headings small and lets the posters carry
      // the page. SemiBold is kept: the weight is what makes it a heading, not the size.
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
      color = NebulaPalette.TextHigh,
      modifier = Modifier.padding(start = NebulaSpace.sm).semantics { heading() },
    )
  }
}

/**
 * A screen's title block: what this screen is, and optionally what it is showing.
 *
 * Used by every pushed screen except Details, which leads with artwork instead.
 */
@Composable
fun ScreenHeader(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  subtitleSpokenLabel: String? = null,
) {
  Column(modifier = modifier.padding(start = NebulaDimens.ScreenEdge - NebulaDimens.TickInset)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      AccentTick(height = 22.dp)
      Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        maxLines = 1,
        // A long film title used to deform the whole Streams header rather than ellipsise.
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = NebulaSpace.sm).semantics { heading() },
      )
    }
    if (subtitle != null) {
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = NebulaPalette.TextMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        // Lands on the same line as the title above it, now that the tick hangs in the margin -
        // this used to be a hand-tuned 19dp chasing a number nothing else in the app used.
        modifier = Modifier
          .padding(top = 6.dp, start = NebulaDimens.TickInset)
          // Some compact visual codes are poor speech. Clear Text semantics so TalkBack announces
          // only the supplied phrase instead of reading both the phrase and the raw glyphs.
          .clearAndSetSemantics {
            contentDescription = subtitleSpokenLabel ?: subtitle
          },
      )
    }
  }
}

/** What a [NebulaBadge] is saying, which decides its colour. */
enum class BadgeTone {
  /** Plain fact: a resolution, a codec, a year. */
  Neutral,

  /** Something the viewer chose or the app picked for them: the source addon, "last used". */
  Accent,

  /** Cached, verified, watched. */
  Good,

  /** A caveat that is not a failure - "no subtitles", "may not play", a paused film. */
  Warn,

  /** An actual failure, inside a screen that is otherwise working. */
  Bad,
}

/**
 * A small pill of metadata.
 *
 * The stream list lives or dies on these: a viewer picking between fifteen releases is reading
 * badges, not titles, so they get real colour separation rather than being fifteen identical grey
 * chips as before.
 *
 * The fills are opaque. As translucent tints they took on whatever was behind them, so the same
 * chip was one colour on the stream list (over the page) and another on the Home billboard (over
 * a bright backdrop through a partial scrim), where an Accent chip over a pale sky nearly
 * vanished. A metadata chip that changes colour depending on the poster is not a chip.
 */
@Composable
fun NebulaBadge(
  text: String,
  modifier: Modifier = Modifier,
  tone: BadgeTone = BadgeTone.Neutral,
) {
  val (bg, fg) = when (tone) {
    BadgeTone.Neutral -> NebulaPalette.SurfaceVariant to NebulaPalette.TextMuted
    BadgeTone.Accent -> NebulaPalette.AccentPlate to NebulaPalette.VioletBright
    BadgeTone.Good -> BadgePlateGood to NebulaPalette.Success
    BadgeTone.Warn -> BadgePlateWarn to NebulaPalette.Caution
    BadgeTone.Bad -> BadgePlateBad to NebulaPalette.Danger
  }
  Text(
    text = text,
    style = MaterialTheme.typography.labelSmall,
    color = fg,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier
      // A server-supplied source name is unbounded; without a cap one can push every other badge
      // off the end of the row.
      .widthIn(max = 200.dp)
      .background(bg, NebulaShapes.extraSmall)
      .padding(horizontal = NebulaSpace.xs, vertical = NebulaSpace.xxs),
  )
}

// Pre-composited over Void at the alphas these were designed at, for the same reason as
// NebulaPalette.AccentPlate: one colour wherever the badge lands, and no per-frame alpha blend.
private val BadgePlateGood = Color(0xFF122D28)
private val BadgePlateWarn = Color(0xFF332820)
private val BadgePlateBad = Color(0xFF331822)

/**
 * Watched-progress bar.
 *
 * Shared between the Continue Watching cards and the episode rows so that "how far in am I" looks
 * identical in both places.
 *
 * Solid violet rather than the accent gradient: a `horizontalGradient` resolves against the box it
 * paints, which for the fill is the *filled portion* - so the whole violet-to-cyan ramp was
 * squeezed into whatever fraction was watched, the tip was cyan at every value, and a 5% bar and a
 * 95% bar carried the same colours. The ramp is kept for the one bar that spans a known full
 * width, the player's scrubber.
 *
 * @param progress 0f..1f; values outside are clamped rather than allowed to overflow the track.
 */
@Composable
fun NebulaProgressBar(
  progress: Float,
  modifier: Modifier = Modifier,
  height: Dp = 5.dp,
) {
  val clamped = progress.coerceIn(0f, 1f)
  Box(
    modifier = modifier
      .height(height)
      // Not Outline: at 1.53:1 on the page the unwatched remainder could not be seen, so the bar
      // said how much had been watched but not how much was left.
      .background(NebulaPalette.TrackInactive, RoundedCornerShape(50)),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth(clamped)
        .height(height)
        .background(NebulaPalette.Violet, RoundedCornerShape(50)),
    )
  }
}

/**
 * Nothing to show here, and what to do about it.
 *
 * Distinct from [FailureMessage]: an empty state is a correct outcome, so it stays quiet - muted
 * type, no error colour, no Retry that would only repeat a search that worked.
 *
 * The caller is responsible for centring this; it fills the width it is given and constrains its
 * own measure. (The `verticalArrangement = Center` this used to carry did nothing at all - a
 * Column with no height constraint has no space to distribute.)
 */
@Composable
fun EmptyState(
  title: String,
  modifier: Modifier = Modifier,
  hint: String? = null,
  icon: ImageVector? = null,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = NebulaDimens.ScreenEdge),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (icon != null) {
      Icon(
        icon,
        // Decorative: the title underneath says the same thing in words.
        contentDescription = null,
        // Was TextFaint, i.e. the palette's quietest tone, on the largest element of the screen.
        tint = NebulaPalette.TextMuted,
        // Padding outside size, or the glyph is drawn into a box the padding has already shrunk -
        // which is how a "48dp" icon rendered at 32dp.
        modifier = Modifier.padding(bottom = NebulaSpace.md).size(NebulaIcon.lg),
      )
    }
    Text(
      title,
      style = MaterialTheme.typography.titleMedium,
      color = NebulaPalette.TextHigh,
      // Both of these are the only content on the screen, so they need a measure. Without one a
      // long message runs edge to edge, past the overscan margin, and a wrapped line is
      // start-aligned inside a centred box - so the block visibly is not centred.
      textAlign = TextAlign.Center,
      modifier = Modifier.widthIn(max = 560.dp),
    )
    if (hint != null) {
      Text(
        text = hint,
        style = MaterialTheme.typography.bodyMedium,
        color = NebulaPalette.TextMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = NebulaSpace.xs).widthIn(max = 560.dp),
      )
    }
  }
}
