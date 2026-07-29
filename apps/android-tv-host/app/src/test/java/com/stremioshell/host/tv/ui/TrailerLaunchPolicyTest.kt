package com.stremioshell.host.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrailerLaunchPolicyTest {
  @Test
  fun `a TMDB YouTube id becomes one browsable https view request`() {
    assertEquals(
      TrailerLaunchRequest(
        action = "android.intent.action.VIEW",
        uri = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        categories = setOf("android.intent.category.BROWSABLE"),
      ),
      TrailerLaunchPolicy.request(" dQw4w9WgXcQ "),
    )
  }

  @Test
  fun `untrusted video values cannot change the destination`() {
    assertNull(TrailerLaunchPolicy.request(null))
    assertNull(TrailerLaunchPolicy.request(""))
    assertNull(TrailerLaunchPolicy.request("https://example.com"))
    assertNull(TrailerLaunchPolicy.request("dQw4w9WgXcQ&list=secret"))
  }

  @Test
  fun `the policy reports a missing external handler without claiming success`() {
    assertEquals(
      TrailerLaunchResult.NoHandler,
      TrailerLaunchPolicy.launch("dQw4w9WgXcQ") { false },
    )
  }

  @Test
  fun `invalid ids are rejected before an external launch is attempted`() {
    var attempts = 0
    assertEquals(
      TrailerLaunchResult.InvalidVideo,
      TrailerLaunchPolicy.launch("../not-a-video") {
        attempts++
        true
      },
    )
    assertEquals(0, attempts)
  }
}
