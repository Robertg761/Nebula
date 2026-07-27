package com.stremioshell.host.tv.player

/**
 * What the display told us about Dolby Vision.
 *
 * [Unknown] is its own answer rather than folded into [NoDolbyVision]: on some
 * boxes the HDR capability query returns nothing at all, and a player that read
 * that as "this screen cannot do DV" would put a warning in front of every DV
 * stream on hardware that handles them perfectly.
 */
enum class DisplayHdrSupport {
  DolbyVision,
  NoDolbyVision,
  Unknown,
}

/**
 * Whether to tell the viewer that the picture is about to look wrong.
 *
 * A Dolby Vision profile 5 stream carries no HDR10 base layer: the picture is
 * IPTPQc2, and a pipeline that cannot apply the DV metadata renders it with the
 * green-and-purple cast this notice exists to explain. The stream picker already
 * badges DV from the release name, but the badge is a fact about the file and this
 * is a fact about the room, which is only knowable once playback has the display
 * in front of it.
 *
 * Deliberately a notice and never a dialog or a refusal: the picture is watchable,
 * some viewers do not mind it, and the ones who do want to know to pick a
 * different stream rather than to be stopped from watching this one.
 */
object DolbyVisionNotice {
  const val MESSAGE = "Dolby Vision stream - colors may look wrong on this screen"

  /**
   * [alreadyWarned] is per file, so a track list re-read — which happens on every
   * track pick and every subtitle added — cannot say the same thing again, and the
   * next episode of a binge gets its own one chance to.
   */
  fun shouldWarn(
    isDolbyVision: Boolean,
    display: DisplayHdrSupport,
    alreadyWarned: Boolean,
  ): Boolean = isDolbyVision && !alreadyWarned && display == DisplayHdrSupport.NoDolbyVision
}
