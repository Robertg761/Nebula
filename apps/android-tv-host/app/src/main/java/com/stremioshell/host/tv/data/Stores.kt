package com.stremioshell.host.tv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
) {
  val progress: Float
    get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** Resume positions and the Continue Watching rail, newest first. */
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
      val next = (rest + entry).sortedByDescending { it.updatedAtMs }.take(MAX_ENTRIES)
      prefs[KEY_WATCH] = json.encodeToString(next)
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
    const val MAX_ENTRIES = 50
  }
}
