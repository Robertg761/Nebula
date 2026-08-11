package com.stremioshell.host.tv.player

import android.content.Context
import android.os.Build
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Bridges Android's system CA trust store to mpv.
 *
 * mpv verifies stream TLS through ffmpeg's mbedtls, which cannot read Android's
 * system trust store - with `tls-verify=yes` and no `tls-ca-file`, verification
 * succeeds or fails on whatever chain the server happens to present. That is not
 * hypothetical: Cloudflare rotates sites between Let's Encrypt and Google Trust
 * Services issuers, and a debrid host moving to a GTS certificate turned every
 * stream open into "certificate is not correctly signed by the trusted CA" while
 * the same host verified fine in OkHttp. Exporting the platform's own roots and
 * pointing `tls-ca-file` at them makes mpv trust what Android trusts.
 *
 * What Android trusts, precisely: the `AndroidCAStore` keystore holds both the
 * firmware's roots (aliases prefixed `system:`) and anything the device owner has
 * installed themselves (`user:`), and only the first set is exported. On
 * targetSdk 24 and above an app does not trust user-installed CAs unless its
 * network security config opts in, and this one does not - so exporting them
 * would make the player the single component in the app that accepts an
 * interception proxy every other request (OkHttp for addons, metadata, images,
 * update downloads) rejects. Stream URLs are addon-supplied and carry debrid
 * credentials in their query strings; that is not the traffic to make an
 * exception for.
 */
object MpvTlsCertificates {
  private const val BUNDLE_NAME = "mpv-ca-bundle.pem"
  private const val SYSTEM_ALIAS_PREFIX = "system:"
  private const val PEM_BEGIN = "-----BEGIN CERTIFICATE-----"
  private const val PEM_END = "-----END CERTIFICATE-----"

  /** PEM's own line length. Not cosmetic: mbedtls's parser expects wrapped base64. */
  private const val PEM_LINE_LENGTH = 64

  /**
   * Returns the PEM bundle of the platform's trusted system roots, writing it on
   * first use. Cached per OS build: the system store only changes with the
   * firmware, so the header records [Build.FINGERPRINT] and the bundle
   * regenerates when it no longer matches. The cached path is a stat plus two
   * line reads; the first launch pays a one-time export (~150 roots) that is far
   * cheaper than the failed-playback alternative - and the Application warms it
   * off the main thread at first idle, so the player's synchronous call normally
   * finds the file already written. Synchronized because of exactly that pair of
   * callers: two exports racing the same temp file would rename a half-written
   * bundle into place.
   *
   * Returns null if the store cannot be read, or if it yielded no certificates at
   * all - callers then leave `tls-ca-file` unset, which is exactly the behaviour
   * before any of this existed. A header-only bundle would be worse than none:
   * `tls-verify=yes` against an empty trust list rejects every host on the
   * internet, and the fingerprint fast-path would keep serving that emptiness for
   * the life of the firmware. Hence both the null and the [File.delete] below,
   * and hence the fast path checking for a certificate rather than only for the
   * header - a bundle written by an older build of this file may be exactly that.
   */
  @Synchronized
  fun ensureBundle(context: Context): File? = runCatching {
    val bundle = File(context.filesDir, BUNDLE_NAME)
    val header = "# ${Build.FINGERPRINT}"
    val cachedHead = if (bundle.isFile) bundle.useLines { it.take(2).toList() } else emptyList()
    if (cachedHead.firstOrNull() == header && cachedHead.getOrNull(1) == PEM_BEGIN) {
      return@runCatching bundle
    }
    val keyStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
    val pem = buildBundleText(
      header = header,
      aliases = keyStore.aliases().iterator().asSequence(),
    ) { alias ->
      // Per certificate, so one root with an encoding the provider chokes on
      // costs that root rather than the whole bundle - and with it every stream.
      runCatching {
        (keyStore.getCertificate(alias) as? X509Certificate)
          ?.let { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }
      }.getOrNull()
    }
    if (pem == null) {
      // Nothing was exported. Leave nothing behind either: a stale bundle from a
      // previous firmware would be read back as this one's.
      bundle.delete()
      return@runCatching null
    }
    // Temp-and-rename so a crash mid-write cannot leave a truncated bundle that
    // silently rejects every certificate.
    val tmp = File(context.filesDir, "$BUNDLE_NAME.tmp")
    tmp.writeText(pem)
    if (!tmp.renameTo(bundle)) {
      bundle.delete()
      check(tmp.renameTo(bundle)) { "could not move CA bundle into place" }
    }
    bundle
  }.getOrNull()

  /**
   * Assembles the bundle text, or null when no certificate could be exported.
   *
   * The keystore lookup and base64 encoding arrive as [encodeCertificate] purely
   * so this - the alias filtering, the skipping, the PEM framing and the "empty
   * means null" verdict - can be tested off a device.
   *
   * [encodeCertificate] returns the base64 DER of the alias's certificate, or
   * null for an alias that holds no X.509 certificate or whose encoding failed.
   */
  internal fun buildBundleText(
    header: String,
    aliases: Sequence<String>,
    encodeCertificate: (String) -> String?,
  ): String? {
    var exported = 0
    val text = buildString {
      appendLine(header)
      for (alias in aliases) {
        if (!alias.startsWith(SYSTEM_ALIAS_PREFIX)) continue
        val encoded = encodeCertificate(alias) ?: continue
        exported++
        appendLine(PEM_BEGIN)
        appendLine(encoded.chunked(PEM_LINE_LENGTH).joinToString("\n"))
        appendLine(PEM_END)
      }
    }
    return text.takeIf { exported > 0 }
  }
}
