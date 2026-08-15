package com.stremioshell.host.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.stremioshell.host.BuildConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** The durable state Settings can report without interpreting WorkManager's internal bookkeeping. */
internal enum class UpdateStatusPhase {
  IDLE,
  CHECK_QUEUED,
  CHECKING,
  UP_TO_DATE,
  DOWNLOAD_QUEUED,
  DOWNLOADING,
  READY,
  RETRY_SCHEDULED,
  FAILED,
}

/**
 * A deliberately small, redacted failure vocabulary.
 *
 * Throwable messages can contain request URLs, response bodies, local paths, or provider details.
 * Persisting only a category keeps Settings and exported preferences useful without retaining any
 * of that data.
 */
internal enum class UpdateFailureKind {
  NETWORK,
  RATE_LIMITED,
  SERVER,
  CONFIGURATION,
  DOWNLOAD,
  REJECTED_RELEASE,
  SCHEDULING,
  UNKNOWN,
}

internal data class UpdateStatus(
  val phase: UpdateStatusPhase = UpdateStatusPhase.IDLE,
  val lastSuccessfulCheckAtMs: Long? = null,
  val lastFailedCheckAtMs: Long? = null,
  val failureKind: UpdateFailureKind? = null,
  val targetVersionName: String? = null,
)

/** Pure transitions, kept separate from Android persistence so the truth rules are unit-testable. */
internal object UpdateStatusReducer {
  private const val MAX_VERSION_DISPLAY_CHARS = 64

  fun queued(
    previous: UpdateStatus,
    requestedAtMs: Long? = null,
  ): UpdateStatus {
    if (previous.phase == UpdateStatusPhase.CHECK_QUEUED ||
      previous.phase == UpdateStatusPhase.CHECKING
    ) {
      return previous
    }
    if (requestedAtMs != null && listOfNotNull(
        previous.lastSuccessfulCheckAtMs,
        previous.lastFailedCheckAtMs,
      ).any { resultAtMs -> resultAtMs >= requestedAtMs }
    ) {
      // WorkManager can run a short check before its enqueue Operation observer resumes. Do not
      // replace that newer result with the older fact that the request was once queued.
      return previous
    }
    return previous.copy(
      phase = UpdateStatusPhase.CHECK_QUEUED,
      failureKind = null,
    )
  }

  fun checking(previous: UpdateStatus): UpdateStatus = previous.copy(
    phase = UpdateStatusPhase.CHECKING,
    failureKind = null,
  )

  fun successfulCheck(
    previous: UpdateStatus,
    phase: UpdateStatusPhase,
    checkedAtMs: Long,
    targetVersionName: String? = null,
  ): UpdateStatus {
    require(
      phase == UpdateStatusPhase.UP_TO_DATE ||
        phase == UpdateStatusPhase.DOWNLOAD_QUEUED ||
        phase == UpdateStatusPhase.DOWNLOADING ||
        phase == UpdateStatusPhase.READY,
    ) { "A successful check cannot publish $phase" }
    return previous.copy(
      phase = phase,
      lastSuccessfulCheckAtMs = checkedAtMs.validTimestamp(),
      failureKind = null,
      targetVersionName = targetVersionName.safeVersionName(),
    )
  }

  fun failedCheck(
    previous: UpdateStatus,
    failureKind: UpdateFailureKind,
    retryScheduled: Boolean,
    failedAtMs: Long,
    successfulCheckAtMs: Long? = null,
    targetVersionName: String? = previous.targetVersionName,
  ): UpdateStatus = previous.copy(
    phase = if (retryScheduled) {
      UpdateStatusPhase.RETRY_SCHEDULED
    } else {
      UpdateStatusPhase.FAILED
    },
    // A repository check can succeed before queueing its download fails. Recording both times is
    // more precise than calling the whole operation either a success or a failure.
    lastSuccessfulCheckAtMs = successfulCheckAtMs?.validTimestamp()
      ?: previous.lastSuccessfulCheckAtMs,
    lastFailedCheckAtMs = failedAtMs.validTimestamp(),
    failureKind = failureKind,
    targetVersionName = targetVersionName.safeVersionName(),
  )

  /** A DownloadManager observation changes current state, not the result time of the last check. */
  fun runtimeState(
    previous: UpdateStatus,
    phase: UpdateStatusPhase,
    targetVersionName: String?,
  ): UpdateStatus {
    require(phase == UpdateStatusPhase.DOWNLOADING || phase == UpdateStatusPhase.READY) {
      "A runtime observation cannot publish $phase"
    }
    return previous.copy(
      phase = phase,
      targetVersionName = targetVersionName.safeVersionName(),
    )
  }

