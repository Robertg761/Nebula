package com.stremioshell.host.tv.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.stremioshell.host.tv.data.addon.StreamSelection
import com.stremioshell.host.tv.data.subtitles.SubtitlesClient
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read-modify-write stores, driven against an in-memory DataStore.
 *
 * The interesting property of every one of them is what they do *not* write. A blob that will not
 * decode used to read as an empty list, and because every mutator is read-modify-write the next
 * ordinary save - a thirty-second progress tick - would persist that emptiness over the viewer's
 * whole history. So each test below checks the stored bytes rather than the returned value.
 */
class WatchStateStoreTest {
  private val logged = mutableListOf<String>()

  @Test
  fun `a corrupt watch blob is left alone rather than overwritten by an upsert`() = runBlocking {
    val corrupt = """[{"key":"movie:1","tmdbId":"""
    val prefs = FakePreferencesStore(mutablePreferencesOf(KEY_WATCH to corrupt))

    store(prefs).upsert(entry("movie:2", positionMs = 60_000, updatedAtMs = 10))

    assertEquals(corrupt, prefs.string(KEY_WATCH))
    assertTrue(logged.single().contains("refused to upsert movie:2"))
  }

  @Test
  fun `a corrupt removals blob also stops the write`() = runBlocking {
    // The removals list is read on the same edit and written on the same edit, so trusting it while
    // it is unreadable would resurrect deleted rows just as silently.
    val prefs = FakePreferencesStore(
      mutablePreferencesOf(
        KEY_WATCH to """[]""",
        KEY_WATCH_REMOVALS to """{"not":"a list"}""",
      ),
    )

    store(prefs).upsert(entry("movie:2", updatedAtMs = 10))

    assertEquals("""[]""", prefs.string(KEY_WATCH))
    assertEquals(1, logged.size)
  }

  @Test
  fun `every mutator refuses a corrupt blob`() = runBlocking {
    val corrupt = "{{{"
    val store = store(FakePreferencesStore(mutablePreferencesOf(KEY_WATCH to corrupt)))

    store.upsertIfAbsent(entry("movie:1", updatedAtMs = 5))
    store.markWatched("movie:1", watchedAtMs = 6)
    store.remove("movie:1", removedAtMs = 7)

    assertEquals(3, logged.size)
  }

  @Test
  fun `a corrupt blob still reads as empty rather than throwing at the screen`() = runBlocking {
    // A rail with nothing in it is a poor screen; an exception on every Home composition is worse,
    // and the bytes are still on disk either way.
    val store = store(FakePreferencesStore(mutablePreferencesOf(KEY_WATCH to "{{{")))

    assertEquals(emptyList<WatchEntry>(), store.entries.first())
    assertNull(store.get("movie:1"))
  }

  @Test
  fun `an ordinary upsert still writes`() = runBlocking {
    val prefs = FakePreferencesStore()
    val store = store(prefs)

    store.upsert(entry("movie:1", positionMs = 1_000, durationMs = 90_000, updatedAtMs = 10))

    assertEquals(1, store.entries.first().size)
    assertEquals(90_000L, store.get("movie:1")?.durationMs)
    assertTrue(logged.isEmpty())
  }

  @Test
  fun `a save made before mpv reported a duration keeps the one already stored`() = runBlocking {
    val prefs = FakePreferencesStore()
    val store = store(prefs)
    store.upsert(entry("movie:1", positionMs = 60_000, durationMs = 5_400_000, updatedAtMs = 10))

    // The player saves on a timer and on the way out, and either can fire before mpv has a
    // duration. Taking the zero at face value left a Continue Watching row drawing 0% progress
    // however far in the viewer actually was.
    store.upsert(entry("movie:1", positionMs = 120_000, durationMs = 0, updatedAtMs = 20))

    val stored = requireNotNull(store.get("movie:1"))
    assertEquals(120_000L, stored.positionMs)
    assertEquals(5_400_000L, stored.durationMs)
    assertTrue(stored.progress > 0f)
  }

  @Test
  fun `a save that does carry a duration is authoritative`() = runBlocking {
    // Switching to a different release of the same title genuinely changes the duration.
    val store = store(FakePreferencesStore())
    store.upsert(entry("movie:1", durationMs = 5_400_000, updatedAtMs = 10))

    store.upsert(entry("movie:1", positionMs = 10, durationMs = 5_500_000, updatedAtMs = 20))

    assertEquals(5_500_000L, store.get("movie:1")?.durationMs)
  }

