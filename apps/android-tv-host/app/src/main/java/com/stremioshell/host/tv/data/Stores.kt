package com.stremioshell.host.tv.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stremioshell.host.tv.data.addon.AddonList
import com.stremioshell.host.tv.data.addon.StreamSelection
import com.stremioshell.host.tv.data.subtitles.SubtitlesClient
import com.stremioshell.host.tv.data.tmdb.MediaItem
import com.stremioshell.host.tv.data.tmdb.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TV_STORE_NAME = "tv_app"

/**
 * The one preferences file behind [SettingsStore], [WatchStateStore], [WatchlistStore] and
 * [StreamPickStore].
 *
 * Because they share it, a 30-second playback progress save emits a new snapshot to all four. So
 * every flow below narrows to the keys it actually reads and applies [distinctUntilChanged] to
 * those *before* decoding them: an unrelated key's write then costs a string comparison instead of
 * a JSON parse of somebody else's data. The decode itself runs on [Dispatchers.Default], because
 * these flows are collected with `stateIn(viewModelScope, ...)`, which otherwise puts a ~60KB parse
 * and a sort on the main thread on every emission. DataStore's own file read is unaffected by
 * [flowOn] - it always runs in the store's own scope.
 */
private val Context.tvDataStore by preferencesDataStore(
  name = TV_STORE_NAME,
  corruptionHandler = preferencesCorruptionHandler(TV_STORE_NAME),
)

/**
 * Scope for writes that must outlive the screen that started them. Saving the
 * resume position races `finish()`: a `lifecycleScope` write gets cancelled at
 * onDestroy before DataStore reaches disk, so the position is silently lost.
 */
val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * How every store below decodes its own JSON blob.
 *
 * [Json.coerceInputValues] for the same reason the network clients set it: an explicit null on a
 * defaulted field should cost that field, not the list it is in. These blobs are written by this
 * app rather than by a service, so the case that matters is version skew - a build that wrote a
 * field this one has since given a default, or the other way round after a downgrade.
 */
private fun storeJson(): Json = Json {
  ignoreUnknownKeys = true
  coerceInputValues = true
}

