package com.stremioshell.host.tv.pairing

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAddressTest {
  @Test
  fun `private and carrier grade NAT addresses can be paired`() {
    listOf("10.1.2.3", "172.20.1.2", "192.168.50.4", "100.64.0.1", "100.127.255.254")
      .forEach { assertTrue(it, isPairingLanIpv4(InetAddress.getByName(it))) }
  }

  @Test
  fun `public loopback link local and adjacent non CGNAT addresses are rejected`() {
    listOf("8.8.8.8", "127.0.0.1", "169.254.1.2", "100.63.255.255", "100.128.0.1")
      .forEach { assertFalse(it, isPairingLanIpv4(InetAddress.getByName(it))) }
  }

  @Test
  fun `only unique local IPv6 can pair without a same-prefix peer gate`() {
    listOf("fc00::4", "fd12:3456:789a::4")
      .forEach { assertTrue(it, isPairingLanAddress(InetAddress.getByName(it))) }
    listOf("::1", "fbff::4", "fe00::4", "fe80::4", "2001:db8::4", "ff02::1")
      .forEach { assertFalse(it, isPairingLanAddress(InetAddress.getByName(it))) }
  }

  @Test
  fun `fallback accepts only physical LAN interface families`() {
    listOf("tun0", "tap1", "vpn0", "p2p-wlan0-0", "dummy0", "wg0")
      .forEach { assertTrue(it, excludedPairingInterface(it)) }
    listOf("wlan0", "wifi0", "eth0", "enp4s0")
      .forEach { assertTrue(it, isFallbackPairingInterface(it)) }
    listOf("rmnet_data0", "wwan0", "ccmni0", "cell0", "usb0", "unknown0")
      .forEach { assertFalse(it, isFallbackPairingInterface(it)) }
  }

  @Test
  fun `QR authorities bracket IPv6 literals but leave IPv4 unchanged`() {
    assertEquals("192.168.50.4", pairingUrlHost(InetAddress.getByName("192.168.50.4")))
    val ipv6Host = pairingUrlHost(InetAddress.getByName("fd12:3456:789a::4"))
    assertTrue(ipv6Host, ipv6Host.startsWith("["))
    assertTrue(ipv6Host, ipv6Host.endsWith("]"))
    assertTrue(ipv6Host, ipv6Host.contains("fd12:3456:789a"))
  }
}
