package com.stremioshell.host.tv.pairing

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTokenTest {

  @Test
  fun `generated tokens use the full length and an unambiguous alphabet`() {
    val token = PairingTokenGenerator.generate(Random(42))

    assertEquals(PairingTokenGenerator.LENGTH, token.length)
    assertTrue(token, token.all { it in '0'..'9' || it in 'a'..'z' })
    // i/l/o/u are excluded so a code read off a TV cannot be mistyped.
    assertFalse(token, token.any { it in "ilou" })
  }

  @Test
  fun `two sessions do not share a token`() {
    val tokens = (1..200).map { PairingTokenGenerator.generate() }

    assertEquals(tokens.size, tokens.toSet().size)
  }

  @Test
  fun `the session token is accepted`() {
    val guard = PairingTokenGuard("abc123xyz0")

    assertTrue(guard.isAuthorized("abc123xyz0"))
  }

  @Test
  fun `a missing token is rejected`() {
    val guard = PairingTokenGuard("abc123xyz0")

    assertFalse(guard.isAuthorized(null))
    assertFalse(guard.isAuthorized(""))
    assertFalse(guard.isAuthorized("   "))
  }

  @Test
  fun `a wrong or truncated token is rejected`() {
    val guard = PairingTokenGuard("abc123xyz0")

    assertFalse(guard.isAuthorized("abc123xyz1"))
    assertFalse(guard.isAuthorized("abc123xyz"))
    assertFalse(guard.isAuthorized("abc123xyz00"))
    assertFalse(guard.isAuthorized("zzzzzzzzzz"))
  }

  @Test
  fun `case and surrounding whitespace do not lock the user out`() {
    val guard = PairingTokenGuard("abc123xyz0")

    assertTrue(guard.isAuthorized("ABC123XYZ0"))
    assertTrue(guard.isAuthorized(" abc123xyz0 "))
  }

  @Test
  fun `an empty session token authorises nothing`() {
    val guard = PairingTokenGuard("")

    assertFalse(guard.isAuthorized(""))
    assertFalse(guard.isAuthorized(null))
    assertFalse(guard.isAuthorized("anything"))
  }
}