  @Test
  fun `a first save with no duration is stored as it arrived`() = runBlocking {
    val store = store(FakePreferencesStore())

    store.upsert(entry("movie:1", positionMs = 5_000, durationMs = 0, updatedAtMs = 10))

    assertEquals(0L, store.get("movie:1")?.durationMs)
  }

  @Test
  fun `a delayed progress save cannot undo a later finished mutation`() = runBlocking {
    val store = store(FakePreferencesStore())
    // Constructed in source order, then deliberately committed in reverse order.
    val delayedProgress = entry("movie:1", positionMs = 60_000, updatedAtMs = 9_000)
    val finishingSource = entry("movie:1", positionMs = 0, updatedAtMs = 1_000)
    val finished = finishingSource.copy(watchedAtMs = 1_000)
    assertEquals(finishingSource.pendingMutation, finished.pendingMutation)

    store.upsert(finished)
    store.upsert(delayedProgress)

    val stored = requireNotNull(store.get("movie:1"))
    assertTrue(stored.watched)
    assertEquals(0L, stored.positionMs)
    // Display time moved backwards, while mutation ordering still knew Finished was later.
    assertEquals(1_000L, stored.updatedAtMs)
    // Decoding persisted JSON is pure; only newly initiated mutations receive an action token.
    assertFalse(stored.pendingMutation.assigned)
  }

  @Test
  fun `a delayed save cannot resurrect a row removed by a later action`() = runBlocking {
    val store = store(FakePreferencesStore())
    val delayed = entry("movie:1", positionMs = 30_000, updatedAtMs = 9_000)
    store.upsert(entry("movie:1", positionMs = 20_000, updatedAtMs = 10_000))

    store.remove("movie:1", removedAtMs = 1_000)
    store.upsert(delayed)

    assertNull(store.get("movie:1"))
  }

  @Test
  fun `a later save survives a backward wall clock correction`() = runBlocking {
    val store = store(FakePreferencesStore())
    val beforeCorrection = entry("movie:1", positionMs = 10_000, updatedAtMs = 50_000)
    val afterCorrection = entry("movie:1", positionMs = 20_000, updatedAtMs = 5_000)

    store.upsert(beforeCorrection)
    store.upsert(afterCorrection)

    val stored = requireNotNull(store.get("movie:1"))
    assertEquals(20_000L, stored.positionMs)
    assertEquals(5_000L, stored.updatedAtMs)
    assertTrue(stored.mutationOrder > 0L)
    assertFalse(stored.pendingMutation.assigned)
  }

  @Test
  fun `legacy watch json without a logical order migrates on its next mutation`() = runBlocking {
    val legacy = """[{"key":"movie:1","tmdbId":1,"mediaType":"movie","title":"Title","updatedAtMs":50000}]"""
    val prefs = FakePreferencesStore(mutablePreferencesOf(KEY_WATCH to legacy))
    val store = store(prefs)

    store.upsert(entry("movie:1", positionMs = 25_000, updatedAtMs = 5_000))

    val migrated = requireNotNull(store.get("movie:1"))
    assertEquals(25_000L, migrated.positionMs)
    assertTrue(migrated.mutationOrder > 0L)
  }

  @Test
  fun `mark watched and remove remain effective after the wall clock moves backwards`() = runBlocking {
    val store = store(FakePreferencesStore())
    store.upsert(entry("movie:1", positionMs = 25_000, updatedAtMs = 50_000))

    store.markWatched("movie:1", watchedAtMs = 5_000)

    val watched = requireNotNull(store.get("movie:1"))
    assertTrue(watched.watched)
    assertEquals(5_000L, watched.updatedAtMs)

    store.remove("movie:1", removedAtMs = 1_000)
    assertNull(store.get("movie:1"))
  }

  @Test
  fun `watch next refuses a degraded read instead of publishing authoritative empty state`() =
    runBlocking {
      val unreadable = object : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("disk unavailable") }

        override suspend fun updateData(
          transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = throw UnsupportedOperationException()
      }
      val store = WatchStateStore(
        store = unreadable,
        log = {},
        readLog = { _, _ -> },
      )

