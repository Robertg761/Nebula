package com.stremioshell.host.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.stremioshell.host.BuildConfig
import com.stremioshell.host.tv.data.SettingsStore
import com.stremioshell.host.tv.data.WatchStateStore
import com.stremioshell.host.tv.player.MpvPlayerActivity
import com.stremioshell.host.tv.ui.Screen
import com.stremioshell.host.tv.ui.StreamLauncher
import com.stremioshell.host.tv.ui.TvApp
import com.stremioshell.host.tv.ui.UpdatePromptHost
import com.stremioshell.host.tv.ui.theme.StremioTvTheme
import com.stremioshell.host.update.UpdateWorkScheduler
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

/**
 * Native Compose TV app: TMDB catalogs + Comet (Real-Debrid) streams + libmpv
 * playback. Runs alongside the WebView shell until it reaches parity, then
 * takes over the launcher alias.
 */
class TvAppActivity : ComponentActivity() {
  /**
   * Guards against a double OK press launching two players: MPVLib is a
   * process-global native singleton and a second create() exits the process.
   * Claimed synchronously on the click, before the suspending resume-position
   * read, so a second press cannot slip through while that read is in flight.
   */
  private val launchInFlight = AtomicBoolean(false)

  /**
   * Set when the player ends an episode, finds a next one, and cannot decide which
   * release to play it from. Compose state rather than a navigation call because the
   * result can arrive while the activity is stopped, before TvApp is composed again.
   */
  private val pendingStreams = mutableStateOf<Screen.Streams?>(null)

  /**
   * The player is started for a result purely so it can hand an episode back for
   * stream selection; a plain exit returns no data and only clears the guard.
   */
  private val playerLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult(),
  ) { result ->
    launchInFlight.set(false)
    val data = result.data ?: return@registerForActivityResult
    IntentCompat.getParcelableExtra(
      data,
      MpvPlayerActivity.EXTRA_RESULT_STREAMS,
      Screen.Streams::class.java,
    )?.let { pendingStreams.value = it }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val watchStore = WatchStateStore(applicationContext)
    // The native app is the launcher target, so it owns update scheduling now;
    // the WebView shell may never be opened again on a fresh install.
    UpdateWorkScheduler.ensureScheduled(this)

    if (BuildConfig.DEBUG) {
      // Debug-only: allow test automation to inject settings via intent extras.
      val debugTmdbKey = intent.getStringExtra("debug_tmdb_key")
      val debugAddonUrl = intent.getStringExtra("debug_addon_url")
      if (debugTmdbKey != null || debugAddonUrl != null) {
        val settings = SettingsStore(applicationContext)
        lifecycleScope.launch {
          debugTmdbKey?.let { settings.setTmdbApiKey(it) }
          debugAddonUrl?.let { settings.setAddonManifestUrl(it) }
        }
      }
    }
    setContent {
      StremioTvTheme {
        TvApp(
          streamLauncher = StreamLauncher { screen, stream ->
            if (launchInFlight.compareAndSet(false, true)) {
              lifecycleScope.launch {
                try {
                  // "Start over" is the one case that ignores a stored position; a watched
                  // record keeps position 0 anyway, so a re-watch also starts at the top.
                  val resumeMs = if (screen.startOver) {
                    0L
                  } else {
                    watchStore.get(MpvPlayerActivity.watchKeyFor(screen))?.positionMs ?: 0L
                  }
                  playerLauncher.launch(
                    MpvPlayerActivity.createIntent(this@TvAppActivity, screen, stream, resumeMs)
                      .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                  )
                } catch (t: Throwable) {
                  // Never leave the guard stuck if the launch never happened.
                  launchInFlight.set(false)
                  throw t
                }
              }
            }
          },
          pendingStreams = pendingStreams.value,
          onPendingStreamsHandled = { pendingStreams.value = null },
        )
        UpdatePromptHost()
      }
    }
  }

  override fun onResume() {
    super.onResume()
    // Back from the player (or never left): launching is allowed again.
    launchInFlight.set(false)
  }
}
