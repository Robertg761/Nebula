package com.stremioshell.host.update

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Exact completed DownloadManager attempt a visible install prompt was evaluated for. */
data class DownloadedUpdateSnapshot(
  val downloadId: Long,
  val versionName: String,
  /** Immutable release asset id/digest when the publishing API supplied one. */
  val assetIdentity: String?,
  /** Durable tokenized destination; also distinguishes DownloadManager id reuse after DB reset. */
  val destinationIdentity: String? = null,
  /** Bounded public release copy retained with the exact downloaded attempt. */
  val releaseNotes: String = "",
)

/** Result of preparing the exact archive represented by an install prompt. */
sealed interface InstallIntentResult {
  data class Ready(val intent: Intent) : InstallIntentResult

  /** The prompt belongs to an attempt that has since been replaced. Nothing failed. */
  data object StalePrompt : InstallIntentResult

  /** The represented attempt vanished, is blocked, or failed its final verification. */
  data object Failed : InstallIntentResult
}

/** A null provider cursor is temporary unavailability, not evidence that the row is absent. */
internal sealed interface DownloadQueryOutcome {
  data class Found(val result: ApkUpdateManager.DownloadQueryResult) : DownloadQueryOutcome
  data object Missing : DownloadQueryOutcome
  data object Unavailable : DownloadQueryOutcome
}

internal enum class DownloadObservation {
  IN_PROGRESS,
  DOWNLOADED,
  STALE,
  MISSING,
  UNAVAILABLE,
}

/** Pure boundary that keeps provider unavailability distinct from confirmed absence. */
internal fun observeDownload(outcome: DownloadQueryOutcome): DownloadObservation = when (outcome) {
  DownloadQueryOutcome.Unavailable -> DownloadObservation.UNAVAILABLE
  DownloadQueryOutcome.Missing -> DownloadObservation.MISSING
  is DownloadQueryOutcome.Found -> when (ApkUpdateManager.downloadRecordState(outcome.result.status)) {
    DownloadRecordState.IN_PROGRESS -> DownloadObservation.IN_PROGRESS
    DownloadRecordState.DOWNLOADED -> DownloadObservation.DOWNLOADED
    DownloadRecordState.STALE -> DownloadObservation.STALE
  }
}

