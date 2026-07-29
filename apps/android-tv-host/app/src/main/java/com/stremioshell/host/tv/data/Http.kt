package com.stremioshell.host.tv.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine

internal const val HTTP_TAG = "TvHttp"
internal const val MAX_JSON_RESPONSE_BYTES: Long = 5L * 1024 * 1024

/** Seam for HTTP GETs so clients stay unit-testable without a server. */
fun interface HttpFetcher {
  /** Returns the response body for a 2xx response; throws [HttpStatusException] otherwise. */
  suspend fun get(url: String): String

  /**
   * Like [get], but the response may be cached and, when the network fails, a previously cached
   * body may be served instead of throwing. Only for responses that stay useful when slightly
   * old - catalogs and metadata, never addon stream URLs (those expire).
   */
  suspend fun getAllowingStale(url: String): String = get(url)
}

/**
 * Cache tuning shared by the interceptors below. Split out from the client so the decisions are
 * inspectable in unit tests.
 */
object HttpCachePolicy {
  /**
   * Request header marking a GET as cacheable-and-stale-servable. Ours only: it is stripped
   * before the request reaches the wire.
   */
  const val CACHEABLE_HEADER = "X-Stremio-Cacheable"

  /** Modest by TV standards; TMDB JSON is small and this only has to cover a few screens. */
  const val DISK_CACHE_BYTES: Long = 20L * 1024 * 1024

  /** How long a cached catalog is served without asking the network at all. */
  const val FRESH_SECONDS: Int = 5 * 60

  /** How old a cached body may be when it is standing in for a failed network call. */
  const val MAX_STALE_SECONDS: Int = 7 * 24 * 60 * 60

  /** True when the caller opted this request into caching with a stale fallback. */
  fun isCacheable(request: Request): Boolean = request.header(CACHEABLE_HEADER) != null

  /** Freshness we stamp on responses as they pass into the cache. */
  fun freshResponseCacheControl(freshSeconds: Int = FRESH_SECONDS): String =
    "public, max-age=$freshSeconds"

  /** Cache-only request used as the fallback when the network is unreachable. */
  fun staleFallback(maxStaleSeconds: Int = MAX_STALE_SECONDS): CacheControl =
    CacheControl.Builder()
      .onlyIfCached()
      .maxStale(maxStaleSeconds, TimeUnit.SECONDS)
      .build()

  /** The same request, but answerable only from the disk cache. */
  fun staleFallbackRequest(request: Request, maxStaleSeconds: Int = MAX_STALE_SECONDS): Request =
    request.newBuilder().cacheControl(staleFallback(maxStaleSeconds)).build()

  /** Drops our marker header so it never reaches the server. */
  fun withoutMarker(request: Request): Request =
    request.newBuilder().removeHeader(CACHEABLE_HEADER).build()

  /**
   * Stamps a freshness window on a response so the cache will store it. TMDB's own headers are
   * not reliably storable, and without this there would be nothing on disk to fall back on.
   */
  fun stampFreshness(response: Response, freshSeconds: Int = FRESH_SECONDS): Response {
    if (!response.isSuccessful) return response
    return response.newBuilder()
      // Pragma: no-cache would veto the Cache-Control we are setting.
      .removeHeader("Pragma")
      .header("Cache-Control", freshResponseCacheControl(freshSeconds))
      .build()
  }
}

/**
 * Application interceptor: when the network fails on a cacheable GET, retry the same request
 * against the disk cache, so a cold start on flaky Wi-Fi still paints rails instead of an error.
 *
 * Sits above the cache in the chain, which is what lets the retry be served from it.
 */
internal class StaleOnNetworkFailureInterceptor(
  private val maxStaleSeconds: Int = HttpCachePolicy.MAX_STALE_SECONDS,
  /** Seam so the fallback path is unit-testable without android.util.Log. */
  private val log: (String) -> Unit = { Log.i(HTTP_TAG, it) },
) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    if (!HttpCachePolicy.isCacheable(request)) return chain.proceed(request)
    val failure = try {
      return chain.proceed(request)
    } catch (error: IOException) {
      error
    }
    val cached = runCatching {
      chain.proceed(HttpCachePolicy.staleFallbackRequest(request, maxStaleSeconds))
    }.getOrNull()
    // `only-if-cached` with nothing stored yields a synthetic 504; report the real network
    // failure in that case rather than a bogus status.
    if (cached == null || !cached.isSuccessful) {
      cached?.close()
      throw failure
    }
    log("network failed; serving cached ${redactSecrets(request.url.toString())}")
    return cached
  }
}

