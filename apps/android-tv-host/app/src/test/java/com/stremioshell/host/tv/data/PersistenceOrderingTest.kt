package com.stremioshell.host.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistenceOrderingTest {
  @Test
  fun `action tokens in one session preserve source order when commits arrive backwards`() {
    val session = "process-a"
    val later = PersistenceOrdering.allocate(
      storedSession = null,
      storedSessionBase = null,
      storedCounter = null,
      observedOrder = 0,
      token = PersistenceMutationToken(session, 2),
    )
    val earlier = PersistenceOrdering.allocate(
      storedSession = session,
      storedSessionBase = later.sessionBase,
      storedCounter = later.counter,
      observedOrder = later.order,
      token = PersistenceMutationToken(session, 1),
    )

    assertTrue(later.order > earlier.order)
    assertTrue(PersistenceOrdering.acceptsMutation(later.order, later.order))
    assertTrue(!PersistenceOrdering.acceptsMutation(later.order, earlier.order))
  }

  @Test
  fun `a new process is rebased above the persisted counter`() {
    val allocation = PersistenceOrdering.allocate(
      storedSession = "old-process",
      storedSessionBase = 10,
      storedCounter = 50,
      observedOrder = 75,
      token = PersistenceMutationToken("new-process", 1),
    )

    assertEquals(76L, allocation.order)
    assertEquals(75L, allocation.sessionBase)
    assertEquals(76L, allocation.counter)
  }

  @Test
  fun `a removal wins a tie and only a later logical mutation restores a row`() {
    assertTrue(!PersistenceOrdering.acceptsAfterRemoval(removedOrder = 200, incomingOrder = 199))
    assertTrue(!PersistenceOrdering.acceptsAfterRemoval(removedOrder = 200, incomingOrder = 200))
    assertTrue(PersistenceOrdering.acceptsAfterRemoval(removedOrder = 200, incomingOrder = 201))
    assertTrue(PersistenceOrdering.acceptsAfterRemoval(removedOrder = null, incomingOrder = 1))
  }

  @Test
  fun `logical recency wins while legacy records keep timestamp order`() {
    assertTrue(PersistenceOrdering.compareNewest(2, 100, 1, 9_000) < 0)
    assertTrue(PersistenceOrdering.compareNewest(1, 100, 0, 9_000) < 0)
    assertTrue(PersistenceOrdering.compareNewest(0, 100, 0, 9_000) > 0)
  }

  @Test
  fun `monotonic preference counter never regresses or becomes negative`() {
    assertEquals(5, PersistenceOrdering.monotonicCounter(existing = 5, incoming = 3))
    assertEquals(6, PersistenceOrdering.monotonicCounter(existing = 5, incoming = 6))
    assertEquals(0, PersistenceOrdering.monotonicCounter(existing = null, incoming = -1))
  }
}