class ApkUpdateManager(
  private val prefsName: String = "stremio_shell_updater"
) {
  private sealed interface DownloadIdResolution {
    data class Resolved(val id: Long) : DownloadIdResolution
    data object UnresolvedActive : DownloadIdResolution
    data object None : DownloadIdResolution
  }

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

  @SuppressLint("ApplySharedPref") // Both commits are durability barriers around external enqueue.
  fun startDownload(context: Context, info: UpdateInfo): Long {
    val publicationIdentity = downloadPublicationIdentity(info, UUID.randomUUID().toString())
    val fileName = downloadFileName(info.latestVersionName, publicationIdentity)
    val downloadsDirectory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
    val file = File(downloadsDirectory, fileName)
    val legacyFile = File(downloadsDirectory, downloadFileName(info.latestVersionName))
    val request = DownloadManager.Request(Uri.parse(info.apkUrl))
      .setTitle("Nebula update")
      .setDescription("Downloading Nebula ${info.latestVersionName}")
      .setMimeType("application/vnd.android.package-archive")
      // Progress only. VISIBILITY_VISIBLE_NOTIFY_COMPLETED leaves a "download complete"
      // notification whose tap action opens the file in the package installer directly - which
      // walks straight past every check this class exists to perform: the size/digest match, the
      // package name, the version, the versionCode, and the signing lineage. The only supported
      // way into the installer is the in-app prompt, which runs all of them first.
      .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
      // The release APK is ~117 MB; never burn a tethered or roaming connection on it.
      .setAllowedOverMetered(false)
      .setAllowedOverRoaming(false)
      .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)

    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    return DownloadStatePublication.publish(
      writeDescription = {
        if (hasActiveInstallHandoffLease(context)) {
          throw IOException("Package Installer still owns the downloaded update")
        }

        // Builds before the direct handoff lease copied the full APK into private storage. Reclaim
        // only expired copies here so an in-flight handoff created by an older process still works.
        prunePrivateInstallHandoffs(context)

        // Builds before destination identity was introduced used only the version in this path.
        // If one died after enqueue but before publishing its id, no preferences identify its row.
        // Cancel that exact legacy owner (and any same-source row still awaiting a local URI) before
        // publishing the new path, or migration would leave two ~117 MB transfers/files behind.
        if (!cleanupLegacyDownload(context, dm, legacyFile, info.apkUrl)) {
          throw IOException("Could not reconcile a legacy update download")
        }

        // DownloadManager refuses to write over a file that is already there. A leftover at this
        // path is normal after a re-download or a process death, so clear it before enqueueing.
        if (file.exists() && !file.delete()) {
          throw IOException("Could not delete the previous update destination")
        }

        // Description first, id second: a hard kill after enqueue must not leave ~117 MB at a path
        // nothing remembers. The process-local publication gate is the other half of this ordering:
        // foreground evaluation cannot observe this intentional metadata-without-id interval and
        // delete the file while enqueue is still in flight.
        prefs(context).edit()
          .remove(KEY_DOWNLOAD_ID)
          .putString(KEY_APK_PATH, file.absolutePath)
          .putString(KEY_DOWNLOAD_SOURCE_URI, info.apkUrl)
          .putString(KEY_DOWNLOADED_VERSION_NAME, normalizeVersionName(info.latestVersionName))
          .putStringOrRemove(KEY_RELEASE_NOTES, releaseNotesSummary(info.releaseNotes).ifBlank { null })
          .putLong(KEY_EXPECTED_SIZE_BYTES, info.apkSizeBytes ?: 0L)
          .putStringOrRemove(KEY_EXPECTED_SHA256, info.apkSha256?.trim()?.lowercase(Locale.ROOT))
          .putStringOrRemove(KEY_EXPECTED_ASSET_IDENTITY, info.apkAssetIdentity())
          // Nothing that was verified before this download describes the file about to land.
          .remove(KEY_VERIFIED_ARCHIVE)
          .removeInstallHandoffLease()
          .commit()
      },
      enqueue = { dm.enqueue(request) },
      writeId = { downloadId ->
        prefs(context).edit()
          .putLong(KEY_DOWNLOAD_ID, downloadId)
          .commit()
      },
      rollback = { downloadId ->
        rollbackFailedPublication(context, dm, downloadId, file)
      },
    )
  }

  private fun queryDownload(context: Context): DownloadQueryOutcome =
    DownloadStatePublication.exclusive {
      val downloadId = prefs(context).getLong(KEY_DOWNLOAD_ID, -1L)
      if (downloadId <= 0L) {
        return@exclusive DownloadQueryOutcome.Missing
      }

      val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
      queryDownloadId(dm, downloadId)
    }

  /** DownloadProvider failures are not folded into a confirmed empty result. */
  private fun queryDownloadId(
    downloadManager: DownloadManager,
    downloadId: Long,
  ): DownloadQueryOutcome {
    return try {
      val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
        ?: return DownloadQueryOutcome.Unavailable
      cursor.use { result ->
        if (!result.moveToFirst()) {
          return@use DownloadQueryOutcome.Missing
        }
        val status = result.getInt(result.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        val bytesDownloaded = result.getLong(
          result.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
        )
        val totalBytes = result.getLong(
          result.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
        )
        val reason = runCatching {
          result.getInt(result.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
        }.getOrNull()
        DownloadQueryOutcome.Found(
          DownloadQueryResult(
            status = status,
            bytesDownloaded = bytesDownloaded,
            totalBytes = totalBytes,
            reason = reason,
          ),
        )
      }
    } catch (_: Exception) {
      DownloadQueryOutcome.Unavailable
    }
  }

  /** Null means DownloadProvider could not provide a trustworthy ownership snapshot. */
  private fun queryDownloadRows(downloadManager: DownloadManager): List<DownloadRecoveryRow>? {
    return try {
      val cursor = downloadManager.query(DownloadManager.Query()) ?: return null
      cursor.use { result ->
        buildList {
          while (result.moveToNext()) {
            add(
              DownloadRecoveryRow(
                id = result.getLong(result.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)),
                localUri = result.getString(
                  result.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI),
                ),
                sourceUri = result.getString(
                  result.getColumnIndexOrThrow(DownloadManager.COLUMN_URI),
                ),
                status = result.getInt(
                  result.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS),
                ),
              ),
            )
          }
        }
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun cleanupLegacyDownload(
    context: Context,
    downloadManager: DownloadManager,
    legacyFile: File,
    expectedSourceUri: String,
  ): Boolean {
    val rows = queryDownloadRows(downloadManager) ?: return false
    val legacyDestinationUri = Uri.fromFile(legacyFile).toString()
    val ids = LegacyDownloadCleanupPolicy.rowsToCancel(
      legacyDestinationUri = legacyDestinationUri,
      expectedSourceUri = expectedSourceUri,
      rows = rows,
    )
    for (id in ids) {
      val removed = try {
        downloadManager.remove(id)
      } catch (_: Exception) {
        return false
      }
      if (removed <= 0 && queryDownloadId(downloadManager, id) != DownloadQueryOutcome.Missing) {
        return false
      }
    }
    val deleted = try {
      !legacyFile.exists() || legacyFile.delete()
    } catch (_: Exception) {
      false
    }
    if (!deleted) return false

    // Do not let a no-longer-current legacy APK retain a verified-file cache entry. The new
    // description removes it as well, but this keeps the cleanup self-contained on early failure.
    prefs(context).edit().remove(KEY_VERIFIED_ARCHIVE).apply()
    return true
  }

  fun getActiveDownloadId(context: Context): Long? = DownloadStatePublication.exclusive {
    (resolveDownloadId(context) as? DownloadIdResolution.Resolved)?.id
  }

  fun isDownloadInProgress(context: Context): Boolean = DownloadStatePublication.exclusive {
    when (resolveDownloadId(context)) {
      DownloadIdResolution.UnresolvedActive -> return@exclusive true
      DownloadIdResolution.None -> return@exclusive false
      is DownloadIdResolution.Resolved -> Unit
    }
    when (observeDownload(queryDownload(context))) {
      DownloadObservation.UNAVAILABLE -> true
      DownloadObservation.MISSING -> {
        if (!isSalvageablePrunedDownload(context, recordMissing = true)) {
          clearDownloadedState(context)
        }
        false
      }
      DownloadObservation.IN_PROGRESS -> true
      DownloadObservation.DOWNLOADED -> false
      DownloadObservation.STALE -> {
        // A missing DownloadManager row, failure, cancellation, or unknown
        // terminal status is not an active download. Leaving its preference in
        // place wedges every future background check in "in progress".
        //
        // This runs before hasDownloadedApk on every worker pass, so it is also the first place
        // that can throw away a salvageable archive - hence the same check here.
          if (!isSalvageablePrunedDownload(context, recordMissing = false)) {
            clearDownloadedState(context)
          }
          false
      }
    }
  }

  fun hasDownloadedApk(context: Context): Boolean = DownloadStatePublication.exclusive {
    when (resolveDownloadId(context)) {
      DownloadIdResolution.UnresolvedActive -> return@exclusive false
      is DownloadIdResolution.Resolved -> Unit
      DownloadIdResolution.None -> {
        // A path without its DownloadManager record is incomplete state, not an
        // installable update.
        if (getDownloadedApkFile(context) != null) {
          clearDownloadedState(context)
        }
        return@exclusive false
      }
    }
    when (observeDownload(queryDownload(context))) {
      DownloadObservation.UNAVAILABLE -> return@exclusive false
      DownloadObservation.MISSING -> {
        if (!isSalvageablePrunedDownload(context, recordMissing = true)) {
          clearDownloadedState(context)
          return@exclusive false
        }
      }
      DownloadObservation.IN_PROGRESS -> return@exclusive false
      DownloadObservation.STALE -> {
          // A terminal failure is a statement about the transfer, unlike a confirmed missing row.
          if (!isSalvageablePrunedDownload(context, recordMissing = false)) {
            clearDownloadedState(context)
            return@exclusive false
          }
      }
      DownloadObservation.DOWNLOADED -> Unit
    }
    val apkFile = getDownloadedApkFile(context)
    if (apkFile == null || !apkFile.exists()) {
      clearDownloadedState(context)
      return@exclusive false
    }

    val rejection = downloadedApkRejection(context)
    if (rejection != null) {
      // Refuse truncated files and archives whose package, version, version
      // code, or signing lineage does not match the expected update. Only a verdict intrinsic to
      // the release is remembered: a corrupt transfer or transient archive read must be allowed
      // to download again on the next worker pass.
      if (isPermanentRejection(rejection)) {
        rememberRejectedRelease(
          context = context,
          versionName = getDownloadedVersionName(context),
          assetIdentity = getExpectedAssetIdentity(context),
          reason = rejection,
        )
      }
      clearDownloadedState(context)
      return@exclusive false
    }
    true
  }

  /**
   * Resolves the only crash window a pair of durable preference commits cannot close: process death
   * after DownloadManager accepted the request but before its id commit. The persisted destination
   * is the ownership proof. Source URL is used only to defer destructive cleanup while a row has
   * not published its local URI yet; it is never enough to adopt a row.
   */
  @SuppressLint("ApplySharedPref") // Recovery must make ownership durable before exposing the id.
  private fun resolveDownloadId(context: Context): DownloadIdResolution {
    val preferences = prefs(context)
    val storedId = preferences.getLong(KEY_DOWNLOAD_ID, -1L)
    if (storedId > 0L) return DownloadIdResolution.Resolved(storedId)
    val destination = preferences.getString(KEY_APK_PATH, null)?.takeIf { it.isNotBlank() }
      ?: return DownloadIdResolution.None
    val expectedDestinationUri = Uri.fromFile(File(destination)).toString()
    val expectedSourceUri = preferences.getString(KEY_DOWNLOAD_SOURCE_URI, null)
      ?.takeIf { it.isNotBlank() }
    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val rows = queryDownloadRows(downloadManager) ?: run {
      // Provider restart/query failure says nothing about ownership. Preserve both the durable
      // description and destination until a later pass can make an evidence-backed decision.
      return DownloadIdResolution.UnresolvedActive
    }

    return when (
      val decision = DownloadRecoveryPolicy.decide(
        expectedDestinationUri = expectedDestinationUri,
        expectedSourceUri = expectedSourceUri,
        rows = rows,
      )
    ) {
      is DownloadRecoveryDecision.Recover -> {
        val committed = preferences.edit().putLong(KEY_DOWNLOAD_ID, decision.downloadId).commit()
        if (!committed) {
          val failure = IOException("Could not persist recovered update id")
          runCatching {
            rollbackFailedPublication(
              context,
              downloadManager,
              decision.downloadId,
              File(destination),
            )
          }.exceptionOrNull()?.let(failure::addSuppressed)
          throw failure
        }
        DownloadIdResolution.Resolved(decision.downloadId)
      }
      is DownloadRecoveryDecision.Cancel -> {
        for (id in decision.downloadIds) {
          val removed = try {
            downloadManager.remove(id)
          } catch (_: Exception) {
            // Do not delete the shared destination unless every proven owner accepted cancellation.
            return DownloadIdResolution.UnresolvedActive
          }
          if (removed <= 0 && queryDownloadId(downloadManager, id) != DownloadQueryOutcome.Missing) {
            return DownloadIdResolution.UnresolvedActive
          }
        }
        val destinationFile = File(destination)
        if (destinationFile.exists() && !destinationFile.delete()) {
          return DownloadIdResolution.UnresolvedActive
        }
        prunePrivateInstallHandoffs(context)
        if (!preferences.edit().removeDownloadedState().commit()) {
          throw IOException("Could not clear ambiguous update metadata")
        }
        DownloadIdResolution.None
      }
      DownloadRecoveryDecision.Wait -> DownloadIdResolution.UnresolvedActive
      DownloadRecoveryDecision.None -> DownloadIdResolution.None
    }
  }

  /**
   * Whether the release the worker is about to download is the one already rejected on this
   * device, and must not be fetched again.
   *
   * Some rejections are permanent by construction and the retry can only ever end the same way:
   * a release published without bumping versionCode is NOT_NEWER forever, a locally-signed build
   * facing a store-signed release is UNTRUSTED_SIGNER forever. Without this the periodic worker
   * re-downloaded ~117 MB every six hours, indefinitely, to reach the identical verdict.
   *
   * The immutable asset identity is part of the key. GitHub permits a publisher to delete a broken
   * asset and upload corrected bytes under the same tag/name; that replacement gets a new asset id
   * (or digest), so the old verdict must not hide it forever.
   */
  fun isRejectedRelease(
    context: Context,
    updateInfo: UpdateInfo,
  ): Boolean = DownloadStatePublication.exclusive {
    val preferences = prefs(context)
    val rejected = preferences.getString(KEY_REJECTED_VERSION_NAME, null)
    if (rejected.isNullOrBlank()) {
      return@exclusive false
    }
    // Migrate verdicts written by builds that remembered every rejection. A device already
    // carrying CORRUPT/UNREADABLE memory must regain retries too, not only devices that encounter
    // those transient failures after this fix is installed.
    val reason = preferences.getString(KEY_REJECTED_REASON, null).orEmpty()
    if (!isPermanentRejection(reason)) {
      forgetRejectedRelease(context)
      return@exclusive false
    }
    val rejectedAssetIdentity = preferences.getString(KEY_REJECTED_ASSET_IDENTITY, null)
    if (rejectionStillApplies(rejected, rejectedAssetIdentity, updateInfo)) {
      return@exclusive true
    }
    forgetRejectedRelease(context)
    false
  }

  /** The verdict that rejected [getRejectedReleaseVersion]'s release, for diagnostics and logs. */
  fun getRejectedReleaseReason(context: Context): String? =
    prefs(context).getString(KEY_REJECTED_REASON, null)

  fun getRejectedReleaseVersion(context: Context): String? =
    prefs(context).getString(KEY_REJECTED_VERSION_NAME, null)

  private fun rememberRejectedRelease(
    context: Context,
    versionName: String?,
    assetIdentity: String?,
    reason: String,
  ) {
    val normalized = versionName?.let { normalizeVersionName(it) }.orEmpty()
    val immutableAsset = assetIdentity?.takeIf { it.isNotBlank() }
    if (normalized.isBlank() || immutableAsset == null) {
      // Without both halves there is no safe permanent key. Retrying costs bandwidth; suppressing a
      // corrected asset forever costs the update itself, so uncertainty takes the retrying path.
      return
    }
    prefs(context).edit()
      .putString(KEY_REJECTED_VERSION_NAME, normalized)
      .putString(KEY_REJECTED_ASSET_IDENTITY, immutableAsset)
      .putString(KEY_REJECTED_REASON, reason)
      .apply()
  }

  private fun forgetRejectedRelease(context: Context) {
    prefs(context).edit()
      .remove(KEY_REJECTED_VERSION_NAME)
      .remove(KEY_REJECTED_ASSET_IDENTITY)
      .remove(KEY_REJECTED_REASON)
      .apply()
  }

  /** Reads the stored state a pruned-row salvage decision needs and applies the policy to it. */
  private fun isSalvageablePrunedDownload(context: Context, recordMissing: Boolean): Boolean {
    val apkFile = getDownloadedApkFile(context)?.takeIf { it.exists() }
    return canSalvagePrunedDownload(
      recordMissing = recordMissing,
      expectedSizeBytes = getExpectedApkSizeBytes(context),
      actualSizeBytes = apkFile?.length(),
      rememberedFingerprint = prefs(context).getString(KEY_VERIFIED_ARCHIVE, null),
      currentFingerprint = apkFile?.let { archiveFingerprint(it) },
    )
  }

  fun getExpectedApkSizeBytes(context: Context): Long? {
    return prefs(context).getLong(KEY_EXPECTED_SIZE_BYTES, 0L).takeIf { it > 0L }
  }

  /** The SHA-256 the release published for this download, when it published one. */
  fun getExpectedApkSha256(context: Context): String? =
    prefs(context).getString(KEY_EXPECTED_SHA256, null)?.takeIf { it.isNotBlank() }

  private fun getExpectedAssetIdentity(context: Context): String? =
    prefs(context).getString(KEY_EXPECTED_ASSET_IDENTITY, null)?.takeIf { it.isNotBlank() }

  /** Metadata-only read: never queries, verifies, removes or otherwise reconciles current state. */
  private fun currentDownloadedUpdateSnapshot(context: Context): DownloadedUpdateSnapshot? {
    val downloadId = prefs(context).getLong(KEY_DOWNLOAD_ID, -1L).takeIf { it > 0L } ?: return null
    val versionName = getDownloadedVersionName(context)
      ?.let(::normalizeVersionName)
      ?.takeIf { it.isNotBlank() }
      ?: return null
    return DownloadedUpdateSnapshot(
      downloadId = downloadId,
      versionName = versionName,
      assetIdentity = getExpectedAssetIdentity(context),
      destinationIdentity = prefs(context).getString(KEY_APK_PATH, null)?.takeIf { it.isNotBlank() },
      releaseNotes = releaseNotesSummary(prefs(context).getString(KEY_RELEASE_NOTES, null).orEmpty()),
    )
  }

  /** Synchronous rollback when DownloadManager owns a row whose id could not be made durable. */
  @SuppressLint("ApplySharedPref") // Cleanup is the synchronous failure half of publication.
  private fun rollbackFailedPublication(
    context: Context,
    downloadManager: DownloadManager,
    downloadId: Long,
    destination: File,
  ) {
    val removed = try {
      downloadManager.remove(downloadId)
    } catch (error: Exception) {
      // Retain the durable description: recovery can still identify this row on the next pass.
      throw IllegalStateException("Could not cancel unpublished update row", error)
    }
    check(removed > 0 || queryDownloadId(downloadManager, downloadId) == DownloadQueryOutcome.Missing) {
      "Could not confirm unpublished update row cancellation"
    }
    check(!destination.exists() || destination.delete()) {
      "Could not delete unpublished update destination"
    }
    prunePrivateInstallHandoffs(context)
    check(prefs(context).edit().removeDownloadedState().commit()) {
      "Could not clear unpublished update metadata"
    }
  }

  private fun SharedPreferences.Editor.removeDownloadedState(): SharedPreferences.Editor =
    remove(KEY_DOWNLOAD_ID)
      .remove(KEY_APK_PATH)
      .remove(KEY_DOWNLOAD_SOURCE_URI)
      .remove(KEY_DOWNLOADED_VERSION_NAME)
      .remove(KEY_RELEASE_NOTES)
      .remove(KEY_EXPECTED_SIZE_BYTES)
      .remove(KEY_EXPECTED_SHA256)
      .remove(KEY_EXPECTED_ASSET_IDENTITY)
      .remove(KEY_VERIFIED_ARCHIVE)
      .removeInstallHandoffLease()

  private fun SharedPreferences.Editor.removeInstallHandoffLease(): SharedPreferences.Editor =
    remove(KEY_INSTALL_LEASE_IDENTITY)
      .remove(KEY_INSTALL_LEASE_EXPIRES_AT_MS)

  /** True only for a still-current, unexpired lease on the exact persisted download attempt. */
  private fun hasActiveInstallHandoffLease(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
    val preferences = prefs(context)
    return installLeaseBlocksMutation(
      storedIdentity = preferences.getString(KEY_INSTALL_LEASE_IDENTITY, null),
      expiresAtMs = preferences.getLong(KEY_INSTALL_LEASE_EXPIRES_AT_MS, 0L),
      current = currentDownloadedUpdateSnapshot(context),
      nowMs = nowMs,
    )
  }

  /** Durability barrier taken before the direct FileProvider URI leaves this class. */
  @SuppressLint("ApplySharedPref")
  private fun persistInstallHandoffLease(
    context: Context,
    update: DownloadedUpdateSnapshot,
    nowMs: Long,
  ): Boolean {
    val current = currentDownloadedUpdateSnapshot(context) ?: return false
    if (!sameDownloadedUpdate(update, current)) return false
    return prefs(context).edit()
      .putString(KEY_INSTALL_LEASE_IDENTITY, installLeaseIdentity(update))
      .putLong(KEY_INSTALL_LEASE_EXPIRES_AT_MS, installLeaseExpiresAt(nowMs))
      .commit()
  }

  /**
   * Blocking, and when the release published a digest it reads the whole ~117 MB file to hash it.
   * Every caller reaches this through [downloadedApkRejection], which is already documented as
   * off-main-thread work and already remembers its answer per file, so the hash is paid once for
   * a given archive rather than on every prompt evaluation.
   */
  fun verifyDownloadedApk(
    context: Context,
    apkFile: File? = getDownloadedApkFile(context),
  ): DownloadIntegrityPolicy.Verdict {
    val readableFile = apkFile?.takeIf { it.exists() }
    val sizeVerdict = DownloadIntegrityPolicy.verify(
      expectedSizeBytes = getExpectedApkSizeBytes(context),
      actualSizeBytes = readableFile?.length()
    )

    val expectedSha256 = getExpectedApkSha256(context)
    if (!DownloadIntegrityPolicy.requiresDigestVerification(sizeVerdict, expectedSha256)) {
      return sizeVerdict
    }
    return DownloadIntegrityPolicy.verifyDigest(
      expectedSha256 = expectedSha256,
      actualSha256 = readableFile?.let { sha256OrNull(it) },
    )
  }

  /** Null when the file cannot be read through, which the integrity policy treats as CORRUPT. */
  private fun sha256OrNull(file: File): String? = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
      val buffer = ByteArray(DEFAULT_HASH_BUFFER_BYTES)
      while (true) {
        val read = stream.read(buffer)
        if (read <= 0) break
        digest.update(buffer, 0, read)
      }
    }
    digest.digest().toLowerHex()
  }.getOrNull()

  internal fun verifyDownloadedArchive(
    context: Context,
    apkFile: File? = getDownloadedApkFile(context),
  ): ApkArchivePolicy.Verdict {
    val archiveFile = apkFile
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
      packageManager.getArchivePackageInfo(archiveFile.absolutePath, flags)?.toIdentity()
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

  fun hasDownloadedApkForVersion(
    context: Context,
    versionName: String,
  ): Boolean = DownloadStatePublication.exclusive {
    if (!hasDownloadedApk(context)) {
      return@exclusive false
    }
    val downloaded = getDownloadedVersionName(context) ?: return@exclusive false
    normalizeVersionName(downloaded) == normalizeVersionName(versionName)
  }

  fun hasPendingDownloadedUpdate(
    context: Context,
    currentVersionName: String,
  ): Boolean = pendingDownloadedUpdate(context, currentVersionName) != null

  /**
   * Atomically validates and snapshots the exact completed attempt a prompt may act on. Version,
   * immutable asset identity and DownloadManager id cross the publication boundary together.
   */
  fun pendingDownloadedUpdate(
    context: Context,
    currentVersionName: String,
  ): DownloadedUpdateSnapshot? = DownloadStatePublication.exclusive {
    val downloadedVersion = getDownloadedVersionName(context)
    if (downloadedVersion == null) {
      clearDownloadedState(context)
      return@exclusive null
    }

    val isPending = isNewerVersion(downloadedVersion, currentVersionName)
    if (!isPending) {
      // Check this before archive verification. After an upgrade, the installed package is expected
      // to make the old archive fail NOT_NEWER; that is cleanup, not a rejected future release.
      // The new process also proves Package Installer finished, so its old lease may be released.
      clearDownloadedState(context, ignoreActiveInstallLease = true)
      return@exclusive null
    }

    if (!hasDownloadedApk(context)) {
      return@exclusive null
    }
    currentDownloadedUpdateSnapshot(context)
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

  /**
   * Drops the download: its DownloadManager row, the file on disk, and everything remembered
   * about it.
   *
   * This used to take a `deleteApk: Boolean = false` parameter, which was a lie in both
   * directions - `dm.remove` deletes the file DownloadManager wrote whatever the flag says, and
   * every caller in the app passed `true` anyway. Keeping a downloaded APK while forgetting its
   * expected size, version and verdict would leave an unidentifiable ~117 MB file in the app's
   * external files directory, which is not a state anything here wants.
   *
   * @return false when an id-less row cannot be identified or Package Installer holds an active
   * direct-file lease. The caller must retry instead of deleting or replacing those bytes.
   */
  @SuppressLint("ApplySharedPref") // The return value promises that ownership was durably cleared.
  fun clearDownloadedState(context: Context): Boolean =
    clearDownloadedState(context, ignoreActiveInstallLease = false)

  @SuppressLint("ApplySharedPref") // The return value promises that ownership was durably cleared.
  private fun clearDownloadedState(
    context: Context,
    ignoreActiveInstallLease: Boolean,
  ): Boolean = DownloadStatePublication.exclusive {
    if (!ignoreActiveInstallLease && hasActiveInstallHandoffLease(context)) {
      return@exclusive false
    }
    val resolution = resolveDownloadId(context)
    if (resolution == DownloadIdResolution.UnresolvedActive) {
      // A durable description with no id can still own an enqueue whose local URI has not appeared
      // yet (or whose provider query is temporarily unavailable). Deleting its path here would
      // recreate the original orphan race; a later pass will recover or safely classify it.
      return@exclusive false
    }
    val existingFile = getDownloadedApkFile(context)
    val existingDownloadId = (resolution as? DownloadIdResolution.Resolved)?.id ?: -1L

    if (existingDownloadId > 0L) {
      val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
      val removed = try {
        dm.remove(existingDownloadId)
      } catch (_: Exception) {
        return@exclusive false
      }
      if (removed <= 0) {
        // Zero is safe only after the provider confirms the row is already gone. During a provider
        // restart it can be indistinguishable from a failed cancellation, so retain ownership.
        when (queryDownload(context)) {
          DownloadQueryOutcome.Missing -> Unit
          DownloadQueryOutcome.Unavailable,
          is DownloadQueryOutcome.Found,
          -> return@exclusive false
        }
      }
    }

    // Belt and braces: dm.remove only deletes what DownloadManager still has a row for, and the
    // pruned-row case is exactly when it does not.
    if (existingFile?.exists() == true) {
      val deleted = try {
        existingFile.delete()
      } catch (_: Exception) {
        false
      }
      if (!deleted) return@exclusive false
    }
    val handoffsPruned = prunePrivateInstallHandoffs(
      context = context,
      deleteAll = ignoreActiveInstallLease,
    )
    if (ignoreActiveInstallLease && !handoffsPruned) return@exclusive false

    prefs(context).edit().removeDownloadedState().commit()
  }

  fun buildInstallIntentFromDownloadedApk(
    context: Context,
    expectedUpdate: DownloadedUpdateSnapshot,
  ): InstallIntentResult = DownloadStatePublication.exclusive {
    // This must be the first state decision in the install path. A stale prompt is not evidence that
    // the replacement attempt is corrupt, so mismatch returns without querying DownloadManager,
    // touching either file, or clearing/cancelling any current state.
    val currentUpdate = currentDownloadedUpdateSnapshot(context)
    staleInstallPromptResult(expectedUpdate, currentUpdate)?.let { return@exclusive it }
    val downloadedFile = getDownloadedApkFile(context) ?: return@exclusive InstallIntentResult.Failed
    if (!downloadedFile.exists()) {
      return@exclusive InstallIntentResult.Failed
    }
    if (needsUnknownSourcesPermission(context)) {
      return@exclusive InstallIntentResult.Failed
    }
    val rejection = downloadedApkRejection(context, revalidate = true, apkFile = downloadedFile)
    if (rejection != null) {
      // Last gate before the installer. Re-parse the exact archive in case it
      // was removed or replaced after the prompt was first evaluated - this is
      // the one caller that must not answer from the remembered verdict.
      if (isPermanentRejection(rejection)) {
        rememberRejectedRelease(
          context = context,
          versionName = getDownloadedVersionName(context),
          assetIdentity = getExpectedAssetIdentity(context),
          reason = rejection,
        )
      }
      clearDownloadedState(context)
      return@exclusive InstallIntentResult.Failed
    }

    val authority = "${context.packageName}.fileprovider"
    val apkUri = runCatching {
      // The DownloadManager destination is already under FileProvider's external-files path. A
      // direct URI avoids allocating a second full APK on constrained TV storage.
      FileProvider.getUriForFile(context, authority, downloadedFile)
    }.getOrElse {
      return@exclusive InstallIntentResult.Failed
    }
    // Commit before the URI escapes. Every clear/replacement path observes this durable lease under
    // the same process lock, and a restarted process observes it through SharedPreferences.
    if (!persistInstallHandoffLease(context, expectedUpdate, System.currentTimeMillis())) {
      return@exclusive InstallIntentResult.Failed
    }
    InstallIntentResult.Ready(
      Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      },
    )
  }

  /**
   * Why the file on disk may not be handed to the package installer, or null if it may.
   *
   * Both checks are expensive: the integrity check hashes the ~117 MB archive whenever the
   * release published a digest, and the archive check makes the platform read and
   * signature-verify all of it. The callers are frequent - every return to the app re-evaluates
   * the update prompt, and the periodic worker asks as well - so the *passing* verdict is
   * remembered against the exact file it was reached for, and a repeat question about an
   * unchanged file costs a stat instead of two full reads. [revalidate] forces the reads for the
   * caller that is about to install.
   *
   * Blocking. Call it off the main thread; UpdatePromptCoordinator.evaluate says the same.
   *
   * The returned string is the name of the verdict that refused the file. It is not shown to
   * anyone: [rememberRejectedRelease] stores it so a permanently-rejected release can be
   * recognised - and skipped - the next time the worker considers downloading it.
   */
  private fun downloadedApkRejection(
    context: Context,
    revalidate: Boolean = false,
    apkFile: File? = getDownloadedApkFile(context),
  ): String? {
    val downloadedFile = getDownloadedApkFile(context)
    val isCanonicalDownload = apkFile?.absolutePath == downloadedFile?.absolutePath
    val fingerprint = apkFile?.let { archiveFingerprint(it) }
    if (
      isCanonicalDownload &&
      !revalidate &&
      fingerprint != null &&
      isRememberedVerifiedArchive(context, fingerprint)
    ) {
      return null
    }

    val integrity = verifyDownloadedApk(context, apkFile)
    if (!DownloadIntegrityPolicy.isInstallable(integrity)) {
      return integrity.name
    }
    val archive = verifyDownloadedArchive(context, apkFile)
    if (archive != ApkArchivePolicy.Verdict.VERIFIED) {
      return archive.name
    }

    if (isCanonicalDownload && fingerprint != null) {
      prefs(context).edit().putString(KEY_VERIFIED_ARCHIVE, fingerprint).apply()
    }
    // A rejection is not remembered *against the file*: every caller deletes the file on one, so
    // there would be nothing left for a remembered "no" to describe. It is remembered against the
    // release version instead, which outlives the file.
    return null
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

  /** Removes private handoff copies left by builds that predate the direct-file lease. */
  private fun prunePrivateInstallHandoffs(
    context: Context,
    deleteAll: Boolean = false,
  ): Boolean {
    val nowMs = System.currentTimeMillis()
    return try {
      val directory = privateInstallDirectory(context)
      if (!directory.exists()) return true
      val files = directory.listFiles() ?: return false
      files.forEach { file ->
        if (
          file.isFile &&
          shouldPruneInstallHandoff(file.name, file.lastModified(), nowMs, deleteAll)
        ) {
          if (!file.delete()) return false
        }
      }
      true
    } catch (_: Exception) {
      false
    }
  }

  private fun privateInstallDirectory(context: Context): File = File(context.filesDir, "updates")

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

  private fun Signature.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray()).toLowerHex()

  companion object {
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_APK_PATH = "apk_path"
    private const val KEY_DOWNLOAD_SOURCE_URI = "download_source_uri"
    private const val KEY_DOWNLOADED_VERSION_NAME = "downloaded_version_name"
    private const val KEY_RELEASE_NOTES = "release_notes"
    private const val KEY_EXPECTED_SIZE_BYTES = "expected_size_bytes"

    /** Lower-case hex SHA-256 the release published for this download, absent if it published none. */
    private const val KEY_EXPECTED_SHA256 = "expected_sha256"

    /** Immutable GitHub id/digest for the asset whose bytes this state describes. */
    private const val KEY_EXPECTED_ASSET_IDENTITY = "expected_asset_identity"

    /** The [verificationFingerprint] of the archive that last passed the installability check. */
    private const val KEY_VERIFIED_ARCHIVE = "verified_archive"

    /** Exact downloaded attempt whose original file Package Installer may still be reading. */
    private const val KEY_INSTALL_LEASE_IDENTITY = "install_lease_identity"
    private const val KEY_INSTALL_LEASE_EXPIRES_AT_MS = "install_lease_expires_at_ms"

    /**
     * The release whose archive was refused on this device, and the verdict that refused it.
     * Outlives [clearDownloadedState] on purpose: the whole point is to remember something about
     * a download after the download is gone.
     */
    private const val KEY_REJECTED_VERSION_NAME = "rejected_version_name"
    private const val KEY_REJECTED_ASSET_IDENTITY = "rejected_asset_identity"
    private const val KEY_REJECTED_REASON = "rejected_reason"

    private const val HEX_DIGITS = "0123456789abcdef"

    /** 64 KB: one page-cache-friendly read per iteration over a ~117 MB file. */
    private const val DEFAULT_HASH_BUFFER_BYTES = 64 * 1024

    /** Retention for legacy private copies created by builds before the direct handoff lease. */
    private const val INSTALL_HANDOFF_RETENTION_MS = 7L * 24L * 60L * 60L * 1_000L

    /** Long enough for Package Installer to stage the granted file, bounded so cancellation recovers. */
    internal const val INSTALL_HANDOFF_LEASE_MS = 60L * 60L * 1_000L

    private fun ByteArray.toLowerHex(): String {
      val hex = CharArray(size * 2)
      forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        hex[index * 2] = HEX_DIGITS[value ushr 4]
        hex[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
      }
      return String(hex)
    }

    /** Writes [value], or removes the key when there is nothing to write. */
    private fun SharedPreferences.Editor.putStringOrRemove(
      key: String,
      value: String?,
    ): SharedPreferences.Editor =
      if (value.isNullOrBlank()) remove(key) else putString(key, value)

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

    /**
     * The comparable form of a version, `v` prefix and flavor suffix removed.
     *
     * It keeps the pre-release suffix: this used to cut the string at the first `-`, which made
     * `0.6.2-beta.1` indistinguishable from `0.6.2` everywhere the updater compares two versions.
     * See [SemVer.normalizeLabel], which is the single definition of that shape.
     */
    internal fun normalizeVersionName(raw: String): String = SemVer.normalizeLabel(raw)

    /**
     * Keeps release copy readable in a TV dialog and prevents a long GitHub body from becoming
     * durable updater state. Markdown headings are presentation punctuation here, not useful text.
     */
    internal fun releaseNotesSummary(raw: String): String {
      val lines = raw
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lineSequence()
        .map { line -> line.trim().trimStart('#').trim() }
        .filter { it.isNotEmpty() }
        .take(MAX_RELEASE_NOTE_LINES)
        .toList()
      if (lines.isEmpty()) return ""
      val joined = lines.joinToString("\n")
      if (joined.length <= MAX_RELEASE_NOTE_CHARS) return joined
      val prefix = joined.take(MAX_RELEASE_NOTE_CHARS - 1).let { clipped ->
        if (clipped.lastOrNull() in '\uD800'..'\uDBFF') clipped.dropLast(1) else clipped
      }
      return prefix.trimEnd() + "\u2026"
    }

    /** Whether two raw version strings name the same release. */
    internal fun isSameRelease(left: String, right: String): Boolean =
      normalizeVersionName(left) == normalizeVersionName(right)

    /** A permanent verdict belongs to one immutable upload, not merely to its mutable release tag. */
    internal fun rejectionStillApplies(
      rejectedVersionName: String,
      rejectedAssetIdentity: String?,
      updateInfo: UpdateInfo,
    ): Boolean {
      val currentAssetIdentity = updateInfo.apkAssetIdentity()
      return isSameRelease(rejectedVersionName, updateInfo.latestVersionName) &&
        !rejectedAssetIdentity.isNullOrBlank() &&
        currentAssetIdentity != null &&
        rejectedAssetIdentity == currentAssetIdentity
    }

    /** Version is normalized defensively; id distinguishes two attempts for the same release asset. */
    internal fun sameDownloadedUpdate(
      expected: DownloadedUpdateSnapshot,
      current: DownloadedUpdateSnapshot,
    ): Boolean =
      expected.downloadId == current.downloadId &&
        isSameRelease(expected.versionName, current.versionName) &&
        expected.assetIdentity == current.assetIdentity &&
        expected.destinationIdentity == current.destinationIdentity

    /** Null only while [current] is still the exact attempt the user acted on. */
    internal fun staleInstallPromptResult(
      expected: DownloadedUpdateSnapshot,
      current: DownloadedUpdateSnapshot?,
    ): InstallIntentResult? = if (current == null || !sameDownloadedUpdate(expected, current)) {
      InstallIntentResult.StalePrompt
    } else {
      null
    }

    /** Only release-intrinsic failures can safely suppress every retry of that release. */
    internal fun isPermanentRejection(reason: String): Boolean = reason in setOf(
      ApkArchivePolicy.Verdict.WRONG_PACKAGE.name,
      ApkArchivePolicy.Verdict.WRONG_VERSION.name,
      ApkArchivePolicy.Verdict.NOT_NEWER.name,
      ApkArchivePolicy.Verdict.UNTRUSTED_SIGNER.name,
    )

    /** Stable lease identity for bytes verified on behalf of one exact DownloadManager attempt. */
    internal fun installLeaseIdentity(update: DownloadedUpdateSnapshot): String {
      val identity = buildString {
        append(update.downloadId)
        append('|')
        append(normalizeVersionName(update.versionName))
        append('|')
        append(update.assetIdentity.orEmpty())
        append('|')
        append(update.destinationIdentity.orEmpty())
      }
      val token = MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .toLowerHex()
      return "${update.downloadId}:$token"
    }

    internal fun installLeaseExpiresAt(nowMs: Long): Long =
      if (nowMs > Long.MAX_VALUE - INSTALL_HANDOFF_LEASE_MS) {
        Long.MAX_VALUE
      } else {
        nowMs + INSTALL_HANDOFF_LEASE_MS
      }

    /** A lease blocks mutation only for its exact current attempt and before its deadline. */
    internal fun installLeaseBlocksMutation(
      storedIdentity: String?,
      expiresAtMs: Long,
      current: DownloadedUpdateSnapshot?,
      nowMs: Long,
    ): Boolean =
      !storedIdentity.isNullOrBlank() &&
      current != null &&
      storedIdentity == installLeaseIdentity(current) &&
      expiresAtMs > nowMs

    /** Partial copies are never shared; completed handoffs get a generous installer-read lease. */
    internal fun shouldPruneInstallHandoff(
      fileName: String,
      lastModifiedMs: Long,
      nowMs: Long,
      deleteAll: Boolean = false,
    ): Boolean {
      if (fileName.endsWith(".apk.part")) return true
      if (!fileName.endsWith(".apk")) return false
      if (deleteAll) return true
      if (lastModifiedMs <= 0L || nowMs < lastModifiedMs) return false
      return nowMs - lastModifiedMs >= INSTALL_HANDOFF_RETENTION_MS
    }

    /**
     * The name DownloadManager writes the archive under.
     *
     * The version goes into a path, and a git ref may legally contain `/` (`v1.2.3/hotfix` is a
     * valid tag name), so the raw tag cannot be interpolated into a file name: a separator would
     * send the write somewhere other than the intended directory, and a name of `..` would send
     * it up out of it. Anything outside the conservative set below becomes `_`.
     */
    internal fun downloadFileName(
      versionName: String,
      publicationIdentity: String? = null,
    ): String {
      val version = sanitizeFileNameComponent(versionName)
      if (publicationIdentity == null) return "StremioShell-$version.apk"
      val identityToken = MessageDigest.getInstance("SHA-256")
        .digest(publicationIdentity.toByteArray(Charsets.UTF_8))
        .toLowerHex()
        .take(16)
      // Leave ample room under the usual 255-byte component limit even for an adversarial tag.
      return "StremioShell-${version.take(120)}-$identityToken.apk"
    }

    /** Immutable releases are stable; an unidentifiable publication gets a per-enqueue nonce. */
    internal fun downloadPublicationIdentity(info: UpdateInfo, fallbackNonce: String): String =
      info.apkAssetIdentity() ?: "attempt:$fallbackNonce"

    internal fun sanitizeFileNameComponent(raw: String): String {
      val mapped = raw.trim().map { char ->
        if (char.isLetterOrDigit() && char.code < 128 || char == '.' || char == '_' || char == '-') {
          char
        } else {
          '_'
        }
      }.joinToString("")
      // A component that is empty, or is only dots, is not a name - it is the current or parent
      // directory, or nothing at all.
      return if (mapped.isBlank() || mapped.all { it == '.' }) FALLBACK_FILE_NAME_COMPONENT else mapped
    }

    private const val FALLBACK_FILE_NAME_COMPONENT = "update"
    private const val MAX_RELEASE_NOTE_LINES = 5
    private const val MAX_RELEASE_NOTE_CHARS = 700

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

    /**
     * Whether a download whose DownloadManager row has vanished should survive.
     *
     * The system prunes rows on its own schedule and the user can clear them from the Downloads
     * app, neither of which says anything about the file. Treating that as a dead download threw
     * away a complete, already-verified ~117 MB APK and made the worker fetch it all over again.
     *
     * The bar for keeping it is deliberately narrow, because the alternative to keeping a file is
     * only a re-download: the row must be *missing* rather than failed or cancelled (a failure is
     * a statement about the file), the file must still be exactly the size the release published,
     * and it must be the same file the installability check already passed - same path, same
     * size, same modification time. Nothing here is trusted as a security decision; the install
     * path re-verifies the archive from scratch with `revalidate = true`.
     */
    internal fun canSalvagePrunedDownload(
      recordMissing: Boolean,
      expectedSizeBytes: Long?,
      actualSizeBytes: Long?,
      rememberedFingerprint: String?,
      currentFingerprint: String?,
    ): Boolean {
      if (!recordMissing) {
        return false
      }
      if (expectedSizeBytes == null || expectedSizeBytes <= 0L) {
        return false
      }
      if (actualSizeBytes == null || actualSizeBytes != expectedSizeBytes) {
        return false
      }
      return currentFingerprint != null && currentFingerprint == rememberedFingerprint
    }
  }
}