/**
 * Network interceptor: stamps a short freshness window on cacheable responses as they pass into
 * the cache. TMDB's own headers are not reliably storable, and without this the disk cache would
 * hold nothing to fall back on.
 */
internal class CacheableResponseInterceptor(
  private val freshSeconds: Int = HttpCachePolicy.FRESH_SECONDS,
) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    if (!HttpCachePolicy.isCacheable(request)) return chain.proceed(request)
    val response = chain.proceed(HttpCachePolicy.withoutMarker(request))
    return HttpCachePolicy.stampFreshness(response, freshSeconds)
  }
}

/**
 * The one OkHttp client for TMDB and addon traffic, so both share a connection pool and one disk
 * cache. [init] is called from the Application because a cache needs a Context; without it the
 * client still works, just uncached (that is the unit-test path).
 */
object SharedHttpClient {
  @Volatile
  private var cacheDir: File? = null

  fun init(context: Context) {
    if (cacheDir != null) return
    cacheDir = runCatching { File(context.applicationContext.cacheDir, "tv-http") }
      .onFailure { Log.w(HTTP_TAG, "no cache dir; HTTP responses will not be cached", it) }
      .getOrNull()
  }

  val client: OkHttpClient by lazy { build(cacheDir) }

  private fun build(dir: File?): OkHttpClient {
    val builder = OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      // A response that trickles a byte at a time keeps resetting the read timeout, so without a
      // ceiling on the whole call Home can sit on "Loading catalogs..." indefinitely.
      .callTimeout(40, TimeUnit.SECONDS)
      .addInterceptor(StaleOnNetworkFailureInterceptor())
      .addNetworkInterceptor(CacheableResponseInterceptor())
    if (dir != null) {
      runCatching { builder.cache(Cache(dir, HttpCachePolicy.DISK_CACHE_BYTES)) }
        .onFailure { Log.w(HTTP_TAG, "HTTP disk cache unavailable", it) }
    }
    return builder.build()
  }
}

object OkHttpFetcher : HttpFetcher {
  override suspend fun get(url: String): String = execute(url, cacheable = false)

  override suspend fun getAllowingStale(url: String): String = execute(url, cacheable = true)

  private suspend fun execute(url: String, cacheable: Boolean): String {
    val builder = Request.Builder().url(url).header("Accept", "application/json")
    if (cacheable) builder.header(HttpCachePolicy.CACHEABLE_HEADER, "1")
    val request = builder.build()
    return SharedHttpClient.client.newCall(request).awaitResponse().use { response ->
      if (!response.isSuccessful) {
        // The URL carries the TMDB api_key, so the detail stays here (redacted) and the thrown
        // message - which the UI may end up rendering - carries only status and host.
        Log.w(HTTP_TAG, "GET ${redactSecrets(url)} failed: HTTP ${response.code}")
        throw HttpStatusException(response.code, request.url.host)
      }
      response.body?.readUtf8Limited(MAX_JSON_RESPONSE_BYTES).orEmpty()
    }
  }
}

/**
 * Reads a response without trusting Content-Length. Chunked/misreported bodies are capped by
 * asking Okio for one byte beyond the limit before turning the buffer into a String.
 */
internal fun ResponseBody.readUtf8Limited(maxBytes: Long): String {
  require(maxBytes >= 0L && maxBytes < Long.MAX_VALUE)
  val declaredBytes = contentLength()
  if (declaredBytes > maxBytes) throw HttpResponseTooLargeException(maxBytes)
  val source = source()
  if (source.request(maxBytes + 1L)) throw HttpResponseTooLargeException(maxBytes)
  return source.readUtf8()
}

/**
 * Coroutine bridge for OkHttp that cancels the socket when its caller times out
 * or leaves the screen. Wrapping blocking `execute()` in an IO dispatcher only
 * cancelled the coroutine; the underlying request kept consuming a connection
 * until OkHttp's own 40-second ceiling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun Call.awaitResponse(): Response =
  suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        if (!continuation.isCancelled) continuation.resumeWithException(e)
      }

      override fun onResponse(call: Call, response: Response) {
        continuation.resume(response) { response.close() }
      }
    })
  }
