package com.stremioshell.host.tv.player

import com.stremioshell.host.tv.data.PlaybackUrlPolicy
import com.stremioshell.host.tv.data.PublicOnlyDns
import com.stremioshell.host.tv.data.SharedHttpClient
import com.stremioshell.host.tv.diagnostics.NebulaDiagnostics
import fi.iki.elonen.NanoHTTPD
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Proxy
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.Locale
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response as OkHttpResponse

/**
 * Loopback transport between libmpv and one public HTTPS stream.
 *
 * libmpv only sees a tokened 127.0.0.1 URL. OkHttp owns TLS, every DNS lookup and every redirect
 * hop, so the public-address policy cannot be bypassed after the initial URL check. Addon request
 * headers are sent only to the stream's exact original origin and never exposed to libmpv.
 */
internal class PlaybackProxyServer private constructor(
  private val client: OkHttpClient,
  private val evictPoolOnClose: Boolean,
  private val hlsMappingLimit: Int,
) : NanoHTTPD(LOOPBACK_HOST, 0), AutoCloseable {
  internal constructor() : this(defaultClient(), true, MAX_HLS_MAPPINGS)

  internal constructor(
    client: OkHttpClient,
    hlsMappingLimit: Int = MAX_HLS_MAPPINGS,
  ) : this(client, false, hlsMappingLimit)

  internal enum class ResourceKind {
    ROOT,
    PLAYLIST,
    SEGMENT,
    KEY,
    MAP,
  }

  internal data class HlsResourceKey(
    val url: HttpUrl,
    val kind: ResourceKind,
    val aes128: Aes128Verification? = null,
  )

  internal data class Aes128Verification(
    val keyUrl: HttpUrl,
    val ivHex: String,
  )

  internal class Resource(
    val token: String?,
    val url: HttpUrl,
    val kind: ResourceKind,
    val aes128: Aes128Verification? = null,
  ) {
    internal var directMediaVerified = false
  }

  internal class Target(
    val token: String,
    val originalUrl: HttpUrl,
    val requestHeaders: Map<String, String>,
    private val mappingLimit: Int = MAX_HLS_MAPPINGS,
  ) {
    val root = Resource(token = null, url = originalUrl, kind = ResourceKind.ROOT)

    private val calls = mutableSetOf<Call>()
    private val resourcesByKey = linkedMapOf<HlsResourceKey, Resource>()
    private val resourcesByToken = mutableMapOf<String, Resource>()
    private val pinnedKeys = mutableMapOf<HttpUrl, ByteArray>()
    private var retired = false

    init {
      require(mappingLimit > 0) { "HLS mapping limit must be positive" }
    }

    fun register(call: Call): Boolean = synchronized(this) {
      if (retired) return@synchronized false
      calls += call
      true
    }

    fun unregister(call: Call) {
      synchronized(this) { calls -= call }
    }

    fun resourceForToken(resourceToken: String): Resource? = synchronized(this) {
      if (retired) null else resourcesByToken[resourceToken]
    }

    @Throws(IOException::class)
    fun mapHlsResources(keys: Collection<HlsResourceKey>): Map<HlsResourceKey, Resource> =
      synchronized(this) {
        if (retired) throw TargetExpiredException()
        val uniqueKeys = LinkedHashSet(keys)
        val missing = uniqueKeys.filterNot(resourcesByKey::containsKey)
        if (resourcesByKey.size + missing.size > mappingLimit) {
          throw HlsLimitException("HLS resource mapping limit exceeded")
        }

        // Stage the complete batch before mutating either map. A rejected manifest can never
        // consume mapping capacity or leave a reachable route behind.
        val staged = linkedMapOf<HlsResourceKey, Resource>()
        val stagedTokens = mutableSetOf<String>()
        missing.forEach { key ->
          var resourceToken: String
          do {
            resourceToken = createToken()
          } while (resourceToken in resourcesByToken || !stagedTokens.add(resourceToken))
          staged[key] = Resource(resourceToken, key.url, key.kind, key.aes128)
        }
        staged.forEach { (key, resource) ->
          resourcesByKey[key] = resource
          resourcesByToken[checkNotNull(resource.token)] = resource
        }
        uniqueKeys.associateWith { key -> resourcesByKey.getValue(key) }
      }

    fun hasVerifiedDirectMedia(resource: Resource): Boolean = synchronized(this) {
      !retired && owns(resource) && resource.directMediaVerified
    }

    fun verifyDirectMedia(resource: Resource): Boolean = synchronized(this) {
      if (retired || !owns(resource)) return@synchronized false
      resource.directMediaVerified = true
      true
    }

    fun pinnedKey(url: HttpUrl): ByteArray? = synchronized(this) {
      if (retired) null else pinnedKeys[url]?.copyOf()
    }

    @Throws(IOException::class)
    fun pinKey(url: HttpUrl, bytes: ByteArray): ByteArray = synchronized(this) {
      if (retired) throw TargetExpiredException()
      val pinned = pinnedKeys.getOrPut(url) { bytes.copyOf() }
      pinned.copyOf()
    }

    fun retire() {
      val toCancel = synchronized(this) {
        if (retired) return
        retired = true
        resourcesByKey.clear()
        resourcesByToken.clear()
        pinnedKeys.values.forEach { bytes -> bytes.fill(0) }
        pinnedKeys.clear()
        calls.toList().also { calls.clear() }
      }
      toCancel.forEach(Call::cancel)
    }

    private fun owns(resource: Resource): Boolean =
      resource === root || resource.token?.let { resourcesByToken[it] === resource } == true
  }

  private data class RemoteCall(
    val response: OkHttpResponse,
    val call: Call,
  )

  private val target = AtomicReference<Target?>()
  private val connections = BoundedAsyncRunner(MAX_CONNECTIONS)

  init {
    setAsyncRunner(connections)
  }

  /** Starts lazily on the mpv worker and replaces any earlier stream atomically. */
  @Synchronized
  @Throws(IOException::class)
  fun open(remoteUrl: String, requestHeaders: Map<String, String>): String {
    val canonical = PlaybackUrlPolicy.allowedUrlOrNull(remoteUrl)
      ?: throw IOException("Playback URL is not permitted")
    val parsed = canonical.toHttpUrlOrNull() ?: throw IOException("Playback URL is malformed")
    val next = Target(
      token = createToken(),
      originalUrl = parsed,
      requestHeaders = StreamRequestHeaders.sanitize(requestHeaders),
      mappingLimit = hlsMappingLimit,
    )
    target.getAndSet(next)?.retire()
    if (!isAlive) {
      try {
        start(SOCKET_READ_TIMEOUT, false)
      } catch (error: IOException) {
        target.compareAndSet(next, null)
        next.retire()
        throw error
      }
    }
    return rootUrl(next)
  }

  override fun serve(session: IHTTPSession): Response {
    if (!isLoopback(session.remoteIpAddress)) return response(Response.Status.FORBIDDEN, "Forbidden")
    if (session.method != Method.GET && session.method != Method.HEAD) {
      return response(Response.Status.METHOD_NOT_ALLOWED, "Method not allowed")
        .also { it.addHeader("Allow", "GET, HEAD") }
    }
    val current = target.get() ?: return response(Response.Status.GONE, "Stream expired")
    val resource = resolveResource(session.uri, current) ?: return when {
      session.uri.startsWith("/stream/") && !session.uri.startsWith(rootPath(current)) ->
        response(Response.Status.GONE, "Stream expired")
      else -> response(Response.Status.NOT_FOUND, "Not found")
    }
    return try {
      proxy(session, current, resource)
    } catch (_: TargetExpiredException) {
      response(Response.Status.GONE, "Stream expired")
    } catch (_: HlsLimitException) {
      NebulaDiagnostics.record("player", "HLS playback limit rejected")
      response(ProxyStatus(413, "Payload Too Large"), "HLS resource exceeds safety limits")
    } catch (_: UnsupportedPlaybackPayloadException) {
      NebulaDiagnostics.record("player", "unsupported playback payload rejected")
      response(Response.Status.UNSUPPORTED_MEDIA_TYPE, "Playback payload is unsupported")
    } catch (error: IOException) {
      NebulaDiagnostics.record(
        "player",
        "playback transport failed: ${error.javaClass.simpleName}",
      )
      response(ProxyStatus(502, "Bad Gateway"), "Stream connection failed")
    }
  }

  override fun close() {
    target.getAndSet(null)?.retire()
    stop()
    // The production client owns this pool. Eviction also makes a closed Activity incapable of
    // leaving authenticated playback sockets reusable by a later player generation.
    if (evictPoolOnClose) client.connectionPool.evictAll()
  }

  private fun proxy(
    session: IHTTPSession,
    currentTarget: Target,
    resource: Resource,
  ): Response {
    val method = session.method
    if (resource.kind == ResourceKind.KEY) {
      currentTarget.pinnedKey(resource.url)?.let { pinned ->
        val body = if (method == Method.HEAD) ByteArray(0) else pinned
        return newFixedLengthResponse(
          Response.Status.OK,
          "application/octet-stream",
          ByteArrayInputStream(body),
          if (method == Method.HEAD) pinned.size.toLong() else body.size.toLong(),
        ).also { it.addHeader("Cache-Control", "no-store") }
      }
    }
    val remoteCall = executeRemote(
      target = currentTarget,
      resource = resource,
      method = method,
      localHeaders = session.headers,
    )
    return try {
      proxyRemote(method, currentTarget, resource, remoteCall)
    } catch (error: IOException) {
      releaseRemote(currentTarget, remoteCall)
      throw error
    } catch (error: RuntimeException) {
      releaseRemote(currentTarget, remoteCall)
      throw error
    }
  }

  @Throws(IOException::class)
  private fun proxyRemote(
    method: Method,
    currentTarget: Target,
    resource: Resource,
    remoteCall: RemoteCall,
  ): Response {
    val remote = remoteCall.response
    val contentType = remote.header("Content-Type") ?: "application/octet-stream"
    val declaration = PlaybackPayloadPolicy.classify(remote.request.url, contentType)
    val hls = when (resource.kind) {
      ResourceKind.ROOT -> declaration == PlaybackPayloadPolicy.Declaration.HLS
      ResourceKind.PLAYLIST -> declaration != PlaybackPayloadPolicy.Declaration.UNSUPPORTED
      ResourceKind.SEGMENT, ResourceKind.KEY, ResourceKind.MAP -> false
    }
    if (
      declaration == PlaybackPayloadPolicy.Declaration.UNSUPPORTED ||
      (!hls && declaration == PlaybackPayloadPolicy.Declaration.HLS)
    ) {
      throw UnsupportedPlaybackPayloadException()
    }

    if (method == Method.HEAD) {
      val length = declaredLength(remote).coerceAtLeast(0L)
      releaseRemote(currentTarget, remoteCall)
      return if (hls) {
        newFixedLengthResponse(
          remoteStatus(remote),
          HLS_CONTENT_TYPE,
          ByteArrayInputStream(ByteArray(0)),
          0L,
        ).also { it.addHeader("Cache-Control", "no-store") }
      } else {
        newFixedLengthResponse(
          remoteStatus(remote),
          contentType,
          ByteArrayInputStream(ByteArray(0)),
          length,
        ).also { copyResponseHeaders(remote, it) }
      }
    }

    if (remote.body == null) {
      releaseRemote(currentTarget, remoteCall)
      return newFixedLengthResponse(
        remoteStatus(remote),
        if (hls) HLS_CONTENT_TYPE else contentType,
        ByteArrayInputStream(ByteArray(0)),
        0L,
      ).also { it.addHeader("Cache-Control", "no-store") }
    }
    if (remote.code !in 200..299) {
      val status = remoteStatus(remote)
      val contentRange = remote.header("Content-Range")
      releaseRemote(currentTarget, remoteCall)
      return newFixedLengthResponse(
        status,
        "text/plain; charset=utf-8",
        ByteArrayInputStream(ByteArray(0)),
        0L,
      ).also { local ->
        contentRange?.let { local.addHeader("Content-Range", it) }
        local.addHeader("Cache-Control", "no-store")
      }
    }
    if (hls) return proxyHls(currentTarget, remoteCall)
    if (resource.kind == ResourceKind.KEY) {
      return proxyKey(currentTarget, resource, remoteCall, contentType)
    }
    return proxyDirectMedia(
      currentTarget,
      resource,
      remoteCall,
      contentType,
    )
  }

  @Throws(IOException::class)
  private fun proxyHls(currentTarget: Target, remoteCall: RemoteCall): Response {
    val remote = remoteCall.response
    if (remote.code != 200 || !responseStartsAtZero(remote)) {
      throw UnsupportedPlaybackPayloadException()
    }
    val encoding = remote.header("Content-Encoding")
    if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
      throw UnsupportedPlaybackPayloadException()
    }
    rejectDeclaredOversize(remote, MAX_HLS_MANIFEST_BYTES.toLong())
    val source = readBoundedBytes(
      checkNotNull(remote.body).byteStream(),
      MAX_HLS_MANIFEST_BYTES,
      "HLS manifest is too large",
    )
    return rewrittenHlsResponse(currentTarget, remoteCall, source)
  }

  @Throws(IOException::class)
  private fun rewrittenHlsResponse(
    currentTarget: Target,
    remoteCall: RemoteCall,
    source: ByteArray,
  ): Response {
    val remote = remoteCall.response
    val rewritten = HlsPlaylistRewriter.rewrite(
      bytes = source,
      baseUrl = remote.request.url,
      mapResources = currentTarget::mapHlsResources,
      localUrl = { mapped -> localResourceUrl(currentTarget, mapped) },
    )
    releaseRemote(currentTarget, remoteCall)
    return newFixedLengthResponse(
      remoteStatus(remote),
      HLS_CONTENT_TYPE,
      ByteArrayInputStream(rewritten),
      rewritten.size.toLong(),
    ).also { it.addHeader("Cache-Control", "no-store") }
  }

  @Throws(IOException::class)
  private fun proxyDirectMedia(
    currentTarget: Target,
    resource: Resource,
    remoteCall: RemoteCall,
    contentType: String,
  ): Response {
    val remote = remoteCall.response
    val maxBytes = when (resource.kind) {
      ResourceKind.SEGMENT -> MAX_SEGMENT_RESPONSE_BYTES
      ResourceKind.MAP -> MAX_MAP_RESPONSE_BYTES
      ResourceKind.ROOT -> Long.MAX_VALUE
      else -> throw UnsupportedPlaybackPayloadException()
    }
    rejectDeclaredOversize(remote, maxBytes)
    val upstream = BufferedInputStream(checkNotNull(remote.body).byteStream())
    val verified = if (responseStartsAtZero(remote)) {
      upstream.mark(SNIFF_BYTES + 1)
      val prefix = readPrefix(upstream, SNIFF_BYTES)
      upstream.reset()
      if (resource.kind == ResourceKind.ROOT && hasExactHlsSignature(prefix)) {
        val encoding = remote.header("Content-Encoding")
        if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
          throw UnsupportedPlaybackPayloadException()
        }
        rejectDeclaredOversize(remote, MAX_HLS_MANIFEST_BYTES.toLong())
        val source = readBoundedBytes(
          upstream,
          MAX_HLS_MANIFEST_BYTES,
          "HLS manifest is too large",
        )
        return rewrittenHlsResponse(currentTarget, remoteCall, source)
      }
      verifyMediaPrefix(currentTarget, resource, prefix) &&
        currentTarget.verifyDirectMedia(resource)
    } else {
      // A separate byte-zero preflight would create a time-of-check/time-of-use gap: an
      // untrusted server could return valid media to the preflight and a manifest to this call.
      // Range remains available after this exact resource has passed byte-zero verification.
      currentTarget.hasVerifiedDirectMedia(resource)
    }
    if (!verified) {
      upstream.close()
      throw UnsupportedPlaybackPayloadException()
    }

    val limited = if (maxBytes == Long.MAX_VALUE) {
      upstream
    } else {
      BoundedInputStream(upstream, maxBytes)
    }
    val stream = ClosingInputStream(limited) { releaseRemote(currentTarget, remoteCall) }
    val length = declaredLength(remote)
    val localResponse = if (length >= 0L) {
      newFixedLengthResponse(remoteStatus(remote), contentType, stream, length)
    } else {
      newChunkedResponse(remoteStatus(remote), contentType, stream)
    }
    copyResponseHeaders(remote, localResponse)
    return localResponse
  }

  @Throws(IOException::class)
  private fun verifyMediaPrefix(
    currentTarget: Target,
    resource: Resource,
    prefix: ByteArray,
  ): Boolean {
    val plainPrefix = resource.aes128?.let { verification ->
      val key = fetchAes128Key(currentTarget, verification.keyUrl)
      try {
        decryptAes128Prefix(prefix, key, verification.ivHex)
      } finally {
        key.fill(0)
      }
    } ?: prefix
    return PlaybackPayloadPolicy.hasDirectMediaSignature(
      plainPrefix,
      allowTransportStream = resource.kind == ResourceKind.SEGMENT,
    )
  }

  @Throws(IOException::class)
  private fun proxyKey(
    currentTarget: Target,
    resource: Resource,
    remoteCall: RemoteCall,
    contentType: String,
  ): Response {
    val remote = remoteCall.response
    if (remote.code != 200 || !responseStartsAtZero(remote)) {
      throw UnsupportedPlaybackPayloadException()
    }
    val encoding = remote.header("Content-Encoding")
    if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
      throw UnsupportedPlaybackPayloadException()
    }
    rejectDeclaredOversize(remote, MAX_KEY_RESPONSE_BYTES)
    val received = readBoundedBytes(
      remote.body?.byteStream() ?: throw UnsupportedPlaybackPayloadException(),
      MAX_KEY_RESPONSE_BYTES.toInt(),
      "HLS key response is too large",
    )
    // Only AES-sized key material needs a stable snapshot for segment verification. Pinning every
    // bounded license/key response would multiply the per-response cap by the mapping ceiling.
    val served = if (received.size == AES_128_KEY_BYTES) {
      currentTarget.pinKey(resource.url, received).also { received.fill(0) }
    } else {
      received
    }
    releaseRemote(currentTarget, remoteCall)
    return newFixedLengthResponse(
      remoteStatus(remote),
      contentType,
      ByteArrayInputStream(served),
      served.size.toLong(),
    ).also { it.addHeader("Cache-Control", "no-store") }
  }

  @Throws(IOException::class)
  private fun fetchAes128Key(currentTarget: Target, keyUrl: HttpUrl): ByteArray {
    currentTarget.pinnedKey(keyUrl)?.let { pinned ->
      if (pinned.size != AES_128_KEY_BYTES) throw UnsupportedPlaybackPayloadException()
      return pinned
    }
    val keyResource = Resource(token = null, url = keyUrl, kind = ResourceKind.KEY)
    val remoteCall = executeRemote(
      target = currentTarget,
      resource = keyResource,
      method = Method.GET,
      localHeaders = emptyMap(),
    )
    return try {
      val remote = remoteCall.response
      val type = remote.header("Content-Type") ?: "application/octet-stream"
      if (
        remote.code != 200 ||
        !responseStartsAtZero(remote) ||
        PlaybackPayloadPolicy.classify(remote.request.url, type) !=
        PlaybackPayloadPolicy.Declaration.GENERIC
      ) {
        throw UnsupportedPlaybackPayloadException()
      }
      rejectDeclaredOversize(remote, MAX_KEY_RESPONSE_BYTES)
      val key = readBoundedBytes(
        remote.body?.byteStream() ?: throw UnsupportedPlaybackPayloadException(),
        MAX_KEY_RESPONSE_BYTES.toInt(),
        "HLS key response is too large",
      )
      if (key.size != AES_128_KEY_BYTES) throw UnsupportedPlaybackPayloadException()
      currentTarget.pinKey(keyUrl, key).also { key.fill(0) }
    } finally {
      releaseRemote(currentTarget, remoteCall)
    }
  }

  private fun decryptAes128Prefix(prefix: ByteArray, key: ByteArray, ivHex: String): ByteArray {
    val completeBytes = prefix.size - prefix.size % AES_BLOCK_BYTES
    if (completeBytes < AES_BLOCK_BYTES) throw UnsupportedPlaybackPayloadException()
    return try {
      Cipher.getInstance("AES/CBC/NoPadding").run {
        init(
          Cipher.DECRYPT_MODE,
          SecretKeySpec(key, "AES"),
          IvParameterSpec(ivHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()),
        )
        doFinal(prefix, 0, completeBytes)
      }
    } catch (_: Exception) {
      throw UnsupportedPlaybackPayloadException()
    }
  }

  @Throws(IOException::class)
  private fun executeRemote(
    target: Target,
    resource: Resource,
    method: Method,
    localHeaders: Map<String, String>,
  ): RemoteCall {
    var currentUrl = resource.url
    repeat(MAX_REDIRECTS + 1) { hop ->
      val request = Request.Builder()
        .url(currentUrl)
        .cacheControl(NO_STORE)
        .header("Accept-Encoding", "identity")
        .apply {
          StreamRequestHeaders.forPlaybackHop(
            target.originalUrl.toString(),
            currentUrl.toString(),
            target.requestHeaders,
          ).forEach(::header)
          if (resource.kind != ResourceKind.PLAYLIST && resource.kind != ResourceKind.KEY) {
            FORWARDED_PLAYER_HEADERS.forEach { name ->
              localHeaders[name]
                ?.takeIf(::isSafeForwardedValue)
                ?.let { value -> header(name, value) }
            }
          }
          if (method == Method.HEAD) head() else get()
        }
        .build()
      val call = client.newCall(request)
      if (!target.register(call)) {
        call.cancel()
        throw TargetExpiredException()
      }
      val response = try {
        call.execute()
      } catch (error: IOException) {
        target.unregister(call)
        if (call.isCanceled()) throw TargetExpiredException()
        throw error
      }
      if (response.code !in REDIRECT_CODES) return RemoteCall(response, call)

      target.unregister(call)
      val location = response.header("Location")
      response.close()
      if (hop >= MAX_REDIRECTS) throw IOException("Too many playback redirects")
      val resolved = location?.let(currentUrl::resolve)
        ?: throw IOException("Playback redirect is missing a valid location")
      val canonical = PlaybackUrlPolicy.allowedUrlOrNull(resolved.toString())
        ?: throw IOException("Playback redirect target is not permitted")
      currentUrl = canonical.toHttpUrlOrNull()
        ?: throw IOException("Playback redirect target is malformed")
    }
    throw IOException("Too many playback redirects")
  }

  private fun resolveResource(uri: String, currentTarget: Target): Resource? {
    if (uri == rootPath(currentTarget)) return currentTarget.root
    val prefix = "${rootPath(currentTarget)}/resource/"
    if (!uri.startsWith(prefix)) return null
    val resourceToken = uri.removePrefix(prefix)
    if (!TOKEN_PATTERN.matches(resourceToken)) return null
    return currentTarget.resourceForToken(resourceToken)
  }

  private fun rootPath(currentTarget: Target): String = "/stream/${currentTarget.token}"

  private fun rootUrl(currentTarget: Target): String =
    "http://$LOOPBACK_HOST:$listeningPort${rootPath(currentTarget)}"

  private fun localResourceUrl(currentTarget: Target, resource: Resource): String =
    "${rootUrl(currentTarget)}/resource/${checkNotNull(resource.token)}"

  private fun releaseRemote(target: Target, remoteCall: RemoteCall) {
    remoteCall.response.close()
    target.unregister(remoteCall.call)
  }

  private fun rejectDeclaredOversize(response: OkHttpResponse, maxBytes: Long) {
    if (maxBytes == Long.MAX_VALUE) return
    if (declaredLength(response) > maxBytes) {
      throw HlsLimitException("Playback response is too large")
    }
  }

  private fun declaredLength(response: OkHttpResponse): Long =
    response.header("Content-Length")?.toLongOrNull()
      ?: response.body?.contentLength()
      ?: 0L

  private fun remoteStatus(remote: OkHttpResponse): Response.IStatus =
    Response.Status.lookup(remote.code)
      ?: ProxyStatus(remote.code, remote.message.ifBlank { "Upstream response" })

  private fun copyResponseHeaders(remote: OkHttpResponse, local: Response) {
    RESPONSE_HEADERS.forEach { name ->
      remote.headers.values(name).forEach { value -> local.addHeader(name, value) }
    }
    local.addHeader("Cache-Control", "no-store")
  }

  private fun response(status: Response.IStatus, message: String): Response =
    newFixedLengthResponse(status, "text/plain; charset=utf-8", message)
      .also { it.addHeader("Cache-Control", "no-store") }

  private fun isLoopback(raw: String?): Boolean = runCatching {
    raw != null && InetAddress.getByName(raw).isLoopbackAddress
  }.getOrDefault(false)

  private fun responseStartsAtZero(response: OkHttpResponse): Boolean {
    if (response.code != 206) return true
    return response.header("Content-Range")
      ?.startsWith("bytes 0-", ignoreCase = true) == true
  }

  private fun hasExactHlsSignature(prefix: ByteArray): Boolean {
    val marker = "#EXTM3U".toByteArray(StandardCharsets.US_ASCII)
    if (prefix.size <= marker.size || !prefix.copyOf(marker.size).contentEquals(marker)) return false
    return prefix[marker.size] == '\n'.code.toByte() || prefix[marker.size] == '\r'.code.toByte()
  }

  private fun readPrefix(input: InputStream, limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var offset = 0
    while (offset < buffer.size) {
      val read = input.read(buffer, offset, buffer.size - offset)
      if (read <= 0) break
      offset += read
    }
    return if (offset == buffer.size) buffer else buffer.copyOf(offset)
  }

  private data class ProxyStatus(
    private val code: Int,
    private val reason: String,
  ) : Response.IStatus {
    override fun getRequestStatus(): Int = code
    override fun getDescription(): String = "$code $reason"
  }

  private class ClosingInputStream(
    input: InputStream,
    private val onClose: () -> Unit,
  ) : FilterInputStream(input) {
    private var closed = false

    override fun close() {
      if (closed) return
      closed = true
      try {
        super.close()
      } finally {
        onClose()
      }
    }
  }

  private class BoundedInputStream(
    input: InputStream,
    private val maxBytes: Long,
  ) : FilterInputStream(input) {
    private var consumed = 0L

    override fun read(): Int {
      if (consumed >= maxBytes) return overflowOrEnd()
      return super.read().also { value -> if (value >= 0) consumed++ }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (length == 0) return 0
      if (consumed >= maxBytes) return overflowOrEnd()
      val permitted = minOf(length.toLong(), maxBytes - consumed).toInt()
      return super.read(buffer, offset, permitted).also { read ->
        if (read > 0) consumed += read
      }
    }

    private fun overflowOrEnd(): Int {
      if (super.read() < 0) return -1
      throw HlsLimitException("Playback response is too large")
    }
  }

  private class BoundedAsyncRunner(maxConnections: Int) : AsyncRunner {
    private val running = Collections.synchronizedList(mutableListOf<ClientHandler>())
    private val threadIndex = AtomicInteger()
    private val executor = ThreadPoolExecutor(
      0,
      maxConnections,
      10L,
      TimeUnit.SECONDS,
      SynchronousQueue(),
    ) { runnable ->
      Thread(runnable, "nebula-playback-proxy-${threadIndex.incrementAndGet()}").apply {
        isDaemon = true
      }
    }

    override fun exec(handler: ClientHandler) {
      running.add(handler)
      try {
        executor.execute(handler)
      } catch (_: RejectedExecutionException) {
        running.remove(handler)
        handler.close()
      }
    }

    override fun closed(handler: ClientHandler) {
      running.remove(handler)
    }

    override fun closeAll() {
      val snapshot = synchronized(running) { ArrayList(running) }
      snapshot.forEach { runCatching { it.close() } }
      executor.shutdown()
    }
  }

  companion object {
    private const val LOOPBACK_HOST = "127.0.0.1"
    private const val TOKEN_BYTES = 24
    private const val MAX_CONNECTIONS = 8
    private const val MAX_REDIRECTS = 5
    private const val SNIFF_BYTES = 2 * 1024
    private const val MAX_HLS_MAPPINGS = 8 * 1024
    private const val MAX_HLS_MANIFEST_BYTES = 512 * 1024
    private const val MAX_KEY_RESPONSE_BYTES = 64L * 1024L
    private const val MAX_MAP_RESPONSE_BYTES = 32L * 1024L * 1024L
    private const val MAX_SEGMENT_RESPONSE_BYTES = 512L * 1024L * 1024L
    private const val AES_128_KEY_BYTES = 16
    private const val AES_BLOCK_BYTES = 16
    private const val HLS_CONTENT_TYPE = "application/vnd.apple.mpegurl; charset=utf-8"
    private val SECURE_RANDOM = SecureRandom()
    private val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{32}")
    private val NO_STORE = CacheControl.Builder().noCache().noStore().build()
    private val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
    private val FORWARDED_PLAYER_HEADERS = setOf(
      "range",
      "if-range",
      "if-none-match",
      "if-modified-since",
    )
    private val RESPONSE_HEADERS = setOf(
      "Accept-Ranges",
      "Content-Range",
      "Content-Encoding",
      "ETag",
      "Last-Modified",
      "Expires",
      "Content-Disposition",
    )

    private fun createToken(): String {
      val bytes = ByteArray(TOKEN_BYTES)
      SECURE_RANDOM.nextBytes(bytes)
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun isSafeForwardedValue(value: String): Boolean =
      value.length <= 8 * 1024 && value.none { it.code < 0x20 || it.code == 0x7f }

    private fun defaultClient(): OkHttpClient =
      PlaybackProxyHttpClient.create(SharedHttpClient.client)
  }
}

