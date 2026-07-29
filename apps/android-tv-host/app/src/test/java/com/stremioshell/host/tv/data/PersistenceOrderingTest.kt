package com.stremioshell.host.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistenceOrderingTest {
  @Test
  fun `older delayed entry cannot replace newer persisted state`() {
    assertFalse(PersistenceOrdering.accepts(existingUpdatedAtMs = 200, incomingUpdatedAtMs = 199))
    assertTrue(PersistenceOrdering.accepts(existingUpdatedAtMs = 200, incomingUpdatedAtMs = 200))
    assertTrue(PersistenceOrdering.accepts(existingUpdatedAtMs = 200, incomingUpdatedAtMs = 201))
    assertTrue(PersistenceOrdering.accepts(existingUpdatedAtMs = null, incomingUpdatedAtMs = 1))
  }

  @Test
  fun `a removal wins ties and only a genuinely newer playback can restore a row`() {
    assertFalse(PersistenceOrdering.acceptsAfterRemoval(removedAtMs = 200, incomingUpdatedAtMs = 199))
    assertFalse(PersistenceOrdering.acceptsAfterRemoval(removedAtMs = 200, incomingUpdatedAtMs = 200))
    assertTrue(PersistenceOrdering.acceptsAfterRemoval(removedAtMs = 200, incomingUpdatedAtMs = 201))
    assertTrue(PersistenceOrdering.acceptsAfterRemoval(removedAtMs = null, incomingUpdatedAtMs = 1))
  }

  @Test
  fun `a delayed duplicate removal cannot weaken the deletion marker`() {
    assertEquals(200, PersistenceOrdering.latestRemoval(existingRemovedAtMs = 200, incomingRemovedAtMs = 199))
    assertEquals(201, PersistenceOrdering.latestRemoval(existingRemovedAtMs = 200, incomingRemovedAtMs = 201))
    assertEquals(1, PersistenceOrdering.latestRemoval(existingRemovedAtMs = null, incomingRemovedAtMs = 1))
  }

  @Test
  fun `monotonic preference counter never regresses or becomes negative`() {
    assertEquals(5, PersistenceOrdering.monotonicCounter(existing = 5, incoming = 3))
    assertEquals(6, PersistenceOrdering.monotonicCounter(existing = 5, incoming = 6))
    assertEquals(0, PersistenceOrdering.monotonicCounter(existing = null, incoming = -1))
  }
}