      assertNull(store.watchNextEntriesOrNull())
    }

  @Test
  fun `watch next distinguishes malformed state from a healthy empty store`() = runBlocking {
    val corrupt = FakePreferencesStore(mutablePreferencesOf(KEY_WATCH to "{{{"))

    assertNull(store(corrupt).watchNextEntriesOrNull())
    assertEquals(emptyList<WatchEntry>(), store(FakePreferencesStore()).watchNextEntriesOrNull())
  }

  @Test
  fun `launcher dismissal survives store recreation without deleting app resume state`() =
    runBlocking {
      val prefs = FakePreferencesStore()
      val first = store(prefs)
      first.upsert(entry("movie:1", positionMs = 10_000, updatedAtMs = 10))

      assertTrue(first.dismissFromWatchNext("movie:1"))

      assertEquals("movie:1", first.get("movie:1")?.key)
      assertEquals(emptyList<WatchEntry>(), first.watchNextEntriesOrNull())
      assertEquals(emptyList<WatchEntry>(), store(prefs).watchNextEntriesOrNull())
    }

  @Test
  fun `queued playback from before dismissal cannot resurrect the row but later playback can`() =
    runBlocking {
      val prefs = FakePreferencesStore()
      val store = store(prefs)
      val session = "watch-next-dismissal-test"
      store.upsert(
        entry(
          "movie:1",
          positionMs = 10_000,
          updatedAtMs = 10,
          pendingMutation = PersistenceMutationToken(session, 1),
        ),
      )
      // Constructed before the launcher action, but deliberately committed after it.
      val queuedBeforeDismissal = entry(
        "movie:1",
        positionMs = 20_000,
        updatedAtMs = 20,
        pendingMutation = PersistenceMutationToken(session, 2),
      )

      assertTrue(
        store.dismissFromWatchNext(
          "movie:1",
          pendingMutation = PersistenceMutationToken(session, 3),
        ),
      )
      store.upsert(queuedBeforeDismissal)

      assertEquals(20_000L, store.get("movie:1")?.positionMs)
      assertEquals(emptyList<WatchEntry>(), store.watchNextEntriesOrNull())

      store.upsert(
        entry(
          "movie:1",
          positionMs = 30_000,
          updatedAtMs = 30,
          pendingMutation = PersistenceMutationToken(session, 4),
        ),
      )

      assertEquals(listOf("movie:1"), store.watchNextEntriesOrNull()?.map { it.key })
    }

  @Test
  fun `later playback that commits before a delayed dismissal receiver still wins`() = runBlocking {
    val store = store(FakePreferencesStore())
    val session = "watch-next-delayed-receiver-test"
    store.upsert(
      entry(
        "movie:1",
        positionMs = 10_000,
        updatedAtMs = 10,
        pendingMutation = PersistenceMutationToken(session, 1),
      ),
    )
    // Broadcast receipt captured order 2, then provider I/O delayed its DataStore transaction.
    val dismissalAtReceipt = PersistenceMutationToken(session, 2)
    store.upsert(
      entry(
        "movie:1",
        positionMs = 20_000,
        updatedAtMs = 20,
        pendingMutation = PersistenceMutationToken(session, 3),
      ),
    )

    assertTrue(store.dismissFromWatchNext("movie:1", dismissalAtReceipt))

    assertEquals(listOf("movie:1"), store.watchNextEntriesOrNull()?.map { it.key })
  }

  @Test
  fun `automatic next episode seeding does not clear a launcher dismissal`() = runBlocking {
    val prefs = FakePreferencesStore()
    val store = store(prefs)
    assertTrue(store.dismissFromWatchNext("episode:1:1:2"))

    store.upsertIfAbsent(entry("episode:1:1:2", updatedAtMs = 20))

    assertEquals("episode:1:1:2", store.get("episode:1:1:2")?.key)
    assertEquals(emptyList<WatchEntry>(), store.watchNextEntriesOrNull())
  }

  @Test
  fun `legacy dismissal clears on the first playback upsert after upgrade`() = runBlocking {
    val legacyEntry =
      """[{"key":"movie:1","tmdbId":1,"mediaType":"movie","title":"Title","positionMs":10000,"updatedAtMs":10,"mutationOrder":1}]"""
    val prefs = FakePreferencesStore(
      mutablePreferencesOf(
        KEY_WATCH to legacyEntry,
        KEY_WATCH_NEXT_DISMISSED_LEGACY to setOf("movie:1"),
      ),
    )
    val store = store(prefs)
    assertEquals(emptyList<WatchEntry>(), store.watchNextEntriesOrNull())

    store.upsert(entry("movie:1", positionMs = 20_000, updatedAtMs = 20))

    assertEquals(listOf("movie:1"), store.watchNextEntriesOrNull()?.map { it.key })
  }

  @Test
  fun `malformed dismissal state cannot become an authoritative launcher snapshot`() = runBlocking {
    val prefs = FakePreferencesStore(
      mutablePreferencesOf(
        KEY_WATCH to "[]",
        KEY_WATCH_NEXT_DISMISSALS to "{{{",
      ),
    )
    val store = store(prefs)

    assertNull(store.watchNextEntriesOrNull())
    store.upsert(entry("movie:1", positionMs = 10_000, updatedAtMs = 10))

    assertEquals("{{{", prefs.string(KEY_WATCH_NEXT_DISMISSALS))
    assertEquals("movie:1", store.get("movie:1")?.key)
    assertNull(store.watchNextEntriesOrNull())
  }

  private fun store(prefs: FakePreferencesStore) = WatchStateStore(
    store = prefs,
    log = { logged += it },
  )

  private fun entry(
    key: String,
    positionMs: Long = 0,
    durationMs: Long = 0,
    updatedAtMs: Long,
    pendingMutation: PersistenceMutationToken = PersistenceMutationClock.next(),
  ) = WatchEntry(
    key = key,
    tmdbId = 1,
    mediaType = "movie",
    title = "Title",
    positionMs = positionMs,
    durationMs = durationMs,
    updatedAtMs = updatedAtMs,
    pendingMutation = pendingMutation,
  )

  private companion object {
    val KEY_WATCH = stringPreferencesKey("watch_state")
    val KEY_WATCH_REMOVALS = stringPreferencesKey("watch_state_removals")
    val KEY_WATCH_NEXT_DISMISSALS = stringPreferencesKey("watch_next_dismissals_v2")
    val KEY_WATCH_NEXT_DISMISSED_LEGACY = stringSetPreferencesKey("watch_next_dismissed_programs")
  }
}

