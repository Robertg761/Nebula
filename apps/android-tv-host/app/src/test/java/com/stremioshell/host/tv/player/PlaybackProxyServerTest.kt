package com.stremioshell.host.tv.player

import com.stremioshell.host.tv.data.PublicOnlyDns
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.OkHttpClient
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProxyServerTest {
  @Test
  fun `playback client cannot reuse a connection opened by the shared client`() {
    val sharedPool = ConnectionPool()
    val shared = OkHttpClient.Builder().connectionPool(sharedPool).build()

    val playback = PlaybackProxyHttpClient.create(shared)

    assertNotSame(shared.connectionPool, playback.connectionPool)
    assertEquals(Proxy.NO_PROXY, playback.proxy)
    assertTrue(playback.dns is PublicOnlyDns)
    assertFalse(playback.followRedirects)
    assertFalse(playback.followSslRedirects)
  }

  @Test
  fun `retired target cancels registered calls and rejects late registration`() {
    val client = OkHttpClient()
    val target = PlaybackProxyServer.Target(
      token = "token",
      originalUrl = "https://video.example/file".toHttpUrl(),
      requestHeaders = emptyMap(),
    )
    val registered = client.newCall(
      Request.Builder().url("https://video.example/registered").build(),
    )
    val late = client.newCall(
      Request.Builder().url("https://video.example/late").build(),
    )

    assertTrue(target.register(registered))
    target.retire()

    assertTrue(registered.isCanceled())
    assertFalse(target.register(late))
  }

  @Test
  fun `loopback transport forwards range and protected headers without exposing them in its url`() {
    val upstream = CopyOnWriteArrayList<Request>()
    val client = fakeClient { request ->
      upstream += request
      if (request.header("Range") == "bytes=10-14") {
        response(request, 206, "Partial Content", "video")
          .newBuilder()
          .header("Content-Range", "bytes 10-14/100")
          .build()
      } else {
        response(request, 200, "OK", directBody("initial"))
      }
    }
    val server = PlaybackProxyServer(client)
    try {
      val localUrl = server.open(
        "https://video.example/movie.mkv",
        mapOf("Authorization" to "Bearer secret", "Cookie" to "session=secret"),
      )

      assertTrue(localUrl.startsWith("http://127.0.0.1:"))
      assertFalse(localUrl.contains("secret"))
      assertEquals(200, get(localUrl).code)
      val result = get(localUrl, mapOf("Range" to "bytes=10-14"))

      assertEquals(206, result.code)
      assertEquals("video", result.body)
      assertEquals("bytes 10-14/100", result.contentRange)
      assertEquals("Bearer secret", upstream.last().header("Authorization"))
      assertEquals("session=secret", upstream.last().header("Cookie"))
      assertEquals("bytes=10-14", upstream.last().header("Range"))
    } finally {
      server.close()
    }
  }

  @Test
  fun `cross-origin redirect stays in the proxy and drops stream credentials`() {
    val upstream = CopyOnWriteArrayList<Request>()
    val client = fakeClient { request ->
      upstream += request
      if (request.url.host == "video.example") {
        response(request, 302, "Found", "")
          .newBuilder()
          .header("Location", "https://cdn.example/movie.mkv")
          .build()
      } else {
        response(request, 200, "OK", directBody("movie"))
      }
    }
    val server = PlaybackProxyServer(client)
    try {
      val localUrl = server.open(
        "https://video.example/start",
        mapOf("Authorization" to "Bearer secret", "User-Agent" to "Nebula TV"),
      )

      val result = get(localUrl)

      assertEquals(200, result.code)
      assertEquals(directBody("movie"), result.body)
      assertEquals(2, upstream.size)
      assertEquals("Bearer secret", upstream.first().header("Authorization"))
      assertNull(upstream.last().header("Authorization"))
      assertEquals("Nebula TV", upstream.last().header("User-Agent"))
      assertEquals("cdn.example", upstream.last().url.host)
    } finally {
      server.close()
    }
  }

  @Test
  fun `redirect to a private address is rejected before a second call`() {
    val upstream = CopyOnWriteArrayList<Request>()
    val client = fakeClient { request ->
      upstream += request
      response(request, 302, "Found", "")
        .newBuilder()
        .header("Location", "https://127.0.0.1/private.mkv")
        .build()
    }
    val server = PlaybackProxyServer(client)
    try {
      val localUrl = server.open("https://video.example/start", emptyMap())

      val result = get(localUrl)

      assertEquals(502, result.code)
      assertEquals(1, upstream.size)
    } finally {
      server.close()
    }
  }

  @Test
  fun `a disguised compound manifest is rejected instead of escaping the proxy`() {
    val client = fakeClient { request ->
      response(
        request,
        200,
        "OK",
        "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nhttps://192.168.1.20/segment.ts",
      )
    }
    val server = PlaybackProxyServer(client)
    try {
      val localUrl = server.open("https://video.example/no-extension", emptyMap())

      val result = get(localUrl)

      assertEquals(415, result.code)
      assertTrue(result.body.contains("unsupported", ignoreCase = true))
    } finally {
      server.close()
    }
  }

  @Test
  fun `suffixless hls is accepted only when its marker starts at byte zero`() {
    val client = fakeClient { request ->
      when (request.url.encodedPath) {
        "/signed" -> response(
          request,
          200,
          "OK",
          "#EXTM3U\n#EXTINF:4,\nsegment.ts\n",
          "application/octet-stream",
        )
        else -> response(
          request,
          200,
          "OK",
          transportStreamBody(),
          "video/mp2t",
        )
      }
    }
    val server = PlaybackProxyServer(client)
    try {
      val root = server.open("https://video.example/signed", emptyMap())
      val manifest = get(root)

      assertEquals(200, manifest.code)
      assertEquals(1, localResourceUrls(manifest.body).size)
      assertOpaqueManifest(manifest.body)
    } finally {
      server.close()
    }
  }

  @Test
  fun `an xml manifest with its root beyond the sniff window is rejected as ambiguous`() {
    val body = "<?xml version=\"1.0\"?><!---->" +
      "<!--${"padding".repeat(600)}-->" +
      "<MPD><BaseURL>http://192.168.1.1/private.ts</BaseURL></MPD>"
    val client = fakeClient { request -> response(request, 200, "OK", body) }
    val server = PlaybackProxyServer(client)
    try {
      val localUrl = server.open("https://video.example/no-extension", emptyMap())

      val result = get(localUrl)

      assertEquals(415, result.code)
      assertTrue(result.body.contains("unsupported", ignoreCase = true))
    } finally {
      server.close()
    }
  }

  @Test
  fun `an elementary stream prefix cannot make a nested manifest trusted`() {
    val body = "\u0000\u0000\u0001\u0067#EXTM3U\nhttps://192.168.1.1/private.ts"
    val client = fakeClient { request -> response(request, 200, "OK", body) }
    val server = PlaybackProxyServer(client)
    try {
      val localUrl = server.open("https://video.example/no-extension", emptyMap())

      val result = get(localUrl)

      assertEquals(415, result.code)
      assertTrue(result.body.contains("unsupported", ignoreCase = true))
    } finally {
      server.close()
    }
  }

  @Test
  fun `an xml comment cannot spoof transport stream sync bytes`() {
    val body = buildString {
      append("<!--G") // First sync byte at offset 4.
      append("x".repeat(192 - length))
      append('G') // 188-byte stride from the first sync byte.
      append("x".repeat(380 - length))
      append('G') // A second 188-byte stride.
      append("--><MPD><BaseURL>http://192.168.1.1/private.ts</BaseURL></MPD>")
    }
    val client = fakeClient { request -> response(request, 200, "OK", body) }
    val server = PlaybackProxyServer(client)
    try {
      val localUrl = server.open("https://video.example/no-extension", emptyMap())

      val result = get(localUrl)

      assertEquals(415, result.code)
      assertTrue(result.body.contains("unsupported", ignoreCase = true))
    } finally {
      server.close()
    }
  }

  @Test
  fun `an xml comment cannot spoof an iso media box type`() {
    val body = "<!--ftyp padding--><MPD>" +
      "<BaseURL>http://192.168.1.1/private.ts</BaseURL></MPD>"
    val client = fakeClient { request -> response(request, 200, "OK", body) }
    val server = PlaybackProxyServer(client)
    try {
      val localUrl = server.open("https://video.example/no-extension", emptyMap())

      val result = get(localUrl)

      assertEquals(415, result.code)
      assertTrue(result.body.contains("unsupported", ignoreCase = true))
    } finally {
      server.close()
    }
  }

  @Test
  fun `master and media playlists rewrite every nested uri to opaque loopback routes`() {
    val upstream = CopyOnWriteArrayList<Request>()
    val client = fakeClient { request ->
      upstream += request
      when (request.url.host to request.url.encodedPath) {
        "video.example" to "/hls/master.m3u8" -> response(
          request,
          200,
          "OK",
          """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",URI="audio/main.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1500000,AUDIO="audio"
            video/main.m3u8
          """.trimIndent(),
          HLS_TYPE,
        )
        "video.example" to "/hls/audio/main.m3u8" -> response(
          request,
          200,
          "OK",
          "#EXTM3U\n#EXTINF:4,\naudio-1.ts\n",
          HLS_TYPE,
        )
        "video.example" to "/hls/video/main.m3u8" -> response(
          request,
          200,
          "OK",
          """
            #EXTM3U
            #EXT-X-MAP:URI="../init.mp4"
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="https://keys.example/key.bin?credential=hidden"
            #EXTINF:4,
            segment-1.ts
            #EXTINF:4,
            https://cdn.example/segment-2.ts?signature=hidden
          """.trimIndent(),
          HLS_TYPE,
        )
        "video.example" to "/hls/init.mp4" -> response(request, 200, "OK", directBody("init"))
        "keys.example" to "/key.bin" -> response(request, 200, "OK", "0123456789abcdef")
        "video.example" to "/hls/video/segment-1.ts" -> response(
          request,
          200,
          "OK",
          transportStreamBody(),
          "video/mp2t",
        )
        "cdn.example" to "/segment-2.ts" -> response(
          request,
          200,
          "OK",
          transportStreamBody(),
          "video/mp2t",
        )
        else -> response(request, 404, "Not Found", "missing")
      }
    }
    val server = PlaybackProxyServer(client)
    try {
      val root = server.open(
        "https://video.example/hls/master.m3u8",
        mapOf("Authorization" to "Bearer secret"),
      )
      val master = get(root)

      assertEquals(200, master.code)
      assertEquals(2, localResourceUrls(master.body).size)
      assertOpaqueManifest(master.body)

      val childResults = localResourceUrls(master.body).associateWith(::get)
      val video = childResults.values.single { "EXT-X-KEY" in it.body }
      assertEquals(200, video.code)
      assertEquals(4, localResourceUrls(video.body).size)
      assertOpaqueManifest(video.body)

      localResourceUrls(video.body).forEach { nested -> assertEquals(200, get(nested).code) }
      assertTrue(upstream.any { it.url.encodedPath == "/hls/init.mp4" })
      assertTrue(upstream.any { it.url.encodedPath == "/key.bin" })
      assertTrue(upstream.any { it.url.encodedPath == "/hls/video/segment-1.ts" })
      assertTrue(upstream.any { it.url.encodedPath == "/segment-2.ts" })
      assertTrue(upstream.filter { it.url.host == "video.example" }.all {
        it.header("Authorization") == "Bearer secret"
      })
    } finally {
      server.close()
    }
  }

  @Test
  fun `absolute cross-origin child playlists and segments drop root credentials`() {
    val upstream = CopyOnWriteArrayList<Request>()
    val client = fakeClient { request ->
      upstream += request
      when (request.url.host to request.url.encodedPath) {
        "video.example" to "/master.m3u8" -> response(
          request,
          200,
          "OK",
          "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nhttps://cdn.example/media.m3u8\n",
          HLS_TYPE,
        )
        "cdn.example" to "/media.m3u8" -> response(
          request,
          200,
          "OK",
          "#EXTM3U\n#EXTINF:4,\nsegment.ts\n",
          HLS_TYPE,
        )
        "cdn.example" to "/segment.ts" -> response(
          request,
          200,
          "OK",
          transportStreamBody(),
          "video/mp2t",
        )
        else -> response(request, 404, "Not Found", "missing")
      }
    }
    val server = PlaybackProxyServer(client)
    try {
      val root = server.open(
        "https://video.example/master.m3u8",
        mapOf(
          "Authorization" to "Bearer secret",
          "Cookie" to "session=secret",
          "User-Agent" to "Nebula TV",
        ),
      )

      val child = get(localResourceUrls(get(root).body).single())
      assertEquals(200, get(localResourceUrls(child.body).single()).code)

      val rootRequest = upstream.single { it.url.host == "video.example" }
      assertEquals("Bearer secret", rootRequest.header("Authorization"))
      assertEquals("session=secret", rootRequest.header("Cookie"))
      upstream.filter { it.url.host == "cdn.example" }.forEach { request ->
        assertNull(request.header("Authorization"))
        assertNull(request.header("Cookie"))
        assertEquals("Nebula TV", request.header("User-Agent"))
      }
    } finally {
      server.close()
    }
  }

  @Test
  fun `private child uri is rejected before any child request`() {
    val upstream = CopyOnWriteArrayList<Request>()
    val client = fakeClient { request ->
      upstream += request
      response(
        request,
        200,
        "OK",
        "#EXTM3U\n#EXTINF:4,\nhttps://192.168.1.20/segment.ts\n",
        HLS_TYPE,
      )
    }
    val server = PlaybackProxyServer(client)
    try {
      val result = get(server.open("https://video.example/media.m3u8", emptyMap()))

      assertEquals(415, result.code)
      assertEquals(1, upstream.size)
    } finally {
      server.close()
    }
  }

  @Test
  fun `unhandled uri bearing tags fail closed instead of reaching libmpv`() {
    val upstream = CopyOnWriteArrayList<Request>()
    val client = fakeClient { request ->
      upstream += request
      response(
        request,
        200,
        "OK",
        """
          #EXTM3U
          #EXT-X-CONTENT-STEERING:SERVER-URI="https://steering.example/config.json",PATHWAY-ID="cdn"
          #EXTINF:4,
          segment.ts
        """.trimIndent(),
        HLS_TYPE,
      )
    }
    val server = PlaybackProxyServer(client)
    try {
      val root = server.open("https://video.example/media.m3u8", emptyMap())

      assertEquals(415, get(root).code)
      assertEquals(1, upstream.size)
    } finally {
      server.close()
    }
  }

  @Test
  fun `manifest level byte range aliasing fails closed while http range remains supported`() {
    val client = fakeClient { request ->
      response(
        request,
        200,
        "OK",
        "#EXTM3U\n#EXT-X-BYTERANGE:1000@2000\nshared.mp4\n",
        HLS_TYPE,
      )
    }
    val server = PlaybackProxyServer(client)
    try {
      assertEquals(
        415,
        get(server.open("https://video.example/media.m3u8", emptyMap())).code,
      )
    } finally {
      server.close()
    }
  }

  @Test
  fun `oversized and overlong hls manifests fail with bounded responses`() {
    val call = AtomicInteger()
    val client = fakeClient { request ->
      val body = if (call.getAndIncrement() == 0) {
        "#EXTM3U\n#${"x".repeat(512 * 1024)}"
      } else {
        "#EXTM3U\n#${"x".repeat(16 * 1024 + 1)}"
      }
      response(request, 200, "OK", body, HLS_TYPE)
    }
    val server = PlaybackProxyServer(client)
    try {
      val root = server.open("https://video.example/media.m3u8", emptyMap())

      assertEquals(413, get(root).code)
      assertEquals(413, get(root).code)
    } finally {
      server.close()
    }
  }

  @Test
  fun `hls uri entry count is bounded`() {
    val body = buildString {
      append("#EXTM3U\n")
      repeat(4 * 1024 + 1) { index -> append("segment-$index.ts\n") }
    }
    val server = PlaybackProxyServer(fakeClient { request ->
      response(request, 200, "OK", body, HLS_TYPE)
    })
    try {
      assertEquals(
        413,
        get(server.open("https://video.example/media.m3u8", emptyMap())).code,
      )
    } finally {
      server.close()
    }
  }

  @Test
  fun `mapping exhaustion is transactional and does not consume capacity`() {
    val call = AtomicInteger()
    val client = fakeClient { request ->
      val body = if (call.getAndIncrement() == 0) {
        "#EXTM3U\none.ts\ntwo.ts\nthree.ts\n"
      } else {
        "#EXTM3U\none.ts\ntwo.ts\n"
      }
      response(request, 200, "OK", body, HLS_TYPE)
    }
    val server = PlaybackProxyServer(client, hlsMappingLimit = 2)
    try {
      val root = server.open("https://video.example/media.m3u8", emptyMap())

      assertEquals(413, get(root).code)
      val accepted = get(root)
      assertEquals(200, accepted.code)
      assertEquals(2, localResourceUrls(accepted.body).size)
    } finally {
      server.close()
    }
  }

  @Test
  fun `resource token expires when a new playback target replaces it`() {
    val client = fakeClient { request ->
      when (request.url.encodedPath) {
        "/media.m3u8" -> response(
          request,
          200,
          "OK",
          "#EXTM3U\nsegment.ts\n",
          HLS_TYPE,
        )
        else -> response(request, 200, "OK", directBody("movie"))
      }
    }
    val server = PlaybackProxyServer(client)
    try {
      val oldRoot = server.open("https://video.example/media.m3u8", emptyMap())
      val oldChild = localResourceUrls(get(oldRoot).body).single()
      server.open("https://video.example/new.mkv", emptyMap())

      assertEquals(410, get(oldChild).code)
    } finally {
      server.close()
    }
  }

  @Test
  fun `nested playlist redirect to a private address is rejected before following it`() {
    val upstream = CopyOnWriteArrayList<Request>()
    val client = fakeClient { request ->
      upstream += request
      when (request.url.encodedPath) {
        "/master.m3u8" -> response(
          request,
          200,
          "OK",
          "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nchild.m3u8\n",
          HLS_TYPE,
        )
        else -> response(request, 302, "Found", "")
          .newBuilder()
          .header("Location", "https://127.0.0.1/private.m3u8")
          .build()
      }
    }
    val server = PlaybackProxyServer(client)
    try {
      val root = server.open("https://video.example/master.m3u8", emptyMap())
      val child = localResourceUrls(get(root).body).single()

      assertEquals(502, get(child).code)
      assertEquals(2, upstream.size)
    } finally {
      server.close()
    }
  }

  @Test
  fun `segment range is preserved after that resource passes byte-zero verification`() {
    val upstream = CopyOnWriteArrayList<Request>()
    val client = fakeClient { request ->
      upstream += request
      when (request.url.encodedPath) {
        "/media.m3u8" -> response(
          request,
          200,
          "OK",
          "#EXTM3U\nsegment.mkv\n",
          HLS_TYPE,
        )
        else -> when (request.header("Range")) {
          "bytes=10-14" -> response(request, 206, "Partial Content", "video")
            .newBuilder()
            .header("Content-Range", "bytes 10-14/100")
            .build()
          else -> response(request, 200, "OK", directBody("segment"))
        }
      }
    }
    val server = PlaybackProxyServer(client)
    try {
      val root = server.open("https://video.example/media.m3u8", emptyMap())
      val segment = localResourceUrls(get(root).body).single()
      assertEquals(200, get(segment).code)

      val result = get(segment, mapOf("Range" to "bytes=10-14"))

      assertEquals(206, result.code)
      assertEquals("video", result.body)
      assertEquals("bytes 10-14/100", result.contentRange)
      assertTrue(upstream.any { it.header("Range") == "bytes=10-14" })
      assertFalse(upstream.any { it.header("Range") == "bytes=0-2047" })
    } finally {
      server.close()
    }
  }

  @Test
  fun `aes 128 segment prefix is verified after bounded key retrieval`() {
    val key = "0123456789abcdef".toByteArray(StandardCharsets.US_ASCII)
    val iv = ByteArray(16).also { it[15] = 7 }
    val encryptedSegment = encryptAes128(transportStreamBody(), key, iv)
    val keyCalls = AtomicInteger()
    val upstream = CopyOnWriteArrayList<Request>()
    val client = fakeClient { request ->
      upstream += request
      when (request.url.encodedPath) {
        "/media.m3u8" -> response(
          request,
          200,
          "OK",
          """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:4,
            segment.ts
          """.trimIndent(),
          HLS_TYPE,
        )
        "/key.bin" -> response(
          request,
          200,
          "OK",
          if (keyCalls.getAndIncrement() == 0) key else ByteArray(16) { 0x55 },
        )
        "/segment.ts" -> response(request, 200, "OK", encryptedSegment, "video/mp2t")
        else -> response(request, 404, "Not Found", "missing")
      }
    }
    val server = PlaybackProxyServer(client)
    try {
      val root = server.open("https://video.example/media.m3u8", emptyMap())
      val resources = localResourceUrls(get(root).body)

      assertEquals(2, resources.size)
      assertTrue(key.contentEquals(get(resources[0]).bytes))
      assertTrue(encryptedSegment.contentEquals(get(resources[1]).bytes))
      assertEquals(1, upstream.count { it.url.encodedPath == "/key.bin" })
    } finally {
      server.close()
    }
  }

  @Test
  fun `dash smooth pls xspf and legacy m3u remain rejected`() {
    val server = PlaybackProxyServer(fakeClient { request ->
      response(request, 200, "OK", directBody("payload"))
    })
    try {
      listOf("stream.mpd", "stream.ism", "stream.isml", "stream.pls", "stream.xspf", "stream.m3u")
        .forEach { path ->
          val root = server.open("https://video.example/$path", emptyMap())
          assertEquals(path, 415, get(root).code)
        }
    } finally {
      server.close()
    }
  }

  private fun fakeClient(responder: (Request) -> Response): OkHttpClient =
    OkHttpClient.Builder()
      .followRedirects(false)
      .followSslRedirects(false)
      .addInterceptor { chain -> responder(chain.request()) }
      .build()

  private fun response(
    request: Request,
    code: Int,
    message: String,
    body: String,
    contentType: String? = null,
  ): Response = response(
    request,
    code,
    message,
    body.toByteArray(StandardCharsets.UTF_8),
    contentType,
  )

  private fun response(
    request: Request,
    code: Int,
    message: String,
    body: ByteArray,
    contentType: String? = null,
  ): Response =
    Response.Builder()
      .request(request)
      .protocol(Protocol.HTTP_1_1)
      .code(code)
      .message(message)
      .body(body.toResponseBody(contentType?.toMediaType()))
      .build()

  /** ASCII RIFF/WAVE header followed by readable fixture data. */
  private fun directBody(data: String): String = "RIFF0000WAVE$data"

  private fun transportStreamBody(): ByteArray = ByteArray(188 * 5).also { bytes ->
    repeat(5) { packet ->
      val offset = packet * 188
      bytes[offset] = 0x47
      bytes[offset + 1] = 0x00
      bytes[offset + 2] = packet.toByte()
      bytes[offset + 3] = 0x10
    }
  }

  private fun encryptAes128(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
    Cipher.getInstance("AES/CBC/PKCS5Padding").run {
      init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
      doFinal(plain)
    }

  private fun localResourceUrls(manifest: String): List<String> =
    Regex("http://127\\.0\\.0\\.1:\\d+/stream/[A-Za-z0-9_-]{32}/resource/[A-Za-z0-9_-]{32}")
      .findAll(manifest)
      .map { it.value }
      .toList()

  private fun assertOpaqueManifest(manifest: String) {
    assertFalse(manifest.contains(".example"))
    assertFalse(manifest.contains("credential="))
    assertFalse(manifest.contains("signature="))
    assertTrue(localResourceUrls(manifest).isNotEmpty())
  }

  private fun get(url: String, headers: Map<String, String> = emptyMap()): LocalResult {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 2_000
    connection.readTimeout = 2_000
    connection.instanceFollowRedirects = false
    headers.forEach(connection::setRequestProperty)
    val code = connection.responseCode
    val bytes = (if (code >= 400) connection.errorStream else connection.inputStream)
      ?.use { it.readBytes() }
      ?: ByteArray(0)
    val result = LocalResult(code, bytes, connection.getHeaderField("Content-Range"))
    connection.disconnect()
    return result
  }

  private data class LocalResult(
    val code: Int,
    val bytes: ByteArray,
    val contentRange: String?,
  ) {
    val body: String get() = bytes.toString(StandardCharsets.UTF_8)
  }

  private companion object {
    const val HLS_TYPE = "application/vnd.apple.mpegurl"
  }
}
