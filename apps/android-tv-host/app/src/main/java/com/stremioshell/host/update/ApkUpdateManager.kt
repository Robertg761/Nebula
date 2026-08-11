package com.stremioshell.host.update

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
import java.security.MessageDigest
import java.util.Locale

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
    val fileName = downloadFileName(info.latestVersionName)
    val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)

    // DownloadManager refuses to write over a file that is already there: the request fails
    // outright with ERROR_FILE_ALREADY_EXISTS. A leftover at this path is normal - a re-download
    // of the same release names the same file, and a download whose id was never persisted
    // leaves one behind - so clear it rather than enqueue a request that cannot succeed.
    runCatching {
      if (file.exists()) {
        file.delete()
      }
    }

    // Two writes, in this order, on purpose. Everything that *describes* the file is recorded
    // before DownloadManager is allowed to start writing it, and the download id is recorded
    // after - which is the opposite of what this used to do.
    //
    // The failure the old single-write-after-enqueue order allowed was an enqueue whose state
    // never landed: DownloadManager downloads ~117 MB to a path nothing in the app remembers, and
    // that file stays there. Split this way, the only gap left is a description with no id, and
    // every reader here treats that as incomplete state and deletes both the entry and the file.
    // A hard kill between the two writes can still cost the queued apply(), and the file it
    // leaves behind is cleaned up by the delete at the top of the next startDownload rather than
    // accumulating.
    prefs(context).edit()
      .remove(KEY_DOWNLOAD_ID)
      .putString(KEY_APK_PATH, file.absolutePath)
      .putString(KEY_DOWNLOADED_VERSION_NAME, normalizeVersionName(info.latestVersionName))
      .putLong(KEY_EXPECTED_SIZE_BYTES, info.apkSizeBytes ?: 0L)
      .putStringOrRemove(KEY_EXPECTED_SHA256, info.apkSha256?.trim()?.lowercase(Locale.ROOT))
      // Nothing that was verified before this download describes the file that is about to
      // land at that path.
      .remove(KEY_VERIFIED_ARCHIVE)
      .apply()

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
    val downloadId = dm.enqueue(request)

    prefs(context).edit()
      .putLong(KEY_DOWNLOAD_ID, downloadId)
      .apply()

    return downloadId
  }

  fun queryDownload(context: Context): DownloadQueryResult? {
    val downloadId = prefs(context).getLong(KEY_DOWNLOAD_ID, -1L)
    if (downloadId <= 0L) {
      return null
    }

    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    // Documented as nullable, and it really does come back null - the provider is a separate
    // process, so a query during its restart or after the row is gone returns nothing at all
    // rather than an empty cursor. `cursor.use` on that threw an NPE out of every caller: the
    // prompt evaluated on resume, so the app crashed coming back to the foreground, and the
    // periodic worker died with it.
    val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId)) ?: return null
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
        //
        // This runs before hasDownloadedApk on every worker pass, so it is also the first place
        // that can throw away a salvageable archive - hence the same check here.
        if (!isSalvageablePrunedDownload(context, recordMissing = query == null)) {
          clearDownloadedState(context)
        }
        false
      }
    }
  }

  fun hasDownloadedApk(context: Context): Boolean {
    if (getActiveDownloadId(context) == null) {
      // A path without its DownloadManager record is incomplete state, not an
      // installable update.
      if (getDownloadedApkFile(context) != null) {
        clearDownloadedState(context)
      }
      return false
    }
    val query = queryDownload(context)
    when (downloadRecordState(query?.status)) {
      DownloadRecordState.IN_PROGRESS -> return false
      DownloadRecordState.STALE -> {
        // A pruned row is not a bad file. The system drops DownloadManager rows on its own
        // schedule, and destroying a complete, already-verified ~117 MB APK because its
        // bookkeeping row aged out cost the user the whole download again. Keep the archive when
        // it is the one we verified; the install path re-validates it from scratch anyway.
        if (!isSalvageablePrunedDownload(context, recordMissing = query == null)) {
          clearDownloadedState(context)
          return false
        }
      }
      DownloadRecordState.DOWNLOADED -> Unit
    }
    val apkFile = getDownloadedApkFile(context)
    if (apkFile == null || !apkFile.exists()) {
      clearDownloadedState(context)
      return false
    }

    val rejection = downloadedApkRejection(context)
    if (rejection != null) {
      // Refuse truncated files and archives whose package, version, version
      // code, or signing lineage does not match the expected update. Remember which release
      // this was before dropping it, so the worker does not spend another ~117 MB on the same
      // bad archive in six hours' time.
      rememberRejectedRelease(context, getDownloadedVersionName(context), rejection)
      clearDownloadedState(context)
      return false
    }
    return true
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
   * Asking also *clears* the memory when [versionName] is a different release, because a new
   * version is exactly the event that makes the remembered verdict stop describing anything.
   */
  fun isRejectedRelease(context: Context, versionName: String): Boolean {
    val rejected = prefs(context).getString(KEY_REJECTED_VERSION_NAME, null)
    if (rejected.isNullOrBlank()) {
      return false
    }
    if (isSameRelease(rejected, versionName)) {
      return true
    }
    forgetRejectedRelease(context)
    return false
  }

  /** The verdict that rejected [getRejectedReleaseVersion]'s release, for diagnostics and logs. */
  fun getRejectedReleaseReason(context: Context): String? =
    prefs(context).getString(KEY_REJECTED_REASON, null)

  fun getRejectedReleaseVersion(context: Context): String? =
    prefs(context).getString(KEY_REJECTED_VERSION_NAME, null)

  private fun rememberRejectedRelease(context: Context, versionName: String?, reason: String) {
    val normalized = versionName?.let { normalizeVersionName(it) }.orEmpty()
    if (normalized.isBlank()) {
      // Nothing to key the memory on; the next check re-downloads, which is the old behaviour.
      return
    }
    prefs(context).edit()
      .putString(KEY_REJECTED_VERSION_NAME, normalized)
      .putString(KEY_REJECTED_REASON, reason)
      .apply()
  }

  private fun forgetRejectedRelease(context: Context) {
    prefs(context).edit()
      .remove(KEY_REJECTED_VERSION_NAME)
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

  /**
   * Blocking, and when the release published a digest it reads the whole ~117 MB file to hash it.
   * Every caller reaches this through [downloadedApkRejection], which is already documented as
   * off-main-thread work and already remembers its answer per file, so the hash is paid once for
   * a given archive rather than on every prompt evaluation.
   */
  fun verifyDownloadedApk(context: Context): DownloadIntegrityPolicy.Verdict {
    val apkFile = getDownloadedApkFile(context)?.takeIf { it.exists() }
    val sizeVerdict = DownloadIntegrityPolicy.verify(
      expectedSizeBytes = getExpectedApkSizeBytes(context),
      actualSizeBytes = apkFile?.length()
    )

    val expectedSha256 = getExpectedApkSha256(context)
    if (!DownloadIntegrityPolicy.requiresDigestVerification(sizeVerdict, expectedSha256)) {
      return sizeVerdict
    }
    return DownloadIntegrityPolicy.verifyDigest(
      expectedSha256 = expectedSha256,
      actualSha256 = apkFile?.let { sha256OrNull(it) },
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
      clearDownloadedState(context)
      return false
    }

    val isPending = isNewerVersion(downloadedVersion, currentVersionName)
    if (!isPending) {
      clearDownloadedState(context)
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

  /**
   * Drops the download: its DownloadManager row, the file on disk, and everything remembered
   * about it.
   *
   * This used to take a `deleteApk: Boolean = false` parameter, which was a lie in both
   * directions - `dm.remove` deletes the file DownloadManager wrote whatever the flag says, and
   * every caller in the app passed `true` anyway. Keeping a downloaded APK while forgetting its
   * expected size, version and verdict would leave an unidentifiable ~117 MB file in the app's
   * external files directory, which is not a state anything here wants.
   */
  fun clearDownloadedState(context: Context) {
    val existingFile = getDownloadedApkFile(context)
    val existingDownloadId = prefs(context).getLong(KEY_DOWNLOAD_ID, -1L)

    if (existingDownloadId > 0) {
      runCatching {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.remove(existingDownloadId)
      }
    }

    // Belt and braces: dm.remove only deletes what DownloadManager still has a row for, and the
    // pruned-row case is exactly when it does not.
    runCatching {
      if (existingFile?.exists() == true) {
        existingFile.delete()
      }
    }

    prefs(context).edit()
      .remove(KEY_DOWNLOAD_ID)
      .remove(KEY_APK_PATH)
      .remove(KEY_DOWNLOADED_VERSION_NAME)
      .remove(KEY_EXPECTED_SIZE_BYTES)
      .remove(KEY_EXPECTED_SHA256)
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
    val rejection = downloadedApkRejection(context, revalidate = true)
    if (rejection != null) {
      // Last gate before the installer. Re-parse the exact archive in case it
      // was removed or replaced after the prompt was first evaluated - this is
      // the one caller that must not answer from the remembered verdict.
      rememberRejectedRelease(context, getDownloadedVersionName(context), rejection)
      clearDownloadedState(context)
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
  private fun downloadedApkRejection(context: Context, revalidate: Boolean = false): String? {
    val fingerprint = getDownloadedApkFile(context)?.let { archiveFingerprint(it) }
    if (!revalidate && fingerprint != null && isRememberedVerifiedArchive(context, fingerprint)) {
      return null
    }

    val integrity = verifyDownloadedApk(context)
    if (!DownloadIntegrityPolicy.isInstallable(integrity)) {
      return integrity.name
    }
    val archive = verifyDownloadedArchive(context)
    if (archive != ApkArchivePolicy.Verdict.VERIFIED) {
      return archive.name
    }

    if (fingerprint != null) {
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
    private const val KEY_DOWNLOADED_VERSION_NAME = "downloaded_version_name"
    private const val KEY_EXPECTED_SIZE_BYTES = "expected_size_bytes"

    /** Lower-case hex SHA-256 the release published for this download, absent if it published none. */
    private const val KEY_EXPECTED_SHA256 = "expected_sha256"

    /** The [verificationFingerprint] of the archive that last passed the installability check. */
    private const val KEY_VERIFIED_ARCHIVE = "verified_archive"

    /**
     * The release whose archive was refused on this device, and the verdict that refused it.
     * Outlives [clearDownloadedState] on purpose: the whole point is to remember something about
     * a download after the download is gone.
     */
    private const val KEY_REJECTED_VERSION_NAME = "rejected_version_name"
    private const val KEY_REJECTED_REASON = "rejected_reason"

    private const val HEX_DIGITS = "0123456789abcdef"

    /** 64 KB: one page-cache-friendly read per iteration over a ~117 MB file. */
    private const val DEFAULT_HASH_BUFFER_BYTES = 64 * 1024

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

    /** Whether two raw version strings name the same release. */
    internal fun isSameRelease(left: String, right: String): Boolean =
      normalizeVersionName(left) == normalizeVersionName(right)

    /**
     * The name DownloadManager writes the archive under.
     *
     * The version goes into a path, and a git ref may legally contain `/` (`v1.2.3/hotfix` is a
     * valid tag name), so the raw tag cannot be interpolated into a file name: a separator would
     * send the write somewhere other than the intended directory, and a name of `..` would send
     * it up out of it. Anything outside the conservative set below becomes `_`.
     */
    internal fun downloadFileName(versionName: String): String =
      "StremioShell-${sanitizeFileNameComponent(versionName)}.apk"

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
