package com.stremioshell.host.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.stremioshell.host.tv.ui.theme.NebulaAccentBrush
import com.stremioshell.host.tv.ui.theme.NebulaDimens
import com.stremioshell.host.tv.ui.theme.NebulaPalette
import com.stremioshell.host.tv.ui.theme.NebulaShapes
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
 * else is [Secondary] (a real alternative) or [Ghost] (an escape hatch: Cancel, Later, Back).
 * Enforcing that by type rather than by convention is what stops a screen growing four equally
 * loud violet buttons, which is how the old Settings screen ended up.
 */
enum class NebulaButtonStyle { Primary, Secondary, Ghost }

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
) {
  val shape = NebulaShapes.large
  val colors = when (style) {
    NebulaButtonStyle.Primary -> ButtonDefaults.colors(
      containerColor = NebulaPalette.Violet,
      contentColor = Color.White,
      focusedContainerColor = NebulaPalette.VioletBright,
      focusedContentColor = Color(0xFF120A2E),
    )
    NebulaButtonStyle.Secondary -> ButtonDefaults.colors(
      containerColor = NebulaPalette.SurfaceVariant,
      contentColor = NebulaPalette.TextHigh,
      focusedContainerColor = NebulaPalette.VioletBright,
      focusedContentColor = Color(0xFF120A2E),
    )
    // Reads as a label until focused, which is what keeps a dialog's "Cancel" from competing with
    // the action next to it while still being obviously reachable.
    NebulaButtonStyle.Ghost -> ButtonDefaults.colors(
      containerColor = Color.Transparent,
      contentColor = NebulaPalette.TextMuted,
      focusedContainerColor = NebulaPalette.SurfaceVariant,
      focusedContentColor = NebulaPalette.TextHigh,
    )
  }
  Button(
    onClick = onClick,
    enabled = enabled,
    colors = colors,
    shape = ButtonDefaults.shape(shape = shape),
    border = nebulaButtonBorder(shape),
    glow = nebulaButtonGlow(),
    scale = ButtonDefaults.scale(focusedScale = 1.05f),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(
      horizontal = 24.dp,
      vertical = 12.dp,
    ),
    modifier = modifier,
  ) {
    if (icon != null) {
      // Decorative: the label beside it already names the action, and a described icon would make
      // every button announce itself twice.
      Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
      Box(Modifier.width(10.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
  }
}

/**
 * A rail's heading, with the accent tick that marks it as one.
 *
 * The tick is what makes a vertical stack of rails scannable: at a glance the viewer sees where
 * each section starts without having to read the words. Cleared from semantics because the heading
 * text beside it already carries the meaning.
 */
@Composable
fun RailHeading(title: String, modifier: Modifier = Modifier) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .padding(start = NebulaDimens.ScreenEdge, bottom = NebulaDimens.RailHeadingGap),
  ) {
    Box(
      modifier = Modifier
        .size(width = 4.dp, height = 22.dp)
        .background(NebulaAccentBrush, RoundedCornerShape(2.dp))
        .clearAndSetSemantics {},
    )
    Text(
      text = title,
      style = MaterialTheme.typography.titleLarge,
      color = NebulaPalette.TextHigh,
      modifier = Modifier.padding(start = 12.dp),
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
  subtitle: String? = null,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier
          .size(width = 5.dp, height = 30.dp)
          .background(NebulaAccentBrush, RoundedCornerShape(3.dp))
          .clearAndSetSemantics {},
      )
      Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(start = 14.dp),
      )
    }
    if (subtitle != null) {
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = NebulaPalette.TextMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp, start = 19.dp),
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

  /** A caveat that is not a failure - "no subtitles", "may not play". */
  Warn,
}

/**
 * A small pill of metadata.
 *
 * The stream list lives or dies on these: a viewer picking between fifteen releases is reading
 * badges, not titles, so they get real colour separation rather than being fifteen identical grey
 * chips as before.
 */
@Composable
fun NebulaBadge(
  text: String,
  tone: BadgeTone = BadgeTone.Neutral,
  modifier: Modifier = Modifier,
) {
  val (bg, fg) = when (tone) {
    BadgeTone.Neutral -> NebulaPalette.SurfaceVariant to NebulaPalette.TextMuted
    BadgeTone.Accent -> NebulaPalette.Violet.copy(alpha = 0.22f) to NebulaPalette.VioletBright
    BadgeTone.Good -> NebulaPalette.Success.copy(alpha = 0.18f) to NebulaPalette.Success
    BadgeTone.Warn -> NebulaPalette.Danger.copy(alpha = 0.18f) to NebulaPalette.Danger
  }
  Text(
    text = text,
    style = MaterialTheme.typography.labelSmall,
    color = fg,
    maxLines = 1,
    modifier = modifier
      .background(bg, RoundedCornerShape(6.dp))
      .padding(horizontal = 8.dp, vertical = 3.dp),
  )
}

/**
 * Watched-progress bar, in the accent gradient.
 *
 * Shared between the Continue Watching cards, the episode rows and the player OSD so that "how far
 * in am I" looks identical in all three places - the viewer should not have to re-learn it when
 * they cross from browsing into playback.
 *
 * @param progress 0f..1f; values outside are clamped rather than allowed to overflow the track.
 */
@Composable
fun NebulaProgressBar(
  progress: Float,
  modifier: Modifier = Modifier,
  height: androidx.compose.ui.unit.Dp = 5.dp,
) {
  val clamped = progress.coerceIn(0f, 1f)
  Box(
    modifier = modifier
      .height(height)
      .background(NebulaPalette.Outline, RoundedCornerShape(50)),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth(clamped)
        .height(height)
        .background(NebulaAccentBrush, RoundedCornerShape(50)),
    )
  }
}

/**
 * Nothing to show here, and what to do about it.
 *
 * Distinct from [FailureMessage]: an empty state is a correct outcome, so it stays quiet - muted
 * type, no error colour, no Retry that would only repeat a search that worked.
 */
@Composable
fun EmptyState(
  title: String,
  hint: String? = null,
  icon: ImageVector? = null,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    if (icon != null) {
      Icon(
        icon,
        // Decorative: the title underneath says the same thing in words.
        contentDescription = null,
        tint = NebulaPalette.TextFaint,
        modifier = Modifier.size(48.dp).padding(bottom = 16.dp),
      )
    }
    Text(title, style = MaterialTheme.typography.titleMedium, color = NebulaPalette.TextHigh)
    if (hint != null) {
      Text(
        text = hint,
        style = MaterialTheme.typography.bodyMedium,
        color = NebulaPalette.TextMuted,
        modifier = Modifier.padding(top = 8.dp),
      )
    }
  }
}
