package com.stremioshell.host.tv.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CoroutineDispatchTest {
  @Test
  fun `json decode work leaves the calling thread`() = runBlocking {
    val callerThread = Thread.currentThread()

    val decodeThread = decodeJsonOffMain { Thread.currentThread() }

    assertNotEquals(callerThread, decodeThread)
  }
}
