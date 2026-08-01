package com.stremioshell.host.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

class ApkUpdateManager(
  private val prefsName: String = "stremio_shell_updater"
) {
  data class DownloadQueryResult(
    val status: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val reason: Int?
  )

  private fun prefs(context: Context) =
    context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

  fun needsUnknownSourcesPermission(context: Context): Boolean {
    return !context.packageManager.canRequestPackageInstalls()
  }

  fun buildUnknownSourcesSettingsIntent(context: Context): Intent {
    return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
      data = Uri.parse("package:${context.packageName}")
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
  }

  fun startDownload(context: Context, info: UpdateInfo): Long {
    val fileName = "StremioShell-${info.latestVersionName}.apk"
    val request = DownloadManager.Request(Uri.parse(info.apkUrl))
      .setTitle("Nebula update")
      .setDescription("Downloading Nebula ${info.latestVersionName}")
      .setMimeType("application/vnd.android.package-archive")
      .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
      // The release APK is ~117 MB; never burn a tethered or roaming connection on it.
      .setAllowedOverMetered(false)
      .setAllowedOverRoaming(false)
      .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)

    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val downloadId = dm.enqueue(request)
    val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)

    prefs(context).edit()
      .putLong(KEY_DOWNLOAD_ID, downloadId)
      .putString(KEY_APK_PATH, file.absolutePath)
      .putString(KEY_DOWNLOADED_VERSION_NAME, normalizeVersionName(info.latestVersionName))
      .putLong(KEY_EXPECTED_SIZE_BYTES, info.apkSizeBytes ?: 0L)
      // Nothing that was verified before this download describes the file that is about to
      // land at that path.
      .remove(KEY_VERIFIED_ARCHIVE)
      .apply()

    return downloadId
  }

  fun queryDownload(context: Context): DownloadQueryResult? {
    val downloadId = prefs(context).getLong(KEY_DOWNLOAD_ID, -1L)
    if (downloadId <= 0L) {
      return null
    }

    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
    cursor.use {
      if (!it.moveToFirst()) {
        return null
      }
      val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
      val bytesDownloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
      val totalBytes = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
      val reason = runCatching {
        it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
      }.getOrNull()
      return DownloadQueryResult(
        status = status,
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
        reason = reason
      )
    }
  }

  fun getActiveDownloadId(context: Context): Long? {
    val downloadId = prefs(context).getLong(KEY_DOWNLOAD_ID, -1L)
    return downloadId.takeIf { it > 0L }
  }

  fun isDownloadInProgress(context: Context): Boolean {
    getActiveDownloadId(context) ?: return false
    val query = queryDownload(context)
    return when (downloadRecordState(query?.status)) {
      DownloadRecordState.IN_PROGRESS -> true
      DownloadRecordState.DOWNLOADED -> false
      DownloadRecordState.STALE -> {
        // A missing DownloadManager row, failure, cancellation, or unknown
        // terminal status is not an active download. Leaving its preference in
        // place wedges every future background check in "in progress".
        clearDownloadedState(context, deleteApk = true)
        false
      }
    }
  }

  fun hasDownloadedApk(context: Context): Boolean {
    if (getActiveDownloadId(context) == null) {
      // A path without its DownloadManager record is incomplete state, not an
      // installable update.
      if (getDownloadedApkFile(context) != null) {
        clearDownloadedState(context, deleteApk = true)
      }
      return false
    }
    val query = queryDownload(context)
    when (downloadRecordState(query?.status)) {
      DownloadRecordState.IN_PROGRESS -> return false
      DownloadRecordState.STALE -> {
        clearDownloadedState(context, deleteApk = true)
        return false
      }
      DownloadRecordState.DOWNLOADED -> Unit
    }
    val apkFile = getDownloadedApkFile(context)
    if (apkFile == null || !apkFile.exists()) {
      clearDownloadedState(context, deleteApk = true)
      return false
    }

    if (!isDownloadedApkInstallable(context)) {
      // Refuse truncated files and archives whose package, version, version
      // code, or signing lineage does not match the expected update.
      clearDownloadedState(context, deleteApk = true)
      return false
    }
    return true
  }

  fun getExpectedApkSizeBytes(context: Context): Long? {
    return prefs(context).getLong(KEY_EXPECTED_SIZE_BYTES, 0L).takeIf { it > 0L }
  }

  fun verifyDownloadedApk(context: Context): DownloadIntegrityPolicy.Verdict {
    val apkFile = getDownloadedApkFile(context)
    val actualSize = apkFile?.takeIf { it.exists() }?.length()
    return DownloadIntegrityPolicy.verify(
      expectedSizeBytes = getExpectedApkSizeBytes(context),
      actualSizeBytes = actualSize
    )
  }

  internal fun verifyDownloadedArchive(context: Context): ApkArchivePolicy.Verdict {
    val apkFile = getDownloadedApkFile(context)
      ?: return ApkArchivePolicy.Verdict.UNREADABLE
    val expectedVersion = getDownloadedVersionName(context)
    val packageManager = context.packageManager
    val flags = packageInfoFlags()

    val installed = try {
      packageManager.getInstalledPackageInfo(context.packageName, flags).toIdentity()
    } catch (_: Exception) {
      null
    }
    val archive = try {
      packageManager.getArchivePackageInfo(apkFile.absolutePath, flags)?.toIdentity()
    } catch (_: Exception) {
      null
    }

    return ApkArchivePolicy.verify(
      expectedPackageName = context.packageName,
      expectedVersionName = expectedVersion,
      installed = installed,
      archive = archive,
    )
  }

  fun hasDownloadedApkForVersion(context: Context, versionName: String): Boolean {
    if (!hasDownloadedApk(context)) {
      return false
    }
    val downloaded = getDownloadedVersionName(context) ?: return false
    return normalizeVersionName(downloaded) == normalizeVersionName(versionName)
  }

  fun hasPendingDownloadedUpdate(context: Context, currentVersionName: String): Boolean {
    if (!hasDownloadedApk(context)) {
      return false
    }

    val downloadedVersion = getDownloadedVersionName(context)
    if (downloadedVersion == null) {
      clearDownloadedState(context, deleteApk = true)
      return false
    }

    val isPending = isNewerVersion(downloadedVersion, currentVersionName)
    if (!isPending) {
      clearDownloadedState(context, deleteApk = true)
    }
    return isPending
  }

  fun getDownloadedApkFile(context: Context): File? {
    val apkPath = prefs(context).getString(KEY_APK_PATH, null) ?: return null
    return File(apkPath)
  }

  fun getDownloadedVersionName(context: Context): String? {
    val stored = prefs(context).getString(KEY_DOWNLOADED_VERSION_NAME, null)?.trim()
    if (!stored.isNullOrEmpty()) {
      return stored
    }

    val fileName = getDownloadedApkFile(context)?.name ?: return null
    return extractVersionFromApkFileName(fileName)
  }

  fun clearDownloadedState(context: Context, deleteApk: Boolean = false) {
    val existingFile = getDownloadedApkFile(context)
    val existingDownloadId = prefs(context).getLong(KEY_DOWNLOAD_ID, -1L)

    if (existingDownloadId > 0) {
      runCatching {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.remove(existingDownloadId)
      }
    }

    if (deleteApk) {
      runCatching {
        if (existingFile?.exists() == true) {
          existingFile.delete()
        }
      }
    }

    prefs(context).edit()
      .remove(KEY_DOWNLOAD_ID)
      .remove(KEY_APK_PATH)
      .remove(KEY_DOWNLOADED_VERSION_NAME)
      .remove(KEY_EXPECTED_SIZE_BYTES)
      .remove(KEY_VERIFIED_ARCHIVE)
      .apply()
  }

  fun buildInstallIntentFromDownloadedApk(context: Context): Intent? {
    val apkFile = getDownloadedApkFile(context) ?: return null
    if (!apkFile.exists()) {
      return null
    }
    if (needsUnknownSourcesPermission(context)) {
      return null
    }
    if (!isDownloadedApkInstallable(context, revalidate = true)) {
      // Last gate before the installer. Re-parse the exact archive in case it
      // was removed or replaced after the prompt was first evaluated - this is
      // the one caller that must not answer from the remembered verdict.
      clearDownloadedState(context, deleteApk = true)
      return null
    }

    val authority = "${context.packageName}.fileprovider"
    val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
    return Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(apkUri, "application/vnd.android.package-archive")
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }

  /**
   * Whether the file on disk may be handed to the package installer.
   *
   * The archive check makes the platform read and signature-verify all ~117 MB of the APK, and
   * the callers are frequent: every return to the app re-evaluates the update prompt, and the
   * periodic worker asks as well. The verdict is therefore remembered against the exact file it
   * was reached for, so a repeat question about an unchanged file costs a stat instead of a full
   * read. [revalidate] forces the read for the caller that is about to install.
   */
  private fun isDownloadedApkInstallable(context: Context, revalidate: Boolean = false): Boolean {
    val fingerprint = getDownloadedApkFile(context)?.let { archiveFingerprint(it) }
    if (!revalidate && fingerprint != null && isRememberedVerifiedArchive(context, fingerprint)) {
      return true
    }

    val installable = DownloadIntegrityPolicy.isInstallable(verifyDownloadedApk(context)) &&
      verifyDownloadedArchive(context) == ApkArchivePolicy.Verdict.VERIFIED
    if (installable && fingerprint != null) {
      prefs(context).edit().putString(KEY_VERIFIED_ARCHIVE, fingerprint).apply()
    }
    // A rejection is never remembered: every caller deletes the file on one, so there would be
    // nothing left for a remembered "no" to describe.
    return installable
  }

  /**
   * Identity of the archive a verdict belongs to. Null when the file is missing or empty, which
   * is a state the full check has to see rather than answer from a stored string.
   */
  private fun archiveFingerprint(file: File): String? {
    val lengthBytes = file.length()
    val lastModifiedMs = file.lastModified()
    if (lengthBytes <= 0L || lastModifiedMs <= 0L) {
      return null
    }
    return verificationFingerprint(file.absolutePath, lengthBytes, lastModifiedMs)
  }

  private fun isRememberedVerifiedArchive(context: Context, fingerprint: String): Boolean =
    prefs(context).getString(KEY_VERIFIED_ARCHIVE, null) == fingerprint

  @Suppress("DEPRECATION")
  private fun PackageManager.getInstalledPackageInfo(
    packageName: String,
    flags: Int,
  ): PackageInfo {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
      getPackageInfo(packageName, flags)
    }
  }

  @Suppress("DEPRECATION")
  private fun PackageManager.getArchivePackageInfo(
    archivePath: String,
    flags: Int,
  ): PackageInfo? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      getPackageArchiveInfo(archivePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
      getPackageArchiveInfo(archivePath, flags)
    }
  }

  @Suppress("DEPRECATION")
  private fun PackageInfo.toIdentity(): ApkPackageIdentity {
    val currentSignatures: Array<out Signature>
    val historySignatures: Array<out Signature>
    val hasMultipleSigners: Boolean

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      val details = signingInfo
      if (details == null) {
        currentSignatures = emptyArray<Signature>()
        historySignatures = emptyArray<Signature>()
        hasMultipleSigners = false
      } else {
        hasMultipleSigners = details.hasMultipleSigners()
        currentSignatures = details.apkContentsSigners.orEmpty()
        historySignatures = if (hasMultipleSigners) {
          currentSignatures
        } else {
          details.signingCertificateHistory.orEmpty()
        }
      }
    } else {
      currentSignatures = signatures.orEmpty()
      historySignatures = currentSignatures
      hasMultipleSigners = currentSignatures.size > 1
    }

    val currentSignerSha256 = currentSignatures.mapTo(linkedSetOf()) { it.sha256() }
    val historySignerSha256 = historySignatures.mapTo(linkedSetOf()) { it.sha256() }
    return ApkPackageIdentity(
      packageName = packageName,
      versionName = versionName,
      versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        longVersionCode
      } else {
        versionCode.toLong()
      },
      signingIdentity = ApkSigningIdentity(
        currentSignerSha256 = currentSignerSha256,
        signerHistorySha256 = historySignerSha256,
        hasMultipleSigners = hasMultipleSigners,
      ),
    )
  }

  private fun Signature.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    val hex = CharArray(digest.size * 2)
    digest.forEachIndexed { index, byte ->
      val value = byte.toInt() and 0xff
      hex[index * 2] = HEX_DIGITS[value ushr 4]
      hex[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
    }
    return String(hex)
  }

  companion object {
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_APK_PATH = "apk_path"
    private const val KEY_DOWNLOADED_VERSION_NAME = "downloaded_version_name"
    private const val KEY_EXPECTED_SIZE_BYTES = "expected_size_bytes"

    /** The [verificationFingerprint] of the archive that last passed the installability check. */
    private const val KEY_VERIFIED_ARCHIVE = "verified_archive"
    private const val HEX_DIGITS = "0123456789ABCDEF"

    /**
     * Identifies a downloaded archive closely enough to reuse a verdict about it.
     *
     * Path, size and modification time are what DownloadManager changes when it writes a
     * different file - including the resumed-and-completed case, where only the last two move.
     * This is a cache key, not a security check: the caller that acts on the verdict re-verifies.
     */
    internal fun verificationFingerprint(
      path: String,
      lengthBytes: Long,
      lastModifiedMs: Long,
    ): String = "$path|$lengthBytes|$lastModifiedMs"

    private fun packageInfoFlags(): Int {
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
      } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
      }
    }

    internal fun isNewerVersion(downloadedVersionName: String, currentVersionName: String): Boolean {
      val downloaded = normalizeVersionName(downloadedVersionName)
      val current = normalizeVersionName(currentVersionName)

      val downloadedSemVer = SemVer.parseOrNull(downloaded)
      val currentSemVer = SemVer.parseOrNull(current)
      return if (downloadedSemVer != null && currentSemVer != null) {
        downloadedSemVer > currentSemVer
      } else {
        downloaded != current
      }
    }

    internal fun normalizeVersionName(raw: String): String {
      return raw
        .trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('-')
    }

    internal fun extractVersionFromApkFileName(fileName: String): String? {
      val prefix = "StremioShell-"
      val suffix = ".apk"
      if (!fileName.startsWith(prefix) || !fileName.endsWith(suffix)) {
        return null
      }
      val rawVersion = fileName.substring(prefix.length, fileName.length - suffix.length)
      return rawVersion.takeIf { it.isNotBlank() }?.let { normalizeVersionName(it) }
    }

    /**
     * Interprets DownloadManager state without treating a stored id as proof of
     * active work. Null is the common stale-id case after the system prunes a
     * record or the user cancels it outside the app.
     */
    internal fun downloadRecordState(status: Int?): DownloadRecordState = when (status) {
      DownloadManager.STATUS_PENDING,
      DownloadManager.STATUS_RUNNING,
      DownloadManager.STATUS_PAUSED -> DownloadRecordState.IN_PROGRESS
      DownloadManager.STATUS_SUCCESSFUL -> DownloadRecordState.DOWNLOADED
      else -> DownloadRecordState.STALE
    }
  }
}

internal enum class DownloadRecordState {
  IN_PROGRESS,
  DOWNLOADED,
  STALE,
}
