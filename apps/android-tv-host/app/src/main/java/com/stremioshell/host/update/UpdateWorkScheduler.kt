package com.stremioshell.host.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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

  fun ensureScheduled(context: Context) {
    if (BuildConfig.DEBUG) {
      return
    }

    val owner = BuildConfig.GITHUB_RELEASE_OWNER.trim()
    val repo = BuildConfig.GITHUB_RELEASE_REPO.trim()
    if (owner.isBlank() || repo.isBlank()) {
      return
    }

    val constraints = Constraints.Builder()
      // Not merely CONNECTED: the download this leads to already refuses metered and roaming
      // connections, so a check on one can only ever end in "found it, cannot fetch it".
      .setRequiredNetworkType(NetworkType.UNMETERED)
      .build()

    val request = PeriodicWorkRequestBuilder<BackgroundUpdateWorker>(
      CHECK_PERIOD_HOURS,
      TimeUnit.HOURS,
    )
      .setConstraints(constraints)
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
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
   * The flag is what keeps this to one cancellation: without it every launch would query the
   * database for a name that has not existed since the update to this build.
   */
  private fun cancelLegacyWorkOnce(context: Context, workManager: WorkManager) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    if (prefs.getBoolean(KEY_LEGACY_WORK_CANCELLED, false)) {
      return
    }
    workManager.cancelUniqueWork(LEGACY_UNIQUE_WORK_NAME)
    prefs.edit().putBoolean(KEY_LEGACY_WORK_CANCELLED, true).apply()
  }
}