class WatchNextDismissalRetentionTest {
  @Test
  fun `dismissal retention is bounded and keeps the newest exact keys`() {
    var retained = emptyList<WatchNextDismissal>()
    for (index in 0..WatchNextDismissalPolicy.MAX_DISMISSALS) {
      retained = WatchNextDismissalPolicy.record(
        existing = retained,
        key = "movie:$index",
        mutationOrder = index + 1L,
      )
    }

    assertEquals(WatchNextDismissalPolicy.MAX_DISMISSALS, retained.size)
    assertFalse(retained.any { it.key == "movie:0" })
    assertTrue(retained.any { it.key == "movie:${WatchNextDismissalPolicy.MAX_DISMISSALS}" })
  }

  @Test
  fun `playback clears only the matching marker and only when its order is later`() {
    val dismissed = listOf(
      WatchNextDismissal("movie:1", mutationOrder = 10),
      WatchNextDismissal("movie:2", mutationOrder = 11),
    )

    assertEquals(
      dismissed,
      WatchNextDismissalPolicy.afterPlayback(dismissed, "movie:1", mutationOrder = 10),
    )
    assertEquals(
      listOf(WatchNextDismissal("movie:2", mutationOrder = 11)),
      WatchNextDismissalPolicy.afterPlayback(dismissed, "movie:1", mutationOrder = 12),
    )
  }
}

class WatchlistStoreTest {
  private val logged = mutableListOf<String>()

  @Test
  fun `a corrupt watchlist is never rewritten from an empty read`() = runBlocking {
    val corrupt = """[{"tmdbId":1,"""
    val prefs = FakePreferencesStore(mutablePreferencesOf(KEY_LIST to corrupt))

    WatchlistStore(prefs) { logged += it }.toggle(entry(2))

    assertEquals(corrupt, prefs.string(KEY_LIST))
    assertTrue(logged.single().contains("refused to toggle"))
  }

  @Test
  fun `removal from a corrupt watchlist is refused too`() = runBlocking {
    val prefs = FakePreferencesStore(mutablePreferencesOf(KEY_LIST to "]["))

    WatchlistStore(prefs) { logged += it }.remove("movie:1")

    assertEquals("][", prefs.string(KEY_LIST))
  }

