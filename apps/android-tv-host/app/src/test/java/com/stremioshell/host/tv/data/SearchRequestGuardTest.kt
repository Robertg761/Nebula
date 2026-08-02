package com.stremioshell.host.tv.data

import com.stremioshell.host.tv.data.tmdb.SearchRequestGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRequestGuardTest {
  @Test
  fun `the same query is reusable only for the credential that owns it`() {
    val guard = SearchRequestGuard()
    val original = guard.begin("dune", "test-key-a")

    assertTrue(guard.canReuse("dune", "test-key-a"))
    assertFalse(guard.canReuse("dune", "test-key-b"))
    assertFalse(guard.isCurrent(original, "dune", "test-key-b"))
  }

  @Test
  fun `clearing the credential invalidates results and paging ownership`() {
    val guard = SearchRequestGuard()
    val request = guard.begin("alien", "test-key")

    assertTrue(guard.updateCredential(null))
    assertNull(guard.current("alien", null))
    assertFalse(guard.isCurrent(request, "alien", null))
  }

  @Test
  fun `a retry gets a distinct generation so a delayed callback is rejected`() {
    val guard = SearchRequestGuard()
    val failed = guard.begin("arrival", "test-key")
    val retry = guard.begin("arrival", "test-key")

    assertNotEquals(failed.generation, retry.generation)
    assertFalse(guard.isCurrent(failed, "arrival", "test-key"))
    assertTrue(guard.isCurrent(retry, "arrival", "test-key"))
  }

  @Test
  fun `request tokens never retain the credential`() {
    val guard = SearchRequestGuard()
    val credential = "credential-must-not-appear"
    val request = guard.begin("foundation", credential)

    assertFalse(request.toString().contains(credential))
  }
}
