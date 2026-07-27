package com.stremioshell.host.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DolbyVisionNoticeTest {
  @Test
  fun `a DV stream on a screen that cannot render it is worth saying once`() {
    assertTrue(
      DolbyVisionNotice.shouldWarn(
        isDolbyVision = true,
        display = DisplayHdrSupport.NoDolbyVision,
        alreadyWarned = false,
      ),
    )
  }

  @Test
  fun `a screen that does support DV is never warned about`() {
    assertFalse(
      DolbyVisionNotice.shouldWarn(
        isDolbyVision = true,
        display = DisplayHdrSupport.DolbyVision,
        alreadyWarned = false,
      ),
    )
  }

  @Test
  fun `an unanswered capability query is not treated as a missing capability`() {
    assertFalse(
      DolbyVisionNotice.shouldWarn(
        isDolbyVision = true,
        display = DisplayHdrSupport.Unknown,
        alreadyWarned = false,
      ),
    )
  }

  @Test
  fun `nothing is said twice for one file`() {
    assertFalse(
      DolbyVisionNotice.shouldWarn(
        isDolbyVision = true,
        display = DisplayHdrSupport.NoDolbyVision,
        alreadyWarned = true,
      ),
    )
  }

  @Test
  fun `an ordinary stream says nothing whatever the screen is`() {
    DisplayHdrSupport.entries.forEach { support ->
      assertFalse(
        support.name,
        DolbyVisionNotice.shouldWarn(
          isDolbyVision = false,
          display = support,
          alreadyWarned = false,
        ),
      )
    }
  }

  @Test
  fun `the notice names the format and blames the screen, not the stream`() {
    assertTrue(DolbyVisionNotice.MESSAGE.contains("Dolby Vision"))
    assertTrue(DolbyVisionNotice.MESSAGE.contains("this screen"))
  }
}
