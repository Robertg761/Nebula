package com.stremioshell.host.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatedConnectivityTest {
  @Test
  fun `an initially validated network does not look like a return`() {
    val transition = ValidatedConnectivityTransition(initiallyValidated = true)

    assertFalse(transition.update(true))
  }

  @Test
  fun `one return emits once despite repeated capability callbacks`() {
    val transition = ValidatedConnectivityTransition(initiallyValidated = false)

    assertTrue(transition.update(true))
    assertFalse(transition.update(true))
    assertFalse(transition.update(true))
  }

  @Test
  fun `each distinct outage can produce one later return`() {
    val transition = ValidatedConnectivityTransition(initiallyValidated = true)

    assertFalse(transition.update(false))
    assertTrue(transition.update(true))
    assertFalse(transition.update(false))
    assertFalse(transition.update(false))
    assertTrue(transition.update(true))
  }

  @Test
  fun `saved content age is omitted when unknown or the clock moved backwards`() {
    assertNull(SavedContentProvenance(null).ageMillis(nowMillis = 20_000))
    assertNull(SavedContentProvenance(30_000).ageMillis(nowMillis = 20_000))
    assertEquals(5_000L, SavedContentProvenance(15_000).ageMillis(nowMillis = 20_000))
  }

  @Test
  fun `combined provenance uses the oldest complete timestamp`() {
    assertEquals(
      10_000L,
      SavedContentProvenance.oldest(
        SavedContentProvenance(20_000),
        SavedContentProvenance(10_000),
      )?.savedAtMillis,
    )
    assertEquals(
      SavedContentReason.Offline,
      SavedContentProvenance.oldest(
        SavedContentProvenance(20_000),
        SavedContentProvenance(10_000, SavedContentReason.Offline),
      )?.reason,
    )
    assertNull(
      SavedContentProvenance.oldest(
        SavedContentProvenance(20_000),
        SavedContentProvenance(null),
      )?.savedAtMillis,
    )
    assertNull(SavedContentProvenance.oldest(null, null))
  }

  @Test
  fun `home provenance requires saved content that is actually visible`() {
    assertTrue(
      SavedContentRefreshPolicy.homeUsesSavedContent(
        visibleTitles = setOf("Trending Movies", "Popular Movies"),
        previousTitles = setOf("Trending Movies"),
        failedTitles = setOf("Trending Movies"),
        staleFallbackTitles = emptySet(),
      ),
    )
    assertTrue(
      SavedContentRefreshPolicy.homeUsesSavedContent(
        visibleTitles = setOf("Trending Movies"),
        previousTitles = emptySet(),
        failedTitles = emptySet(),
        staleFallbackTitles = setOf("Trending Movies"),
      ),
    )
    assertFalse(
      SavedContentRefreshPolicy.homeUsesSavedContent(
        visibleTitles = setOf("Trending Movies"),
        previousTitles = emptySet(),
        failedTitles = setOf("Popular Movies"),
        staleFallbackTitles = setOf("Popular Shows"),
      ),
    )
  }
}
