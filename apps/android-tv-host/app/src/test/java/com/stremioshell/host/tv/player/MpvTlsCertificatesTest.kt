package com.stremioshell.host.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundle assembly, off a device. Keystore access and base64 encoding are the
 * caller's lambda precisely so the decisions in here - which aliases count, what a
 * failed certificate costs, and what an empty export means - are testable.
 */
class MpvTlsCertificatesTest {
  private val header = "# test/fingerprint:1"

  private fun bundle(
    aliases: List<String>,
    encode: (String) -> String? = { "DER-$it" },
  ): String? = MpvTlsCertificates.buildBundleText(header, aliases.asSequence(), encode)

  @Test
  fun `only the firmware's own roots are exported`() {
    val text = bundle(listOf("system:a1b2.0", "user:deadbeef.0", "system:c3d4.0"))!!

    assertTrue(text.contains("DER-system:a1b2.0"))
    assertTrue(text.contains("DER-system:c3d4.0"))
    // A CA the device owner installed is trusted by nothing else in this app on
    // targetSdk 34, and mpv must not be the exception that accepts an
    // interception proxy for debrid URLs.
    assertFalse(text.contains("deadbeef"))
    assertEquals(2, text.lines().count { it == "-----BEGIN CERTIFICATE-----" })
  }

  @Test
  fun `a certificate that will not encode costs itself and nothing else`() {
    val text = bundle(listOf("system:good.0", "system:broken.0", "system:alsogood.0")) { alias ->
      // What a CertificateEncodingException, or an alias holding something that is
      // not an X.509 certificate, reaches this function as. It used to abort the
      // whole export and leave mpv with no trust store at all.
      "DER-$alias".takeUnless { alias.contains("broken") }
    }!!

    assertTrue(text.contains("DER-system:good.0"))
    assertTrue(text.contains("DER-system:alsogood.0"))
    assertFalse(text.contains("broken"))
  }

  @Test
  fun `an export with nothing in it is null rather than a header-only bundle`() {
    // tls-verify=yes against a bundle with no certificates in it rejects every
    // host on the internet, and the fingerprint cache would serve that emptiness
    // for the life of the firmware. Null instead: the caller leaves tls-ca-file
    // unset, which is merely the behaviour from before any of this existed.
    assertNull(bundle(emptyList()))
    assertNull(bundle(listOf("user:deadbeef.0")))
    assertNull(bundle(listOf("system:a1b2.0")) { null })
  }

  @Test
  fun `the header comes first and every certificate is framed as PEM`() {
    val lines = bundle(listOf("system:a1b2.0"))!!.lines()

    assertEquals(header, lines[0])
    assertEquals("-----BEGIN CERTIFICATE-----", lines[1])
    assertEquals("DER-system:a1b2.0", lines[2])
    assertEquals("-----END CERTIFICATE-----", lines[3])
  }

  @Test
  fun `long encodings are wrapped at PEM's line length`() {
    val encoded = "A".repeat(150)
    val body = bundle(listOf("system:a1b2.0")) { encoded }!!
      .lines()
      .filter { it.startsWith("A") }

    // mbedtls parses wrapped base64; one 150-character line is not PEM.
    assertEquals(listOf(64, 64, 22), body.map { it.length })
    assertEquals(encoded, body.joinToString(""))
  }
}
