package com.stremioshell.host.tv.ui

import com.stremioshell.host.tv.LoadState
import com.stremioshell.host.tv.StreamsRequestKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamsResumePolicyTest {
  private val request = StreamsRequestKey("tt123", season = 1, episode = 2)

  @Test
  fun staleNoAddonFailureReloadsAfterAddonIsAdded() {
    assertTrue(
      shouldLoadStreamsOnResume(
        loadIssued = true,
        heldRequest = request,
        requested = request,
        state = LoadState.Failed("No addon configured. Add a stream addon in Settings."),
        addons = listOf("https://addon.example/manifest.json"),
      ),
    )
  }

  @Test
  fun noAddonFailureDoesNotReloadLoopWhileConfigurationIsStillEmpty() {
    assertFalse(
      shouldLoadStreamsOnResume(
        loadIssued = true,
        heldRequest = request,
        requested = request,
        state = LoadState.Failed("No addon configured. Add a stream addon in Settings."),
        addons = emptyList(),
      ),
    )
  }

  @Test
  fun showAllResetsAFarRetainedListPositionBeforeRestoringFocus() {
    // A target at either of the first two presentation rows must explicitly return to zero rather
    // than trusting a LazyListState that can still be parked far down the pre-filtered list.
    assertEquals(0, streamListFocusScrollIndex(preselectedRow = 0))
    assertEquals(0, streamListFocusScrollIndex(preselectedRow = 1))
    assertEquals(7, streamListFocusScrollIndex(preselectedRow = 8))
    assertNull(streamListFocusScrollIndex(preselectedRow = -1))
  }
}
