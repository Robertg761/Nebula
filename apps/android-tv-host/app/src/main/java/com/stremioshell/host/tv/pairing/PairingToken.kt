package com.stremioshell.host.tv.pairing

import java.security.SecureRandom
import java.util.Random

/**
 * One-time secret that authorises a single pairing session.
 *
 * The pairing server is plain HTTP on the LAN, so the token is the only thing
 * standing between a curious neighbour on the same Wi-Fi and the user's TMDB
 * key / Real-Debrid-bearing Comet URL. It is minted when the pairing screen
 * opens, carried in the QR URL, and dies with the server.
 */
object PairingTokenGenerator {
  /**
   * Crockford-style alphabet: digits plus lowercase letters with i/l/o/u
   * removed, so a code read off a TV and typed into a phone cannot be
   * misread as a look-alike.
   */
  private const val ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"

  /** 10 symbols over a 32-symbol alphabet = 50 bits, still short enough to type. */
  const val LENGTH = 10

  private val secureRandom: Random by lazy { SecureRandom() }

  fun generate(random: Random = secureRandom): String =
    String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })
}

/**
 * Checks the token presented on a request against the one minted for this
 * session. Anything that does not match exactly is refused, including a
 * missing token: there is no "no token means local request" escape hatch.
 */
class PairingTokenGuard(token: String) {
  private val expected: String = token.trim().lowercase()

  /**
   * True only for [provided] equal to the session token. Comparison is
   * case-insensitive (the alphabet is single-case, so accepting an
   * auto-capitalised paste costs no entropy) and takes the same time for
   * every same-length candidate.
   */
  fun isAuthorized(provided: String?): Boolean {
    if (expected.isEmpty()) return false
    val candidate = provided?.trim()?.lowercase() ?: return false
    if (candidate.length != expected.length) return false
    var diff = 0
    for (i in expected.indices) {
      diff = diff or (expected[i].code xor candidate[i].code)
    }
    return diff == 0
  }
}
