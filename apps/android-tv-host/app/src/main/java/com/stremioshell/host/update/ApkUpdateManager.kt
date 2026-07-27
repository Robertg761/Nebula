package com.stremioshell.host.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

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
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
      !context.packageManager.canRequestPackageInstalls()
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
      .setTitle("Stremio Shell update")
      .setDescription("Downloading Stremio Shell ${info.latestVersionName}")
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
    val downloadId = getActiveDownloadId(context) ?: return false
    val query = queryDownload(context)
    if (query == null) {
      clearDownloadedState(context, deleteApk = false)
      return false
    }

    return when (query.status) {
      DownloadManager.STATUS_PENDING,
      DownloadManager.STATUS_RUNNING,
      DownloadManager.STATUS_PAUSED -> true
      DownloadManager.STATUS_FAILED -> {
        clearDownloadedState(context, deleteApk = true)
        false
      }
      DownloadManager.STATUS_SUCCESSFUL -> false
      else -> {
        runCatching {
          val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
          dm.remove(downloadId)
        }
        clearDownloadedState(context, deleteApk = false)
        false
      }
    }
  }

  fun hasDownloadedApk(context: Context): Boolean {
    val apkFile = getDownloadedApkFile(context) ?: return false
    val query = queryDownload(context) ?: return false
    if (query.status != DownloadManager.STATUS_SUCCESSFUL || !apkFile.exists()) {
      return false
    }

    val verdict = verifyDownloadedApk(context)
    if (!DownloadIntegrityPolicy.isInstallable(verdict)) {
      // A truncated APK would only fail at the installer, after the user has
      // committed to the update; drop it so the next check re-downloads.
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
      return true
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
    if (!DownloadIntegrityPolicy.isInstallable(verifyDownloadedApk(context))) {
      // Last gate before the installer: a short file here is a guaranteed
      // "app not installed" dialog, so bin it and let the next check retry.
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

  companion object {
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_APK_PATH = "apk_path"
    private const val KEY_DOWNLOADED_VERSION_NAME = "downloaded_version_name"
    private const val KEY_EXPECTED_SIZE_BYTES = "expected_size_bytes"

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
  }
}
