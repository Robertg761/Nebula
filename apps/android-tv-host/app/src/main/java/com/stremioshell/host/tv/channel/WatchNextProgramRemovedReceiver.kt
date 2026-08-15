package com.stremioshell.host.tv.channel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import androidx.tvprovider.media.tv.TvContractCompat
import com.stremioshell.host.tv.data.PersistenceMutationClock
import com.stremioshell.host.tv.data.PersistenceMutationToken
import com.stremioshell.host.tv.data.WatchStateStore
import com.stremioshell.host.tv.data.logPersistenceFailure
import com.stremioshell.host.tv.data.persistenceScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Persists the viewer's launcher removal before deleting the corresponding provider row. */
class WatchNextProgramRemovedReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val programId = WatchNextDismissalPolicy.programId(
      action = intent.action,
      rawProgramId = intent.getLongExtra(TvContract.EXTRA_WATCH_NEXT_PROGRAM_ID, -1L),
    ) ?: return
    val pending = goAsync()
    val appContext = context.applicationContext
    // Capture the action order at broadcast receipt, before provider I/O can let a genuinely later
    // playback action overtake this coroutine.
    val pendingMutation = PersistenceMutationClock.next()
    persistenceScope.launch {
      try {
        dismiss(appContext, programId, pendingMutation)
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        logPersistenceFailure("Watch Next dismissal could not be persisted", error)
      } finally {
        pending.finish()
      }
    }
  }

  private suspend fun dismiss(
    context: Context,
    programId: Long,
    pendingMutation: PersistenceMutationToken,
  ) {
    val uri = TvContractCompat.buildWatchNextProgramUri(programId)
    val providerId = try {
      context.contentResolver.query(
        uri,
        arrayOf(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID),
        null,
        null,
        null,
      )?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
      }
    } catch (error: Exception) {
      logPersistenceFailure("Watch Next dismissal row could not be read", error)
      return
    }
    val key = WatchNextDismissalPolicy.providerId(providerId) ?: return

    val persisted = try {
      WatchStateStore(context).dismissFromWatchNext(key, pendingMutation)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      logPersistenceFailure("Watch Next dismissal could not be saved", error)
      return
    }
    if (!persisted) return

    // Serialize behind every app publish after the durable marker. An older publish either finishes
    // before this reconciliation and is cleaned up here, or reads the marker itself. A failed full
    // reconciliation still gets the exact-row deletion the platform contract asks for; future syncs
    // retain the marker and try the complete diff again.
    val reconciled = try {
      WatchNextSync.reconcileNow(context) == WatchNextPublishResult.Published
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      logPersistenceFailure("Dismissed Watch Next state could not be reconciled", error)
      false
    }
    if (reconciled) return

    try {
      context.contentResolver.delete(uri, null, null)
    } catch (error: Exception) {
      logPersistenceFailure("Dismissed Watch Next row could not be deleted", error)
    }
  }
}

/** Pure validation for the exported broadcast and provider-owned identifier. */
internal object WatchNextDismissalPolicy {
  private const val ACTION = "android.media.tv.action.WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED"
  private const val MAX_PROVIDER_ID_CHARS = 256

  fun programId(action: String?, rawProgramId: Long): Long? =
    rawProgramId.takeIf { action == ACTION && it > 0L }

  fun providerId(raw: String?): String? = raw
    ?.trim()
    ?.takeIf { it.isNotEmpty() && it.length <= MAX_PROVIDER_ID_CHARS }
}
