package com.stremioshell.host.tv.channel

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.BaseColumns
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.stremioshell.host.tv.TvAppActivity

/**
 * Writes the mapped rows into the system TV provider's Watch Next table, which is
 * what puts resume points on the Android TV home screen next to Netflix's and
 * YouTube's.
 *
 * Deliberately thin: every decision lives in [WatchNextMapper], [WatchNextDiff] and the
 * unit-testable [WatchNextPublishExecutor], while the ContentValues update semantics are covered
 * by a device-backed test. Provider failures never escape to playback, but they are reported to
 * [WatchNextSync] so a failed reconciliation does not spend the throttle window as if it landed.
 */
// tvprovider 1.0.0 marks its Watch Next compatibility surface RestrictedApi
// even though this is the documented public API for third-party TV apps.
// Keep the suppression at this adapter boundary rather than disabling the lint
// check project-wide, so any unrelated use still fails CI.
@SuppressLint("RestrictedApi")
class WatchNextPublisher(context: Context) {
  private val context = context.applicationContext
  private val executor = WatchNextPublishExecutor(
    AndroidWatchNextProvider(this.context, ::contentValuesFor),
  ) { message, error ->
    if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
  }

  /**
   * Whether reconciliation landed, could not exist on this image, or reached a retryable failure.
   * Keeping those outcomes distinct prevents phones/non-TV emulators from retrying forever while
   * still handing a real provider failure back to the scheduler.
   */
  internal fun publish(desired: List<WatchNextProgramData>): WatchNextPublishResult =
    executor.publish(desired)

  internal fun contentValuesFor(program: WatchNextProgramData): ContentValues {
    val builder = WatchNextProgram.Builder()
    builder.setType(
      when (program.type) {
        WatchNextProgramType.Movie -> TvContractCompat.WatchNextPrograms.TYPE_MOVIE
        WatchNextProgramType.TvEpisode -> TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE
      }
    )
    builder.setWatchNextType(
      when (program.kind) {
        WatchNextKind.Continue -> TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
        WatchNextKind.Next -> TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
      }
    )
    builder.setTitle(program.title)
    builder.setInternalProviderId(program.internalProviderId)
    builder.setLastEngagementTimeUtcMillis(program.lastEngagementTimeUtcMillis)
    builder.setIntent(deepLinkIntent(program.deepLinkUri))
    program.posterArtUri?.let { builder.setPosterArtUri(Uri.parse(it)) }
    program.lastPlaybackPositionMillis?.let { builder.setLastPlaybackPositionMillis(it) }
    program.durationMillis?.let { builder.setDurationMillis(it) }
    program.seasonNumber?.let { builder.setSeasonNumber(it) }
    program.episodeNumber?.let { builder.setEpisodeNumber(it) }
    return builder.build().toContentValues().apply {
      // This same ContentValues object is used for inserts and in-place updates. Builder fields
      // that are not set are omitted rather than written as SQL NULL, which means an update would
      // otherwise retain the old value. The visible case is Restart: the mapped row becomes NEXT
      // with no resume position, but the launcher keeps drawing the previous progress bar because
      // COLUMN_LAST_PLAYBACK_POSITION_MILLIS was never cleared.
      if (program.posterArtUri == null) {
        putNull(TvContractCompat.WatchNextPrograms.COLUMN_POSTER_ART_URI)
      }
      if (program.lastPlaybackPositionMillis == null) {
        putNull(TvContractCompat.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS)
      }
      if (program.durationMillis == null) {
        putNull(TvContractCompat.WatchNextPrograms.COLUMN_DURATION_MILLIS)
      }
      if (program.seasonNumber == null) {
        putNull(TvContractCompat.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER)
      }
      if (program.episodeNumber == null) {
        putNull(TvContractCompat.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER)
      }
    }
  }

  /**
   * Explicitly targeted: the home screen starts whatever the row's intent URI
   * decodes to, and naming the component means a press cannot be caught by
   * another app that happens to claim the scheme.
   */
  private fun deepLinkIntent(deepLinkUri: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUri))
      .setClass(context, TvAppActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  private companion object {
    const val TAG = "WatchNext"
  }
}

internal enum class WatchNextPublishResult { Published, Unavailable, Failed }