private class TargetExpiredException : IOException("Playback target expired")

private class HlsLimitException(message: String) : IOException(message)

private class UnsupportedPlaybackPayloadException : IOException("Playback payload is unsupported")

internal object PlaybackProxyHttpClient {
  fun create(base: OkHttpClient): OkHttpClient = base.newBuilder()
    .cache(null)
    // newBuilder otherwise shares the application's existing pool. A connection established by
    // an ordinary client can have skipped PublicOnlyDns and must never be reused for playback.
    .connectionPool(ConnectionPool())
    // A configured HTTP proxy resolves the destination itself, outside PublicOnlyDns. Playback
    // must connect directly so every destination address is checked in this process.
    .proxy(Proxy.NO_PROXY)
    .dns(PublicOnlyDns())
    .followRedirects(false)
    .followSslRedirects(false)
    .callTimeout(0, TimeUnit.MILLISECONDS)
    .build()
}

/** Strict, bounded HLS parsing and URI rewriting. */
private object HlsPlaylistRewriter {
  private const val MAX_LINES = 16 * 1024
  private const val MAX_LINE_CHARS = 16 * 1024
  private const val MAX_URI_ENTRIES = 4 * 1024
  private const val MAX_REWRITTEN_BYTES = 1024 * 1024

  private data class Replacement(
    val lineIndex: Int,
    val start: Int,
    val end: Int,
    val key: PlaybackProxyServer.HlsResourceKey,
  )

