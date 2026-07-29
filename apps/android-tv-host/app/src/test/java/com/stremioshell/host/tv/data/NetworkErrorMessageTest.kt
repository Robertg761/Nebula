package com.stremioshell.host.tv.data

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkErrorMessageTest {
  @Test
  fun `unauthorized tmdb points at the api key setting`() {
    val message = NetworkErrorMessage.forThrowable(NetworkSource.Tmdb, HttpStatusException(401, "api.themoviedb.org"))
    assertTrue(message, message.contains("HTTP 401"))
    assertTrue(message, message.contains("API key in Settings"))
  }

  @Test
  fun `unauthorized addon points at the addon url setting`() {
    val message = NetworkErrorMessage.forThrowable(NetworkSource.Addon, HttpStatusException(403, "comet.example"))
    assertTrue(message, message.contains("addon URL in Settings"))
  }

  @Test
  fun `no api key or url ever reaches the message`() {
    // The whole point of the mapping: a status failure must not carry request detail with it.
    val leaky = HttpStatusException(401, "api.themoviedb.org")
    val message = NetworkErrorMessage.forThrowable(NetworkSource.Tmdb, leaky)
    assertFalse(message, message.contains("api_key"))
    assertFalse(message, message.contains("http"))
  }

  @Test
  fun `rate limit and server errors read as temporary`() {
    assertTrue(NetworkErrorMessage.forStatus(NetworkSource.Tmdb, 429).contains("rate limit"))
    val serverError = NetworkErrorMessage.forStatus(NetworkSource.Tmdb, 503)
    assertTrue(serverError, serverError.contains("HTTP 503"))
    assertTrue(serverError, serverError.contains("Try again"))
  }

  @Test
  fun `unknown status still names the status`() {
    assertEquals(
      "Couldn't reach TMDB (HTTP 418).",
      NetworkErrorMessage.forStatus(NetworkSource.Tmdb, 418),
    )
  }

  @Test
  fun `offline reads as no internet rather than a dns error`() {
    val message = NetworkErrorMessage.forThrowable(
      NetworkSource.Tmdb,
      UnknownHostException("api.themoviedb.org: No address associated with hostname"),
    )
    assertEquals("No internet connection. Check your network and try again.", message)
  }

  @Test
  fun `timeouts from either socket or call timeout map to the same sentence`() {
    val socket = NetworkErrorMessage.forThrowable(NetworkSource.Tmdb, SocketTimeoutException("timeout"))
    val call = NetworkErrorMessage.forThrowable(NetworkSource.Tmdb, InterruptedIOException("timeout"))
    assertTrue(socket, socket.contains("Timed out waiting for TMDB"))
    assertEquals(socket, call)
  }

  @Test
  fun `tls and malformed json get their own sentences`() {
    assertTrue(
      NetworkErrorMessage.forThrowable(NetworkSource.Tmdb, SSLHandshakeException("bad cert"))
        .contains("Secure connection"),
    )
    assertTrue(
      NetworkErrorMessage.forThrowable(NetworkSource.Tmdb, SerializationException("Unexpected JSON token"))
        .contains("Couldn't read the response"),
    )
  }

  @Test
  fun `generic io failure falls back to a network hint`() {
    val message = NetworkErrorMessage.forThrowable(NetworkSource.Addon, IOException("unexpected end of stream"))
    assertEquals("Couldn't reach the addon. Check your network connection.", message)
  }

  @Test
  fun `an oversized response is distinguished from an offline service`() {
    assertEquals(
      "The response from the addon was too large to use safely.",
      NetworkErrorMessage.forThrowable(
        NetworkSource.Addon,
        HttpResponseTooLargeException(MAX_JSON_RESPONSE_BYTES),
      ),
    )
  }

  @Test
  fun `a wrapped cause is still recognized`() {
    val wrapped = IllegalStateException("wrapper", HttpStatusException(429, "api.themoviedb.org"))
    assertTrue(NetworkErrorMessage.forThrowable(NetworkSource.Tmdb, wrapped).contains("rate limit"))
  }

  @Test
  fun `unrecognized and null failures still produce a sentence`() {
    assertEquals(
      "Something went wrong loading from TMDB.",
      NetworkErrorMessage.forThrowable(NetworkSource.Tmdb, IllegalArgumentException("nope")),
    )
    assertEquals(
      "Something went wrong loading from TMDB.",
      NetworkErrorMessage.forThrowable(NetworkSource.Tmdb, null),
    )
  }

  @Test
  fun `a cause cycle does not hang the mapping`() {
    val a = IllegalStateException("a")
    val b = IllegalStateException("b", a)
    a.initCause(b)
    assertEquals(
      "Something went wrong loading from TMDB.",
      NetworkErrorMessage.forThrowable(NetworkSource.Tmdb, b),
    )
  }

  @Test
  fun `redaction keeps a public TMDB route but drops every query value`() {
    assertEquals(
      "https://api.themoviedb.org/3/trending/movie/week?<redacted>",
      redactSecrets("https://api.themoviedb.org/3/trending/movie/week?api_key=abc123def&language=en-US"),
    )
  }

  @Test
  fun `redaction removes addon userinfo opaque config paths and arbitrary query keys`() {
    assertEquals(
      "https://comet.example/<redacted>/stream/movie/tt1.json?<redacted>",
      redactSecrets(
        "https://user:password@comet.example/private-key/stream/movie/tt1.json?session=s3cret",
      ),
    )
  }

  @Test
  fun `redaction identifies a root addon route without inventing a secret path`() {
    assertEquals(
      "https://comet.example/stream/movie/tt1.json",
      redactSecrets("https://comet.example/stream/movie/tt1.json"),
    )
    assertEquals("<redacted-url>", redactSecrets("not a URL token=secret"))
  }
}
