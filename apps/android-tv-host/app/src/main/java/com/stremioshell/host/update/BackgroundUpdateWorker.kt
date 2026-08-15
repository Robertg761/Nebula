package com.stremioshell.host.update

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.stremioshell.host.BuildConfig
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class BackgroundUpdateWorker(
  appContext: Context,
  workerParams: WorkerParameters
) : Worker(appContext, workerParams) {
  private val updateRepository = UpdateRepository()
  private val apkUpdateManager = ApkUpdateManager()
  private val statusStore = UpdateStatusStore(appContext)

  /**
   * Never returns [Result.failure]. This is the periodic release check, and WorkManager drops a
   * periodic request that fails: one unexpected throw - a DownloadManager provider that answered
   * with nothing, a preferences read during a locked user profile - would take the app's only
   * update path with it until the next launch re-registered the work. A retry re-runs inside the
   * same period; a success waits for the next one. Both keep the chain alive.
   */
  override fun doWork(): Result {
    return try {
      // Periodic and manual work have separate WorkManager names. Serialize their complete check
      // paths so they cannot race status publication or spend two anonymous GitHub requests on the
      // same moment. This lock does not cover prompt or Settings reads.
      UpdateCheckExecution.exclusive { runUpdateCheck() }
    } catch (error: Throwable) {
      val retryable = isRetryable(error)
      val kind = failureKind(error, duringDownload = false)
      Log.w(TAG, "Update check stopped unexpectedly (${kind.name}).")
      runCatching {
        statusStore.recordFailedCheck(kind, retryScheduled = retryable)
      }
      if (retryable) Result.retry() else Result.success()
    }
  }

  private fun runUpdateCheck(): Result {
    if (BuildConfig.DEBUG) {
      return Result.success()
    }

    val owner = BuildConfig.GITHUB_RELEASE_OWNER.trim()
    val repo = BuildConfig.GITHUB_RELEASE_REPO.trim()
    if (owner.isBlank() || repo.isBlank()) {
      Log.d(TAG, "Skipping periodic update check: release repository not configured.")
      statusStore.recordFailedCheck(
        UpdateFailureKind.CONFIGURATION,
        retryScheduled = false,
      )
      return Result.success()
    }

    statusStore.recordChecking()
    val info = runCatching {
      updateRepository.checkForUpdate(
        owner = owner,
        repo = repo,
        currentVersionName = BuildConfig.VERSION_NAME
      )
    }.getOrElse { error ->
      val retryable = isRetryable(error)
      val kind = failureKind(error, duringDownload = false)
      Log.w(TAG, "Update check failed (${kind.name}).")
      statusStore.recordFailedCheck(kind, retryScheduled = retryable)
      return if (retryable) Result.retry() else Result.success()
    }
    val checkedAtMs = System.currentTimeMillis()

    // Hold the updater's publication gate across observation, decision, cleanup, and enqueue so a
    // prompt-side reconciliation cannot change ownership between those steps. The GitHub request
    // above stays outside this gate, so Settings and the prompt are never blocked behind network
    // I/O. The worker-level gate above independently prevents duplicate manual/periodic runs.
    return DownloadStatePublication.exclusive {
      // Query DownloadManager rather than trusting the persisted id. Failed,
      // cancelled and system-pruned rows are reconciled here so they cannot wedge
      // every future worker run in DOWNLOAD_IN_PROGRESS.
      val hasActiveDownload = apkUpdateManager.isDownloadInProgress(applicationContext)
      val hasDownloadedForVersion = !hasActiveDownload && (
        info?.let {
          apkUpdateManager.hasDownloadedApkForVersion(applicationContext, it.latestVersionName)
        } ?: false
      )
      // Asking also forgets the stored rejection when this is a different release, which is how a
      // new version un-sticks a device that refused the previous one.
      val isRejectedRelease = info?.let {
        apkUpdateManager.isRejectedRelease(applicationContext, it)
      } ?: false
      val decision = AutoUpdatePolicy.decide(
        updateInfo = info,
        hasDownloadedForVersion = hasDownloadedForVersion,
        hasActiveDownload = hasActiveDownload,
        isRejectedRelease = isRejectedRelease
      )

      when (decision) {
        AutoUpdatePolicy.Decision.NO_UPDATE -> {
          statusStore.recordSuccessfulCheck(UpdateStatusPhase.UP_TO_DATE, checkedAtMs)
          Result.success()
        }
        AutoUpdatePolicy.Decision.ALREADY_DOWNLOADED -> {
          statusStore.recordSuccessfulCheck(
            UpdateStatusPhase.READY,
            checkedAtMs,
            info?.latestVersionName,
          )
          Result.success()
        }
        AutoUpdatePolicy.Decision.DOWNLOAD_IN_PROGRESS -> {
          // The active row may belong to a release older than the newest repository response.
          // Report the bytes Android is actually transferring, not the release we cannot start yet.
          val activeVersion = apkUpdateManager.getDownloadedVersionName(applicationContext)
            ?: info?.latestVersionName
          statusStore.recordSuccessfulCheck(
            UpdateStatusPhase.DOWNLOADING,
            checkedAtMs,
            activeVersion,
          )
          Result.success()
        }
        AutoUpdatePolicy.Decision.REJECTED_RELEASE -> {
          Log.d(
            TAG,
            "Skipping ${info?.latestVersionName}: already rejected " +
              "(${apkUpdateManager.getRejectedReleaseReason(applicationContext)}).",
          )
          statusStore.recordFailedCheck(
            failureKind = UpdateFailureKind.REJECTED_RELEASE,
            retryScheduled = false,
            successfulCheckAtMs = checkedAtMs,
            targetVersionName = info?.latestVersionName,
          )
          Result.success()
        }
        AutoUpdatePolicy.Decision.START_DOWNLOAD -> {
          val updateInfo = info ?: return@exclusive Result.success()
          runCatching {
            if (!apkUpdateManager.clearDownloadedState(applicationContext)) {
              throw IOException("Update download ownership is still being reconciled")
            }
            apkUpdateManager.startDownload(applicationContext, updateInfo)
            statusStore.recordSuccessfulCheck(
              UpdateStatusPhase.DOWNLOAD_QUEUED,
              checkedAtMs,
              updateInfo.latestVersionName,
            )
            Log.d(TAG, "Queued background update download for ${updateInfo.latestVersionName}.")
          }.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
              val retryable = isRetryable(error)
              Log.w(TAG, "Failed to queue background update download (DOWNLOAD).")
              statusStore.recordFailedCheck(
                failureKind = failureKind(error, duringDownload = true),
                retryScheduled = retryable,
                successfulCheckAtMs = checkedAtMs,
                targetVersionName = updateInfo.latestVersionName,
              )
              if (retryable) Result.retry() else Result.success()
            }
          )
        }
      }
    }
  }

  companion object {
    private const val TAG = "StremioHostUpdateWorker"
    private val GITHUB_ERROR_REGEX = Regex("""GitHub API error (\d{3})""")

    internal fun isRetryable(error: Throwable): Boolean {
      if (error is UnknownHostException || error is SocketTimeoutException || error is IOException) {
        return true
      }

      findGitHubApiException(error)?.let { apiError ->
        return apiError.rateLimited || apiError.statusCode in 500..599
      }

      val message = error.message.orEmpty()
      val statusCode = GITHUB_ERROR_REGEX.find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
      return statusCode == 429 || (statusCode != null && statusCode in 500..599)
    }

    internal fun failureKind(
      error: Throwable,
      duringDownload: Boolean,
    ): UpdateFailureKind {
      if (duringDownload) return UpdateFailureKind.DOWNLOAD

      findGitHubApiException(error)?.let { apiError ->
        return when {
          apiError.rateLimited -> UpdateFailureKind.RATE_LIMITED
          apiError.statusCode in 500..599 -> UpdateFailureKind.SERVER
          else -> UpdateFailureKind.UNKNOWN
        }
      }

      if (error is UnknownHostException || error is SocketTimeoutException || error is IOException) {
        return UpdateFailureKind.NETWORK
      }

      val statusCode = GITHUB_ERROR_REGEX.find(error.message.orEmpty())
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
      return when {
        statusCode == 429 -> UpdateFailureKind.RATE_LIMITED
        statusCode != null && statusCode in 500..599 -> UpdateFailureKind.SERVER
        else -> UpdateFailureKind.UNKNOWN
      }
    }

    private fun findGitHubApiException(error: Throwable): GitHubApiException? {
      var current: Throwable? = error
      var depth = 0
      while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is GitHubApiException) return current
        current = current.cause
        depth++
      }
      return null
    }

    private const val MAX_CAUSE_DEPTH = 8
  }
}

/** Process-local gate shared by every BackgroundUpdateWorker instance. */
internal object UpdateCheckExecution {
  private val lock = Any()

  fun <T> exclusive(block: () -> T): T = synchronized(lock, block)
}
