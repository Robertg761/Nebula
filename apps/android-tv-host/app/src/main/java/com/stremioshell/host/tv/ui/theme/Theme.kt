package com.stremioshell.host.tv.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Shapes
import androidx.tv.material3.darkColorScheme

/**
 * Corner radii, from "a chip" to "a dialog".
 *
 * Softer than Material's defaults across the board: at three metres a 4dp radius is
 * indistinguishable from a square corner, so the shape language only reads at all if the steps are
 * exaggerated. Posters deliberately stay off this scale - they use [NebulaDimens.PosterShape], because
 * artwork wants a tighter corner than a button does.
 */
val NebulaShapes = Shapes(
  extraSmall = RoundedCornerShape(8.dp),
  small = RoundedCornerShape(12.dp),
  medium = RoundedCornerShape(16.dp),
  large = RoundedCornerShape(22.dp),
  extraLarge = RoundedCornerShape(28.dp),
)

private val NebulaColors = darkColorScheme(
  primary = NebulaPalette.Violet,
  onPrimary = Color.White,
  primaryContainer = NebulaPalette.SurfaceVariant,
  onPrimaryContainer = NebulaPalette.VioletBright,
  secondary = NebulaPalette.Cyan,
  onSecondary = Color(0xFF06202A),
  secondaryContainer = NebulaPalette.SurfaceVariant,
  onSecondaryContainer = NebulaPalette.Cyan,
  tertiary = NebulaPalette.Success,
  onTertiary = Color(0xFF03291B),
  background = NebulaPalette.Void,
  onBackground = NebulaPalette.TextHigh,
  surface = NebulaPalette.Surface,
  onSurface = NebulaPalette.TextHigh,
  surfaceVariant = NebulaPalette.SurfaceVariant,
  onSurfaceVariant = NebulaPalette.TextMuted,
  surfaceTint = NebulaPalette.Violet,
  inverseSurface = NebulaPalette.TextHigh,
  inverseOnSurface = NebulaPalette.Void,
  error = NebulaPalette.Danger,
  onError = Color(0xFF2A0509),
  errorContainer = Color(0xFF3A1219),
  onErrorContainer = NebulaPalette.Danger,
  // tv-material3 draws focus outlines from these, so they are the accent rather than a neutral:
  // every focus ring in the app inherits from here.
  border = NebulaPalette.VioletBright,
  borderVariant = NebulaPalette.Outline,
  scrim = NebulaPalette.Scrim,
)

/**
 * Wraps content in Nebula's colors, type and shapes.
 *
 * Every activity that renders Compose goes through this, including the player - the OSD reads its
 * accent from the same scheme as the browse UI, which is what stops the app feeling like two apps
 * bolted together.
 */
@Composable
fun NebulaTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = NebulaColors,
    typography = NebulaTypography,
    shapes = NebulaShapes,
    content = content,
  )
}