/** User configuration for the native TV app. */
class SettingsStore internal constructor(
  private val store: DataStore<Preferences>,
  /** Seam so the degraded-read path can be exercised without android.util.Log. */
  readLog: (String, Throwable) -> Unit = ::logPersistenceFailure,
) {
  constructor(context: Context) : this(context.tvDataStore)

  private val json = storeJson()
  private val snapshots = store.recoveringSnapshots(TV_STORE_NAME, readLog)
  private val data = snapshots.map { it.preferences }

  val tmdbApiKey: Flow<String> = data
    .map { it[KEY_TMDB].orEmpty() }
    .distinctUntilChanged()

  /**
   * Every stream addon, in the viewer's own order.
   *
   * Reads through [AddonList.migrated], so an install that predates the list - and
   * therefore has only [KEY_ADDON] written - sees its one URL as a one-entry list
   * with nothing to do and nothing to lose. The legacy key is left in place rather
   * than rewritten: nothing reads it once the list key exists, and leaving it makes
   * a downgrade survivable.
   *
   * Both keys are carried through the distinct check, because the migration reads both.
   */
  val addonManifestUrls: Flow<List<String>> = data
    .map { prefs -> prefs[KEY_ADDONS] to prefs[KEY_ADDON].orEmpty() }
    .distinctUntilChanged()
    .map { (stored, legacy) -> AddonList.migrated(decodeUrls(stored), legacy) }
    .flowOn(Dispatchers.Default)

  /**
   * Where subtitles are fetched from. Configurable because the default is one
   * community server with no account behind it, and a viewer whose language it
   * covers badly has nowhere else to point.
   */
  val subtitlesBaseUrl: Flow<String> = data
    .map { prefs ->
      prefs[KEY_SUBTITLES]?.trim()?.ifBlank { null } ?: SubtitlesClient.OPENSUBTITLES_V3_BASE
    }
    .distinctUntilChanged()

  suspend fun setTmdbApiKey(value: String) {
    store.edit { it[KEY_TMDB] = value.trim() }
  }

  suspend fun setAddonManifestUrls(urls: List<String>) {
    store.edit { it[KEY_ADDONS] = json.encodeToString(AddonList.sanitized(urls)) }
  }

  /**
   * Commits the two phone-pairing fields in one DataStore transaction.
   *
   * Pairing must not report success after only one half reached disk. Callers pass the already
   * merged values, including whichever stored half the phone deliberately left blank.
   */
  suspend fun setPairedConfiguration(tmdbKey: String, addonUrls: List<String>) {
    store.edit { prefs ->
      prefs[KEY_TMDB] = tmdbKey.trim()
      prefs[KEY_ADDONS] = json.encodeToString(AddonList.sanitized(addonUrls))
    }
  }

  /** Commits every field behind Settings' Save button as one indivisible preference edit. */
  suspend fun setConfiguration(
    tmdbKey: String,
    addonUrls: List<String>,
    subtitlesBaseUrl: String,
  ) {
    store.edit { prefs ->
      prefs[KEY_TMDB] = tmdbKey.trim()
      prefs[KEY_ADDONS] = json.encodeToString(AddonList.sanitized(addonUrls))
      prefs[KEY_SUBTITLES] = subtitlesBaseUrl.trim()
    }
  }

  /**
   * Everything [SettingsSaveGuard] needs about what is already on disk, read as one snapshot.
   *
   * One emission rather than a `first()` per field, because the fields have to agree with each
   * other and with [StoredSettings.readable]: pairing a key from a healthy read with an addon list
   * from a failed one is exactly the state the guard exists to refuse to act on.
   */
  suspend fun storedSnapshot(): StoredSettings {
    val snapshot = snapshots.first()
    return withContext(Dispatchers.Default) {
      val prefs = snapshot.preferences
      StoredSettings(
        tmdbKey = prefs[KEY_TMDB].orEmpty(),
        addonUrls = AddonList.migrated(decodeUrls(prefs[KEY_ADDONS]), prefs[KEY_ADDON].orEmpty()),
        readable = !snapshot.degraded,
      )
    }
  }

  /**
   * Commits a guarded Save, still as one indivisible edit.
   *
   * A value the guard held back is not written *at all*, rather than written back as itself. When
   * the stored read succeeded those are the same thing. When it failed they are not, and the
   * difference is whether a Save leaves a working TMDB key alone or replaces it with the empty
   * string that the failed read handed the guard.
   */
  suspend fun persist(resolved: ResolvedSettings) {
    store.edit { prefs ->
      if (!resolved.keptTmdbKey) prefs[KEY_TMDB] = resolved.tmdbKey.trim()
      if (!resolved.keptAddonUrls) {
        prefs[KEY_ADDONS] = json.encodeToString(AddonList.sanitized(resolved.addonUrls))
      }
      prefs[KEY_SUBTITLES] = resolved.subtitlesBaseUrl.trim()
    }
  }

  /** Replaces the first addon, leaving any others alone. See [AddonList.replacingFirst]. */
  suspend fun setAddonManifestUrl(value: String) {
    store.edit { prefs ->
      val current = AddonList.migrated(decodeUrls(prefs[KEY_ADDONS]), prefs[KEY_ADDON].orEmpty())
      prefs[KEY_ADDONS] = json.encodeToString(AddonList.replacingFirst(current, value))
    }
  }

  /** Blank resets to [SubtitlesClient.OPENSUBTITLES_V3_BASE]. */
  suspend fun setSubtitlesBaseUrl(value: String) {
    store.edit { it[KEY_SUBTITLES] = value.trim() }
  }

  /**
   * Null when the list has never been written, which is the one state that makes
   * the legacy single-URL key worth reading. A stored `[]` is a viewer who removed
   * their last addon and must stay removed.
   */
  private fun decodeUrls(raw: String?): List<String>? {
    if (raw == null) return null
    return runCatching { json.decodeFromString<List<String>>(raw) }.getOrNull()
  }

  private companion object {
    val KEY_TMDB = stringPreferencesKey("tmdb_api_key")
    val KEY_ADDON = stringPreferencesKey("addon_manifest_url")
    val KEY_ADDONS = stringPreferencesKey("addon_manifest_urls")
    val KEY_SUBTITLES = stringPreferencesKey("subtitles_base_url")
  }
}

