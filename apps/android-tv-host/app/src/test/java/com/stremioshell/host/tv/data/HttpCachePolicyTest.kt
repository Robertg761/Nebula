package com.stremioshell.host.tv.data

import java.io.InputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
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
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
  fun `credential probes require a network revalidation`() {
    val request = HttpCachePolicy.requiringNetwork(plainRequest)

    assertTrue(request.cacheControl.noCache)
    assertTrue(!request.cacheControl.onlyIfCached)
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
  fun `retryable statuses include rate limits and server failures only`() {
    assertTrue(HttpCachePolicy.isRetryableStatus(429))
    assertTrue(HttpCachePolicy.isRetryableStatus(500))
    assertTrue(HttpCachePolicy.isRetryableStatus(599))
    assertTrue(!HttpCachePolicy.isRetryableStatus(404))
    assertTrue(!HttpCachePolicy.isRetryableStatus(401))
  }

  @Test
  fun `retry after parses bounded seconds and an http date`() {
    assertEquals(120L, HttpCachePolicy.retryAfterSeconds("120"))
    assertEquals(7L * 24 * 60 * 60, HttpCachePolicy.retryAfterSeconds(Long.MAX_VALUE.toString()))
    assertEquals(
      60L,
      HttpCachePolicy.retryAfterSeconds(
        "Wed, 21 Oct 2015 07:28:00 GMT",
        nowEpochMillis = 1_445_412_420_000L,
      ),
    )
    assertEquals(
      0L,
      HttpCachePolicy.retryAfterSeconds(
        "Wed, 21 Oct 2015 07:28:00 GMT",
        nowEpochMillis = 1_445_412_540_000L,
      ),
    )
    assertNull(HttpCachePolicy.retryAfterSeconds("-1"))
    assertNull(HttpCachePolicy.retryAfterSeconds("not-a-date"))
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
  fun `network body failure on a successful response is served from stale cache`() {
    val networkBody = FailingBody()
    val chain = FakeChain(cacheableRequest) { request ->
      if (request.cacheControl.onlyIfCached) {
        response(request, 200).newBuilder().body("stale".toResponseBody(null)).build()
      } else {
        response(request, 200).newBuilder().body(networkBody).build()
      }
    }

    val result = StaleOnNetworkFailureInterceptor(log = {}).intercept(chain)

    assertEquals("stale", requireNotNull(result.body).string())
    assertEquals("network-body", result.header(HttpCachePolicy.STALE_PROVENANCE_HEADER))
    assertEquals(2, chain.attempts.size)
    assertTrue(networkBody.closed)
  }

  @Test
  fun `retryable status is replaced by stale cache with provenance and retry hint`() {
    val logged = mutableListOf<String>()
    val serverBody = CloseTrackingBody()
    val chain = FakeChain(cacheableRequest) { request ->
      if (request.cacheControl.onlyIfCached) {
        // Real OkHttp refuses a second application-interceptor proceed while the first body is
        // open. This assertion protects the status-fallback path from working only in fake chains.
        assertTrue(serverBody.closed)
        response(request, 200)
      } else {
        response(request, 429)
          .newBuilder()
          .header("Retry-After", "120")
          .body(serverBody)
          .build()
      }
    }

    val result = StaleOnNetworkFailureInterceptor(log = { logged += it }).intercept(chain)

    assertEquals(200, result.code)
    assertEquals("http-429", result.header(HttpCachePolicy.STALE_PROVENANCE_HEADER))
    assertEquals(2, chain.attempts.size)
    assertTrue(logged.single().contains("HTTP 429; retry-after=120s"))
    assertTrue(!logged.single().contains("secret"))
  }

  @Test
  fun `retryable status is preserved when no stale body exists`() {
    val chain = FakeChain(cacheableRequest) { request ->
      if (request.cacheControl.onlyIfCached) response(request, 504) else response(request, 503)
    }

    val result = StaleOnNetworkFailureInterceptor(log = {}).intercept(chain)

    assertEquals(503, result.code)
    assertNull(result.header(HttpCachePolicy.STALE_PROVENANCE_HEADER))
  }

  @Test
  fun `non retryable status never attempts stale cache`() {
    val chain = FakeChain(cacheableRequest) { request -> response(request, 404) }

    val result = StaleOnNetworkFailureInterceptor(log = {}).intercept(chain)

    assertEquals(404, result.code)
    assertEquals(1, chain.attempts.size)
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
  fun `response consumption is dispatched away from the caller thread`() = runBlocking {
    val callerThread = Thread.currentThread()
    val call = ImmediateCall(plainRequest) { response(plainRequest, 200) }

    val bodyThread = call.awaitAndConsumeResponse { Thread.currentThread() }

    assertNotEquals(callerThread, bodyThread)
  }

  @Test
  fun `cancelling during a response body read cancels the OkHttp call`() = runBlocking {
    val body = BlockingBody()
    val call = ImmediateCall(plainRequest, onCancel = body::release) {
      response(plainRequest, 200).newBuilder().body(body).build()
    }
    val job = launch {
      call.awaitAndConsumeResponse { response ->
        requireNotNull(response.body).readUtf8Limited(5)
      }
    }
    yield()
    assertTrue(body.started.await(5, TimeUnit.SECONDS))

    job.cancelAndJoin()

    assertTrue(call.isCanceled())
  }

  @Test
  fun `a value produced after cancellation is cleaned instead of leaked`() = runBlocking {
    val consumeStarted = CountDownLatch(1)
    val allowReturn = CountDownLatch(1)
    val cleaned = CountDownLatch(1)
    val value = Any()
    val call = ImmediateCall(plainRequest) { response(plainRequest, 200) }
    val job = launch {
      call.awaitAndConsumeResponse<Any>(
        onCancellation = {
          assertSame(value, it)
          cleaned.countDown()
        },
      ) {
        consumeStarted.countDown()
        allowReturn.await()
        value
      }
    }
    yield()
    assertTrue(consumeStarted.await(5, TimeUnit.SECONDS))

    job.cancel()
    allowReturn.countDown()
    job.join()

    assertTrue(call.isCanceled())
    assertTrue(cleaned.await(5, TimeUnit.SECONDS))
  }

  @Test
  fun `an eagerly buffered body is decoded from the bytes it already holds`() {
    val chain = FakeChain(cacheableRequest) { request ->
      response(request, 200).newBuilder().body("""{"ok":true}""".toResponseBody(null)).build()
    }

    val body = requireNotNull(StaleOnNetworkFailureInterceptor(log = {}).intercept(chain).body)

    // The interceptor has to hold the whole body anyway so that it can still retry against cache.
    // Streaming those bytes back out through an Okio buffer to build the String left a response
    // that is allowed to reach 5MB existing three times over.
    assertTrue(body is BufferedBytesResponseBody)
    assertEquals("""{"ok":true}""", body.readUtf8Limited(MAX_JSON_RESPONSE_BYTES))
    // Re-readable, unlike a streamed body: the cache and OkHttp's own logging may look at it too.
    assertEquals("""{"ok":true}""", body.readUtf8Limited(MAX_JSON_RESPONSE_BYTES))
    // The ceiling still applies; skipping the stream must not mean skipping the check.
    assertThrows(HttpResponseTooLargeException::class.java) { body.readUtf8Limited(2) }
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

  /** A call whose headers are available immediately and whose body may still block. */
  private class ImmediateCall(
    private val request: Request,
    private val onCancel: () -> Unit = {},
    private val response: () -> Response,
  ) : Call {
    private var executed = false
    private var cancelled = false

    override fun request(): Request = request
    override fun execute(): Response = throw UnsupportedOperationException()
    override fun enqueue(responseCallback: Callback) {
      executed = true
      responseCallback.onResponse(this, response())
    }
    override fun cancel() {
      cancelled = true
      onCancel()
    }
    override fun isExecuted(): Boolean = executed
    override fun isCanceled(): Boolean = cancelled
    override fun timeout(): Timeout = Timeout.NONE
    override fun clone(): Call = ImmediateCall(request, onCancel, response)
  }

  private class BlockingBody : ResponseBody() {
    val started = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val input = object : InputStream() {
      override fun read(): Int {
        started.countDown()
        released.await()
        throw IOException("cancelled")
      }
    }
    private val buffered = input.source().buffer()

    override fun contentType() = null
    override fun contentLength(): Long = -1L
    override fun source(): BufferedSource = buffered

    fun release() {
      released.countDown()
    }
  }

  private class UnknownLengthBody(text: String) : ResponseBody() {
    private val body = Buffer().writeUtf8(text)

    override fun contentType() = null
    override fun contentLength(): Long = -1L
    override fun source(): BufferedSource = body
  }

  private class FailingBody : ResponseBody() {
    var closed = false
      private set
    private var emitted = false
    private val input = object : InputStream() {
      override fun read(): Int {
        if (!emitted) {
          emitted = true
          return '{'.code
        }
        throw IOException("body failed")
      }

      override fun close() {
        closed = true
      }
    }
    private val buffered = input.source().buffer()

    override fun contentType() = null
    override fun contentLength(): Long = -1L
    override fun source(): BufferedSource = buffered
  }

  private class CloseTrackingBody : ResponseBody() {
    private val delegate = "{}".toResponseBody(null)
    var closed = false
      private set

    override fun contentType() = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun source(): BufferedSource = delegate.source()
    override fun close() {
      closed = true
      delegate.close()
    }
  }
}