  /** Resolves the old target after the running app has reached or passed its version. */
  fun installedTarget(
    previous: UpdateStatus,
    targetVersionName: String,
    currentVersionName: String,
  ): UpdateStatus {
    require(
      previous.phase == UpdateStatusPhase.DOWNLOAD_QUEUED ||
        previous.phase == UpdateStatusPhase.DOWNLOADING ||
        previous.phase == UpdateStatusPhase.READY,
    ) { "An installed target cannot resolve ${previous.phase}" }
    val target = checkNotNull(targetVersionName.safeVersionName())
    require(!ApkUpdateManager.isNewerVersion(target, currentVersionName)) {
      "The target is still newer than the running app"
    }
    return previous.copy(
      phase = UpdateStatusPhase.UP_TO_DATE,
      failureKind = null,
      targetVersionName = null,
    )
  }

  private fun Long.validTimestamp(): Long? = takeIf { it > 0L }

  private fun String?.safeVersionName(): String? = this
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.take(MAX_VERSION_DISPLAY_CHARS)
}

/** Serializes read-modify-write transitions made by worker, scheduler, and Settings instances. */
private object UpdateStatusPublication {
  private val lock = Any()

  fun <T> exclusive(block: () -> T): T = synchronized(lock, block)
}

/**
 * Small SharedPreferences-backed status ledger shared by periodic checks and Settings.
 *
 * Writers call this off the main thread. Commits are synchronous because a completed Worker must
 * not report a state that existed only in memory when Android tears down the process afterward.
 */