@Serializable
data class WatchEntry(
  /** "movie:<tmdbId>" or "episode:<tmdbId>:<season>:<episode>". */
  val key: String,
  val tmdbId: Int,
  val mediaType: String,
  val title: String,
  val posterUrl: String? = null,
  val season: Int? = null,
  val episode: Int? = null,
  val positionMs: Long = 0,
  val durationMs: Long = 0,
  val updatedAtMs: Long,
  /**
   * When playback crossed the finished threshold, or null while the video is
   * still part-way through.
   *
   * A finished video used to have its entry deleted, which left it looking
   * exactly like one nobody had ever opened: no watched marks in an episode list,
   * and nothing to hang a next-episode suggestion off. Absent in every record
   * written before that changed, which reads as unwatched - the same thing those
   * records already meant.
   */
  val watchedAtMs: Long? = null,
) {
  val watched: Boolean get() = watchedAtMs != null

  /**
   * Full for a watched video regardless of the stored position, which is cleared
   * when the threshold is crossed: a progress bar that snapped back to empty on
   * the last frame is the bug this exists to avoid.
   */
  val progress: Float
    get() = when {
      watched -> 1f
      durationMs > 0 -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
      else -> 0f
    }
}

/** Bounded deletion marker preventing a delayed player save from resurrecting a removed row. */
@Serializable
private data class WatchRemoval(
  val key: String,
  val removedAtMs: Long,
)

/**
 * Resume positions, watched history and the Continue Watching rail, newest first.
 *
 * One list holds both kinds of record: they are written from the same places and
 * a video moves from one to the other, so splitting them would mean two writes
 * per finish with a window where a video was neither. [WatchStateRetention] is
 * what keeps them from crowding each other out.
 */
