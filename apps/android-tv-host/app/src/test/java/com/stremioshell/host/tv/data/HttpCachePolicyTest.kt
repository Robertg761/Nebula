package com.stremioshell.host.tv.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpCachePolicyTest {
  private val cacheableRequest = Request.Builder()
    .url("https://api.themoviedb.org/3/trending/movie/week?api_key=secret")
    .header(HttpCachePolicy.CACHEABLE_HEADER, "1")
    .build()

  private val plainRequest = Request.Builder()
    .url("https://comet.example/cfg/stream/movie/tt1.json")
    .build()

  @Test
  fun `only marked requests are cacheable`() {
    assertTrue(HttpCachePolicy.isCacheable(cacheableRequest))
    assertTrue(!HttpCachePolicy.isCacheable(plainRequest))
  }

  @Test
  fun `stale fallback request is answerable only from cache`() {
    val fallback = HttpCachePolicy.staleFallbackRequest(cacheableRequest, maxStaleSeconds = 600)
    assertTrue(fallback.cacheControl.onlyIfCached)
    assertEquals(600, fallback.cacheControl.maxStaleSeconds)
    assertEquals(cacheableRequest.url, fallback.url)
  }

  @Test
  fun `marker header never reaches the server`() {
    assertNull(HttpCachePolicy.withoutMarker(cacheableRequest).header(HttpCachePolicy.CACHEABLE_HEADER))
  }

  @Test
  fun `freshness is stamped on successful responses only`() {
    val ok = HttpCachePolicy.stampFreshness(response(cacheableRequest, 200, pragma = "no-cache"), 300)
    assertEquals("public, max-age=300", ok.header("Cache-Control"))
    // Pragma: no-cache would otherwise veto the Cache-Control we just set.
    assertNull(ok.header("Pragma"))

    val error = HttpCachePolicy.stampFreshness(response(cacheableRequest, 401), 300)
    assertNull(error.header("Cache-Control"))
  }

  @Test
  fun `network failure on a cacheable get is served from cache`() {
    val logged = mutableListOf<String>()
    val chain = FakeChain(cacheableRequest) { request ->
      if (request.cacheControl.onlyIfCached) response(request, 200) else throw IOException("offline")
    }

    val response = StaleOnNetworkFailureInterceptor(maxStaleSeconds = 60) { logged += it }
      .intercept(chain)

    assertEquals(200, response.code)
    assertEquals(2, chain.attempts.size)
    assertTrue(chain.attempts[1].cacheControl.onlyIfCached)
    assertEquals(1, logged.size)
    assertTrue(logged.single().contains("?<redacted>"))
    assertTrue(!logged.single().contains("secret"))
  }

  @Test
  fun `network failure with nothing cached reports the network failure`() {
    // `only-if-cached` with an empty cache yields a synthetic 504; surfacing that instead of the
    // real failure would tell the user the wrong thing.
    val chain = FakeChain(cacheableRequest) { request ->
      if (request.cacheControl.onlyIfCached) response(request, 504) else throw IOException("offline")
    }

    val error = assertThrows(IOException::class.java) {
      StaleOnNetworkFailureInterceptor(log = {}).intercept(chain)
    }
    assertEquals("offline", error.message)
  }

  @Test
  fun `unmarked requests get no stale fallback`() {
    // Addon stream URLs expire, so replaying a cached response would hand the player a dead link.
    val chain = FakeChain(plainRequest) { throw IOException("offline") }

    assertThrows(IOException::class.java) {
      StaleOnNetworkFailureInterceptor(log = {}).intercept(chain)
    }
    assertEquals(1, chain.attempts.size)
  }

  @Test
  fun `response interceptor stamps marked responses and leaves others alone`() {
    val marked = FakeChain(cacheableRequest) { request -> response(request, 200) }
    val stamped = CacheableResponseInterceptor(freshSeconds = 120).intercept(marked)
    assertEquals("public, max-age=120", stamped.header("Cache-Control"))
    assertNull(marked.attempts.single().header(HttpCachePolicy.CACHEABLE_HEADER))

    val plain = FakeChain(plainRequest) { request -> response(request, 200) }
    assertNull(CacheableResponseInterceptor().intercept(plain).header("Cache-Control"))
  }

  @Test
  fun `cancelling a coroutine cancels its in-flight OkHttp call`() = runBlocking {
    val call = PendingCall(plainRequest)
    val job = launch { call.awaitResponse() }
    yield()

    job.cancelAndJoin()

    assertTrue(call.isCanceled())
  }

  @Test
  fun `json response reads are bounded even when length is unknown`() {
    assertEquals("12345", "12345".toResponseBody(null).readUtf8Limited(5))
    assertThrows(HttpResponseTooLargeException::class.java) {
      "123456".toResponseBody(null).readUtf8Limited(5)
    }
    assertThrows(HttpResponseTooLargeException::class.java) {
      UnknownLengthBody("123456").readUtf8Limited(5)
    }
  }

  private fun response(request: Request, code: Int, pragma: String? = null): Response {
    val builder = Response.Builder()
      .request(request)
      .protocol(Protocol.HTTP_1_1)
      .code(code)
      .message(if (code == 200) "OK" else "Error")
      .body("{}".toResponseBody(null))
    if (pragma != null) builder.header("Pragma", pragma)
    return builder.build()
  }

  /** Minimal Interceptor.Chain that records what each interceptor asked for. */
  private class FakeChain(
    private val request: Request,
    private val handler: (Request) -> Response,
  ) : Interceptor.Chain {
    val attempts = mutableListOf<Request>()

    override fun request(): Request = request

    override fun proceed(request: Request): Response {
      attempts += request
      return handler(request)
    }

    override fun connection(): Connection? = null
    override fun call(): Call = throw UnsupportedOperationException()
    override fun connectTimeoutMillis(): Int = 0
    override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    override fun readTimeoutMillis(): Int = 0
    override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    override fun writeTimeoutMillis(): Int = 0
    override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
  }

  /** A call that stays in flight until the coroutine under test cancels it. */
  private class PendingCall(private val request: Request) : Call {
    private var executed = false
    private var cancelled = false

    override fun request(): Request = request
    override fun execute(): Response = throw UnsupportedOperationException()
    override fun enqueue(responseCallback: Callback) {
      executed = true
    }
    override fun cancel() {
      cancelled = true
    }
    override fun isExecuted(): Boolean = executed
    override fun isCanceled(): Boolean = cancelled
    override fun timeout(): Timeout = Timeout.NONE
    override fun clone(): Call = PendingCall(request)
  }

  private class UnknownLengthBody(text: String) : ResponseBody() {
    private val body = Buffer().writeUtf8(text)

    override fun contentType() = null
    override fun contentLength(): Long = -1L
    override fun source(): BufferedSource = body
  }
}
