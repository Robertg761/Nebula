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
 * Deliberately thin: every decision lives in [WatchNextMapper] and [WatchNextDiff],
 * while the ContentValues update semantics are covered by a device-backed test.
 * The remaining ContentResolver calls need a TV provider. Every failure is swallowed -
 * a home-screen row is a nicety, and the provider is missing entirely on a phone or a
 * non-TV emulator image.
 */
// tvprovider 1.0.0 marks its Watch Next compatibility surface RestrictedApi
// even though this is the documented public API for third-party TV apps.
// Keep the suppression at this adapter boundary rather than disabling the lint
// check project-wide, so any unrelated use still fails CI.
@SuppressLint("RestrictedApi")
class WatchNextPublisher(context: Context) {
  private val context = context.applicationContext

  fun publish(desired: List<WatchNextProgramData>) {
    if (!providerAvailable()) return
    val resolver = context.contentResolver
    val plan = runCatching { WatchNextDiff.plan(queryOwnRows(), desired) }
      .getOrElse {
        Log.w(TAG, "Watch Next query failed", it)
        return
      }
    for (id in plan.deletes) {
      runCatching {
        resolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null)
      }.onFailure { Log.w(TAG, "Watch Next delete failed for row $id", it) }
    }
    for ((id, program) in plan.updates) {
      runCatching {
        val updated = resolver.update(
          TvContractCompat.buildWatchNextProgramUri(id),
          contentValuesFor(program),
          null,
          null,
        )
        // The launcher may remove a row after the query. Reinsert only on a clean zero-row update;
        // an exception could be a transient provider failure, where inserting risks a duplicate.
        if (updated == 0) {
          resolver.insert(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            contentValuesFor(program),
          )
        }
      }.onFailure {
        Log.w(TAG, "Watch Next update failed for ${program.internalProviderId}", it)
      }
    }
    for (program in plan.inserts) {
      runCatching {
        resolver.insert(
          TvContractCompat.WatchNextPrograms.CONTENT_URI,
          contentValuesFor(program),
        )
      }.onFailure {
        Log.w(TAG, "Watch Next insert failed for ${program.internalProviderId}", it)
      }
    }
  }

  /**
   * Absent on anything that is not running the leanback system stack - a phone, or
   * a TV emulator image built without the TV provider - where every call below
   * would otherwise throw on each save.
   */
  private fun providerAvailable(): Boolean =
    context.packageManager.resolveContentProvider(TvContractCompat.AUTHORITY, 0) != null

  /**
   * The provider scopes a plain query to the calling package's own rows, so this
   * needs no permission and can never see - or delete - another app's rows.
   */
  private fun queryOwnRows(): List<ExistingWatchNextRow> {
    val projection = arrayOf(
      BaseColumns._ID,
      TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
    )
    val rows = ArrayList<ExistingWatchNextRow>()
    context.contentResolver.query(
      TvContractCompat.WatchNextPrograms.CONTENT_URI,
      projection,
      null,
      null,
      null,
    )?.use { cursor ->
      while (cursor.moveToNext()) {
        rows += ExistingWatchNextRow(
          id = cursor.getLong(0),
          internalProviderId = if (cursor.isNull(1)) null else cursor.getString(1),
        )
      }
    }
    return rows
  }

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