/** Provider boundary kept free of Android values so every failure contract has a local unit test. */
internal interface WatchNextProvider {
  fun available(): Boolean
  /** Null is a provider failure, not proof that the table is empty. */
  fun queryOwnRows(): List<ExistingWatchNextRow>?
  fun delete(id: Long)
  /** Number of rows updated; zero means the launcher removed it after the query. */
  fun update(id: Long, program: WatchNextProgramData): Int
  /** False includes ContentResolver.insert returning null. */
  fun insert(program: WatchNextProgramData): Boolean
}

/** Applies one complete diff and reports partial failure instead of laundering it as success. */
internal class WatchNextPublishExecutor(
  private val provider: WatchNextProvider,
  private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
  fun publish(desired: List<WatchNextProgramData>): WatchNextPublishResult {
    val available = try {
      provider.available()
    } catch (error: Throwable) {
      log("Watch Next provider lookup failed", error)
      return WatchNextPublishResult.Failed
    }
    if (!available) return WatchNextPublishResult.Unavailable
    val existing = try {
      provider.queryOwnRows() ?: return WatchNextPublishResult.Failed.also {
        log("Watch Next query returned no cursor", null)
      }
    } catch (error: Throwable) {
      log("Watch Next query failed", error)
      return WatchNextPublishResult.Failed
    }
    val plan = WatchNextDiff.plan(existing, desired)
    var succeeded = true

    for (id in plan.deletes) {
      try {
        provider.delete(id)
      } catch (error: Throwable) {
        succeeded = false
        log("Watch Next delete failed for row $id", error)
      }
    }
    for ((id, program) in plan.updates) {
      try {
        val updated = provider.update(id, program)
        // A clean zero means the queried row vanished. Reinsert the desired row, but a null insert
        // is still a failed reconciliation and must be retried rather than called published.
        if (updated == 0 && !provider.insert(program)) {
          succeeded = false
          log("Watch Next reinsert failed for ${program.internalProviderId}", null)
        } else if (updated < 0) {
          succeeded = false
          log("Watch Next update returned $updated for ${program.internalProviderId}", null)
        }
      } catch (error: Throwable) {
        succeeded = false
        log("Watch Next update failed for ${program.internalProviderId}", error)
      }
    }
    for (program in plan.inserts) {
      try {
        if (!provider.insert(program)) {
          succeeded = false
          log("Watch Next insert returned null for ${program.internalProviderId}", null)
        }
      } catch (error: Throwable) {
        succeeded = false
        log("Watch Next insert failed for ${program.internalProviderId}", error)
      }
    }
    return if (succeeded) WatchNextPublishResult.Published else WatchNextPublishResult.Failed
  }
}

/** Android's nullable/throwing ContentResolver surface translated without losing failure states. */
@SuppressLint("RestrictedApi")
private class AndroidWatchNextProvider(
  context: Context,
  private val valuesFor: (WatchNextProgramData) -> ContentValues,
) : WatchNextProvider {
  private val context = context.applicationContext
  private val resolver = this.context.contentResolver

  override fun available(): Boolean =
    context.packageManager.resolveContentProvider(TvContractCompat.AUTHORITY, 0) != null

  /** The provider scopes this query to the calling package's own rows. */
  override fun queryOwnRows(): List<ExistingWatchNextRow>? {
    val projection = arrayOf(
      BaseColumns._ID,
      TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
    )
    val cursor = resolver.query(
      TvContractCompat.WatchNextPrograms.CONTENT_URI,
      projection,
      null,
      null,
      null,
    ) ?: return null
    return cursor.use {
      buildList {
        while (cursor.moveToNext()) {
          add(
            ExistingWatchNextRow(
              id = cursor.getLong(0),
              internalProviderId = if (cursor.isNull(1)) null else cursor.getString(1),
            )
          )
        }
      }
    }
  }

  override fun delete(id: Long) {
    resolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null)
  }

  override fun update(id: Long, program: WatchNextProgramData): Int = resolver.update(
    TvContractCompat.buildWatchNextProgramUri(id),
    valuesFor(program),
    null,
    null,
  )

  override fun insert(program: WatchNextProgramData): Boolean = resolver.insert(
    TvContractCompat.WatchNextPrograms.CONTENT_URI,
    valuesFor(program),
  ) != null
}
