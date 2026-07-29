package com.stremioshell.host.tv.pairing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingValidationPolicyTest {
  @Test
  fun `setup is complete only when TMDB and every configured addon connect`() {
    assertTrue(
      PairingValidation(
        hasTmdbKey = true,
        tmdbConnected = true,
        addons = listOf(
          PairingConnectionCheck("Comet", true),
          PairingConnectionCheck("Torrentio", true),
        ),
      ).complete,
    )
    assertFalse(
      PairingValidation(
        hasTmdbKey = true,
        tmdbConnected = true,
        addons = listOf(
          PairingConnectionCheck("Comet", true),
          PairingConnectionCheck("Torrentio", false),
        ),
      ).complete,
    )
  }

  @Test
  fun `an empty configuration can never be reported as paired`() {
    assertFalse(
      PairingValidation(
        hasTmdbKey = false,
        tmdbConnected = false,
        addons = emptyList(),
      ).complete,
    )
  }

  @Test
  fun `failure copy names sources without exposing credentials`() {
    val message = PairingValidationPolicy.failureMessage(
      PairingValidation(
        hasTmdbKey = true,
        tmdbConnected = false,
        addons = listOf(
          PairingConnectionCheck("Comet", false),
          PairingConnectionCheck("Torrentio", true),
        ),
      ),
    )
    assertTrue(message.contains("TMDB"))
    assertTrue(message.contains("Comet"))
    assertFalse(message.contains("https://"))
    assertFalse(message.contains("api_key"))
  }
}
