package com.stremioshell.host.tv.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal const val HTTP_TAG = "TvHttp"
internal const val MAX_JSON_RESPONSE_BYTES: Long = 5L * 1024 * 1024

/** Seam for HTTP GETs so clients stay unit-testable without a server. */
fun interface HttpFetcher {
  /** Returns the response body for a 2xx response; throws [HttpStatusException] otherwise. */
  suspend fun get(url: String): String

  /**
   * Requires a network revalidation instead of accepting a fresh disk-cache hit.
   *
   * Credential checks use this path: a response cached before a key was revoked must not be
   * mistaken for proof that the key still works. Test fetchers can keep delegating to [get].
   */
  suspend fun getFresh(url: String): String = get(url)

  /**
   * Like [get], but the response may be cached and, when the network fails, a previously cached
   * body may be served instead of throwing. Only for responses that stay useful when slightly
   * old - catalogs and metadata, never addon stream URLs (those expire).
   */
  suspend fun getAllowingStale(url: String): String = get(url)

  /**
   * Answers from the disk cache or not at all, returning null when nothing is stored.
   *
   * Never opens a socket, so a miss costs a disk lookup rather than a round trip. That is what
   * makes it usable ahead of a network load rather than only as a fallback: Home reads its rails
   * this way on a cold open and paints them while TMDB is still being asked. Only ever paired with
   * a real load, because a hit says nothing about whether the body is still current.
   *
   * Defaults to an empty cache so test fetchers, which have no cache to consult, take the network
   * path exactly as they do today.
   */
  suspend fun getCachedOnly(url: String): String? = null
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

  /**
   * Local-only response header describing why a stale body was returned. It is added after the
   * cache lookup, never sent to a service, and gives diagnostics/tests provenance without changing
   * [HttpFetcher]'s deliberately tiny public API.
   */
  const val STALE_PROVENANCE_HEADER = "X-Nebula-Stale-Provenance"

  /** True when the caller opted this request into caching with a stale fallback. */
  fun isCacheable(request: Request): Boolean = request.header(CACHEABLE_HEADER) != null

  /** Statuses where an older catalog is more useful than the service's transient error page. */
  fun isRetryableStatus(code: Int): Boolean = code == 429 || code in 500..599

  /**
   * Parses the two legal Retry-After forms: delta-seconds and an HTTP date.
   *
   * The result is capped because it is a scheduling/diagnostic hint, not authority for a remote
   * server to suppress refreshes forever. A past date means retry now.
   */
  fun retryAfterSeconds(
    raw: String?,
    nowEpochMillis: Long = System.currentTimeMillis(),
  ): Long? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    value.toLongOrNull()?.let { seconds ->
      if (seconds < 0) return null
      return seconds.coerceAtMost(MAX_RETRY_AFTER_SECONDS)
    }
    val retryAt = runCatching {
      SimpleDateFormat(RETRY_AFTER_DATE_PATTERN, Locale.US).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("GMT")
      }.parse(value)
    }.getOrNull() ?: return null
    val remainingMillis = (retryAt.time - nowEpochMillis).coerceAtLeast(0L)
    return ((remainingMillis + 999L) / 1_000L).coerceAtMost(MAX_RETRY_AFTER_SECONDS)
  }

  /** Freshness we stamp on responses as they pass into the cache. */
  fun freshResponseCacheControl(freshSeconds: Int = FRESH_SECONDS): String =
    "public, max-age=$freshSeconds"

  /**
   * Disk or nothing: OkHttp answers from the cache or synthesises a 504, and never opens a socket.
   *
   * One control for both uses - the fallback after a network failure, and the deliberate read-ahead
   * on a cold start - so the two can never disagree about how old a body may be and still count.
   */
  fun cacheOnly(maxStaleSeconds: Int = MAX_STALE_SECONDS): CacheControl =
    CacheControl.Builder()
      .onlyIfCached()
      .maxStale(maxStaleSeconds, TimeUnit.SECONDS)
      .build()

  /** The same request, but answerable only from the disk cache. */
  fun cacheOnlyRequest(request: Request, maxStaleSeconds: Int = MAX_STALE_SECONDS): Request =
    request.newBuilder().cacheControl(cacheOnly(maxStaleSeconds)).build()

  /** Drops our marker header so it never reaches the server. */
  fun withoutMarker(request: Request): Request =
    request.newBuilder().removeHeader(CACHEABLE_HEADER).build()

  /** Forces a network round-trip while still allowing HTTP conditional revalidation. */
  fun requiringNetwork(request: Request): Request =
    request.newBuilder().cacheControl(CacheControl.FORCE_NETWORK).build()

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

  /** Marks a cached response with local provenance after a network failure or retryable status. */
  fun markStaleFallback(response: Response, provenance: String): Response =
    response.newBuilder()
      .header(STALE_PROVENANCE_HEADER, provenance)
      .build()

  private const val MAX_RETRY_AFTER_SECONDS = 7L * 24 * 60 * 60
  private const val RETRY_AFTER_DATE_PATTERN = "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
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
    var responseStarted = false
    val networkResponse = try {
      val response = chain.proceed(request)
      responseStarted = true
      eagerlyBufferSuccessfulBody(response)
    } catch (error: IOException) {
      val cached = cachedResponse(chain, request)
      if (cached == null) throw error
      val provenance = if (responseStarted) "network-body" else "network-io"
      val phase = if (responseStarted) "network body failed" else "network failed"
      log("$phase; serving cached ${redactSecrets(request.url.toString())}")
      return HttpCachePolicy.markStaleFallback(cached, provenance)
    }

    if (!HttpCachePolicy.isRetryableStatus(networkResponse.code)) return networkResponse
    val status = networkResponse.code
    val retryAfter = HttpCachePolicy.retryAfterSeconds(networkResponse.header("Retry-After"))
    // OkHttp requires an application interceptor to close one response before calling proceed
    // again. Keep a bodyless copy for the no-cache case: callers only need its status/headers and
    // must still receive the service failure rather than a synthetic cache 504.
    val passThrough = networkResponse.newBuilder()
      .body(ByteArray(0).toResponseBody(networkResponse.body?.contentType()))
      .build()
    networkResponse.close()
    val cached = cachedResponse(chain, request) ?: return passThrough
    val retryHint = retryAfter?.let { "; retry-after=${it}s" }.orEmpty()
    log(
      "HTTP $status$retryHint; serving cached ${redactSecrets(request.url.toString())}",
    )
    return HttpCachePolicy.markStaleFallback(cached, "http-$status")
  }

  private fun cachedResponse(chain: Interceptor.Chain, request: Request): Response? {
    val cached = runCatching {
      chain.proceed(HttpCachePolicy.cacheOnlyRequest(request, maxStaleSeconds))
    }.getOrNull()
    // `only-if-cached` with nothing stored yields a synthetic 504.
    if (cached == null || !cached.isSuccessful) {
      cached?.close()
      return null
    }
    return cached
  }

  /**
   * Consumes successful marked responses while this interceptor can still retry against cache.
   *
   * Returning the original streaming body would move an IOException after headers outside the
   * interceptor, making the stale fallback unreachable. The replacement remains bounded and is
   * consumed a second time only from memory by [OkHttpFetcher].
   */
  private fun eagerlyBufferSuccessfulBody(response: Response): Response {
    if (!response.isSuccessful) return response
    val body = response.body ?: return response
    val contentType = body.contentType()
    val bytes = try {
      body.readByteArrayLimited(MAX_JSON_RESPONSE_BYTES)
    } finally {
      body.close()
    }
    return response.newBuilder()
      .body(BufferedBytesResponseBody(bytes, contentType))
      .build()
  }
}

