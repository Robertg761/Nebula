package com.stremioshell.host.tv.data

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackUrlPolicyTest {
  @Test
  fun `public https stream is allowed without rewriting its signed query`() {
    val url = " https://cdn.example.com:8443/video.mkv?token=a%2Bb%3D#part "

    assertEquals(
      "https://cdn.example.com:8443/video.mkv?token=a%2Bb%3D#part",
      PlaybackUrlPolicy.allowedUrlOrNull(url),
    )
  }

  @Test
  fun `allowed stream returns the canonical url that was audited`() {
    assertEquals(
      "https://media.example.com/video.mkv",
      PlaybackUrlPolicy.allowedUrlOrNull(" HTTPS://MEDIA.EXAMPLE.COM:443/video.mkv "),
    )
  }

  @Test
  fun `cleartext requires an explicit opt in and still needs a public host`() {
    val publicUrl = "http://media.example.com/video.mp4"

    assertEquals(
      PlaybackUrlRejection.CleartextNotAllowed,
      rejectedReason(publicUrl),
    )
    assertEquals(
      publicUrl,
      PlaybackUrlPolicy.allowedUrlOrNull(publicUrl, allowCleartextHttp = true),
    )
    assertEquals(
      PlaybackUrlRejection.NonPublicTarget,
      rejectedReason("http://192.168.1.20/video", allowCleartextHttp = true),
    )
  }

  @Test
  fun `mpv local and custom protocols are rejected`() {
    listOf(
      "file:///sdcard/movie.mkv",
      "content://media/external/video/1",
      "lavf://concat:secret",
      "fd://4",
      "memory://payload",
      "rtmp://media.example.com/live",
      "magnet:?xt=urn:btih:abc",
      "ytdl://https://media.example.com/watch",
      "/sdcard/movie.mkv",
      "//media.example.com/video",
    ).forEach { url ->
      assertEquals(url, PlaybackUrlRejection.UnsupportedScheme, rejectedReason(url))
    }
  }

  @Test
  fun `loopback private carrier nat and link local ipv4 targets are rejected`() {
    listOf(
      "0.0.0.0",
      "10.2.3.4",
      "100.64.1.1",
      "127.0.0.1",
      "169.254.10.20",
      "172.16.0.1",
      "172.31.255.254",
      "192.168.1.1",
      "224.0.0.1",
    ).forEach { host ->
      assertEquals(
        host,
        PlaybackUrlRejection.NonPublicTarget,
        rejectedReason("https://$host/video"),
      )
    }
    assertTrue(PlaybackUrlPolicy.isAllowed("https://8.8.8.8/video"))
  }

  @Test
  fun `abbreviated hex integer and leading zero numeric hosts are rejected`() {
    listOf(
      "127.1",
      "127.0.1",
      "10.1",
      "192.168.1",
      "0177.0.0.1",
      "0x7f.0.0.1",
      "2130706433",
      "0x7f000001",
    ).forEach { host ->
      assertEquals(
        host,
        PlaybackUrlRejection.NonPublicTarget,
        rejectedReason("https://$host/video"),
      )
    }
  }

  @Test
  fun `loopback private link local and mapped ipv6 targets are rejected`() {
    listOf(
      "[::]",
      "[::1]",
      "[fc00::1]",
      "[fd12:3456::1]",
      "[fe80::1]",
      "[::ffff:127.0.0.1]",
      "[::192.0.2.1]",
      "[64:ff9b:1::7f00:1]",
      "[2001::1]",
      "[2001:db8::1]",
      "[2002:7f00:1::1]",
      "[3ffe::1]",
      "[3fff::1]",
      "[ff02::1]",
    ).forEach { host ->
      assertEquals(
        host,
        PlaybackUrlRejection.NonPublicTarget,
        rejectedReason("https://$host/video"),
      )
    }
    assertTrue(PlaybackUrlPolicy.isAllowed("https://[2606:4700:4700::1111]/video"))
  }

  @Test
  fun `local style and single label names are rejected`() {
    listOf(
      "localhost",
      "player.localhost",
      "nas.local",
      "media.lan",
      "video.home",
      "service.internal",
      "living-room-tv",
    ).forEach { host ->
      assertEquals(
        host,
        PlaybackUrlRejection.NonPublicTarget,
        rejectedReason("https://$host/video"),
      )
    }
  }

  @Test
  fun `empty malformed whitespace and oversized urls are rejected`() {
    assertEquals(PlaybackUrlRejection.Empty, rejectedReason(" \t "))
    assertEquals(PlaybackUrlRejection.Malformed, rejectedReason("https://exa mple.com/video"))
    assertEquals(PlaybackUrlRejection.Malformed, rejectedReason("https:///video"))
    assertEquals(
      PlaybackUrlRejection.TooLong,
      rejectedReason("https://media.example.com/" + "a".repeat(PlaybackUrlPolicy.MAX_URL_CHARS)),
    )
    assertNull(PlaybackUrlPolicy.allowedUrlOrNull("https://media.example.com/\nfile"))
  }

  @Test
  fun `public only dns rejects a private or mixed answer`() {
    val public = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
    val private = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))

    assertEquals(
      listOf(public),
      PublicOnlyDns(FakeDns(listOf(public))).lookup("media.example.com"),
    )
    assertThrows(UnknownHostException::class.java) {
      PublicOnlyDns(FakeDns(listOf(private))).lookup("media.example.com")
    }
    assertThrows(UnknownHostException::class.java) {
      PublicOnlyDns(FakeDns(listOf(public, private))).lookup("media.example.com")
    }
  }

  private fun rejectedReason(
    url: String,
    allowCleartextHttp: Boolean = false,
  ): PlaybackUrlRejection {
    val result = PlaybackUrlPolicy.validate(url, allowCleartextHttp)
    return (result as PlaybackUrlValidation.Rejected).reason
  }

  private class FakeDns(private val addresses: List<InetAddress>) : Dns {
    override fun lookup(hostname: String): List<InetAddress> = addresses
  }
}