  private data class Attribute(
    val name: String,
    val value: String,
    val valueStart: Int,
    val valueEnd: Int,
    val quoted: Boolean,
  )

  private data class Aes128Template(
    val keyUrl: HttpUrl,
    val explicitIvHex: String?,
  ) {
    fun forSequence(sequence: Long): PlaybackProxyServer.Aes128Verification =
      PlaybackProxyServer.Aes128Verification(
        keyUrl = keyUrl,
        ivHex = explicitIvHex ?: sequence.toString(16).padStart(32, '0'),
      )
  }

  @Throws(IOException::class)
  fun rewrite(
    bytes: ByteArray,
    baseUrl: HttpUrl,
    mapResources: (Collection<PlaybackProxyServer.HlsResourceKey>) ->
      Map<PlaybackProxyServer.HlsResourceKey, PlaybackProxyServer.Resource>,
    localUrl: (PlaybackProxyServer.Resource) -> String,
  ): ByteArray {
    val decoded = decodeUtf8(bytes).removePrefix("\uFEFF")
    val lines = decoded
      .replace("\r\n", "\n")
      .replace('\r', '\n')
      .split('\n')
      .toMutableList()
    if (lines.size > MAX_LINES) throw HlsLimitException("HLS manifest has too many lines")
    if (lines.firstOrNull() != "#EXTM3U") throw UnsupportedPlaybackPayloadException()

    val replacements = mutableListOf<Replacement>()
    var expectVariantUri = false
    var activeAes128: Aes128Template? = null
    var mediaSequence = 0L
    var segmentIndex = 0L
    lines.forEachIndexed { index, line ->
      if (line.length > MAX_LINE_CHARS) throw HlsLimitException("HLS manifest line is too long")
      if (line.any { it.code < 0x20 || it.code == 0x7f }) {
        throw UnsupportedPlaybackPayloadException()
      }
      if (line.isEmpty()) return@forEachIndexed

      if (!line.startsWith('#')) {
        val kind = if (expectVariantUri) {
          PlaybackProxyServer.ResourceKind.PLAYLIST
        } else {
          PlaybackProxyServer.ResourceKind.SEGMENT
        }
        expectVariantUri = false
        val aes128 = if (kind == PlaybackProxyServer.ResourceKind.SEGMENT) {
          if (segmentIndex > Long.MAX_VALUE - mediaSequence) {
            throw UnsupportedPlaybackPayloadException()
          }
          activeAes128?.forSequence(mediaSequence + segmentIndex)
        } else {
          null
        }
        replacements += Replacement(
          index,
          0,
          line.length,
          resourceKey(baseUrl, line, kind, aes128),
        )
        if (kind == PlaybackProxyServer.ResourceKind.SEGMENT) segmentIndex++
        return@forEachIndexed
      }

      if (expectVariantUri) throw UnsupportedPlaybackPayloadException()
      when (line.substringBefore(':')) {
        "#EXT-X-STREAM-INF" -> {
          rejectUriAttributes(line)
          expectVariantUri = true
        }
        "#EXT-X-MEDIA" -> addAttributeReplacement(
          lines,
          index,
          baseUrl,
          PlaybackProxyServer.ResourceKind.PLAYLIST,
          required = false,
          replacements,
        )
        "#EXT-X-I-FRAME-STREAM-INF",
        "#EXT-X-IMAGE-STREAM-INF",
        "#EXT-X-RENDITION-REPORT",
        -> addAttributeReplacement(
          lines,
          index,
          baseUrl,
          PlaybackProxyServer.ResourceKind.PLAYLIST,
          required = true,
          replacements,
        )
        "#EXT-X-KEY" -> activeAes128 = addKeyReplacement(
          lines,
          index,
          baseUrl,
          sessionKey = false,
          replacements,
        )
        "#EXT-X-SESSION-KEY" -> addKeyReplacement(
          lines,
          index,
          baseUrl,
          sessionKey = true,
          replacements,
        )
        "#EXT-X-MAP" -> {
          val aes128 = activeAes128?.let { template ->
            if (template.explicitIvHex == null) throw UnsupportedPlaybackPayloadException()
            template.forSequence(mediaSequence)
          }
          addAttributeReplacement(
            lines,
            index,
            baseUrl,
            PlaybackProxyServer.ResourceKind.MAP,
            required = true,
            replacements,
            aes128,
          )
        }
        "#EXT-X-PART" -> {
          if (activeAes128 != null) throw UnsupportedPlaybackPayloadException()
          addAttributeReplacement(
            lines,
            index,
            baseUrl,
            PlaybackProxyServer.ResourceKind.SEGMENT,
            required = true,
            replacements,
          )
        }
        "#EXT-X-PRELOAD-HINT" -> {
          if (activeAes128 != null) throw UnsupportedPlaybackPayloadException()
          addPreloadReplacement(lines, index, baseUrl, replacements)
        }
        "#EXT-X-MEDIA-SEQUENCE" -> {
          if (segmentIndex != 0L) throw UnsupportedPlaybackPayloadException()
          mediaSequence = line.substringAfter(':', missingDelimiterValue = "")
            .takeIf { value -> value.isNotEmpty() && value.all(Char::isDigit) }
            ?.toLongOrNull()
            ?: throw UnsupportedPlaybackPayloadException()
        }
        "#EXT-X-BYTERANGE" -> {
          // Byte-range playlist entries can make one opaque route represent unrelated offsets.
          // Keep ordinary HTTP Range support, but reject this manifest-level aliasing.
          throw UnsupportedPlaybackPayloadException()
        }
        else -> rejectUriAttributes(line)
      }
    }
    if (expectVariantUri) throw UnsupportedPlaybackPayloadException()
    if (replacements.size > MAX_URI_ENTRIES) {
      throw HlsLimitException("HLS manifest has too many URI entries")
    }

    val mapped = mapResources(replacements.map(Replacement::key))
    replacements.groupBy(Replacement::lineIndex).forEach { (lineIndex, entries) ->
      var rewritten = lines[lineIndex]
      entries.sortedByDescending(Replacement::start).forEach { replacement ->
        val resource = mapped[replacement.key] ?: throw TargetExpiredException()
        rewritten = rewritten.replaceRange(
          replacement.start,
          replacement.end,
          localUrl(resource),
        )
      }
      lines[lineIndex] = rewritten
    }
    val output = lines.joinToString("\n").toByteArray(StandardCharsets.UTF_8)
    if (output.size > MAX_REWRITTEN_BYTES) {
      throw HlsLimitException("Rewritten HLS manifest is too large")
    }
    return output
  }