/**
 * The buffered replacement body, keeping the bytes it already holds reachable as bytes.
 *
 * `ByteArray.toResponseBody()` would copy them into an Okio buffer that [readUtf8Limited] then
 * copies out again to build the String - three live copies of a body that is allowed to reach 5MB,
 * for a body that has already been read and length-checked once. [source] still works for any
 * caller that wants one (OkHttp's own logging, the cache), so this is a shortcut, not a special
 * case.
 */
internal class BufferedBytesResponseBody(
  private val bytes: ByteArray,
  private val mediaType: MediaType?,
) : ResponseBody() {
  override fun contentType(): MediaType? = mediaType

  override fun contentLength(): Long = bytes.size.toLong()

  override fun source(): BufferedSource = Buffer().write(bytes)

  fun utf8(): String = String(bytes, Charsets.UTF_8)
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
  /**
   * Where a GET is allowed to be answered from. One enum rather than a row of booleans, because the
   * combinations are not independent: a cache-only read must not be marked cacheable, and a forced
   * revalidation is the opposite of both.
   */
  private enum class Reach {
    /** Uncached in practice: nothing stamps the response as storable on its way past the cache. */
    Direct,

    /** A network round trip even when a fresh copy is on disk. */
    Revalidated,

    /** Fresh cache, else network, else whatever the cache still holds. */
    Cacheable,

    /** Disk or nothing. */
    CacheOnly,
  }

  override suspend fun get(url: String): String = execute(url, Reach.Direct)

  override suspend fun getFresh(url: String): String = execute(url, Reach.Revalidated)

  override suspend fun getAllowingStale(url: String): String = execute(url, Reach.Cacheable)

  /**
   * An empty cache is the expected answer here, not an error: it is what a first run looks like,
   * and every caller has a network load behind this one. So a miss - OkHttp's synthetic 504, or a
   * cache that could not be opened at all - comes back as null rather than as something to report.
   */
  override suspend fun getCachedOnly(url: String): String? =
    try {
      execute(url, Reach.CacheOnly)
    } catch (_: IOException) {
      null
    }

  private suspend fun execute(url: String, reach: Reach): String {
    val builder = Request.Builder().url(url).header("Accept", "application/json")
    // The marker opts a response into being stored and into the stale fallback. A cache-only read
    // must not carry it: the miss it expects is a synthetic 504, which is a retryable status, so
    // the interceptor would search the cache a second time for the body it was just told is absent.
    if (reach == Reach.Cacheable) builder.header(HttpCachePolicy.CACHEABLE_HEADER, "1")
    val built = builder.build()
    val request = when (reach) {
      Reach.Revalidated -> HttpCachePolicy.requiringNetwork(built)
      Reach.CacheOnly -> HttpCachePolicy.cacheOnlyRequest(built)
      Reach.Direct, Reach.Cacheable -> built
    }
    return SharedHttpClient.client.newCall(request).awaitAndConsumeResponse { response ->
      if (!response.isSuccessful) {
        // The URL carries the TMDB api_key, so the detail stays here (redacted) and the thrown
        // message - which the UI may end up rendering - carries only status and host.
        if (reach != Reach.CacheOnly) {
          Log.w(HTTP_TAG, "GET ${redactSecrets(url)} failed: HTTP ${response.code}")
        }
        throw HttpStatusException(
          code = response.code,
          host = request.url.host,
          retryAfterSeconds = HttpCachePolicy.retryAfterSeconds(response.header("Retry-After")),
        )
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
  // Already read by the interceptor that buffered it, and its length is exact rather than declared,
  // so the ceiling still applies but nothing has to be streamed to enforce it.
  if (this is BufferedBytesResponseBody) {
    if (contentLength() > maxBytes) throw HttpResponseTooLargeException(maxBytes)
    return utf8()
  }
  val declaredBytes = contentLength()
  if (declaredBytes > maxBytes) throw HttpResponseTooLargeException(maxBytes)
  val source = source()
  if (source.request(maxBytes + 1L)) throw HttpResponseTooLargeException(maxBytes)
  return source.readUtf8()
}

/** Byte-preserving counterpart used when an interceptor must replay the already-audited body. */
internal fun ResponseBody.readByteArrayLimited(maxBytes: Long): ByteArray {
  require(maxBytes >= 0L && maxBytes < Long.MAX_VALUE)
  val declaredBytes = contentLength()
  if (declaredBytes > maxBytes) throw HttpResponseTooLargeException(maxBytes)
  val source = source()
  if (source.request(maxBytes + 1L)) throw HttpResponseTooLargeException(maxBytes)
  return source.readByteArray()
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

/**
 * Awaits headers and consumes the response on [Dispatchers.IO].
 *
 * The second cancellable bridge is intentional. [awaitResponse] can cancel the socket while it is
 * waiting for headers, but its continuation is complete once headers arrive. Keeping another
 * continuation active around body consumption means leaving a screen still calls [Call.cancel],
 * which closes a slow/chunked body instead of occupying an OkHttp connection until callTimeout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun <T : Any> Call.awaitAndConsumeResponse(
  onCancellation: (T) -> Unit = {},
  consume: (Response) -> T,
): T {
  val ownsResult = AtomicBoolean(false)
  var produced: T? = null
  fun discardProducedResult() {
    if (ownsResult.compareAndSet(true, false)) {
      produced?.let { value -> runCatching { onCancellation(value) } }
    }
  }

  return try {
    val delivered = withContext(Dispatchers.IO) {
      awaitResponse().use { response ->
        suspendCancellableCoroutine { continuation ->
          continuation.invokeOnCancellation {
            cancel()
            discardProducedResult()
          }
          try {
            val value = consume(response)
            produced = value
            ownsResult.set(true)
            if (continuation.isActive) {
              continuation.resume(value) { discardProducedResult() }
            } else {
              discardProducedResult()
            }
          } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(error)
          }
        }
      }
    }
    // Ownership transfers to the caller only after withContext has delivered the value back across
    // its dispatcher boundary. Cancellation before that point is handled below.
    ownsResult.set(false)
    delivered
  } catch (cancellation: CancellationException) {
    discardProducedResult()
    throw cancellation
  }
}
