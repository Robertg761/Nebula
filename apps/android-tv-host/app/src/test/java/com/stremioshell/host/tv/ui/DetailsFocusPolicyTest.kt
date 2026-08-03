package com.stremioshell.host.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsFocusPolicyTest {
  @Test
  fun `a handled return remains handled after recreation`() {
    assertFalse(
      hasPendingDetailsReturnFocus(
        generation = 1,
        handledGeneration = 1,
        focusKey = "episode:1:4",
      ),
    )
  }

  @Test
  fun `a new round trip requests its saved target`() {
    assertTrue(
      hasPendingDetailsReturnFocus(
        generation = 2,
        handledGeneration = 1,
        focusKey = "episode:1:4",
      ),
    )
  }

  @Test
  fun `a rebuilt details route cannot rearm header focus while return is pending`() {
    assertFalse(
      shouldRequestDetailsPrimaryFocus(
        primaryFocusClaimed = false,
        resumeFocusClaimed = false,
        resumeTargetReady = false,
        returnFocusPending = true,
      ),
    )
  }

  @Test
  fun `a real activity reconstruction can rearm cold header focus`() {
    assertTrue(
      shouldRequestDetailsPrimaryFocus(
        primaryFocusClaimed = false,
        resumeFocusClaimed = false,
        resumeTargetReady = false,
        returnFocusPending = false,
      ),
    )
  }

  @Test
  fun `cached resume goes directly to the episode instead of racing the header`() {
    assertFalse(
      shouldRequestDetailsPrimaryFocus(
        primaryFocusClaimed = false,
        resumeFocusClaimed = false,
        resumeTargetReady = true,
        returnFocusPending = false,
      ),
    )
  }

  @Test
  fun `season retry has a focus destination in every load state`() {
    assertEquals(
      SeasonRetryFocusDestination.SeasonChip,
      seasonRetryFocusDestination(true, EpisodeLoadFocusState.Loading, false),
    )
    assertEquals(
      SeasonRetryFocusDestination.Retry,
      seasonRetryFocusDestination(true, EpisodeLoadFocusState.Failed, false),
    )
    assertEquals(
      SeasonRetryFocusDestination.SeasonChip,
      seasonRetryFocusDestination(true, EpisodeLoadFocusState.Empty, false),
    )
    assertEquals(
      SeasonRetryFocusDestination.FirstEpisode,
      seasonRetryFocusDestination(true, EpisodeLoadFocusState.Populated, false),
    )
    assertEquals(
      SeasonRetryFocusDestination.ResumeEpisode,
      seasonRetryFocusDestination(true, EpisodeLoadFocusState.Populated, true),
    )
  }

  @Test
  fun `leaving an acquired loading anchor cancels its eventual focus handoff`() {
    assertTrue(
      loadingAnchorWasSuperseded(
        requestActive = true,
        stillLoading = true,
        anchorWasFocused = true,
        anchorFocused = false,
      ),
    )
  }

  @Test
  fun `loading handoff stays armed until its anchor has actually received focus`() {
    assertFalse(
      loadingAnchorWasSuperseded(
        requestActive = true,
        stillLoading = true,
        anchorWasFocused = false,
        anchorFocused = false,
      ),
    )
  }

  @Test
  fun `settled results do not mistake their programmatic handoff for user navigation`() {
    assertFalse(
      loadingAnchorWasSuperseded(
        requestActive = true,
        stillLoading = false,
        anchorWasFocused = true,
        anchorFocused = false,
      ),
    )
  }
}
