package com.stremioshell.host.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateWorkSchedulerTest {

  @Test
  fun `manual and periodic checks require a release build and complete repository identity`() {
    assertTrue(UpdateWorkScheduler.checksAvailable(false, "owner", "repo"))
    assertFalse(UpdateWorkScheduler.checksAvailable(true, "owner", "repo"))
    assertFalse(UpdateWorkScheduler.checksAvailable(false, "", "repo"))
    assertFalse(UpdateWorkScheduler.checksAvailable(false, "owner", "   "))
  }
}
