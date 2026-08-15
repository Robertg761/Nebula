package com.stremioshell.host.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.stremioshell.host.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * Registers the periodic release check.
 *
 * Blocking (WorkManager initialization, a preferences file, a database read), so call it off the
 * main thread and off the launch path - see TvAppActivity.
 */
object UpdateWorkScheduler {
  /**
   * The name carries a revision because the policy below is KEEP: an existing registration is
   * left alone, so a changed schedule only reaches a device that already has one by being
   * enqueued under a new name. v1 ran hourly on any connection.
   */
  private const val UNIQUE_WORK_NAME = "stremio-shell-update-v2"

  /** Manual checks are deduplicated without changing the periodic chain's cadence. */
  private const val MANUAL_UNIQUE_WORK_NAME = "stremio-shell-update-manual-v1"

  /** The v1 name, cancelled exactly once per install so its hourly spec cannot outlive this. */
  private const val LEGACY_UNIQUE_WORK_NAME = "stremio-shell-update-hourly"

  /** Shares the updater's preferences file, which this process already reads for downloads. */
  private const val PREFS_NAME = "stremio_shell_updater"
  private const val KEY_LEGACY_WORK_CANCELLED = "legacy_hourly_work_cancelled"

  /**
   * Four checks a day. The worker only pre-downloads the APK; what the viewer sees is
   * UpdatePromptHost re-evaluating the already-downloaded file on ON_RESUME, so a longer period
   * costs nothing but a later download of a release nobody has been offered yet.
   */
  private const val CHECK_PERIOD_HOURS = 6L

  internal enum class ManualCheckRequest {
    QUEUED,
    UNAVAILABLE,
    FAILED_TO_QUEUE,
  }

  fun ensureScheduled(context: Context) {
    val owner = BuildConfig.GITHUB_RELEASE_OWNER.trim()
    val repo = BuildConfig.GITHUB_RELEASE_REPO.trim()
    if (!checksAvailable(BuildConfig.DEBUG, owner, repo)) {
      return
    }

    val request = PeriodicWorkRequestBuilder<BackgroundUpdateWorker>(
      CHECK_PERIOD_HOURS,
      TimeUnit.HOURS,
    )
      .setConstraints(updateConstraints())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_MINUTES, TimeUnit.MINUTES)
      .build()

    val workManager = WorkManager.getInstance(context)
    cancelLegacyWorkOnce(context, workManager)
    // KEEP rather than UPDATE: this runs on every launch, and UPDATE rewrote the work spec each
    // time for a request that had not changed.
    workManager.enqueueUniquePeriodicWork(
      UNIQUE_WORK_NAME,
      ExistingPeriodicWorkPolicy.KEEP,
      request
    )
  }

  /**
   * Queues the same worker used by the periodic path and waits only for WorkManager to persist it.
   * A successful return means queued, never checked or downloaded. `KEEP` makes repeated presses
   * share one unfinished manual request, while the worker's publication gate prevents that request
   * and a periodic run from enqueueing duplicate APK downloads.
   *
   * Blocking by design. Settings calls this from Dispatchers.IO.
   */
  internal fun requestManualCheck(context: Context): ManualCheckRequest {
    val appContext = context.applicationContext
    val owner = BuildConfig.GITHUB_RELEASE_OWNER.trim()
    val repo = BuildConfig.GITHUB_RELEASE_REPO.trim()
    val statusStore = UpdateStatusStore(appContext)
    if (!checksAvailable(BuildConfig.DEBUG, owner, repo)) {
      statusStore.recordFailedCheck(
        UpdateFailureKind.CONFIGURATION,
        retryScheduled = false,
      )
      return ManualCheckRequest.UNAVAILABLE
    }

    val requestedAtMs = System.currentTimeMillis()
    val queued = try {
      val request = OneTimeWorkRequestBuilder<BackgroundUpdateWorker>()
        .setConstraints(updateConstraints())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_BACKOFF_MINUTES, TimeUnit.MINUTES)
        .build()
      WorkManager.getInstance(appContext)
        .enqueueUniqueWork(MANUAL_UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        .result
        .get(ENQUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      // The database accepted the request. This is still only QUEUED; if the worker already
      // advanced or completed, the timestamp-aware reducer preserves that newer fact.
      statusStore.recordQueued(requestedAtMs)
      true
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      false
    } catch (_: Exception) {
      false
    }

    if (!queued) {
      statusStore.recordFailedCheck(
        UpdateFailureKind.SCHEDULING,
        retryScheduled = false,
      )
      return ManualCheckRequest.FAILED_TO_QUEUE
    }
    return ManualCheckRequest.QUEUED
  }

  internal fun checksAvailable(debug: Boolean, owner: String, repo: String): Boolean =
    !debug && owner.isNotBlank() && repo.isNotBlank()

  /** One network policy for both manual and periodic checks. */
  private fun updateConstraints(): Constraints = Constraints.Builder()
    // Not merely CONNECTED: the download this leads to already refuses metered and roaming
    // connections, so a check on one can only ever end in "found it, cannot fetch it".
    .setRequiredNetworkType(NetworkType.UNMETERED)
    .build()

  /**
   * The flag is what keeps this to one cancellation: without it every launch would query the
   * database for a name that has not existed since the update to this build.
   *
   * It is written only once the cancellation has actually happened. `cancelUniqueWork` is
   * asynchronous - it hands back an [androidx.work.Operation] and does the database write on
   * WorkManager's own executor - so recording the flag beside the call recorded an intention, not
   * a fact: a process death in the gap left the flag set and v1's hourly spec running forever,
   * next to v2's six-hourly one. This function already runs off the main thread (see the class
   * comment), so it can simply wait for the Operation before writing anything.
   *
   * The flag itself is written with apply(), and the one way to lose it is a hard kill before the
   * queued write lands. That costs a redundant cancel of a name that no longer exists on the next
   * launch, which is the harmless direction of this trade - unlike the old order, whose failure
   * was hourly work nothing would ever cancel again.
   */
  private fun cancelLegacyWorkOnce(context: Context, workManager: WorkManager) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    if (prefs.getBoolean(KEY_LEGACY_WORK_CANCELLED, false)) {
      return
    }

    val cancelled = try {
      workManager.cancelUniqueWork(LEGACY_UNIQUE_WORK_NAME)
        .result
        .get(CANCEL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      true
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      false
    } catch (_: Exception) {
      // Timed out, or WorkManager reported a failure. Leaving the flag unset costs one more
      // cancel attempt on the next launch, which is the cheap half of this trade.
      false
    }

    if (cancelled) {
      prefs.edit().putBoolean(KEY_LEGACY_WORK_CANCELLED, true).apply()
    }
  }

  /** Long enough for a database write behind a cold WorkManager, short enough not to be a hang. */
  private const val CANCEL_TIMEOUT_SECONDS = 10L
  private const val ENQUEUE_TIMEOUT_SECONDS = 10L
  private const val RETRY_BACKOFF_MINUTES = 15L
}
