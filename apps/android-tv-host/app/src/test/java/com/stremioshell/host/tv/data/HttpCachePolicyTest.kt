package com.stremioshell.host.tv.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
}
