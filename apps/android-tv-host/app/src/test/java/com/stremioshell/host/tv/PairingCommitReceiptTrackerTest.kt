package com.stremioshell.host.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCommitReceiptTrackerTest {
  @Test
  fun `pause after the commit gate waits for the phone response and keeps its receipt`() {
    val tracker = PairingCommitReceiptTracker<String>()

    assertTrue(tracker.beginCommit())
    assertEquals(PairingPauseDecision.WaitForResponse, tracker.pause())
    assertTrue(tracker.active())
    tracker.recordReceipt("saved")

    val completion = tracker.finishResponse()!!
    assertEquals("saved", completion.receipt)
    assertTrue(completion.closeServer)
    assertFalse(tracker.active())
  }

  @Test
  fun `resume before the phone response cancels deferred shutdown`() {
    val tracker = PairingCommitReceiptTracker<String>()
    assertTrue(tracker.beginCommit())
    assertEquals(PairingPauseDecision.WaitForResponse, tracker.pause())

    assertTrue(tracker.resumePendingResponse())
    tracker.recordReceipt("saved")

    val completion = tracker.finishResponse()!!
    assertEquals("saved", completion.receipt)
    assertFalse(completion.closeServer)
    assertTrue(tracker.active())
  }

  @Test
  fun `pause before the commit gate closes the session and prevents a late commit`() {
    val tracker = PairingCommitReceiptTracker<String>()

    assertEquals(PairingPauseDecision.CloseNow, tracker.pause())

    assertFalse(tracker.beginCommit())
    assertFalse(tracker.active())
    assertNull(tracker.finishResponse())
  }

  @Test
  fun `explicit exit suppresses a receipt even when the commit cannot be cancelled`() {
    val tracker = PairingCommitReceiptTracker<String>()
    assertTrue(tracker.beginCommit())
    tracker.recordReceipt("saved")

    tracker.stop(suppressReceipt = true)

    val completion = tracker.finishResponse()!!
    assertNull(completion.receipt)
    assertTrue(completion.closeServer)
  }
}