class WatchStateStore internal constructor(
  private val store: DataStore<Preferences>,
  /** Seam so the refuse-to-write path can be asserted on without android.util.Log. */
  private val log: (String) -> Unit = { logPersistence(it) },
) {
  constructor(context: Context) : this(context.tvDataStore)

  private val json = storeJson()
  private val data = store.recoveringData(TV_STORE_NAME)

  /**
   * The heaviest flow in the app: several hundred entries, and the one the player rewrites every
   * thirty seconds. Decoding and sorting it belong off the main thread by a wide margin.
   */
  val entries: Flow<List<WatchEntry>> = data
    .map { it[KEY_WATCH] }
    .distinctUntilChanged()
    .map { raw -> decode(raw).sortedByDescending { it.updatedAtMs } }
    .flowOn(Dispatchers.Default)

  // Off the caller's dispatcher for the decode: the Play press reads a resume position through
  // here from the main thread, and the parse is the whole watch list, not one entry.
  suspend fun get(key: String): WatchEntry? = withContext(Dispatchers.Default) {
    decode(data.first()[KEY_WATCH]).firstOrNull { it.key == key }
  }

  suspend fun upsert(entry: WatchEntry) {
    store.edit { prefs ->
      val stored = decodeOrNull(prefs[KEY_WATCH]) ?: return@edit refuse("upsert ${entry.key}")
      val existing = stored.firstOrNull { it.key == entry.key }
      if (!PersistenceOrdering.accepts(existing?.updatedAtMs, entry.updatedAtMs)) return@edit
      val removals = decodeRemovalsOrNull(prefs[KEY_WATCH_REMOVALS])
        ?: return@edit refuse("upsert ${entry.key}")
      val removedAt = removals.firstOrNull { it.key == entry.key }?.removedAtMs
      if (!PersistenceOrdering.acceptsAfterRemoval(removedAt, entry.updatedAtMs)) return@edit
      val rest = stored.filterNot { it.key == entry.key }
      prefs[KEY_WATCH] = json.encodeToString(
        WatchStateRetention.prune(rest + preservingDuration(entry, existing)),
      )
      if (removedAt != null) {
        prefs[KEY_WATCH_REMOVALS] = json.encodeToString(removals.filterNot { it.key == entry.key })
      }
    }
  }

  /**
   * Keeps a known duration when the incoming save does not carry one.
   *
   * The player saves progress on a timer and on the way out, and both can fire before mpv has
   * reported a duration - a save during the first seconds of playback, or one from a session that
   * never finished loading. Taking the incoming zero at face value replaced a good duration with
   * nothing, and a Continue Watching row whose duration is zero draws a progress bar of zero
   * however far into the film the viewer got.
   *
   * Only ever fills a gap: a save that does carry a duration is authoritative, because a duration
   * genuinely changes when the viewer switches to a different release of the same title.
   */
  private fun preservingDuration(entry: WatchEntry, existing: WatchEntry?): WatchEntry =
    if (entry.durationMs <= 0 && existing != null) {
      entry.copy(durationMs = existing.durationMs)
    } else {
      entry
    }

  /**
   * Writes [entry] only when nothing is stored for its key, so seeding the next
   * episode of a series cannot overwrite a resume point the viewer already has
   * in it (a re-watch that stopped part-way through episode 4).
   */
  suspend fun upsertIfAbsent(entry: WatchEntry) {
    store.edit { prefs ->
      val stored = decodeOrNull(prefs[KEY_WATCH])
        ?: return@edit refuse("seed ${entry.key}")
      if (stored.any { it.key == entry.key }) return@edit
      val removals = decodeRemovalsOrNull(prefs[KEY_WATCH_REMOVALS])
        ?: return@edit refuse("seed ${entry.key}")
      val removedAt = removals.firstOrNull { it.key == entry.key }?.removedAtMs
      if (!PersistenceOrdering.acceptsAfterRemoval(removedAt, entry.updatedAtMs)) return@edit
      prefs[KEY_WATCH] = json.encodeToString(WatchStateRetention.prune(stored + entry))
      if (removedAt != null) {
        prefs[KEY_WATCH_REMOVALS] = json.encodeToString(removals.filterNot { it.key == entry.key })
      }
    }
  }

  /**
   * Marks a stored video watched and drops its resume point. Only ever updates an
   * existing record: the callers are acting on a card that is already on screen,
   * and inventing an entry from a key alone would produce one with no title.
   */
  suspend fun markWatched(key: String, watchedAtMs: Long) {
    store.edit { prefs ->
      val stored = decodeOrNull(prefs[KEY_WATCH]) ?: return@edit refuse("mark $key watched")
      val entry = stored.firstOrNull { it.key == key } ?: return@edit
      if (!PersistenceOrdering.accepts(entry.updatedAtMs, watchedAtMs)) return@edit
      val marked = entry.copy(positionMs = 0, updatedAtMs = watchedAtMs, watchedAtMs = watchedAtMs)
      prefs[KEY_WATCH] = json.encodeToString(
        WatchStateRetention.prune(stored.filterNot { it.key == key } + marked),
      )
    }
  }

  suspend fun remove(key: String, removedAtMs: Long = System.currentTimeMillis()) {
    store.edit { prefs ->
      val stored = decodeOrNull(prefs[KEY_WATCH]) ?: return@edit refuse("remove $key")
      val storedRemovals = decodeRemovalsOrNull(prefs[KEY_WATCH_REMOVALS])
        ?: return@edit refuse("remove $key")
      val existing = stored.firstOrNull { it.key == key }
      if (existing != null && existing.updatedAtMs > removedAtMs) return@edit
      prefs[KEY_WATCH] = json.encodeToString(stored.filterNot { it.key == key })
      val effectiveRemovedAtMs = PersistenceOrdering.latestRemoval(
        existingRemovedAtMs = storedRemovals.firstOrNull { it.key == key }?.removedAtMs,
        incomingRemovedAtMs = removedAtMs,
      )
      val removals = storedRemovals
        .filterNot { it.key == key }
        .plus(WatchRemoval(key, effectiveRemovedAtMs))
        .sortedByDescending { it.removedAtMs }
        .take(MAX_WATCH_REMOVALS)
      prefs[KEY_WATCH_REMOVALS] = json.encodeToString(removals)
    }
  }

  /**
   * The stored list, or null when there are bytes there that will not decode.
   *
   * The distinction is the whole point. Every mutator above is read-modify-write, so a blob that
   * decodes to "empty" because it is corrupt is not a harmless read failure: the next thirty-second
   * progress save would write back a one-entry list and the viewer's entire history would be gone,
   * silently, with nothing left to recover it from. Null means "do not write", and the bytes stay
   * on disk for a future version - or a future look at the file - to make sense of.
   */
  private fun decodeOrNull(raw: String?): List<WatchEntry>? {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching { json.decodeFromString<List<WatchEntry>>(raw) }.getOrNull()
  }

  private fun decodeRemovalsOrNull(raw: String?): List<WatchRemoval>? {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching { json.decodeFromString<List<WatchRemoval>>(raw) }.getOrNull()
  }

  /**
   * What a reader sees: an unreadable blob presents as an empty list.
   *
   * Safe here in a way it is not in a mutator - a rail with nothing in it is a poor screen, but it
   * is recoverable, and it is a great deal better than an exception on every Home composition.
   */
  private fun decode(raw: String?): List<WatchEntry> = decodeOrNull(raw) ?: emptyList()

  private fun refuse(action: String) {
    log("watch state is unreadable; refused to $action rather than overwrite it")
  }

  private companion object {
    val KEY_WATCH = stringPreferencesKey("watch_state")
    val KEY_WATCH_REMOVALS = stringPreferencesKey("watch_state_removals")
    const val MAX_WATCH_REMOVALS = 200
  }
}

