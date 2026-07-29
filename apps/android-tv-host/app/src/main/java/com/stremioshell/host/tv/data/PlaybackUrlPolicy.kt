package com.stremioshell.host.tv.data

import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Why an addon-provided playback URL was refused before it reached mpv. */
enum class PlaybackUrlRejection {
  Empty,
  TooLong,
  Malformed,
  UnsupportedScheme,
  CleartextNotAllowed,
  NonPublicTarget,
}

/** Result of validating one untrusted addon playback URL. */
sealed interface PlaybackUrlValidation {
  data class Allowed(val url: String) : PlaybackUrlValidation
  data class Rejected(val reason: PlaybackUrlRejection) : PlaybackUrlValidation
}

/**
 * Strict policy for URLs handed to mpv's `loadfile`.
 *
 * mpv understands far more than web URLs (`file:`, `lavf:`, `fd:`, `memory:`, custom protocols and
 * local paths). A stream addon is remote, untrusted input, so it gets only an ordinary public HTTP
 * origin. HTTPS is the default; cleartext HTTP requires an explicit call-site opt-in and still
 * cannot target a LAN/private/link-local address.
 *
 * This policy is deliberately pure and does not resolve DNS. Explicit IP literals and local-style
 * names are rejected here; network security remains responsible for redirects and DNS rebinding.
 */
object PlaybackUrlPolicy {
  const val MAX_URL_CHARS = 8 * 1024

  fun validate(
    raw: String,
    allowCleartextHttp: Boolean = false,
  ): PlaybackUrlValidation {
    val candidate = raw.trim()
    if (candidate.isEmpty()) {
      return PlaybackUrlValidation.Rejected(PlaybackUrlRejection.Empty)
    }
    if (candidate.length > MAX_URL_CHARS) {
      return PlaybackUrlValidation.Rejected(PlaybackUrlRejection.TooLong)
    }
    // OkHttp canonicalizes some whitespace. Reject it before parsing so the string that was
    // audited is exactly the one the player receives.
    if (candidate.any { it.isISOControl() || it.isWhitespace() }) {
      return PlaybackUrlValidation.Rejected(PlaybackUrlRejection.Malformed)
    }

    val scheme = candidate.substringBefore(':', missingDelimiterValue = "")
      .lowercase(Locale.ROOT)
    if (scheme != "https" && scheme != "http") {
      return PlaybackUrlValidation.Rejected(PlaybackUrlRejection.UnsupportedScheme)
    }
    val originPrefix = "$scheme://"
    if (
      !candidate.regionMatches(0, originPrefix, 0, originPrefix.length, ignoreCase = true) ||
      candidate.drop(originPrefix.length).substringBefore('/').isEmpty() ||
      '\\' in candidate
    ) {
      return PlaybackUrlValidation.Rejected(PlaybackUrlRejection.Malformed)
    }
    if (scheme == "http" && !allowCleartextHttp) {
      return PlaybackUrlValidation.Rejected(PlaybackUrlRejection.CleartextNotAllowed)
    }

    val parsed = candidate.toHttpUrlOrNull()
      ?: return PlaybackUrlValidation.Rejected(PlaybackUrlRejection.Malformed)
    if (parsed.scheme != scheme || parsed.host.isBlank()) {
      return PlaybackUrlValidation.Rejected(PlaybackUrlRejection.Malformed)
    }
    if (!isPublicTarget(parsed.host)) {
      return PlaybackUrlValidation.Rejected(PlaybackUrlRejection.NonPublicTarget)
    }
    // Hand the consumer the same canonical URL that was parsed and audited. Returning the raw
    // spelling creates a parser differential between OkHttp here and native libmpv later.
    return PlaybackUrlValidation.Allowed(parsed.toString())
  }

  /** The canonical audited URL, only when [validate] allows it. */
  fun allowedUrlOrNull(
    raw: String,
    allowCleartextHttp: Boolean = false,
  ): String? = (validate(raw, allowCleartextHttp) as? PlaybackUrlValidation.Allowed)?.url

  fun isAllowed(
    raw: String,
    allowCleartextHttp: Boolean = false,
  ): Boolean = validate(raw, allowCleartextHttp) is PlaybackUrlValidation.Allowed

  private fun isPublicTarget(rawHost: String): Boolean {
    val host = rawHost.lowercase(Locale.ROOT).trimEnd('.')
    if (host.isEmpty()) return false
    if (
      host == "localhost" ||
      LOCAL_SUFFIXES.any { host.endsWith(it) }
    ) {
      return false
    }

    // inet_aton-style abbreviations such as 127.1 and 192.168.1 are accepted by common native
    // resolvers, but are not matched by a four-octet regex. Treat every all-numeric/hex dotted form
    // as an address and allow only canonical dotted decimal; otherwise it could reach a different
    // target in libmpv than the one audited here.
    if (looksNumericAddress(host)) {
      val address = parseCanonicalIpv4(host) ?: return false
      return isPublicAddress(address)
    }

    parseIpv6Literal(host)?.let { address ->
      return isPublicAddress(address)
    }

    // A single-label host is resolved by local search domains/mDNS on many TVs. Public playback
    // services use a qualified name, so declining it closes a useful local-network escape hatch.
    if ('.' !in host) return false
    return true
  }

