package com.stremioshell.host.tv.pairing

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale

/** Best-guess physical-LAN address the phone can reach, or null if not on one. */
@Suppress("DEPRECATION") // No non-callback snapshot API exists for ranking all current networks.
fun findPairingLanAddress(context: Context): InetAddress? {
  val fromActiveNetworks = runCatching {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val active = manager.activeNetwork
    manager.allNetworks
      .mapNotNull { network -> networkCandidate(manager, network, active) }
      .sortedBy { it.rank }
      .asSequence()
      .flatMap { it.addresses.sortedBy(::addressRank).asSequence() }
      .firstOrNull(::isPairingLanAddress)
  }.getOrNull()
  if (fromActiveNetworks != null) return fromActiveNetworks

  // Old/vendor network stacks occasionally expose no LinkProperties. The fallback is still
  // deterministic and explicitly keeps VPN/Wi-Fi Direct interfaces from winning enumeration.
  return runCatching {
    NetworkInterface.getNetworkInterfaces().asSequence()
      .filter { it.isUp && !it.isLoopback }
      // A private/CGNAT address is not proof of a LAN: cellular rmnet/wwan interfaces commonly
      // carry one too. Unlike ConnectivityManager this fallback has no transport metadata, so use
      // a positive Wi-Fi/Ethernet interface allowlist instead of accepting every unknown family.
      .filter { isFallbackPairingInterface(it.name) }
      .sortedBy { interfaceRank(it.name) }
      .flatMap { networkInterface ->
        networkInterface.inetAddresses.asSequence()
          .filter(::isPairingLanAddress)
          .sortedBy(::addressRank)
      }
      .firstOrNull()
  }.getOrNull()
}

private data class LanNetworkCandidate(
  val rank: Int,
  val addresses: List<InetAddress>,
)

private fun networkCandidate(
  manager: ConnectivityManager,
  network: Network,
  active: Network?,
): LanNetworkCandidate? {
  val capabilities = manager.getNetworkCapabilities(network) ?: return null
  if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
  val physical = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
  if (!physical) return null
  val properties = manager.getLinkProperties(network) ?: return null
  if (excludedPairingInterface(properties.interfaceName.orEmpty())) return null
  val addresses = properties.linkAddresses.map { it.address }.filter(::isPairingLanAddress)
  if (addresses.isEmpty()) return null
  return LanNetworkCandidate(rank = if (network == active) 0 else 1, addresses = addresses)
}

/** IPv4 remains preferred on a dual-stack LAN; unique-local IPv6 is the safe IPv6-only fallback. */
private fun addressRank(address: InetAddress): Int = when (address) {
  is Inet4Address -> 0
  is Inet6Address -> 1
  else -> 2
}

internal fun isPairingLanAddress(address: InetAddress): Boolean = when (address) {
  is Inet4Address -> isPairingLanIpv4(address)
  is Inet6Address -> isPairingLanIpv6(address)
  else -> false
}

/** RFC1918 plus carrier-grade NAT, which Starlink commonly assigns to customer equipment. */
internal fun isPairingLanIpv4(address: InetAddress): Boolean {
  if (address !is Inet4Address) return false
  if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress) return false
  if (address.isMulticastAddress) return false
  val bytes = address.address
  val first = bytes[0].toInt() and 0xff
  val second = bytes[1].toInt() and 0xff
  val carrierGradeNat = first == 100 && second in 64..127
  return address.isSiteLocalAddress || carrierGradeNat
}

/**
 * Unique-local IPv6 on a physical Wi-Fi/Ethernet network.
 *
 * Link-local addresses are deliberately excluded even though two peers on one segment can route
 * them. Their `%zone` is local to the phone rather than the TV and browser support for RFC 6874
 * zone identifiers is inconsistent, so putting one in a QR code would not be a portable endpoint.
 * Global unicast is excluded too: binding one exact physical-interface address prevents wildcard
 * exposure, but it does not prove an arriving peer is on the LAN rather than elsewhere on the
 * globally routable prefix. Without a same-prefix peer gate, ULA is the safe boundary.
 */
internal fun isPairingLanIpv6(address: InetAddress): Boolean {
  if (address !is Inet6Address) return false
  if (
    address.isAnyLocalAddress ||
    address.isLoopbackAddress ||
    address.isLinkLocalAddress ||
    address.isMulticastAddress
  ) {
    return false
  }
  val first = address.address[0].toInt() and 0xff
  return (first and 0xfe) == 0xfc // fc00::/7
}

/** URI authority spelling for a selected address; IPv6 literals must be bracketed. */
internal fun pairingUrlHost(address: InetAddress): String {
  val host = requireNotNull(address.hostAddress) { "Pairing address has no numeric host" }
  return if (address is Inet6Address) {
    // A scope should not normally survive the eligibility policy above, but encode it correctly if
    // a vendor reports one on a global address rather than emitting an invalid raw `%` in the URI.
    "[${host.replace("%", "%25")}]"
  } else {
    host
  }
}

internal fun excludedPairingInterface(rawName: String): Boolean {
  val name = rawName.lowercase(Locale.ROOT)
  return listOf("tun", "tap", "vpn", "p2p", "dummy", "wg").any(name::startsWith)
}

/** Physical-LAN interface families safe to consider when Android exposes no transport metadata. */
internal fun isFallbackPairingInterface(rawName: String): Boolean {
  if (excludedPairingInterface(rawName)) return false
  val name = rawName.lowercase(Locale.ROOT)
  return name.startsWith("wlan") ||
    name.startsWith("wifi") ||
    name.startsWith("eth") ||
    name.startsWith("en")
}

private fun interfaceRank(rawName: String): Int {
  val name = rawName.lowercase(Locale.ROOT)
  return when {
    name.startsWith("wlan") || name.startsWith("wifi") -> 0
    name.startsWith("eth") || name.startsWith("en") -> 1
    else -> 2
  }
}