/**
 * A title saved to "My List".
 *
 * Carries a copy of everything a card and a Details arrival need rather than just an
 * id: the row has to draw with no TMDB key and no network, which is exactly the state
 * a TV is in for the first second after it wakes up.
 */
@Serializable
data class WatchlistEntry(
  val tmdbId: Int,
  /** "movie" or "show", the same spelling [WatchEntry] stores. */
  val mediaType: String,
  val title: String,
  val posterUrl: String? = null,
  val backdropUrl: String? = null,
  val overview: String = "",
  val year: String? = null,
  val rating: Double? = null,
  val addedAtMs: Long,
) {
  /** Identity, and the lazy-row key. TMDB numbers movies and shows separately. */
  val key: String get() = "$mediaType:$tmdbId"

  val type: MediaType get() = if (mediaType == SHOW) MediaType.Show else MediaType.Movie

  fun toMediaItem(): MediaItem = MediaItem(
    tmdbId = tmdbId,
    type = type,
    title = title,
    posterUrl = posterUrl,
    backdropUrl = backdropUrl,
    overview = overview,
    year = year,
    rating = rating,
  )

  companion object {
    private const val SHOW = "show"

    fun of(item: MediaItem, addedAtMs: Long): WatchlistEntry = WatchlistEntry(
      tmdbId = item.tmdbId,
      mediaType = storageType(item.type),
      title = item.title,
      posterUrl = item.posterUrl,
      backdropUrl = item.backdropUrl,
      overview = item.overview,
      year = item.year,
      rating = item.rating,
      addedAtMs = addedAtMs,
    )

    /** The key a screen holding only a type and an id can test membership with. */
    fun keyOf(type: MediaType, tmdbId: Int): String = "${storageType(type)}:$tmdbId"

    private fun storageType(type: MediaType): String = if (type == MediaType.Show) SHOW else "movie"
  }
}