internal enum class DownloadRecordState {
  IN_PROGRESS,
  DOWNLOADED,
  STALE,
}

internal data class DownloadRecoveryRow(
  val id: Long,
  val localUri: String?,
  val sourceUri: String?,
  val status: Int,
)

internal class DownloadPublicationException(message: String) : IOException(message)

internal sealed interface DownloadRecoveryDecision {
  data class Recover(val downloadId: Long) : DownloadRecoveryDecision
  data class Cancel(val downloadIds: Set<Long>) : DownloadRecoveryDecision
  data object Wait : DownloadRecoveryDecision
  data object None : DownloadRecoveryDecision
}

/** Identifies updater rows left by the pre-identity, version-only destination scheme. */
internal object LegacyDownloadCleanupPolicy {
  fun rowsToCancel(
    legacyDestinationUri: String,
    expectedSourceUri: String,
    rows: List<DownloadRecoveryRow>,
  ): Set<Long> = rows.asSequence()
    .filter { row ->
      row.id > 0L && (
        row.localUri == legacyDestinationUri ||
          (row.localUri == null && row.sourceUri == expectedSourceUri)
        )
    }
    .mapTo(linkedSetOf()) { it.id }
}

/** Pure ownership policy for the enqueue-to-id-commit process-death window. */
internal object DownloadRecoveryPolicy {
  fun decide(
    expectedDestinationUri: String,
    expectedSourceUri: String?,
    rows: List<DownloadRecoveryRow>,
  ): DownloadRecoveryDecision {
    val exactDestination = rows.filter { row ->
      row.id > 0L && row.localUri == expectedDestinationUri
    }
    if (exactDestination.size == 1) {
      val row = exactDestination.single()
      return if (expectedSourceUri == null || row.sourceUri == expectedSourceUri) {
        DownloadRecoveryDecision.Recover(row.id)
      } else {
        // The destination is ours but the persisted source proves the row is not. Cancel the
        // conflict rather than adopting its bytes under this update's expected metadata.
        DownloadRecoveryDecision.Cancel(setOf(row.id))
      }
    }
    if (exactDestination.size > 1) {
      // There cannot safely be two owners of one destination. Cancel only the rows proven to target
      // that exact file and let the normal worker retry from a clean description.
      return DownloadRecoveryDecision.Cancel(exactDestination.mapTo(linkedSetOf()) { it.id })
    }

    val unresolvedInFlight = rows.any { row ->
      val sourceCouldBeOurs = expectedSourceUri == null || row.sourceUri == expectedSourceUri
      row.localUri == null &&
        sourceCouldBeOurs && ApkUpdateManager.downloadRecordState(row.status) ==
        DownloadRecordState.IN_PROGRESS
    }
    return if (unresolvedInFlight) DownloadRecoveryDecision.Wait else DownloadRecoveryDecision.None
  }
}