  private fun addKeyReplacement(
    lines: List<String>,
    index: Int,
    baseUrl: HttpUrl,
    sessionKey: Boolean,
    replacements: MutableList<Replacement>,
  ): Aes128Template? {
    val attributes = parseAttributes(lines[index])
    rejectByteRangeAttributes(attributes)
    val method = attributes.singleOrNull { it.name == "METHOD" }?.value
      ?: throw UnsupportedPlaybackPayloadException()
    val uri = attributes.singleOrNull { it.name == "URI" }
    rejectUnexpectedUriAttributes(attributes)
    if (method == "NONE") {
      if (sessionKey || uri != null) throw UnsupportedPlaybackPayloadException()
      return null
    }
    val requiredUri = uri ?: throw UnsupportedPlaybackPayloadException()
    val key = addReplacement(
      lines[index],
      index,
      baseUrl,
      PlaybackProxyServer.ResourceKind.KEY,
      requiredUri,
      replacements,
    )
    if (sessionKey || method != "AES-128") return null
    val keyFormat = attributes.singleOrNull { it.name == "KEYFORMAT" }?.value
    if (keyFormat != null && keyFormat != "identity") {
      throw UnsupportedPlaybackPayloadException()
    }
    val iv = attributes.singleOrNull { it.name == "IV" }?.value?.let(::canonicalIv)
    return Aes128Template(key.url, iv)
  }

