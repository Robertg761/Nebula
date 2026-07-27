package com.stremioshell.host.tv.channel

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
 * Deliberately thin and untested: every decision lives in [WatchNextMapper] and
 * [WatchNextDiff], and what is left is ContentResolver calls that only a device
 * can answer. Every failure is swallowed - a home-screen row is a nicety, and the
 * provider is missing entirely on a phone or a non-TV emulator image.
 */
class WatchNextPublisher(context: Context) {
  private val context = context.applicationContext

  fun publish(desired: List<WatchNextProgramData>) {
    if (!providerAvailable()) return
    val resolver = context.contentResolver
    runCatching {
      val plan = WatchNextDiff.plan(queryOwnRows(), desired)
      for (id in plan.deletes) {
        resolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null)
      }
      for ((id, program) in plan.updates) {
        resolver.update(
          TvContractCompat.buildWatchNextProgramUri(id),
          contentValuesFor(program),
          null,
          null,
        )
      }
      for (program in plan.inserts) {
        resolver.insert(
          TvContractCompat.WatchNextPrograms.CONTENT_URI,
          contentValuesFor(program),
        )
      }
    }.onFailure { Log.w(TAG, "Watch Next publish failed", it) }
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

  private fun contentValuesFor(program: WatchNextProgramData): ContentValues {
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
    return builder.build().toContentValues()
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
