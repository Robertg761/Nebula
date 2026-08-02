package com.stremioshell.host.tv.pairing

import com.stremioshell.host.tv.data.addon.AddonList
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    server = ConfigPairingServer(token) { submission ->
      submissions.add(submission)
      PairingApplyResult.Saved(receiptFor(submission))
    }
    server.start()
  }

  @After
  fun stopServer() {
    server.stop()
  }

  private data class Reply(val code: Int, val body: String, val connection: String? = null)

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
      Reply(
        code,
        stream?.bufferedReader()?.use { it.readText() }.orEmpty(),
        connection.getHeaderField("Connection"),
      )
    } finally {
      connection.disconnect()
    }
  }

  private fun nextSubmission(): PairingSubmission? = submissions.poll(1, TimeUnit.SECONDS)

  /** Sends headers HttpURLConnection deliberately repairs, such as a missing/invalid length. */
  private fun rawPost(
    contentLength: String?,
    contentType: String = "application/x-www-form-urlencoded",
    body: String = "",
    path: String = "/config?t=$token",
  ): Reply = Socket("127.0.0.1", server.listeningPort).use { socket ->
    socket.soTimeout = 5_000
    val request = buildString {
      append("POST $path HTTP/1.1\r\n")
      append("Host: 127.0.0.1\r\n")
      append("Content-Type: $contentType\r\n")
      append("Connection: keep-alive\r\n")
      if (contentLength != null) append("Content-Length: $contentLength\r\n")
      append("\r\n")
      append(body)
    }
    socket.getOutputStream().apply {
      write(request.toByteArray(StandardCharsets.ISO_8859_1))
      flush()
    }

    val input = socket.getInputStream()
    val status = readAsciiLine(input)
    val headers = mutableMapOf<String, String>()
    while (true) {
      val line = readAsciiLine(input)
      if (line.isEmpty()) break
      val separator = line.indexOf(':')
      if (separator > 0) {
        headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
      }
    }
    val bytes = ByteArray(headers["content-length"]?.toIntOrNull() ?: 0)
    var read = 0
    while (read < bytes.size) {
      val count = input.read(bytes, read, bytes.size - read)
      if (count < 0) break
      read += count
    }
    Reply(
      code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: -1,
      body = String(bytes, 0, read, StandardCharsets.UTF_8),
      connection = headers["connection"],
    )
  }

  private fun readAsciiLine(input: InputStream): String {
    val bytes = ByteArrayOutputStream()
    while (true) {
      val value = input.read()
      if (value < 0 || value == '\n'.code) break
      if (value != '\r'.code) bytes.write(value)
    }
    return bytes.toString(StandardCharsets.ISO_8859_1.name())
  }

  private fun receiptFor(submission: PairingSubmission) = PairingReceipt(
    tmdbKeyChanged = submission.tmdbKey != null,
    addonUrlsChanged = submission.addonUrls != null,
    hasTmdbKey = submission.tmdbKey != null,
    addonCount = submission.addonUrls?.size ?: 0,
  )

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
    // ...and it carries the token onward in the action, where the server can authenticate before
    // parsing the credential-bearing body.
    assertTrue(reply.body, reply.body.contains("""action="/config?t=$token""""))
  }

  @Test
  fun `an untokened POST cannot overwrite the TVs configuration`() {
    val reply = request("/config", form = "tmdb=attacker-key&addon=https%3A%2F%2Fevil%2Fmanifest.json")

    assertEquals(403, reply.code)
    assertNull(nextSubmission())
  }

  @Test
  fun `a POST carrying a wrong token is refused`() {
    val reply = request("/config?t=wrongwrong", form = "tmdb=attacker-key")

    assertEquals(403, reply.code)
    assertNull(nextSubmission())
  }

  @Test
  fun `a tokened POST delivers the submission and reports the blank field as unchanged`() {
    val reply = request("/config?t=$token", form = "tmdb=my-key&addon=")

    assertEquals(200, reply.code)
    assertEquals(PairingSubmission(tmdbKey = "my-key", addonUrls = null), nextSubmission())
    assertTrue(reply.body, reply.body.contains("TMDB API key: updated"))
    assertTrue(reply.body, reply.body.contains("Stream addons: unchanged"))
  }

  @Test
  fun `a tokened POST delivers every addon line the phone submitted`() {
    val form = "t=$token&addon=" +
      "https%3A%2F%2Fcomet.example%2Fmanifest.json%0D%0Atorrentio.example"

    val reply = request("/config?t=$token", form = form)

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
    val reply = request("/config?t=$token", form = "tmdb=&addon=%20%20")

    assertEquals(200, reply.code)
    assertNull(nextSubmission())
    assertTrue(reply.body, reply.body.contains("Enter at least one value."))
  }

  @Test
  fun `an addon box with nothing usable in it is reported, not half-applied`() {
    val reply = request("/config?t=$token", form = "tmdb=my-key&addon=stremio%3A%2F%2F")

    assertEquals(200, reply.code)
    // The key is not saved either: a success page that quietly dropped the URLs would send the
    // viewer to the TV to work out why nothing plays.
    assertNull(nextSubmission())
    assertTrue(reply.body, reply.body.contains("No usable addon link in that box."))
  }

  @Test
  fun `the token in a POST body is never parsed as authorization`() {
    val reply = request("/config", form = "t=$token&tmdb=attacker-key")

    assertEquals(403, reply.code)
    assertNull(nextSubmission())
  }

  @Test
  fun `a successful submission consumes the token atomically`() {
    assertEquals(200, request("/config?t=$token", form = "tmdb=first").code)

    val second = request("/config?t=$token", form = "tmdb=second")

    assertEquals(403, second.code)
    assertEquals(403, request("/?t=$token").code)
    assertEquals(PairingSubmission("first", null), nextSubmission())
    assertNull(nextSubmission())
  }

  @Test
  fun `a storage failure is reported and releases the token for a retry`() {
    server.stop()
    val attempts = AtomicInteger()
    server = ConfigPairingServer(token) { submission ->
      if (attempts.getAndIncrement() == 0) {
        PairingApplyResult.Failed("Storage is temporarily unavailable.")
      } else {
        submissions.add(submission)
        PairingApplyResult.Saved(receiptFor(submission))
      }
    }
    server.start()

    val failed = request("/config?t=$token", form = "tmdb=first")
    val retried = request("/config?t=$token", form = "tmdb=second")

    assertEquals(200, failed.code)
    assertTrue(failed.body, failed.body.contains("Storage is temporarily unavailable."))
    assertFalse(failed.body, failed.body.contains("Saved to your TV"))
    assertEquals(200, retried.code)
    assertTrue(retried.body, retried.body.contains("Saved to your TV"))
    assertEquals(PairingSubmission("second", null), nextSubmission())
  }

  @Test
  fun `an oversized authenticated form is rejected before parsing`() {
    val reply = request("/config?t=$token", form = "tmdb=${"x".repeat(33 * 1024)}")

    assertEquals(413, reply.code)
    assertEquals("close", reply.connection?.lowercase())
    assertNull(nextSubmission())
  }

  @Test
  fun `POST length rejections close the keep-alive connection`() {
    val replies = listOf(
      rawPost(contentLength = null),
      rawPost(contentLength = "not-a-number"),
      rawPost(contentLength = (33 * 1024).toString()),
    )

    assertEquals(listOf(411, 411, 413), replies.map { it.code })
    replies.forEach { assertEquals("close", it.connection?.lowercase()) }
    assertNull(nextSubmission())
  }

  @Test
  fun `a body parse rejection closes the keep-alive connection`() {
    // Multipart without a boundary makes NanoHTTPD's parseBody reject the request.
    val reply = rawPost(
      contentLength = "3",
      contentType = "multipart/form-data",
      body = "abc",
    )

    assertEquals(400, reply.code)
    assertEquals("close", reply.connection?.lowercase())
    assertNull(nextSubmission())
  }

  @Test
  fun `a tokened POST to the wrong path closes without consuming its body`() {
    val reply = rawPost(
      contentLength = "3",
      body = "abc",
      path = "/not-config?t=$token",
    )

    assertEquals(404, reply.code)
    assertEquals("close", reply.connection?.lowercase())
    assertNull(nextSubmission())
  }

  @Test
  fun `mixed valid and invalid addon lines are rejected together`() {
    val form = "tmdb=my-key&addon=" +
      "https%3A%2F%2Fvalid.example%2Fmanifest.json%0Astremio%3A%2F%2F"

    val reply = request("/config?t=$token", form = form)

    assertEquals(200, reply.code)
    assertTrue(reply.body, reply.body.contains("Every addon line must be a usable manifest link."))
    assertNull(nextSubmission())
  }

  @Test
  fun `more than the supported addon count is rejected instead of truncated`() {
    val addons = (1..AddonList.MAX_ADDONS + 1)
      .joinToString("%0A") { "https%3A%2F%2Fa$it.example%2Fmanifest.json" }

    val reply = request("/config?t=$token", form = "addon=$addons")

    assertEquals(200, reply.code)
    assertTrue(reply.body, reply.body.contains("Enter no more than ${AddonList.MAX_ADDONS}"))
    assertNull(nextSubmission())
  }
}