  @Test
  fun `an ordinary toggle still writes, and reads back`() = runBlocking {
    val store = WatchlistStore(FakePreferencesStore()) { logged += it }

    store.toggle(entry(7))

    assertEquals(listOf(7), store.entries.first().map { it.tmdbId })
    assertTrue(logged.isEmpty())
  }

  private fun entry(tmdbId: Int) = WatchlistEntry(
    tmdbId = tmdbId,
    mediaType = "movie",
    title = "Title",
    addedAtMs = 1,
  )

  private companion object {
    val KEY_LIST = stringPreferencesKey("watchlist")
  }
}

class StreamPickStoreTest {
  private val logged = mutableListOf<String>()

  @Test
  fun `a corrupt pick list is not replaced by the next remembered pick`() = runBlocking {
    val corrupt = "not json at all"
    val prefs = FakePreferencesStore(mutablePreferencesOf(KEY_PICKS to corrupt))

    StreamPickStore(prefs) { logged += it }
      .remember(StreamSelection(seriesId = "tt2", updatedAtMs = 5))

    assertEquals(corrupt, prefs.string(KEY_PICKS))
    assertTrue(logged.single().contains("refused to remember tt2"))
  }

  @Test
  fun `a corrupt pick list reads as nothing remembered`() = runBlocking {
    val store = StreamPickStore(FakePreferencesStore(mutablePreferencesOf(KEY_PICKS to "x"))) {
      logged += it
    }

    assertNull(store.get("tt2"))
    assertEquals(emptyMap<String, StreamSelection>(), store.selections.first())
  }

  @Test
  fun `an ordinary pick is remembered and read back`() = runBlocking {
    val store = StreamPickStore(FakePreferencesStore()) { logged += it }

    store.remember(StreamSelection(seriesId = "tt2", label = "Comet 1080p", updatedAtMs = 5))

    assertEquals("Comet 1080p", store.get("tt2")?.label)
    assertTrue(logged.isEmpty())
  }

  @Test
  fun `stream picks use action order across backward clocks and reversed commits`() = runBlocking {
    val store = StreamPickStore(FakePreferencesStore()) { logged += it }
    val delayed = StreamSelection(seriesId = "tt2", label = "old", updatedAtMs = 50_000)
    val later = StreamSelection(seriesId = "tt2", label = "new", updatedAtMs = 5_000)

    store.remember(later)
    store.remember(delayed)

    val stored = requireNotNull(store.get("tt2"))
    assertEquals("new", stored.label)
    assertEquals(5_000L, stored.updatedAtMs)
    assertTrue(stored.mutationOrder > 0L)
    assertFalse(stored.pendingMutation.assigned)
  }

  @Test
  fun `legacy stream pick json migrates even when its wall time is in the future`() = runBlocking {
    val prefs = FakePreferencesStore(
      mutablePreferencesOf(
        KEY_PICKS to """[{"seriesId":"tt2","label":"legacy","updatedAtMs":50000}]""",
      ),
    )
    val store = StreamPickStore(prefs) { logged += it }

    store.remember(StreamSelection(seriesId = "tt2", label = "new", updatedAtMs = 5_000))

    val migrated = requireNotNull(store.get("tt2"))
    assertEquals("new", migrated.label)
    assertTrue(migrated.mutationOrder > 0L)
  }

  private companion object {
    val KEY_PICKS = stringPreferencesKey("stream_picks")
  }
}

class SettingsStoreSnapshotTest {
  @Test
  fun `a healthy snapshot reports what is stored`() = runBlocking {
    val prefs = FakePreferencesStore(
      mutablePreferencesOf(
        stringPreferencesKey("tmdb_api_key") to "abc123",
        stringPreferencesKey("addon_manifest_urls") to """["https://comet.example/manifest.json"]""",
        stringPreferencesKey("subtitles_base_url") to "https://subs.example",
      ),
    )

    val snapshot = SettingsStore(prefs).storedSnapshot()

    assertTrue(snapshot.readable)
    assertEquals("abc123", snapshot.tmdbKey)
    assertEquals(listOf("https://comet.example/manifest.json"), snapshot.addonUrls)
    assertEquals("https://subs.example", snapshot.subtitlesBaseUrl)
  }

