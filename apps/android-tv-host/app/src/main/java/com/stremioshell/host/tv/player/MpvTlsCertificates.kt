package com.stremioshell.host.tv.player

import android.content.Context
import android.os.Build
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Bridges Android's CA trust store to mpv.
 *
 * mpv verifies stream TLS through ffmpeg's mbedtls, which cannot read Android's
 * system trust store - with `tls-verify=yes` and no `tls-ca-file`, verification
 * succeeds or fails on whatever chain the server happens to present. That is not
 * hypothetical: Cloudflare rotates sites between Let's Encrypt and Google Trust
 * Services issuers, and a debrid host moving to a GTS certificate turned every
 * stream open into "certificate is not correctly signed by the trusted CA" while
 * the same host verified fine in OkHttp. Exporting the platform's own roots and
 * pointing `tls-ca-file` at them makes mpv trust exactly what Android trusts.
 */
object MpvTlsCertificates {
  private const val BUNDLE_NAME = "mpv-ca-bundle.pem"

  /**
   * Returns the PEM bundle of the platform's trusted roots, writing it on first
   * use. Cached per OS build: the system store only changes with the firmware,
   * so the header records [Build.FINGERPRINT] and the bundle regenerates when it
   * no longer matches. The cached path is a stat plus one line read; the first
   * launch pays a one-time export (~200 roots) that is far cheaper than the
   * failed-playback alternative - and the Application warms it off the main
   * thread at first idle, so the player's synchronous call normally finds the
   * file already written. Synchronized because of exactly that pair of callers:
   * two exports racing the same temp file would rename a half-written bundle
   * into place. Returns null if the store cannot be read - callers then leave
   * `tls-ca-file` unset, which is exactly today's behavior.
   */
  @Synchronized
  fun ensureBundle(context: Context): File? = runCatching {
    val bundle = File(context.filesDir, BUNDLE_NAME)
    val header = "# ${Build.FINGERPRINT}"
    if (bundle.isFile && bundle.useLines { it.firstOrNull() } == header) {
      return@runCatching bundle
    }
    val keyStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
    val pem = buildString {
      appendLine(header)
      for (alias in keyStore.aliases()) {
        val cert = keyStore.getCertificate(alias) as? X509Certificate ?: continue
        appendLine("-----BEGIN CERTIFICATE-----")
        appendLine(Base64.encodeToString(cert.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n"))
        appendLine("-----END CERTIFICATE-----")
      }
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
}