  /**
   * Whether one already-resolved address is safe for an untrusted network resource.
   *
   * Exposed internally so OkHttp consumers can enforce the same boundary at connection time,
   * after DNS has answered and on every redirect.
   */
  internal fun isPublicAddress(address: InetAddress): Boolean {
    if (
      address.isAnyLocalAddress ||
      address.isLoopbackAddress ||
      address.isLinkLocalAddress ||
      address.isSiteLocalAddress ||
      address.isMulticastAddress
    ) {
      return false
    }
    if (address is Inet4Address) return isPublicIpv4(address.address)
    if (address.address.size == 16) return isPublicIpv6(address.address)
    return false
  }

  private fun looksNumericAddress(host: String): Boolean {
    val labels = host.split('.')
    return labels.isNotEmpty() && labels.all { label ->
      label.isNotEmpty() && (
        label.all(Char::isDigit) ||
          (
            label.length > 2 &&
              label.startsWith("0x", ignoreCase = true) &&
              label.drop(2).all(Char::isHexDigit)
            )
        )
    }
  }

  /** Accepts only the spelling whose meaning cannot differ between URL parsers. */
  private fun parseCanonicalIpv4(host: String): InetAddress? {
    val labels = host.split('.')
    if (labels.size != 4) return null
    val octets = ByteArray(4)
    labels.forEachIndexed { index, label ->
      if (label.length > 1 && label.startsWith('0')) return null
      val value = label.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
      octets[index] = value.toByte()
    }
    return InetAddress.getByAddress(octets)
  }

  /** The colon makes this a literal; InetAddress therefore cannot fall through to a DNS query. */
  private fun parseIpv6Literal(host: String): InetAddress? {
    if (':' !in host) return null
    return runCatching { InetAddress.getByName(host) }.getOrNull()
  }

  /**
   * Excludes non-global IPv4 blocks not all represented by InetAddress's legacy site-local flags:
   * carrier NAT, protocol/documentation ranges, benchmarking space and reserved/broadcast space.
   */
  private fun isPublicIpv4(bytes: ByteArray): Boolean {
    if (bytes.size != 4) return false
    val a = bytes[0].toInt() and 0xff
    val b = bytes[1].toInt() and 0xff
    val c = bytes[2].toInt() and 0xff
    return when {
      a == 0 -> false
      a == 10 -> false
      a == 100 && b in 64..127 -> false
      a == 127 -> false
      a == 169 && b == 254 -> false
      a == 172 && b in 16..31 -> false
      a == 192 && b == 0 && c == 0 -> false
      a == 192 && b == 0 && c == 2 -> false
      a == 192 && b == 168 -> false
      a == 198 && b in 18..19 -> false
      a == 198 && b == 51 && c == 100 -> false
      a == 203 && b == 0 && c == 113 -> false
      a >= 224 -> false
      else -> true
    }
  }

  private fun isPublicIpv6(bytes: ByteArray): Boolean {
    if (bytes.size != 16) return false
    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff
    // fc00::/7 unique-local and fec0::/10 deprecated site-local space.
    if (first and 0xfe == 0xfc) return false
    if (first == 0xfe && second and 0xc0 == 0xc0) return false
    // IPv4-mapped addresses need the IPv4 range rules too.
    val mapped = bytes.take(10).all { it == 0.toByte() } &&
      bytes[10] == 0xff.toByte() &&
      bytes[11] == 0xff.toByte()
    if (mapped) return isPublicIpv4(bytes.copyOfRange(12, 16))
    // Accept only the IPv6 global-unicast allocation (2000::/3). This also excludes legacy
    // IPv4-compatible addresses and both well-known/local-use translation prefixes such as
    // 64:ff9b:1::/48, whose apparent IPv6 destination can reach a different IPv4 trust zone.
    if (first and 0xe0 != 0x20) return false
    val third = bytes[2].toInt() and 0xff
    val fourth = bytes[3].toInt() and 0xff
    // 2001:0000::/23 contains protocol assignments rather than ordinary public endpoints.
    if (first == 0x20 && second == 0x01 && third and 0xfe == 0) return false
    // Documentation-only ranges: 2001:db8::/32 and 3fff::/20.
    if (first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8) return false
    // 2002::/16 is deprecated 6to4 and embeds an IPv4 destination outside this address check.
    if (first == 0x20 && second == 0x02) return false
    // 3ffe::/16 was the retired 6bone allocation and is no longer global unicast space.
    if (first == 0x3f && second == 0xfe) return false
    if (first == 0x3f && second == 0xff && third and 0xf0 == 0) return false
    return true
  }

  private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".lan", ".home", ".internal")
}

/** DNS gate for untrusted downloadable resources; one private answer rejects the whole lookup. */
internal class PublicOnlyDns(
  private val delegate: Dns = Dns.SYSTEM,
) : Dns {
  override fun lookup(hostname: String): List<InetAddress> {
    val addresses = delegate.lookup(hostname)
    if (addresses.isEmpty() || addresses.any { !PlaybackUrlPolicy.isPublicAddress(it) }) {
      throw UnknownHostException("Refusing non-public address for $hostname")
    }
    return addresses
  }
}

private fun Char.isHexDigit(): Boolean =
  isDigit() || lowercaseChar() in 'a'..'f'