  @Test
  fun `a failed read is reported as unreadable rather than as an unconfigured TV`() = runBlocking {
    val snapshot = SettingsStore(UnreadableStore()) { _, _ -> }.storedSnapshot()

    // Same blank values as a first run, and that is exactly the confusion this flag exists to
    // prevent: SettingsSaveGuard must not read them as "the viewer has no key".
    assertFalse(snapshot.readable)
    assertEquals("", snapshot.tmdbKey)
    assertEquals(emptyList<String>(), snapshot.addonUrls)
  }

  @Test
  fun `a save persists only the values the guard did not hold back`() = runBlocking {
    val prefs = FakePreferencesStore(
      mutablePreferencesOf(
        stringPreferencesKey("tmdb_api_key") to "abc123",
        stringPreferencesKey("addon_manifest_urls") to """["https://comet.example/manifest.json"]""",
      ),
    )
    val store = SettingsStore(prefs)
    val resolved = SettingsSaveGuard.resolve(
      SettingsDraft(tmdbKey = "", addonUrls = emptyList(), subtitlesBaseUrl = "subs.example"),
      // The shape a Save takes while the preferences file cannot be read.
      StoredSettings(tmdbKey = "", addonUrls = emptyList(), readable = false),
    )

    store.persist(resolved)

    // Untouched, not rewritten from the failed read's stand-in values.
    assertEquals("abc123", prefs.string(stringPreferencesKey("tmdb_api_key")))
    assertEquals(
      """["https://comet.example/manifest.json"]""",
      prefs.string(stringPreferencesKey("addon_manifest_urls")),
    )
    // The one field the draft did supply still lands.
    assertEquals("https://subs.example", prefs.string(stringPreferencesKey("subtitles_base_url")))
  }

  @Test
  fun `a save with values in it writes all three fields`() = runBlocking {
    val prefs = FakePreferencesStore()
    val resolved = SettingsSaveGuard.resolve(
      SettingsDraft("newkey", listOf("comet.example"), ""),
      StoredSettings("", emptyList()),
    )

    SettingsStore(prefs).persist(resolved)

    assertEquals("newkey", prefs.string(stringPreferencesKey("tmdb_api_key")))
    assertEquals(
      """["https://comet.example/manifest.json"]""",
      prefs.string(stringPreferencesKey("addon_manifest_urls")),
    )
  }

  @Test
  fun `a degraded subtitle seed does not overwrite the stored custom addon`() = runBlocking {
    val prefs = FakePreferencesStore(
      mutablePreferencesOf(stringPreferencesKey("subtitles_base_url") to "https://saved-subs.example"),
    )
    val resolved = SettingsSaveGuard.resolve(
      SettingsDraft("", emptyList(), SubtitlesClient.OPENSUBTITLES_V3_BASE),
      StoredSettings("", emptyList(), readable = false),
    )

    SettingsStore(prefs).persist(resolved)

    assertEquals(
      "https://saved-subs.example",
      prefs.string(stringPreferencesKey("subtitles_base_url")),
    )
  }

  @Test
  fun `addon mutation reads the commit snapshot even while the public read is degraded`() =
    runBlocking {
      val key = stringPreferencesKey("addon_manifest_urls")
      val prefs = DegradedReadWritableStore(
        mutablePreferencesOf(key to """["https://saved.example/manifest.json"]"""),
      )

      val changed = SettingsStore(prefs) { _, _ -> }.updateAddonManifestUrls { current ->
        current + "https://new.example/manifest.json"
      }

      assertTrue(changed)
      assertEquals(
        """["https://saved.example/manifest.json","https://new.example/manifest.json"]""",
        prefs.string(key),
      )
    }

