package com.stremioshell.host.tv.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Its own DataStore file rather than a few more keys in `tv_app`: these are
 * written from the player while a film runs, and the app's settings and watch
 * state are written from the UI, so keeping them apart means neither can hold up
 * the other's edit.
 */
private const val PLAYER_STORE_NAME = "tv_player"

private val Context.playerPrefsDataStore by preferencesDataStore(
  name = PLAYER_STORE_NAME,
  corruptionHandler = preferencesCorruptionHandler(PLAYER_STORE_NAME),
)

/**
 * The playback choices that outlive one file.
 *
 * Only preferences a viewer would be annoyed to set twice are here. Playback
 * speed is per-session (a speed set for one film is rarely wanted for the next)
 * and the audio/subtitle delays are per-file (they correct one release's muxing),
 * so neither is stored.
 */
data class PlayerPrefs(
  /** Container language tag, normalized; blank means "let mpv decide". */
  val audioLanguage: String = "",
  /** As above, plus "off" for subtitles the viewer switched off. */
  val subtitleLanguage: String = "",
  /** A [com.stremioshell.host.tv.player.SubtitleSize] storage name; blank means the default. */
  val subtitleSize: String = "",
  /**
   * A [com.stremioshell.host.tv.player.AudioOutputMode] storage name; blank means
   * the default. Stored because it describes the room's amplifier rather than the
   * film: a viewer who has an AVR has it for every film they watch.
   */
  val audioOutput: String = "",
  /** Whether an ended episode may move into the next one without an OK press. */
  val autoPlayNext: Boolean = true,
  /** Length of the up-next card's automatic countdown. */
  val upNextCountdownSeconds: Int = PlaybackPreferencePolicy.DEFAULT_COUNTDOWN_SECONDS,
  /**
   * How many times the transport panel has been opened, saturating once the key legend has been
   * shown enough times. See [com.stremioshell.host.tv.player.OsdHintPolicy].
   */
  val osdOpens: Int = 0,
)

/** Reads and writes the player's cross-file preferences. */
class PlayerPrefsStore(private val context: Context) {
  private val store = context.playerPrefsDataStore
  private val data = store.recoveringData(PLAYER_STORE_NAME)

  val prefs: Flow<PlayerPrefs> = data.map(::read)

  /**
   * One-shot read, for the player's startup: `alang`/`slang` have to be set
   * before `loadfile`, which is a single point in time rather than something
   * that can follow a flow.
   */
  suspend fun get(): PlayerPrefs = read(data.first())

  suspend fun setAudioLanguage(value: String) {
    store.edit { it[KEY_AUDIO_LANG] = normalizeLanguage(value) }
  }

  suspend fun setSubtitleLanguage(value: String) {
    store.edit { it[KEY_SUB_LANG] = normalizeLanguage(value) }
  }

  /** Writes the two free-text language fields together, as the Settings action presents them. */
  suspend fun setLanguages(audio: String, subtitles: String) {
    store.edit {
      it[KEY_AUDIO_LANG] = normalizeLanguage(audio)
      it[KEY_SUB_LANG] = normalizeLanguage(subtitles)
    }
  }

  suspend fun setSubtitleSize(storageName: String) {
    store.edit { it[KEY_SUB_SIZE] = storageName.trim() }
  }

  suspend fun setAudioOutput(storageName: String) {
    store.edit { it[KEY_AUDIO_OUTPUT] = storageName.trim() }
  }

  suspend fun setAutoPlayNext(enabled: Boolean) {
    store.edit { it[KEY_AUTO_PLAY_NEXT] = enabled }
  }

  suspend fun setUpNextCountdownSeconds(seconds: Int) {
    store.edit {
      it[KEY_UP_NEXT_COUNTDOWN_SECONDS] = PlaybackPreferencePolicy.countdownSeconds(seconds)
    }
  }

  suspend fun setOsdOpens(value: Int) {
    store.edit {
      it[KEY_OSD_OPENS] = PersistenceOrdering.monotonicCounter(it[KEY_OSD_OPENS], value)
    }
  }

  /**
   * Resets viewer-facing playback choices without resetting the hidden OSD-hint counter. The
   * latter is tutorial history, not a playback preference, and showing the legend again would make
   * Reset feel as though it changed unrelated behaviour.
   */
  suspend fun resetPlaybackPreferences() {
    store.edit {
      it.remove(KEY_AUDIO_LANG)
      it.remove(KEY_SUB_LANG)
      it.remove(KEY_SUB_SIZE)
      it.remove(KEY_AUDIO_OUTPUT)
      it.remove(KEY_AUTO_PLAY_NEXT)
      it.remove(KEY_UP_NEXT_COUNTDOWN_SECONDS)
    }
  }

  private fun read(prefs: Preferences) = PlayerPrefs(
    audioLanguage = prefs[KEY_AUDIO_LANG].orEmpty(),
    subtitleLanguage = prefs[KEY_SUB_LANG].orEmpty(),
    subtitleSize = prefs[KEY_SUB_SIZE].orEmpty(),
    audioOutput = prefs[KEY_AUDIO_OUTPUT].orEmpty(),
    autoPlayNext = prefs[KEY_AUTO_PLAY_NEXT] ?: true,
    upNextCountdownSeconds = PlaybackPreferencePolicy.countdownSeconds(
      prefs[KEY_UP_NEXT_COUNTDOWN_SECONDS] ?: PlaybackPreferencePolicy.DEFAULT_COUNTDOWN_SECONDS,
    ),
    osdOpens = (prefs[KEY_OSD_OPENS] ?: 0).coerceAtLeast(0),
  )

  private fun normalizeLanguage(value: String): String = value.trim().lowercase(Locale.ROOT)

  private companion object {
    val KEY_AUDIO_LANG = stringPreferencesKey("player_audio_language")
    val KEY_SUB_LANG = stringPreferencesKey("player_subtitle_language")
    val KEY_SUB_SIZE = stringPreferencesKey("player_subtitle_size")
    val KEY_AUDIO_OUTPUT = stringPreferencesKey("player_audio_output")
    val KEY_AUTO_PLAY_NEXT = booleanPreferencesKey("player_auto_play_next")
    val KEY_UP_NEXT_COUNTDOWN_SECONDS = intPreferencesKey("player_up_next_countdown_seconds")
    val KEY_OSD_OPENS = intPreferencesKey("player_osd_opens")
  }
}

/**
 * Safe, remote-friendly choices for the next-episode countdown.
 *
 * Settings cycles this short ladder rather than accepting arbitrary text. Reading still
 * normalises defensively because an older/debug build or a restored DataStore can contain any
 * integer; the nearest supported value keeps both the UI label and the player's timer bounded.
 */
object PlaybackPreferencePolicy {
  val COUNTDOWN_SECONDS: List<Int> = listOf(5, 10, 15, 30)
  const val DEFAULT_COUNTDOWN_SECONDS = 15

  fun countdownSeconds(raw: Int): Int =
    COUNTDOWN_SECONDS.minBy { candidate -> abs(candidate.toLong() - raw.toLong()) }

  fun nextCountdownSeconds(current: Int): Int {
    val normalized = countdownSeconds(current)
    val index = COUNTDOWN_SECONDS.indexOf(normalized)
    return COUNTDOWN_SECONDS[(index + 1) % COUNTDOWN_SECONDS.size]
  }
}