internal class UpdateStatusStore(
  context: Context,
  private val nowMs: () -> Long = System::currentTimeMillis,
) {
  private val appContext = context.applicationContext
  private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val apkUpdateManager = ApkUpdateManager()

  // Stable for the lifetime of this store. A getter that built a new callbackFlow on every
  // recomposition repeatedly detached and reattached the SharedPreferences listener on Settings.
  val updates: Flow<UpdateStatus> = callbackFlow {
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
      trySend(current())
    }
    preferences.registerOnSharedPreferenceChangeListener(listener)
    trySend(current())
    awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
  }.distinctUntilChanged()

  fun current(): UpdateStatus = UpdateStatusPublication.exclusive { read() }

  fun recordQueued(requestedAtMs: Long? = null): UpdateStatus = mutate { previous ->
    UpdateStatusReducer.queued(previous, requestedAtMs)
  }

  fun recordChecking(): UpdateStatus = mutate(UpdateStatusReducer::checking)

  fun recordSuccessfulCheck(
    phase: UpdateStatusPhase,
    checkedAtMs: Long,
    targetVersionName: String? = null,
  ): UpdateStatus = mutate { previous ->
    UpdateStatusReducer.successfulCheck(previous, phase, checkedAtMs, targetVersionName)
  }

  fun recordFailedCheck(
    failureKind: UpdateFailureKind,
    retryScheduled: Boolean,
    failedAtMs: Long = nowMs(),
    successfulCheckAtMs: Long? = null,
    targetVersionName: String? = null,
  ): UpdateStatus = mutate { previous ->
    UpdateStatusReducer.failedCheck(
      previous = previous,
      failureKind = failureKind,
      retryScheduled = retryScheduled,
      failedAtMs = failedAtMs,
      successfulCheckAtMs = successfulCheckAtMs,
      targetVersionName = targetVersionName ?: previous.targetVersionName,
    )
  }

  /**
   * Reconciles the persisted summary with the same DownloadManager and archive verification used
   * by the prompt. This lets Settings move from queued to downloading to ready while it is open.
   */
  fun reconcileDownloadState(): UpdateStatus {
    val snapshot = current()
    if (snapshot.phase !in RECONCILABLE_PHASES) {
      return snapshot
    }
    // DownloadManager metadata is authoritative if a newer release appeared while an older APK
    // was still transferring. The repository's latest tag is not necessarily the active row.
    val version = apkUpdateManager.getDownloadedVersionName(appContext)
      ?: snapshot.targetVersionName
      ?: return snapshot

    // The new process may observe the old archive before UpdatePromptHost removes its metadata.
    // Version order is enough to resolve the ledger now: an installed target is no longer pending
    // even while cleanup of its physical APK is still racing this Settings read.
    if (!ApkUpdateManager.isNewerVersion(version, BuildConfig.VERSION_NAME)) {
      // IDLE has no recorded repository result to support the global claim "up to date". The
      // stale archive is prompt-owned cleanup; keep Settings honest until a real check runs.
      if (snapshot.phase == UpdateStatusPhase.IDLE) return snapshot
      return mutate { previous ->
        if (!previous.canAcceptRuntimeObservation(snapshot)) previous else {
          UpdateStatusReducer.installedTarget(
            previous = previous,
            targetVersionName = version,
            currentVersionName = BuildConfig.VERSION_NAME,
          )
        }
      }
    }

    val downloaded = apkUpdateManager.hasDownloadedApkForVersion(appContext, version)
    val active = !downloaded && apkUpdateManager.isDownloadInProgress(appContext)
    return when {
      downloaded -> mutate { previous ->
        if (!previous.canAcceptRuntimeObservation(snapshot)) previous else {
          UpdateStatusReducer.runtimeState(previous, UpdateStatusPhase.READY, version)
        }
      }
      active -> mutate { previous ->
        if (!previous.canAcceptRuntimeObservation(snapshot)) previous else {
          UpdateStatusReducer.runtimeState(previous, UpdateStatusPhase.DOWNLOADING, version)
        }
      }
      snapshot.phase == UpdateStatusPhase.DOWNLOAD_QUEUED ||
        snapshot.phase == UpdateStatusPhase.DOWNLOADING ||
        snapshot.phase == UpdateStatusPhase.READY -> mutate { previous ->
        if (!previous.canAcceptRuntimeObservation(snapshot)) previous else {
          UpdateStatusReducer.failedCheck(
            previous = previous,
            failureKind = UpdateFailureKind.DOWNLOAD,
            retryScheduled = false,
            failedAtMs = nowMs(),
            targetVersionName = version,
          )
        }
      }
      else -> snapshot
    }
  }

  private fun UpdateStatus.canAcceptRuntimeObservation(snapshot: UpdateStatus): Boolean =
    phase in RECONCILABLE_PHASES &&
      phase == snapshot.phase &&
      targetVersionName == snapshot.targetVersionName

  private fun mutate(transform: (UpdateStatus) -> UpdateStatus): UpdateStatus =
    UpdateStatusPublication.exclusive {
      val previous = read()
      val next = transform(previous)
      if (next != previous) write(next)
      next
    }

  private fun read(): UpdateStatus = UpdateStatus(
    phase = preferences.getString(KEY_PHASE, null)
      ?.let { stored -> runCatching { UpdateStatusPhase.valueOf(stored) }.getOrNull() }
      ?: UpdateStatusPhase.IDLE,
    lastSuccessfulCheckAtMs = preferences.getLong(KEY_LAST_SUCCESS, 0L).takeIf { it > 0L },
    lastFailedCheckAtMs = preferences.getLong(KEY_LAST_FAILURE, 0L).takeIf { it > 0L },
    failureKind = preferences.getString(KEY_FAILURE_KIND, null)
      ?.let { stored -> runCatching { UpdateFailureKind.valueOf(stored) }.getOrNull() },
    targetVersionName = preferences.getString(KEY_TARGET_VERSION, null),
  )

  @SuppressLint("ApplySharedPref")
  private fun write(status: UpdateStatus) {
    preferences.edit()
      .putString(KEY_PHASE, status.phase.name)
      .putOptionalLong(KEY_LAST_SUCCESS, status.lastSuccessfulCheckAtMs)
      .putOptionalLong(KEY_LAST_FAILURE, status.lastFailedCheckAtMs)
      .putOptionalString(KEY_FAILURE_KIND, status.failureKind?.name)
      .putOptionalString(KEY_TARGET_VERSION, status.targetVersionName)
      .commit()
  }

  private fun SharedPreferences.Editor.putOptionalLong(
    key: String,
    value: Long?,
  ): SharedPreferences.Editor = if (value == null) remove(key) else putLong(key, value)

  private fun SharedPreferences.Editor.putOptionalString(
    key: String,
    value: String?,
  ): SharedPreferences.Editor = if (value == null) remove(key) else putString(key, value)

  private companion object {
    private const val PREFS_NAME = "stremio_shell_update_status"
    private const val KEY_PHASE = "phase"
    private const val KEY_LAST_SUCCESS = "last_successful_check_at_ms"
    private const val KEY_LAST_FAILURE = "last_failed_check_at_ms"
    private const val KEY_FAILURE_KIND = "failure_kind"
    private const val KEY_TARGET_VERSION = "target_version"

    private val RECONCILABLE_PHASES = setOf(
      UpdateStatusPhase.IDLE,
      UpdateStatusPhase.DOWNLOAD_QUEUED,
      UpdateStatusPhase.DOWNLOADING,
      UpdateStatusPhase.READY,
    )
  }
}