  @Test
  fun `malformed addon json is not mistaken for an absent list or overwritten by a mutation`() =
    runBlocking {
      val key = stringPreferencesKey("addon_manifest_urls")
      val corrupt = "{{{"
      val prefs = FakePreferencesStore(
        mutablePreferencesOf(
          key to corrupt,
          stringPreferencesKey("addon_manifest_url") to "https://legacy.example/manifest.json",
        ),
      )
      val store = SettingsStore(prefs)

      val result = runCatching {
        store.updateAddonManifestUrls { it + "https://new.example/manifest.json" }
      }
      val directListResult = runCatching {
        store.setAddonManifestUrls(listOf("https://new.example/manifest.json"))
      }
      val directConfigurationResult = runCatching {
        store.setConfiguration("key", listOf("https://new.example/manifest.json"), "")
      }

      assertTrue(result.isFailure)
      assertTrue(directListResult.isFailure)
      assertTrue(directConfigurationResult.isFailure)
      assertEquals(corrupt, prefs.string(key))
      assertEquals(emptyList<String>(), store.addonManifestUrls.first())
      val snapshot = store.storedSnapshot()
      assertTrue(snapshot.readable)
      assertFalse(snapshot.addonUrlsReadable)
      assertEquals(emptyList<String>(), snapshot.addonUrls)
    }

  @Test
  fun `a guarded settings save leaves malformed addon bytes untouched even with typed urls`() =
    runBlocking {
      val addonKey = stringPreferencesKey("addon_manifest_urls")
      val tmdbKey = stringPreferencesKey("tmdb_api_key")
      val subtitlesKey = stringPreferencesKey("subtitles_base_url")
      val corrupt = "not-json"
      val prefs = FakePreferencesStore(mutablePreferencesOf(addonKey to corrupt))
      val store = SettingsStore(prefs)
      val stored = store.storedSnapshot()
      val resolved = SettingsSaveGuard.resolve(
        SettingsDraft(
          tmdbKey = "new-key",
          addonUrls = listOf("https://new.example/manifest.json"),
          subtitlesBaseUrl = "https://subs.example",
        ),
        stored,
      )

      store.persist(resolved)

      assertTrue(resolved.keptAddonUrls)
      assertTrue(resolved.addonUrlsUnreadable)
      assertEquals(corrupt, prefs.string(addonKey))
      assertEquals("new-key", prefs.string(tmdbKey))
      assertEquals("https://subs.example", prefs.string(subtitlesKey))
    }

  @Test
  fun `settings persist refuses malformed addon bytes that appeared after resolution`() =
    runBlocking {
      val addonKey = stringPreferencesKey("addon_manifest_urls")
      val tmdbKey = stringPreferencesKey("tmdb_api_key")
      val corrupt = "not-json"
      val prefs = FakePreferencesStore(mutablePreferencesOf(addonKey to corrupt))
      val resolved = SettingsSaveGuard.resolve(
        SettingsDraft(
          tmdbKey = "new-key",
          addonUrls = listOf("https://new.example/manifest.json"),
          subtitlesBaseUrl = "",
        ),
        // This represents the earlier healthy snapshot. The store contains malformed bytes by
        // the time persist enters its transaction, and those current bytes must win the check.
        StoredSettings(tmdbKey = "old-key", addonUrls = emptyList()),
      )

      val result = runCatching { SettingsStore(prefs).persist(resolved) }

      assertTrue(result.isFailure)
      assertEquals(corrupt, prefs.string(addonKey))
      assertNull(prefs.string(tmdbKey))
    }

  private class UnreadableStore : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw IOException("disk unavailable") }

    override suspend fun updateData(
      transform: suspend (t: Preferences) -> Preferences,
    ): Preferences = throw UnsupportedOperationException()
  }

  private class DegradedReadWritableStore(initial: Preferences) : DataStore<Preferences> {
    private var stored = initial
    override val data: Flow<Preferences> = flow { throw IOException("read path unavailable") }

    override suspend fun updateData(
      transform: suspend (t: Preferences) -> Preferences,
    ): Preferences = transform(stored).also { stored = it }

    fun string(key: Preferences.Key<String>): String? = stored[key]
  }
}

/**
 * DataStore's contract with none of its file.
 *
 * `edit {}` is an extension over [DataStore.updateData], so a store that simply keeps the last
 * value exercises the real mutator paths - including the case that matters here, where the
 * transform returns without touching anything and nothing is written.
 */
internal class FakePreferencesStore(
  initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {
  private val state = MutableStateFlow(initial)

  override val data: Flow<Preferences> = state

  override suspend fun updateData(
    transform: suspend (t: Preferences) -> Preferences,
  ): Preferences {
    val updated = transform(state.value)
    state.value = updated
    return updated
  }

  /** The stored bytes, which is what a "did this write?" assertion has to look at. */
  fun string(key: Preferences.Key<String>): String? = state.value[key]
}
