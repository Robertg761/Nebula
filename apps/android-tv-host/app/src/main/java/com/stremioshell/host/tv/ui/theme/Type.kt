package com.stremioshell.host.tv.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography
import com.stremioshell.host.R

/**
 * Outfit, bundled as four static weights.
 *
 * A geometric sans rather than the platform Roboto: it is the single cheapest thing that stops the
 * app reading as "a stock Android app", and its wide apertures survive the aggressive sharpening
 * most TVs apply. Static instances rather than the variable font because the variable axis needs
 * `FontVariation` plumbing for a saving of ~60 KB.
 */
val NebulaFont = FontFamily(
  Font(R.font.nebula_regular, FontWeight.Normal),
  Font(R.font.nebula_medium, FontWeight.Medium),
  Font(R.font.nebula_semibold, FontWeight.SemiBold),
  Font(R.font.nebula_bold, FontWeight.Bold),
)

/**
 * Trims the extra leading Compose adds above the first line and below the last, so a heading's box
 * is the size it looks. Without it every stacked text block on a TV carries invisible slack that
 * makes tuned spacing land wrong.
 *
 * This passed `Trim.None` for its whole life, i.e. it kept exactly the slack the paragraph above
 * warns about. Outfit's metrics (upem 1000, hhea ascent 1000, descent -260) give a natural line of
 * 1.26em, so with Trim.None every block carried half the difference above and below: bodyLarge
 * +2.15sp each side, bodySmall +1.2sp. MediaCard's 12dp caption gap therefore rendered as ~13.2dp
 * and its 3dp subtitle gap as ~4.6dp - a 50% error on the tightest measurement in the app. With
 * Trim.Both every dp in every Column means what it says.
 */
private val Trim = LineHeightStyle(
  alignment = LineHeightStyle.Alignment.Center,
  trim = LineHeightStyle.Trim.Both,
)

private fun nebula(
  size: Int,
  lineHeight: Int,
  weight: FontWeight,
  tracking: Double = 0.0,
) = TextStyle(
  fontFamily = NebulaFont,
  fontSize = size.sp,
  lineHeight = lineHeight.sp,
  fontWeight = weight,
  letterSpacing = tracking.sp,
  lineHeightStyle = Trim,
)

/**
 * Scaled for a 10-foot read, which is why every step sits above the Material phone default: body
 * copy is 18sp rather than 16, and the smallest label in the app is 12sp. Nothing below that ships,
 * because at three metres it is decoration rather than text.
 *
 * Headings are SemiBold rather than Bold - Outfit's Bold is heavy enough that a rail of them looks
 * shouty next to poster art - and display sizes get slightly negative tracking, which is what keeps
 * a 40sp title from looking loose.
 */
val NebulaTypography = Typography(
  // The three display steps had leading tighter than Outfit's own 1.26em line, so a two-line title
  // could touch: line 1's descender box overlapped line 2's ascender box by 0.06em at displaySmall.
  // Both the hero and the Details title are two-line-capable, so these are set to clear 1.25em.
  displayLarge = nebula(57, 72, FontWeight.Bold, -0.5),
  displayMedium = nebula(46, 58, FontWeight.Bold, -0.4),
  displaySmall = nebula(40, 50, FontWeight.SemiBold, -0.3),
  headlineLarge = nebula(34, 42, FontWeight.SemiBold, -0.2),
  headlineMedium = nebula(30, 38, FontWeight.SemiBold),
  headlineSmall = nebula(25, 32, FontWeight.SemiBold),
  titleLarge = nebula(24, 30, FontWeight.SemiBold),
  titleMedium = nebula(19, 26, FontWeight.Medium),
  titleSmall = nebula(17, 23, FontWeight.Medium),
  bodyLarge = nebula(18, 27, FontWeight.Normal),
  bodyMedium = nebula(16, 24, FontWeight.Normal),
  bodySmall = nebula(14, 20, FontWeight.Normal),
  // Labels are the badges, pills and hint lines. Positive tracking, because they are short strings
  // read at a glance rather than sentences.
  labelLarge = nebula(15, 20, FontWeight.SemiBold, 0.4),
  labelMedium = nebula(13, 18, FontWeight.Medium, 0.5),
  labelSmall = nebula(12, 16, FontWeight.Medium, 0.6),
)

/**
 * The wordmark's tracking, as a ratio of the size it is set at.
 *
 * NEBULA appears twice - in the nav rail at 24sp and on the first-run screen at 46sp - and each
 * site used to hard-code an absolute `.sp` tracking (5 and 14), which are 0.208em and 0.304em. So
 * the two were different logotypes, under a comment at one of them claiming it was set the way the
 * other was. Tracking is a ratio; expressing it as one makes the claim true.
 */
fun TextStyle.wordmark(): TextStyle = copy(letterSpacing = fontSize * 0.30f)
