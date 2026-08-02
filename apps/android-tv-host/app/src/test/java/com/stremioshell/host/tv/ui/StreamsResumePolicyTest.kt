package com.stremioshell.host.tv.ui

import com.stremioshell.host.tv.LoadState
import com.stremioshell.host.tv.StreamsRequestKey
import org.junit.Assert.assertFalse
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
}