  private fun addPreloadReplacement(
    lines: List<String>,
    index: Int,
    baseUrl: HttpUrl,
    replacements: MutableList<Replacement>,
  ) {
    val attributes = parseAttributes(lines[index])
    rejectByteRangeAttributes(attributes)
    val type = attributes.singleOrNull { it.name == "TYPE" }?.value
      ?: throw UnsupportedPlaybackPayloadException()
    val kind = when (type) {
      "PART" -> PlaybackProxyServer.ResourceKind.SEGMENT
      "MAP" -> PlaybackProxyServer.ResourceKind.MAP
      else -> throw UnsupportedPlaybackPayloadException()
    }
    val uri = attributes.singleOrNull { it.name == "URI" }
      ?: throw UnsupportedPlaybackPayloadException()
    rejectUnexpectedUriAttributes(attributes)
    addReplacement(lines[index], index, baseUrl, kind, uri, replacements)
  }

  private fun addAttributeReplacement(
    lines: List<String>,
    index: Int,
    baseUrl: HttpUrl,
    kind: PlaybackProxyServer.ResourceKind,
    required: Boolean,
    replacements: MutableList<Replacement>,
    aes128: PlaybackProxyServer.Aes128Verification? = null,
  ) {
    val attributes = parseAttributes(lines[index])
    rejectByteRangeAttributes(attributes)
    val uri = attributes.singleOrNull { it.name == "URI" }
    rejectUnexpectedUriAttributes(attributes)
    if (uri == null) {
      if (required) throw UnsupportedPlaybackPayloadException()
      return
    }
    addReplacement(lines[index], index, baseUrl, kind, uri, replacements, aes128)
  }

