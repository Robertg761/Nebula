package com.stremioshell.host.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ApkArchivePolicyTest {
  private val installed = identity(
    packageName = PACKAGE_NAME,
    versionName = "0.6.1",
    versionCode = 17L,
    currentSigners = setOf("OLD"),
  )

  @Test
  fun `accepts the expected newer package signed by the installed signer`() {
    assertEquals(
      ApkArchivePolicy.Verdict.VERIFIED,
      verify(
        identity(
          packageName = PACKAGE_NAME,
          versionName = "0.6.2",
          versionCode = 18L,
          currentSigners = setOf("OLD"),
        ),
      ),
    )
  }

  @Test
  fun `rejects another application package`() {
    assertEquals(
      ApkArchivePolicy.Verdict.WRONG_PACKAGE,
      verify(
        identity(
          packageName = "example.attacker",
          versionName = "0.6.2",
          versionCode = 18L,
          currentSigners = setOf("OLD"),
        ),
      ),
    )
  }

  @Test
  fun `rejects a different release version`() {
    assertEquals(
      ApkArchivePolicy.Verdict.WRONG_VERSION,
      verify(
        identity(
          packageName = PACKAGE_NAME,
          versionName = "0.6.3",
          versionCode = 18L,
          currentSigners = setOf("OLD"),
        ),
      ),
    )
  }

  @Test
  fun `rejects equal or lower version codes`() {
    listOf(17L, 16L).forEach { versionCode ->
      assertEquals(
        ApkArchivePolicy.Verdict.NOT_NEWER,
        verify(
          identity(
            packageName = PACKAGE_NAME,
            versionName = "0.6.2",
            versionCode = versionCode,
            currentSigners = setOf("OLD"),
          ),
        ),
      )
    }
  }

  @Test
  fun `accepts a later prerelease on the same numeric version code`() {
    val betaOne = installed.copy(versionName = "0.6.2-beta.1", versionCode = 18L)
    val betaTwo = installed.copy(versionName = "0.6.2-beta.2", versionCode = 18L)

    assertEquals(
      ApkArchivePolicy.Verdict.VERIFIED,
      ApkArchivePolicy.verify(PACKAGE_NAME, "v0.6.2-beta.2", betaOne, betaTwo),
    )
  }

  @Test
  fun `accepts stable after a same-code prerelease but not another copy of stable`() {
    val beta = installed.copy(versionName = "0.6.2-beta.2", versionCode = 18L)
    val stable = installed.copy(versionName = "0.6.2", versionCode = 18L)

    assertEquals(
      ApkArchivePolicy.Verdict.VERIFIED,
      ApkArchivePolicy.verify(PACKAGE_NAME, "v0.6.2", beta, stable),
    )
    assertEquals(
      ApkArchivePolicy.Verdict.NOT_NEWER,
      ApkArchivePolicy.verify(PACKAGE_NAME, "v0.6.2", stable, stable),
    )
  }

  @Test
  fun `same version code cannot cross to a newer numeric core`() {
    val sameCodeNextCore = installed.copy(versionName = "0.6.2", versionCode = 17L)

    assertEquals(
      ApkArchivePolicy.Verdict.NOT_NEWER,
      ApkArchivePolicy.verify(PACKAGE_NAME, "0.6.2", installed, sameCodeNextCore),
    )
  }

  @Test
  fun `rejects an unrelated signer`() {
    assertEquals(
      ApkArchivePolicy.Verdict.UNTRUSTED_SIGNER,
      verify(
        identity(
          packageName = PACKAGE_NAME,
          versionName = "0.6.2",
          versionCode = 18L,
          currentSigners = setOf("ATTACKER"),
        ),
      ),
    )
  }

  @Test
  fun `accepts a forward signing rotation with a verified old signer in its lineage`() {
    assertEquals(
      ApkArchivePolicy.Verdict.VERIFIED,
      verify(
        identity(
          packageName = PACKAGE_NAME,
          versionName = "0.6.2",
          versionCode = 18L,
          currentSigners = setOf("NEW"),
          signerHistory = setOf("OLD", "NEW"),
        ),
      ),
    )
  }

  @Test
  fun `does not permit rollback to a signer predating the installed key`() {
    val rotatedInstall = identity(
      packageName = PACKAGE_NAME,
      versionName = "0.6.1",
      versionCode = 17L,
      currentSigners = setOf("NEW"),
      signerHistory = setOf("OLD", "NEW"),
    )
    val oldKeyArchive = identity(
      packageName = PACKAGE_NAME,
      versionName = "0.6.2",
      versionCode = 18L,
      currentSigners = setOf("OLD"),
    )

    assertEquals(
      ApkArchivePolicy.Verdict.UNTRUSTED_SIGNER,
      ApkArchivePolicy.verify(
        expectedPackageName = PACKAGE_NAME,
        expectedVersionName = "0.6.2",
        installed = rotatedInstall,
        archive = oldKeyArchive,
      ),
    )
  }

  @Test
  fun `multi signer updates require the exact signer set`() {
    val multiSignerInstall = identity(
      packageName = PACKAGE_NAME,
      versionName = "0.6.1",
      versionCode = 17L,
      currentSigners = setOf("A", "B"),
      hasMultipleSigners = true,
    )
    val missingSignerArchive = identity(
      packageName = PACKAGE_NAME,
      versionName = "0.6.2",
      versionCode = 18L,
      currentSigners = setOf("A"),
    )

    assertEquals(
      ApkArchivePolicy.Verdict.UNTRUSTED_SIGNER,
      ApkArchivePolicy.verify(
        expectedPackageName = PACKAGE_NAME,
        expectedVersionName = "0.6.2",
        installed = multiSignerInstall,
        archive = missingSignerArchive,
      ),
    )
  }

  @Test
  fun `rejects missing expectations metadata and signer identities`() {
    assertEquals(
      ApkArchivePolicy.Verdict.MISSING_EXPECTED_VERSION,
      ApkArchivePolicy.verify(PACKAGE_NAME, null, installed, installed),
    )
    assertEquals(
      ApkArchivePolicy.Verdict.UNREADABLE,
      ApkArchivePolicy.verify(PACKAGE_NAME, "0.6.2", installed, null),
    )
    assertEquals(
      ApkArchivePolicy.Verdict.UNTRUSTED_SIGNER,
      verify(
        ApkPackageIdentity(
          packageName = PACKAGE_NAME,
          versionName = "0.6.2",
          versionCode = 18L,
          signingIdentity = null,
        ),
      ),
    )
  }

  private fun verify(archive: ApkPackageIdentity): ApkArchivePolicy.Verdict {
    return ApkArchivePolicy.verify(
      expectedPackageName = PACKAGE_NAME,
      expectedVersionName = "0.6.2",
      installed = installed,
      archive = archive,
    )
  }

  private fun identity(
    packageName: String,
    versionName: String,
    versionCode: Long,
    currentSigners: Set<String>,
    signerHistory: Set<String> = currentSigners,
    hasMultipleSigners: Boolean = false,
  ) = ApkPackageIdentity(
    packageName = packageName,
    versionName = versionName,
    versionCode = versionCode,
    signingIdentity = ApkSigningIdentity(
      currentSignerSha256 = currentSigners,
      signerHistorySha256 = signerHistory,
      hasMultipleSigners = hasMultipleSigners,
    ),
  )

  private companion object {
    const val PACKAGE_NAME = "com.stremioshell.host.tv"
  }
}
