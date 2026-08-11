package com.stremioshell.host.tv.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.stremioshell.host.tv.data.addon.StreamSelection
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

  private fun store(prefs: FakePreferencesStore) = WatchStateStore(prefs) { logged += it }

  private fun entry(
    key: String,
    positionMs: Long = 0,
    durationMs: Long = 0,
    updatedAtMs: Long,
  ) = WatchEntry(
    key = key,
    tmdbId = 1,
    mediaType = "movie",
    title = "Title",
    positionMs = positionMs,
    durationMs = durationMs,
    updatedAtMs = updatedAtMs,
  )

  private companion object {
    val KEY_WATCH = stringPreferencesKey("watch_state")
    val KEY_WATCH_REMOVALS = stringPreferencesKey("watch_state_removals")
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
      ),
    )

    val snapshot = SettingsStore(prefs).storedSnapshot()

    assertTrue(snapshot.readable)
    assertEquals("abc123", snapshot.tmdbKey)
    assertEquals(listOf("https://comet.example/manifest.json"), snapshot.addonUrls)
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

  private class UnreadableStore : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw IOException("disk unavailable") }

    override suspend fun updateData(
      transform: suspend (t: Preferences) -> Preferences,
    ): Preferences = throw UnsupportedOperationException()
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
