package com.stremioshell.host.tv

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Stops a plain Gradle connected-test invocation from mutating a personal TV.
 *
 * The opt-in is deliberately supplied only by `scripts/run-tv-instrumentation-physical.sh`, after
 * that wrapper has copied the target app's DataStore outside the app sandbox. Test-local
 * `@After`/`finally` restoration is still useful between cases, but it cannot protect against the
 * instrumentation process dying or Gradle uninstalling the target package.
 */
internal object PhysicalTvInstrumentationGuard {
  const val EXTERNAL_BACKUP_ARGUMENT = "nebula.externalDataStoreBackupCreated"

  fun requireExternalBackupOnPhysicalDevice() {
    if (isEmulator()) return

    val arguments = InstrumentationRegistry.getArguments()
    check(arguments.getString(EXTERNAL_BACKUP_ARGUMENT) == "true") {
      "Refusing to run Nebula instrumentation on physical hardware without an external " +
        "DataStore backup. Use scripts/run-tv-instrumentation-physical.sh; do not run " +
        ":app:connectedDebugAndroidTest directly against a personal TV."
    }
  }

  private fun isEmulator(): Boolean {
    val fingerprint = Build.FINGERPRINT.lowercase()
    val hardware = Build.HARDWARE.lowercase()
    val model = Build.MODEL.lowercase()
    val product = Build.PRODUCT.lowercase()

    return fingerprint.startsWith("generic") ||
      fingerprint.contains("emulator") ||
      hardware == "goldfish" ||
      hardware == "ranchu" ||
      model.contains("emulator") ||
      model.contains("android sdk built for") ||
      product.startsWith("sdk_") ||
      product.contains("_sdk")
  }
}