  private fun addReplacement(
    line: String,
    lineIndex: Int,
    baseUrl: HttpUrl,
    kind: PlaybackProxyServer.ResourceKind,
    attribute: Attribute,
    replacements: MutableList<Replacement>,
    aes128: PlaybackProxyServer.Aes128Verification? = null,
  ): PlaybackProxyServer.HlsResourceKey {
    if (!attribute.quoted) throw UnsupportedPlaybackPayloadException()
    if (line.substring(attribute.valueStart, attribute.valueEnd) != attribute.value) {
      throw UnsupportedPlaybackPayloadException()
    }
    val key = resourceKey(baseUrl, attribute.value, kind, aes128)
    replacements += Replacement(
      lineIndex,
      attribute.valueStart,
      attribute.valueEnd,
      key,
    )
    return key
  }

  private fun resourceKey(
    baseUrl: HttpUrl,
    raw: String,
    kind: PlaybackProxyServer.ResourceKind,
    aes128: PlaybackProxyServer.Aes128Verification? = null,
  ): PlaybackProxyServer.HlsResourceKey {
    if (
      raw.isEmpty() ||
      raw.length > PlaybackUrlPolicy.MAX_URL_CHARS ||
      raw.any { it.isWhitespace() || it.isISOControl() } ||
      '\\' in raw ||
      "{$" in raw
    ) {
      throw UnsupportedPlaybackPayloadException()
    }
    val resolved = baseUrl.resolve(raw) ?: throw UnsupportedPlaybackPayloadException()
    if (
      resolved.fragment != null ||
      resolved.username.isNotEmpty() ||
      resolved.password.isNotEmpty()
    ) {
      throw UnsupportedPlaybackPayloadException()
    }
    val canonical = PlaybackUrlPolicy.allowedUrlOrNull(resolved.toString())
      ?: throw UnsupportedPlaybackPayloadException()
    val url = canonical.toHttpUrlOrNull() ?: throw UnsupportedPlaybackPayloadException()
    return PlaybackProxyServer.HlsResourceKey(url, kind, aes128)
  }

