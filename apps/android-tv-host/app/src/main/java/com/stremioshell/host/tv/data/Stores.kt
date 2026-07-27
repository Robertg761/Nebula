package com.stremioshell.host.tv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stremioshell.host.tv.data.addon.StreamSelection
import com.stremioshell.host.tv.data.tmdb.MediaItem
import com.stremioshell.host.tv.data.tmdb.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.tvDataStore by preferencesDataStore(name = "tv_app")

/**
 * Scope for writes that must outlive the screen that started them. Saving the
 * resume position races `finish()`: a `lifecycleScope` write gets cancelled at
 * onDestroy before DataStore reaches disk, so the position is silently lost.
 */
val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** User configuration for the native TV app. */
class SettingsStore(private val context: Context) {
  val tmdbApiKey: Flow<String> = context.tvDataStore.data.map { it[KEY_TMDB] .orEmpty() }
  val addonManifestUrl: Flow<String> = context.tvDataStore.data.map { it[KEY_ADDON].orEmpty() }

  suspend fun setTmdbApiKey(value: String) {
    context.tvDataStore.edit { it[KEY_TMDB] = value.trim() }
  }

  suspend fun setAddonManifestUrl(value: String) {
    context.tvDataStore.edit { it[KEY_ADDON] = value.trim() }
  }

  private companion object {
    val KEY_TMDB = stringPreferencesKey("tmdb_api_key")
    val KEY_ADDON = stringPreferencesKey("addon_manifest_url")
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

/**
 * Resume positions, watched history and the Continue Watching rail, newest first.
 *
 * One list holds both kinds of record: they are written from the same places and
 * a video moves from one to the other, so splitting them would mean two writes
 * per finish with a window where a video was neither. [WatchStateRetention] is
 * what keeps them from crowding each other out.
 */
class WatchStateStore(private val context: Context) {
  private val json = Json { ignoreUnknownKeys = true }

  val entries: Flow<List<WatchEntry>> = context.tvDataStore.data.map { prefs ->
    decode(prefs[KEY_WATCH]).sortedByDescending { it.updatedAtMs }
  }

  suspend fun get(key: String): WatchEntry? {
    return decode(context.tvDataStore.data.first()[KEY_WATCH]).firstOrNull { it.key == key }
  }

  suspend fun upsert(entry: WatchEntry) {
    context.tvDataStore.edit { prefs ->
      val rest = decode(prefs[KEY_WATCH]).filterNot { it.key == entry.key }
      prefs[KEY_WATCH] = json.encodeToString(WatchStateRetention.prune(rest + entry))
    }
  }

  /**
   * Writes [entry] only when nothing is stored for its key, so seeding the next
   * episode of a series cannot overwrite a resume point the viewer already has
   * in it (a re-watch that stopped part-way through episode 4).
   */
  suspend fun upsertIfAbsent(entry: WatchEntry) {
    context.tvDataStore.edit { prefs ->
      val stored = decode(prefs[KEY_WATCH])
      if (stored.any { it.key == entry.key }) return@edit
      prefs[KEY_WATCH] = json.encodeToString(WatchStateRetention.prune(stored + entry))
    }
  }

  /**
   * Marks a stored video watched and drops its resume point. Only ever updates an
   * existing record: the callers are acting on a card that is already on screen,
   * and inventing an entry from a key alone would produce one with no title.
   */
  suspend fun markWatched(key: String, watchedAtMs: Long) {
    context.tvDataStore.edit { prefs ->
      val stored = decode(prefs[KEY_WATCH])
      val entry = stored.firstOrNull { it.key == key } ?: return@edit
      val marked = entry.copy(positionMs = 0, updatedAtMs = watchedAtMs, watchedAtMs = watchedAtMs)
      prefs[KEY_WATCH] = json.encodeToString(
        WatchStateRetention.prune(stored.filterNot { it.key == key } + marked),
      )
    }
  }

  suspend fun remove(key: String) {
    context.tvDataStore.edit { prefs ->
      prefs[KEY_WATCH] = json.encodeToString(decode(prefs[KEY_WATCH]).filterNot { it.key == key })
    }
  }

  private fun decode(raw: String?): List<WatchEntry> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching { json.decodeFromString<List<WatchEntry>>(raw) }.getOrDefault(emptyList())
  }

  private companion object {
    val KEY_WATCH = stringPreferencesKey("watch_state")
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
class WatchlistStore(private val context: Context) {
  private val json = Json { ignoreUnknownKeys = true }

  val entries: Flow<List<WatchlistEntry>> = context.tvDataStore.data.map { prefs ->
    WatchlistRetention.ordered(decode(prefs[KEY_LIST]))
  }

  /**
   * Saves or unsaves [entry] in one read-modify-write, so the button cannot act on a
   * membership value that has already changed underneath it.
   */
  suspend fun toggle(entry: WatchlistEntry) {
    context.tvDataStore.edit { prefs ->
      val next = WatchlistRetention.toggled(decode(prefs[KEY_LIST]), entry)
      prefs[KEY_LIST] = json.encodeToString(next)
    }
  }

  suspend fun remove(key: String) {
    context.tvDataStore.edit { prefs ->
      prefs[KEY_LIST] = json.encodeToString(WatchlistRetention.remove(decode(prefs[KEY_LIST]), key))
    }
  }

  private fun decode(raw: String?): List<WatchlistEntry> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching { json.decodeFromString<List<WatchlistEntry>>(raw) }.getOrDefault(emptyList())
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
class StreamPickStore(private val context: Context) {
  private val json = Json { ignoreUnknownKeys = true }

  val selections: Flow<Map<String, StreamSelection>> = context.tvDataStore.data.map { prefs ->
    decode(prefs[KEY_PICKS]).associateBy { it.seriesId }
  }

  suspend fun get(seriesId: String): StreamSelection? {
    return decode(context.tvDataStore.data.first()[KEY_PICKS]).firstOrNull { it.seriesId == seriesId }
  }

  suspend fun remember(selection: StreamSelection) {
    context.tvDataStore.edit { prefs ->
      val rest = decode(prefs[KEY_PICKS]).filterNot { it.seriesId == selection.seriesId }
      val next = (rest + selection).sortedByDescending { it.updatedAtMs }.take(MAX_SERIES)
      prefs[KEY_PICKS] = json.encodeToString(next)
    }
  }

  private fun decode(raw: String?): List<StreamSelection> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching { json.decodeFromString<List<StreamSelection>>(raw) }.getOrDefault(emptyList())
  }

  private companion object {
    val KEY_PICKS = stringPreferencesKey("stream_picks")
    const val MAX_SERIES = 40
  }
}
