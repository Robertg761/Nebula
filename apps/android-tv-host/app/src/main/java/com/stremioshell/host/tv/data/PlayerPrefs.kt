package com.stremioshell.host.tv.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Its own DataStore file rather than a few more keys in `tv_app`: these are
 * written from the player while a film runs, and the app's settings and watch
 * state are written from the UI, so keeping them apart means neither can hold up
 * the other's edit.
 */
private val Context.playerPrefsDataStore by preferencesDataStore(name = "tv_player")

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
  /**
   * How many times the transport panel has been opened, saturating once the key legend has been
   * shown enough times. See [com.stremioshell.host.tv.player.OsdHintPolicy].
   */
  val osdOpens: Int = 0,
)

/** Reads and writes the player's cross-file preferences. */
class PlayerPrefsStore(private val context: Context) {
  val prefs: Flow<PlayerPrefs> = context.playerPrefsDataStore.data.map(::read)

  /**
   * One-shot read, for the player's startup: `alang`/`slang` have to be set
   * before `loadfile`, which is a single point in time rather than something
   * that can follow a flow.
   */
  suspend fun get(): PlayerPrefs = read(context.playerPrefsDataStore.data.first())

  suspend fun setAudioLanguage(value: String) {
    context.playerPrefsDataStore.edit { it[KEY_AUDIO_LANG] = value.trim() }
  }

  suspend fun setSubtitleLanguage(value: String) {
    context.playerPrefsDataStore.edit { it[KEY_SUB_LANG] = value.trim() }
  }

  suspend fun setSubtitleSize(storageName: String) {
    context.playerPrefsDataStore.edit { it[KEY_SUB_SIZE] = storageName.trim() }
  }

  suspend fun setAudioOutput(storageName: String) {
    context.playerPrefsDataStore.edit { it[KEY_AUDIO_OUTPUT] = storageName.trim() }
  }

  suspend fun setOsdOpens(value: Int) {
    context.playerPrefsDataStore.edit { it[KEY_OSD_OPENS] = value }
  }

  private fun read(prefs: Preferences) = PlayerPrefs(
    audioLanguage = prefs[KEY_AUDIO_LANG].orEmpty(),
    subtitleLanguage = prefs[KEY_SUB_LANG].orEmpty(),
    subtitleSize = prefs[KEY_SUB_SIZE].orEmpty(),
    audioOutput = prefs[KEY_AUDIO_OUTPUT].orEmpty(),
    osdOpens = prefs[KEY_OSD_OPENS] ?: 0,
  )

  private companion object {
    val KEY_AUDIO_LANG = stringPreferencesKey("player_audio_language")
    val KEY_SUB_LANG = stringPreferencesKey("player_subtitle_language")
    val KEY_SUB_SIZE = stringPreferencesKey("player_subtitle_size")
    val KEY_AUDIO_OUTPUT = stringPreferencesKey("player_audio_output")
    val KEY_OSD_OPENS = intPreferencesKey("player_osd_opens")
  }
}