/**
 * Process-local publication gate for updater state and DownloadManager IPC.
 *
 * Every state-reconciling reader enters [exclusive], while [publish] keeps both durable preference
 * commits and enqueue between one pair of monitor edges. The description commit must succeed before
 * enqueue; an id-commit failure rolls the accepted row back. Process death can still occur after
 * enqueue returns and before the id commit begins, so [DownloadRecoveryPolicy] separately recovers
 * ownership only from an exact persisted destination and defers cleanup while its local URI is not
 * available.
 */
internal object DownloadStatePublication {
  private val lock = Any()

  internal fun <T> exclusive(block: () -> T): T = synchronized(lock, block)

  fun <T> publish(
    writeDescription: () -> Boolean,
    enqueue: () -> T,
    writeId: (T) -> Boolean,
    rollback: (T) -> Unit,
  ): T = exclusive {
    if (!writeDescription()) {
      throw DownloadPublicationException("Could not persist update metadata before enqueue")
    }
    val id = enqueue()
    val publicationFailure = runCatching {
      if (!writeId(id)) {
        throw DownloadPublicationException("Could not persist enqueued update id")
      }
    }.exceptionOrNull()
    if (publicationFailure != null) {
      runCatching { rollback(id) }
        .exceptionOrNull()
        ?.let(publicationFailure::addSuppressed)
      throw publicationFailure
    }
    id
  }
}
