package com.stremioshell.host.tv.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween

/**
 * Nebula's motion, as one set of numbers.
 *
 * The app shipped with essentially no motion: every overlay, every screen and every state change
 * cut in on a single frame. That is the difference a viewer reads as "an app" rather than "a
 * product" long before they could tell you why.
 *
 * The constraint this is designed inside is the hardware. This runs on an Amlogic A55-class box
 * whose median frame during a scroll is already ~23ms against a 16.7ms budget, so the rule here is
 * not "animate tastefully", it is **animate almost nothing, and only the cheap kind**:
 *
 * - **Alpha on a single node is free.** It is a compositing parameter; it neither recomposes the
 *   node's children nor re-measures anything. Every fade below is one of these.
 * - **Layout animation is not free.** Anything that changes a size, a padding or an arrangement
 *   re-measures the subtree every frame it runs. None of these tokens are for that, and the two
 *   places tempted by it (the synopsis expander, the season switch) deliberately stay instant.
 * - **Nothing animates per-item inside a scrolling row.** A rail holds a dozen cards; a running
 *   animator on each is a dozen recompositions per frame on the app's hottest path. Card focus
 *   motion stays tv-material's own, which the framework drives on the render thread.
 *
 * Durations are longer than a phone's. A TV is watched from three metres by someone holding a
 * D-pad, so motion has to be legible at a glance rather than merely quick, and the input rate is
 * one press at a time rather than a continuous drag.
 */
object NebulaMotion {
  /** Something arriving. Decelerates, so it settles rather than stops. */
  const val EnterMs = 220

  /** Something leaving. Accelerates away - a slow exit reads as lag. */
  const val ExitMs = 140

  /** A value changing in place: a colour, a selection, a chip's fill. */
  const val StandardMs = 180

  /**
   * The player OSD, which sits over moving video.
   *
   * Faster than [EnterMs] in both directions: the panel is summoned by a keypress and the viewer is
   * already looking at where it will be, so anything slower feels like the remote is lagging.
   */
  const val OsdEnterMs = 160
  const val OsdExitMs = 110

  /**
   * A full-bleed backdrop crossfading in behind a page.
   *
   * Deliberately the slowest thing in the app. It is one alpha on one node, it happens once per
   * screen, and a large image that cuts in at full opacity is the single most jarring frame in the
   * browsing flow.
   */
  const val BackdropMs = 420

  /**
   * How long a load may take before the UI admits it is loading.
   *
   * Details, episodes and search results are frequently served from cache in well under this, and a
   * spinner that appears and vanishes inside three frames reads as a flicker - the screen looks
   * broken rather than fast. Below this the screen simply stays as it was.
   */
  const val BusyDelayMs = 400

  /** Arriving content: decelerate. */
  val EnterEasing: Easing = LinearOutSlowInEasing

  /** Departing content: accelerate. */
  val ExitEasing: Easing = FastOutLinearInEasing

  /**
   * The house curve for anything that is neither arriving nor leaving but moving.
   *
   * A hard-out ease: quick to commit, long to settle. On a TV this is what makes a selection feel
   * like it was already decided before the animation started, which is the feel a remote wants -
   * the viewer has pressed a button, not dragged something.
   */
  val EmphasisEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

  fun <T> enter() = tween<T>(durationMillis = EnterMs, easing = EnterEasing)

  fun <T> exit() = tween<T>(durationMillis = ExitMs, easing = ExitEasing)

  fun <T> standard() = tween<T>(durationMillis = StandardMs, easing = EmphasisEasing)

  fun <T> osdEnter() = tween<T>(durationMillis = OsdEnterMs, easing = EnterEasing)

  fun <T> osdExit() = tween<T>(durationMillis = OsdExitMs, easing = ExitEasing)

  fun <T> backdrop() = tween<T>(durationMillis = BackdropMs, easing = EnterEasing)
}
