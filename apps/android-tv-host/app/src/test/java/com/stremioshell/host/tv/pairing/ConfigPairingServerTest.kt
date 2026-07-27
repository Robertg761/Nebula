package com.stremioshell.host.tv.pairing

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives the real server over loopback. NanoHTTPD is plain JVM code, so the
 * whole request path - including the token gate that keeps the rest of the LAN
 * away from the user's credentials - runs in a normal unit test.
 */
class ConfigPairingServerTest {
  private val token = "abc123xyz0"
  private val submissions = LinkedBlockingQueue<PairingSubmission>()
  private lateinit var server: ConfigPairingServer

  @Before
  fun startServer() {
    server = ConfigPairingServer(token) { submissions.add(it) }
    server.start()
  }

  @After
  fun stopServer() {
    server.stop()
  }

  private data class Reply(val code: Int, val body: String)

  private fun request(path: String, form: String? = null): Reply {
    val connection = URL("http://127.0.0.1:${server.listeningPort}$path")
      .openConnection() as HttpURLConnection
    connection.connectTimeout = 5_000
    connection.readTimeout = 5_000
    if (form != null) {
      connection.requestMethod = "POST"
      connection.doOutput = true
      connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
      connection.outputStream.use { it.write(form.toByteArray()) }
    }
    return try {
      val code = connection.responseCode
      val stream = if (code < 400) connection.inputStream else connection.errorStream
      Reply(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
    } finally {
      connection.disconnect()
    }
  }

  private fun nextSubmission(): PairingSubmission? = submissions.poll(1, TimeUnit.SECONDS)

  @Test
  fun `a LAN host without the token gets nothing`() {
    assertEquals(403, request("/").code)
    assertEquals(403, request("/?t=").code)
    assertEquals(403, request("/?t=wrongwrong").code)
    assertEquals(403, request("/anything?t=wrongwrong").code)
  }

  @Test
  fun `the tokened form never echoes the stored configuration back`() {
    val reply = request("/?t=$token")

    assertEquals(200, reply.code)
    // Write-only: the key input carries no value and the URL textarea is empty, so a
    // stolen token still cannot read the Real-Debrid-bearing Comet URL off the TV.
    assertTrue(reply.body, reply.body.contains("""placeholder="Leave empty to keep current key""""))
    assertTrue(reply.body, reply.body.contains("></textarea>"))
    assertFalse(reply.body, reply.body.contains("""<input name="tmdb" value="""))
    // ...and it carries the token onward for the POST.
    assertTrue(reply.body, reply.body.contains("""<input type="hidden" name="t" value="$token">"""))
  }

  @Test
  fun `an untokened POST cannot overwrite the TVs configuration`() {
    val reply = request("/config", form = "tmdb=attacker-key&addon=https%3A%2F%2Fevil%2Fmanifest.json")

    assertEquals(403, reply.code)
    assertNull(nextSubmission())
  }

  @Test
  fun `a POST carrying a wrong token is refused`() {
    val reply = request("/config", form = "t=wrongwrong&tmdb=attacker-key")

    assertEquals(403, reply.code)
    assertNull(nextSubmission())
  }

  @Test
  fun `a tokened POST delivers the submission and reports the blank field as unchanged`() {
    val reply = request("/config", form = "t=$token&tmdb=my-key&addon=")

    assertEquals(200, reply.code)
    assertEquals(PairingSubmission(tmdbKey = "my-key", addonUrls = null), nextSubmission())
    assertTrue(reply.body, reply.body.contains("TMDB API key: updated"))
    assertTrue(reply.body, reply.body.contains("Stream addons: unchanged"))
  }

  @Test
  fun `a tokened POST delivers every addon line the phone submitted`() {
    val form = "t=$token&addon=" +
      "https%3A%2F%2Fcomet.example%2Fmanifest.json%0D%0Atorrentio.example"

    val reply = request("/config", form = form)

    assertEquals(200, reply.code)
    assertEquals(
      PairingSubmission(
        tmdbKey = null,
        addonUrls = listOf(
          "https://comet.example/manifest.json",
          "https://torrentio.example/manifest.json",
        ),
      ),
      nextSubmission(),
    )
    assertTrue(reply.body, reply.body.contains("Stream addons: 2 saved"))
    // Count only: this page rides the same cleartext HTTP the form does, and the URLs carry the
    // viewer's Real-Debrid key.
    assertFalse(reply.body, reply.body.contains("comet.example"))
  }

  @Test
  fun `a tokened POST with both fields blank re-serves the form instead of clearing anything`() {
    val reply = request("/config", form = "t=$token&tmdb=&addon=%20%20")

    assertEquals(200, reply.code)
    assertNull(nextSubmission())
    assertTrue(reply.body, reply.body.contains("Enter at least one value."))
  }

  @Test
  fun `an addon box with nothing usable in it is reported, not half-applied`() {
    val reply = request("/config", form = "t=$token&tmdb=my-key&addon=stremio%3A%2F%2F")

    assertEquals(200, reply.code)
    // The key is not saved either: a success page that quietly dropped the URLs would send the
    // viewer to the TV to work out why nothing plays.
    assertNull(nextSubmission())
    assertTrue(reply.body, reply.body.contains("No usable addon link in that box."))
  }
}