/** "My List": titles saved for later, newest first. */
class WatchlistStore internal constructor(
  private val store: DataStore<Preferences>,
  private val log: (String) -> Unit = { logPersistence(it) },
) {
  constructor(context: Context) : this(context.tvDataStore)

  private val json = storeJson()
  private val data = store.recoveringData(TV_STORE_NAME)

  val entries: Flow<List<WatchlistEntry>> = data
    .map { it[KEY_LIST] }
    .distinctUntilChanged()
    .map { raw -> WatchlistRetention.ordered(decodeOrNull(raw).orEmpty()) }
    .flowOn(Dispatchers.Default)

  /**
   * Saves or unsaves [entry] in one read-modify-write, so the button cannot act on a
   * membership value that has already changed underneath it.
   */
  suspend fun toggle(entry: WatchlistEntry) {
    store.edit { prefs ->
      val stored = decodeOrNull(prefs[KEY_LIST]) ?: return@edit refuse("toggle ${entry.key}")
      prefs[KEY_LIST] = json.encodeToString(WatchlistRetention.toggled(stored, entry))
    }
  }

  suspend fun remove(key: String) {
    store.edit { prefs ->
      val stored = decodeOrNull(prefs[KEY_LIST]) ?: return@edit refuse("remove $key")
      prefs[KEY_LIST] = json.encodeToString(WatchlistRetention.remove(stored, key))
    }
  }

  /** Null for an unreadable blob; see [WatchStateStore.decodeOrNull] for why that matters. */
  private fun decodeOrNull(raw: String?): List<WatchlistEntry>? {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching { json.decodeFromString<List<WatchlistEntry>>(raw) }.getOrNull()
  }

  private fun refuse(action: String) {
    log("watchlist is unreadable; refused to $action rather than overwrite it")
  }

  private companion object {
    val KEY_LIST = stringPreferencesKey("watchlist")
  }
}

/**
 * Remembers the release a viewer picked for a series, so the next episode can
 * start on the same one instead of reopening a list of eighty rows.
 *
 * Keyed by IMDb id and capped: this is a convenience, not a library, and an old
 * series' remembered release is worth less than the space it takes in a
 * preferences file that is rewritten on every watch-state save.
 */
class StreamPickStore internal constructor(
  private val store: DataStore<Preferences>,
  private val log: (String) -> Unit = { logPersistence(it) },
) {
  constructor(context: Context) : this(context.tvDataStore)

  private val json = storeJson()
  private val data = store.recoveringData(TV_STORE_NAME)

  val selections: Flow<Map<String, StreamSelection>> = data
    .map { it[KEY_PICKS] }
    .distinctUntilChanged()
    .map { raw -> decodeOrNull(raw).orEmpty().associateBy { it.seriesId } }
    .flowOn(Dispatchers.Default)

  // Off the caller's dispatcher for the decode, for the same reason as WatchStateStore.get: this
  // is read from the main thread on the way into playback, and it parses the whole remembered-pick
  // list rather than the one entry that was asked for.
  suspend fun get(seriesId: String): StreamSelection? = withContext(Dispatchers.Default) {
    decodeOrNull(data.first()[KEY_PICKS]).orEmpty().firstOrNull { it.seriesId == seriesId }
  }

  suspend fun remember(selection: StreamSelection) {
    store.edit { prefs ->
      val stored = decodeOrNull(prefs[KEY_PICKS])
        ?: return@edit refuse("remember ${selection.seriesId}")
      val existing = stored.firstOrNull { it.seriesId == selection.seriesId }
      if (!PersistenceOrdering.accepts(existing?.updatedAtMs, selection.updatedAtMs)) return@edit
      val rest = stored.filterNot { it.seriesId == selection.seriesId }
      val next = (rest + selection).sortedByDescending { it.updatedAtMs }.take(MAX_SERIES)
      prefs[KEY_PICKS] = json.encodeToString(next)
    }
  }

  /** Null for an unreadable blob; see [WatchStateStore.decodeOrNull] for why that matters. */
  private fun decodeOrNull(raw: String?): List<StreamSelection>? {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching { json.decodeFromString<List<StreamSelection>>(raw) }.getOrNull()
  }

  private fun refuse(action: String) {
    log("stream picks are unreadable; refused to $action rather than overwrite them")
  }

  private companion object {
    val KEY_PICKS = stringPreferencesKey("stream_picks")
    const val MAX_SERIES = 40
  }
}
