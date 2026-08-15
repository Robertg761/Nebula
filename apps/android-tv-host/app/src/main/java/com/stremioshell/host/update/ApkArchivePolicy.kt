package com.stremioshell.host.update

internal data class ApkSigningIdentity(
  val currentSignerSha256: Set<String>,
  val signerHistorySha256: Set<String>,
  val hasMultipleSigners: Boolean,
)

internal data class ApkPackageIdentity(
  val packageName: String?,
  val versionName: String?,
  val versionCode: Long,
  val signingIdentity: ApkSigningIdentity?,
)

internal object ApkArchivePolicy {
  enum class Verdict {
    VERIFIED,
    UNREADABLE,
    MISSING_EXPECTED_VERSION,
    WRONG_PACKAGE,
    WRONG_VERSION,
    NOT_NEWER,
    UNTRUSTED_SIGNER,
  }

  fun verify(
    expectedPackageName: String,
    expectedVersionName: String?,
    installed: ApkPackageIdentity?,
    archive: ApkPackageIdentity?,
  ): Verdict {
    if (installed == null || archive == null) {
      return Verdict.UNREADABLE
    }

    val expectedVersion = normalizeVersion(expectedVersionName)
      ?: return Verdict.MISSING_EXPECTED_VERSION
    if (installed.packageName != expectedPackageName || archive.packageName != expectedPackageName) {
      return Verdict.WRONG_PACKAGE
    }
    if (normalizeVersion(archive.versionName) != expectedVersion) {
      return Verdict.WRONG_VERSION
    }
    if (archive.versionCode < installed.versionCode) {
      return Verdict.NOT_NEWER
    }
    if (
      archive.versionCode == installed.versionCode &&
      !isSameCodePrereleaseUpgrade(installed.versionName, archive.versionName)
    ) {
      return Verdict.NOT_NEWER
    }

    val installedSigning = installed.signingIdentity
      ?: return Verdict.UNTRUSTED_SIGNER
    val archiveSigning = archive.signingIdentity
      ?: return Verdict.UNTRUSTED_SIGNER
    if (!isTrustedUpdate(installedSigning, archiveSigning)) {
      return Verdict.UNTRUSTED_SIGNER
    }

    return Verdict.VERIFIED
  }

  private fun normalizeVersion(value: String?): String? {
    return value?.let(SemVer::normalizeLabel)?.takeIf { it.isNotBlank() }
  }

  /**
   * The release workflow may publish beta.1, beta.2 and stable from one numeric source version.
   * Android permits an equal-versionCode replacement; allow it only when the embedded semantic
   * version moves forward within that same numeric core. A stable-to-next-core release must still
   * bump versionCode, and an identical release remains rejected.
   */
  private fun isSameCodePrereleaseUpgrade(installed: String?, archive: String?): Boolean {
    val installedVersion = installed?.let(SemVer::parseOrNull) ?: return false
    val archiveVersion = archive?.let(SemVer::parseOrNull) ?: return false
    val sameCore = installedVersion.major == archiveVersion.major &&
      installedVersion.minor == archiveVersion.minor &&
      installedVersion.patch == archiveVersion.patch
    return sameCore && archiveVersion > installedVersion
  }

  private fun isTrustedUpdate(
    installed: ApkSigningIdentity,
    archive: ApkSigningIdentity,
  ): Boolean {
    if (installed.currentSignerSha256.isEmpty() || archive.currentSignerSha256.isEmpty()) {
      return false
    }

    // Multi-signer APKs cannot rotate individual signers: every current signer
    // must remain exactly the same.
    if (installed.hasMultipleSigners || archive.hasMultipleSigners) {
      return installed.hasMultipleSigners == archive.hasMultipleSigners &&
        installed.currentSignerSha256 == archive.currentSignerSha256
    }

    // PackageManager only exposes a past signer after validating the APK's
    // proof-of-rotation. Requiring the installed current signer in the new
    // archive's lineage permits a forward rotation without permitting an old
    // key to replace an app that has already rotated away from it.
    val archiveLineage = archive.signerHistorySha256.ifEmpty {
      archive.currentSignerSha256
    }
    return archiveLineage.containsAll(installed.currentSignerSha256)
  }
}
