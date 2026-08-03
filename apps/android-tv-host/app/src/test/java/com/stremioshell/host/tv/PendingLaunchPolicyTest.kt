package com.stremioshell.host.tv

import com.stremioshell.host.tv.channel.WatchNextTarget
import com.stremioshell.host.tv.search.LaunchRequest
import com.stremioshell.host.tv.search.SearchLaunch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingLaunchPolicyTest {
  @Test
  fun `fresh activity routes its retained launch intent`() {
    val target = WatchNextTarget("movie", 550)

    assertEquals(
      PendingLaunchSeed.Fresh(PendingLaunch.WatchNext(target)),
      PendingLaunchPolicy.onCreate(
        restoringState = false,
        restoredPending = null,
        retainedIntentRequest = LaunchRequest.OpenWatchNext(target),
      ),
    )
  }

  @Test
  fun `restored activity does not replay a consumed retained intent`() {
    assertNull(
      PendingLaunchPolicy.onCreate(
        restoringState = true,
        restoredPending = null,
        retainedIntentRequest = LaunchRequest.OpenSearch(SearchLaunch("old query")),
      )
    )
  }

  @Test
  fun `restored activity keeps a request that was still pending when state was saved`() {
    val pending = PendingLaunchEvent(7L, PendingLaunch.Search(SearchLaunch("dune")))

    assertEquals(
      PendingLaunchSeed.Restored(pending),
      PendingLaunchPolicy.onCreate(
        restoringState = true,
        restoredPending = pending,
        retainedIntentRequest = LaunchRequest.OpenSettings,
      ),
    )
  }

  @Test
  fun `plain launch never creates a pending destination`() {
    assertNull(PendingLaunchPolicy.from(LaunchRequest.Launch))
  }

  @Test
  fun `older callback cannot consume an identical newer request`() {
    val tracker = PendingLaunchTracker()
    val request = PendingLaunch.Search(SearchLaunch(""))
    val older = tracker.enqueue(request)
    val newer = tracker.enqueue(request)

    assertNull(tracker.consume(older.id))
    assertEquals(newer, tracker.current)
    assertEquals(request, tracker.consume(newer.id))
    assertNull(tracker.current)
  }

  @Test
  fun `restored identity keeps later event ids monotonic`() {
    val tracker = PendingLaunchTracker()
    tracker.restore(PendingLaunchEvent(41L, PendingLaunch.Settings))

    assertEquals(42L, tracker.enqueue(PendingLaunch.Settings).id)
  }
}
