package com.stremioshell.host.tv

import com.stremioshell.host.tv.data.PlayerPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMutationTrackerTest {
  @Test
  fun `completion remains observable until a replacement composition consumes it`() {
    val tracker = SettingsMutationTracker()
    val requestId = tracker.begin(SettingsMutationRequest.AddAddon("https://a.example"))!!

    tracker.complete(requestId, SettingsMutationResult.Failed)

    val restoredObserver = tracker.operation.value!!
    assertEquals(requestId, restoredObserver.requestId)
    assertEquals(SettingsMutationResult.Failed, restoredObserver.result)
    assertFalse(restoredObserver.running)
    tracker.consume(requestId)
    assertNull(tracker.operation.value)
  }

  @Test
  fun `one operation stays busy until consumed and stale completions cannot replace it`() {
    val tracker = SettingsMutationTracker()
    val first = tracker.begin(SettingsMutationRequest.ClearTmdbKey)!!

    assertNull(tracker.begin(SettingsMutationRequest.ResetPlayback))
    assertTrue(tracker.operation.value!!.running)
    tracker.complete(first + 1, SettingsMutationResult.Changed)
    assertTrue(tracker.operation.value!!.running)
    tracker.complete(first, SettingsMutationResult.Changed, PlayerPrefs())
    tracker.consume(first)

    val second = tracker.begin(SettingsMutationRequest.ResetPlayback)
    assertEquals(first + 1, second)
  }
}
