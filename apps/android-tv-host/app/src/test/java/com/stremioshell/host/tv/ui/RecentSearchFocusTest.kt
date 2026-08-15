package com.stremioshell.host.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentSearchFocusTest {
  @Test
  fun `removing a middle row focuses the next row`() {
    assertEquals(
      "Three",
      RecentSearchFocus.afterRemoval(listOf("One", "Two", "Three"), "Two"),
    )
  }

  @Test
  fun `removing the final row focuses its previous neighbour`() {
    assertEquals(
      "Two",
      RecentSearchFocus.afterRemoval(listOf("One", "Two", "Three"), "Three"),
    )
  }

  @Test
  fun `removing the only row hands focus back to the field`() {
    assertNull(RecentSearchFocus.afterRemoval(listOf("One"), "one"))
  }
}
