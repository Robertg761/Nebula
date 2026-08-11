package com.stremioshell.host.tv.data

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cache decisions in [HttpCachePolicy], measured against a real OkHttp disk cache.
 *
 * Everything else about the cache is asserted through fake chains, which is faster and says more
 * about intent - but a fake chain cannot answer the question this file exists for. The disk cache
 * sits *below* application interceptors, inside OkHttp, and whether a request is answered from it
 * is decided by OkHttp's own reading of the headers we set, not by any code of ours. Only a client
 * with a cache attached and a server that can be counted can show that the Direct reach really is
 * uncached, and the loopback server below is a good deal less machinery than a MockWebServer
 * dependency this module does not otherwise need.
 */
class HttpDiskCacheTest {
  private val cacheDir = Files.createTempDirectory("nebula-http-cache").toFile()
  private val cache = Cache(cacheDir, 4L * 1024 * 1024)
  private var server: LoopbackOrigin? = null

  @After
  fun tearDown() {
    server?.close()
    cache.close()
    cacheDir.deleteRecursively()
  }

  @Test
  fun `a direct get is never answered from the disk cache`() {
    // The origin says its JSON is publicly cacheable for an hour. Addon origins routinely do, and
    // it is exactly the wrong thing to believe about a body that carries a signed debrid link.
    val origin = LoopbackOrigin(cacheControl = "public, max-age=3600").also { server = it }
    val client = client()

    // A cacheable read of the same URL, twice, to prove the harness caches at all: without this
    // the Direct assertions below would pass just as happily against a cache that never works.
    val cacheable = HttpCachePolicy.requestFor(origin.url, HttpReach.Cacheable)
    assertEquals("""{"stream":"live"}""", client.body(cacheable))
    assertEquals(1, origin.requests.get())
    assertEquals("""{"stream":"live"}""", client.body(cacheable))
    assertEquals("second cacheable read reached the origin", 1, origin.requests.get())
    assertEquals(1, cache.hitCount())

    // Same URL, same cache, now through the reach addon stream requests use. Both reads must go to
    // the origin: one for the stored body being ignored, one for nothing new being stored.
    val direct = HttpCachePolicy.requestFor(origin.url, HttpReach.Direct)
    assertEquals("""{"stream":"live"}""", client.body(direct))
    assertEquals("a direct read was answered from disk", 2, origin.requests.get())
    assertEquals("""{"stream":"live"}""", client.body(direct))
    assertEquals(3, origin.requests.get())
    assertEquals("a direct read was served by the cache", 1, cache.hitCount())
  }

  @Test
  fun `a direct response is not left in the cache for a later reader`() {
    val origin = LoopbackOrigin(cacheControl = "public, max-age=3600").also { server = it }
    val client = client()

    client.body(HttpCachePolicy.requestFor(origin.url, HttpReach.Direct))

    // Nothing was written, so a later cache-only read - Home's cold-open shortcut, or the stale
    // fallback - cannot find an expired stream link and hand it to the player. OkHttp answers an
    // unsatisfiable `only-if-cached` with a synthetic 504 and opens no socket, which the origin's
    // unchanged request count confirms.
    val cacheOnly = HttpCachePolicy.requestFor(origin.url, HttpReach.CacheOnly)
    assertEquals(504, client.code(cacheOnly))
    assertEquals(1, origin.requests.get())
    assertEquals(0, cache.hitCount())
  }

  private fun client(): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(
      StaleOnNetworkFailureInterceptor(retryAfterGate = RetryAfterGate { 0L }, log = {}),
    )
    .addNetworkInterceptor(CacheableResponseInterceptor())
    .cache(cache)
    .build()

  private fun OkHttpClient.body(request: okhttp3.Request): String =
    newCall(request).execute().use { requireNotNull(it.body).string() }

  private fun OkHttpClient.code(request: okhttp3.Request): Int =
    newCall(request).execute().use { it.code }

  /**
   * A single-response HTTP/1.1 origin on loopback that counts what it was asked.
   *
   * Closes every connection rather than keeping it alive: one fewer thing to get right, and OkHttp
   * treats a closed connection as normal completion.
   */
  private class LoopbackOrigin(private val cacheControl: String) : Closeable {
    private val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    val requests = AtomicInteger()
    val url: String get() = "http://127.0.0.1:${socket.localPort}/stream/movie/tt1.json"

    init {
      Thread(::serve, "loopback-origin").apply { isDaemon = true }.start()
    }

    override fun close() {
      socket.close()
    }

    private fun serve() {
      while (!socket.isClosed) {
        val connection = try {
          socket.accept()
        } catch (_: IOException) {
          return
        }
        connection.use { client ->
          val reader = client.getInputStream().bufferedReader()
          val requestLine = reader.readLine() ?: return@use
          if (requestLine.isBlank()) return@use
          while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
          }
          requests.incrementAndGet()
          val body = """{"stream":"live"}""".toByteArray()
          val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: $cacheControl\r\n")
            append("Connection: close\r\n\r\n")
          }
          client.getOutputStream().apply {
            write(head.toByteArray())
            write(body)
            flush()
          }
        }
      }
    }
  }
}