  private fun canonicalIv(raw: String): String {
    if (!raw.startsWith("0x") || raw.length !in 3..34) {
      throw UnsupportedPlaybackPayloadException()
    }
    val hex = raw.drop(2)
    if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
      throw UnsupportedPlaybackPayloadException()
    }
    return hex.lowercase(Locale.ROOT).padStart(32, '0')
  }

  private fun rejectUriAttributes(line: String) {
    // Parse any tag mentioning URI, even when whitespace makes it non-conformant. Native HLS
    // parsers may be more permissive than this parser; failing closed avoids leaving such a URI
    // unrewritten because a narrow detector did not recognize it.
    if ("URI" !in line) return
    rejectUnexpectedUriAttributes(parseAttributes(line), allowUri = false)
  }

  private fun rejectUnexpectedUriAttributes(
    attributes: List<Attribute>,
    allowUri: Boolean = true,
  ) {
    if (attributes.any { it.name.endsWith("URI") && (!allowUri || it.name != "URI") }) {
      throw UnsupportedPlaybackPayloadException()
    }
  }

  private fun rejectByteRangeAttributes(attributes: List<Attribute>) {
    if (attributes.any { it.name.startsWith("BYTERANGE") }) {
      throw UnsupportedPlaybackPayloadException()
    }
  }

  private fun parseAttributes(line: String): List<Attribute> {
    val colon = line.indexOf(':')
    if (colon < 0 || colon == line.lastIndex) throw UnsupportedPlaybackPayloadException()
    val attributes = mutableListOf<Attribute>()
    val names = mutableSetOf<String>()
    var cursor = colon + 1
    while (cursor < line.length) {
      val nameStart = cursor
      while (
        cursor < line.length &&
        (line[cursor] in 'A'..'Z' || line[cursor].isDigit() || line[cursor] == '-')
      ) {
        cursor++
      }
      if (cursor == nameStart || cursor >= line.length || line[cursor] != '=') {
        throw UnsupportedPlaybackPayloadException()
      }
      val name = line.substring(nameStart, cursor)
      if (!names.add(name)) throw UnsupportedPlaybackPayloadException()
      cursor++
      val quoted = cursor < line.length && line[cursor] == '"'
      val valueStart: Int
      val valueEnd: Int
      if (quoted) {
        cursor++
        valueStart = cursor
        while (cursor < line.length && line[cursor] != '"') cursor++
        if (cursor >= line.length) throw UnsupportedPlaybackPayloadException()
        valueEnd = cursor
        cursor++
      } else {
        valueStart = cursor
        while (cursor < line.length && line[cursor] != ',') cursor++
        valueEnd = cursor
      }
      if (valueStart == valueEnd) throw UnsupportedPlaybackPayloadException()
      attributes += Attribute(
        name = name,
        value = line.substring(valueStart, valueEnd),
        valueStart = valueStart,
        valueEnd = valueEnd,
        quoted = quoted,
      )
      if (cursor == line.length) break
      if (line[cursor] != ',' || cursor == line.lastIndex) {
        throw UnsupportedPlaybackPayloadException()
      }
      cursor++
    }
    return attributes
  }

  private fun decodeUtf8(bytes: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes))
      .toString()
  } catch (_: Exception) {
    throw UnsupportedPlaybackPayloadException()
  }
}

