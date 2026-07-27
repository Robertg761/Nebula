package com.stremioshell.host.tv.channel

/** A Watch Next row already in the provider, reduced to what the diff needs. */
data class ExistingWatchNextRow(val id: Long, val internalProviderId: String?)

data class WatchNextSyncPlan(
  val inserts: List<WatchNextProgramData>,
  val updates: List<Pair<Long, WatchNextProgramData>>,
  val deletes: List<Long>,
)

/**
 * Works out the smallest set of provider writes that makes the app's rows equal
 * the desired list.
 *
 * Updating in place rather than delete-then-insert keeps a row's identity across
 * publishes, which matters because the TV home animates and reorders rows: a full
 * rewrite on every 30-second progress save would make the row visibly churn.
 * Deleting whatever is left over is also what takes a finished or removed title
 * off the home screen - there is no separate removal path to get wrong.
 */
object WatchNextDiff {
  fun plan(
    existing: List<ExistingWatchNextRow>,
    desired: List<WatchNextProgramData>,
  ): WatchNextSyncPlan {
    val byProviderId = LinkedHashMap<String, Long>()
    val deletes = ArrayList<Long>()
    for (row in existing) {
      val providerId = row.internalProviderId
      // A row with no provider id predates this code (or came from a crashed
      // publish) and can never be matched again, so it can only be cleaned up.
      // Same for a duplicate: keep the first, drop the rest.
      if (providerId == null || byProviderId.putIfAbsent(providerId, row.id) != null) {
        deletes += row.id
      }
    }

    val inserts = ArrayList<WatchNextProgramData>()
    val updates = ArrayList<Pair<Long, WatchNextProgramData>>()
    val kept = HashSet<String>()
    for (program in desired) {
      if (!kept.add(program.internalProviderId)) continue
      val existingId = byProviderId[program.internalProviderId]
      if (existingId == null) inserts += program else updates += existingId to program
    }

    for ((providerId, id) in byProviderId) {
      if (providerId !in kept) deletes += id
    }

    return WatchNextSyncPlan(inserts = inserts, updates = updates, deletes = deletes)
  }
}