/** Classifies compound manifests and proves direct binary media at byte zero. */
internal object PlaybackPayloadPolicy {
  enum class Declaration {
    GENERIC,
    HLS,
    UNSUPPORTED,
  }

  private val HLS_MIME_TYPES = setOf(
    "application/vnd.apple.mpegurl",
    "application/x-mpegurl",
    "audio/mpegurl",
    "audio/x-mpegurl",
  )
  private val UNSUPPORTED_MIME_TYPES = setOf(
    "application/dash+xml",
    "application/vnd.ms-sstr+xml",
    "audio/x-scpls",
    "application/pls+xml",
    "application/xspf+xml",
  )
  private val UNSUPPORTED_PATH_SUFFIXES = setOf(
    ".m3u",
    ".mpd",
    ".ism",
    ".isml",
    ".pls",
    ".xspf",
  )

  fun classify(url: HttpUrl, contentType: String?): Declaration {
    val mime = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    val path = url.encodedPath.lowercase(Locale.ROOT)
    if (
      mime in UNSUPPORTED_MIME_TYPES ||
      UNSUPPORTED_PATH_SUFFIXES.any(path::endsWith)
    ) {
      return Declaration.UNSUPPORTED
    }
    if (mime in HLS_MIME_TYPES || path.endsWith(".m3u8")) return Declaration.HLS
    return Declaration.GENERIC
  }

  fun hasDirectMediaSignature(prefix: ByteArray, allowTransportStream: Boolean): Boolean {
    if (prefix.isEmpty()) return false
    if (prefix.startsWithBytes(0x1a, 0x45, 0xdf, 0xa3)) return true // Matroska/WebM EBML
    if (prefix.startsWithAscii("fLaC") || prefix.startsWithAscii("OggS")) return true
    if (
      prefix.size >= 12 &&
      prefix.startsWithAscii("RIFF") &&
      (prefix.startsWithAscii("WAVE", 8) || prefix.startsWithAscii("AVI ", 8))
    ) {
      return true
    }
    if (hasIsoBmffSignature(prefix)) return true
    return allowTransportStream && hasTransportStreamSignature(prefix)
  }

  private fun hasIsoBmffSignature(prefix: ByteArray): Boolean {
    if (prefix.size < 12) return false
    val boxSize = prefix.readUnsignedInt(0)
    if (boxSize != 0L && boxSize != 1L && boxSize !in 8L..prefix.size.toLong()) return false
    return ISO_BMFF_BOX_TYPES.any { prefix.startsWithAscii(it, 4) }
  }

  private fun hasTransportStreamSignature(prefix: ByteArray): Boolean {
    if (prefix.size < TRANSPORT_STREAM_PACKET_BYTES * TRANSPORT_STREAM_MIN_PACKETS) return false
    return (0 until TRANSPORT_STREAM_MIN_PACKETS).all { packet ->
      val offset = packet * TRANSPORT_STREAM_PACKET_BYTES
      prefix[offset].toInt() and 0xff == 0x47 &&
        prefix[offset + 1].toInt() and 0x80 == 0 &&
        prefix[offset + 3].toInt() and 0x30 != 0
    }
  }

  private fun ByteArray.startsWithAscii(value: String, offset: Int = 0): Boolean {
    if (offset < 0 || size - offset < value.length) return false
    return value.indices.all { index -> this[offset + index].toInt() and 0xff == value[index].code }
  }

  private fun ByteArray.startsWithBytes(vararg values: Int): Boolean {
    if (size < values.size) return false
    return values.indices.all { index -> this[index].toInt() and 0xff == values[index] }
  }

  private fun ByteArray.readUnsignedInt(offset: Int): Long {
    var value = 0L
    repeat(4) { index -> value = (value shl 8) or (this[offset + index].toLong() and 0xffL) }
    return value
  }

  private const val TRANSPORT_STREAM_PACKET_BYTES = 188
  private const val TRANSPORT_STREAM_MIN_PACKETS = 5
  private val ISO_BMFF_BOX_TYPES = setOf("ftyp", "styp", "moov", "moof", "sidx")
}

@Throws(IOException::class)
private fun readBoundedBytes(input: InputStream, limit: Int, message: String): ByteArray {
  val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
  val buffer = ByteArray(8 * 1024)
  var total = 0
  while (true) {
    val read = input.read(buffer)
    if (read < 0) break
    if (read == 0) continue
    total += read
    if (total > limit) throw HlsLimitException(message)
    output.write(buffer, 0, read)
  }
  return output.toByteArray()
}
