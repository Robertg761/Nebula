package com.stremioshell.host.tv

import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.stremioshell.host.BuildConfig
import com.stremioshell.host.tv.channel.WatchNextDeepLink
import com.stremioshell.host.tv.channel.WatchNextSync
import com.stremioshell.host.tv.channel.WatchNextTarget
import com.stremioshell.host.tv.data.SettingsStore
import com.stremioshell.host.tv.data.WatchStateStore
import com.stremioshell.host.tv.data.tmdb.MediaType
import com.stremioshell.host.tv.player.MpvPlayerActivity
import com.stremioshell.host.tv.search.LaunchIntents
import com.stremioshell.host.tv.search.LaunchRequest
import com.stremioshell.host.tv.search.SearchKeys
import com.stremioshell.host.tv.search.SearchLaunch
import com.stremioshell.host.tv.ui.Screen
import com.stremioshell.host.tv.ui.StreamLauncher
import com.stremioshell.host.tv.ui.TvApp
import com.stremioshell.host.tv.ui.UpdatePromptHost
import com.stremioshell.host.tv.ui.theme.NebulaTheme
import com.stremioshell.host.update.UpdateWorkScheduler
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val STATE_PENDING_KIND = "tv.pending_launch.kind"
private const val STATE_PENDING_ID = "tv.pending_launch.id"
private const val STATE_PENDING_WATCH_TYPE = "tv.pending_launch.watch.type"
private const val STATE_PENDING_WATCH_TMDB = "tv.pending_launch.watch.tmdb"
private const val STATE_PENDING_WATCH_SEASON = "tv.pending_launch.watch.season"
private const val STATE_PENDING_WATCH_EPISODE = "tv.pending_launch.watch.episode"
private const val STATE_PENDING_WATCH_POSITION = "tv.pending_launch.watch.position"
private const val STATE_PENDING_SEARCH_QUERY = "tv.pending_launch.search.query"

private const val PENDING_WATCH_NEXT = "watch_next"
private const val PENDING_SEARCH = "search"
private const val PENDING_SETTINGS = "settings"

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
   * The same instance TvApp composes with: `viewModels()` resolves against this activity's
   * ViewModelStore, which is the owner the composition's `viewModel()` uses too.
   *
   * Read only from [onTrimMemory], and only at levels the system cannot dispatch before the first
   * composition has already created it - so this never brings the ViewModel (and its eager
   * DataStore collectors) into existence ahead of the launch.
   */
  private val viewModel: TvAppViewModel by viewModels()

  /**
   * Keeps [scheduleBackgroundUpdateChecks] to one enqueue per activity instance: onResume also
   * fires on every return from the player, and re-registering the same periodic work each time is
   * a database write for no change.
   */
  private val updateSchedulingRequested = AtomicBoolean(false)

  /**
   * Set when the player ends an episode, finds a next one, and cannot decide which
   * release to play it from. Compose state rather than a navigation call because the
   * result can arrive while the activity is stopped, before TvApp is composed again.
   */
  private val pendingStreams = mutableStateOf<Screen.Streams?>(null)

  /**
   * The one external/remote launch request TvApp has not consumed yet. Event identity is Compose
   * state as well as payload, so two identical search/deep-link requests remain distinct effects.
   * Populated on a fresh launch and from onNewIntent because this Activity is singleTask.
   */
  private val pendingLaunches = PendingLaunchTracker()
  private val pendingLaunchEvent = mutableStateOf<PendingLaunchEvent?>(null)

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
    when (val seed = PendingLaunchPolicy.onCreate(
      restoringState = savedInstanceState != null,
      restoredPending = savedInstanceState?.pendingLaunchEvent(),
      retainedIntentRequest = requestOf(intent),
    )) {
      is PendingLaunchSeed.Fresh -> queueLaunch(seed.request)
      is PendingLaunchSeed.Restored -> restoreLaunch(seed.event)
      null -> Unit
    }

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
      NebulaTheme {
        val launchEvent = pendingLaunchEvent.value
        val watchNextEvent = launchEvent?.takeIf { it.request is PendingLaunch.WatchNext }
        val searchEvent = launchEvent?.takeIf { it.request is PendingLaunch.Search }
        val settingsEvent = launchEvent?.takeIf { it.request == PendingLaunch.Settings }
        val watchNextLaunch = (watchNextEvent?.request as? PendingLaunch.WatchNext)?.target
        val searchLaunch = (searchEvent?.request as? PendingLaunch.Search)?.launch
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
          pendingLaunchId = launchEvent?.id,
          pendingDeepLink = watchNextLaunch?.let { target ->
            Screen.Details(
              type = if (target.mediaType == WatchNextDeepLink.TYPE_SHOW) {
                MediaType.Show
              } else {
                MediaType.Movie
              },
              tmdbId = target.tmdbId,
              initialSeason = target.season,
              initialEpisode = target.episode,
            )
          },
          onPendingDeepLinkHandled = {
            watchNextEvent?.let(::consumeLaunch)
          },
          pendingSearch = searchLaunch,
          onPendingSearchHandled = {
            searchEvent?.let(::consumeLaunch)
          },
          pendingOpenSettings = settingsEvent != null,
          onPendingOpenSettingsHandled = { settingsEvent?.let(::consumeLaunch) },
        )
        UpdatePromptHost()
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    // getIntent() is what the rest of the activity reads; leaving it on the
    // original launch intent would make a second deep link invisible to it.
    setIntent(intent)
    route(intent)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    pendingLaunches.current?.let(outState::putPendingLaunch)
    super.onSaveInstanceState(outState)
  }

  /**
   * Files a destination request as the newest event. A plain relaunch changes nothing: it says
   * nothing about a request the composition has not picked up yet.
   */
  private fun route(intent: Intent?) {
    PendingLaunchPolicy.from(requestOf(intent))?.let(::queueLaunch)
  }

  private fun requestOf(intent: Intent?): LaunchRequest =
    LaunchIntents.route(intent?.action, intent?.dataString, queryOf(intent))

  /**
   * Replaces any older destination request. The pending state is one ordered event, even though
   * TvApp exposes its three payload shapes separately.
   */
  private fun queueLaunch(request: PendingLaunch) {
    val event = pendingLaunches.enqueue(request)
    pendingLaunchEvent.value = event
  }

  private fun restoreLaunch(event: PendingLaunchEvent) {
    pendingLaunches.restore(event)
    pendingLaunchEvent.value = event
  }

  /** Ignores a stale callback if a newer request arrived before the old effect was cancelled. */
  private fun consumeLaunch(event: PendingLaunchEvent) {
    if (pendingLaunches.consume(event.id) == null) return
    pendingLaunchEvent.value = null
  }

  /**
   * The search query off an intent from anyone. This activity is exported and declared
   * searchable, so the extras are hostile input: a non-string under the key reads as
   * absent (Bundle's typed getters swallow that), and a Bundle that cannot be unparcelled
   * at all throws, which must not take the launch down with it.
   */
  private fun queryOf(intent: Intent?): String? {
    if (intent == null) return null
    return runCatching {
      intent.getStringExtra(LaunchIntents.EXTRA_QUERY)
        ?: intent.getStringExtra(LaunchIntents.EXTRA_USER_QUERY)
    }.getOrNull()
  }

  /**
   * The remote's search/mic key, wherever the viewer is in the app.
   *
   * onKeyDown rather than dispatchKeyEvent: this is the "nobody else wanted it" hook, so
   * a screen that ever does want the key keeps first refusal on it.
   */
  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    if (SearchKeys.opensSearch(keyCode)) {
      queueLaunch(PendingLaunch.Search(SearchLaunch("")))
      return true
    }
    return super.onKeyDown(keyCode, event)
  }

  override fun onResume() {
    super.onResume()
    // Back from the player (or never left): launching is allowed again.
    launchInFlight.set(false)
    scheduleBackgroundUpdateChecks()
  }

  /**
   * Registers the periodic update check, once per activity instance and never on the launch path.
   *
   * The first `WorkManager.getInstance` loads the library, builds its configuration and opens the
   * work database, and enqueueing then writes to it. That used to sit in onCreate directly in
   * front of setContent, so a launch paid for it before anything was drawn. The idle handler runs
   * when the main thread first has nothing left to do - after the first frame - and the enqueue
   * itself goes to an IO thread from there. Nothing user-visible waits on this: the install prompt
   * is driven separately by UpdatePromptHost on ON_RESUME.
   */
  private fun scheduleBackgroundUpdateChecks() {
    if (!updateSchedulingRequested.compareAndSet(false, true)) return
    Looper.myQueue().addIdleHandler {
      // The native app is the launcher target, so it owns update scheduling now;
      // the WebView shell may never be opened again on a fresh install.
      lifecycleScope.launch(Dispatchers.IO) {
        UpdateWorkScheduler.ensureScheduled(applicationContext)
      }
      false
    }
  }

  /**
   * Gives the browsing caches back when the system asks for memory.
   *
   * UI_HIDDEN gets its own branch because it is a lifecycle signal, not a pressure one: Android
   * delivers it every time the player covers this activity, pressure or none. The metadata caches
   * are safe to drop there - they are read-through, so losing them costs a silent refetch on some
   * later navigation - but the stream list is not: the picker is usually the screen waiting right
   * behind the player, and clearing its list on every playback start turned every BACK out of the
   * player into a full all-addons refetch where the list used to be standing ready. That ~half a
   * megabyte is not what saves a 4K decode from the low-memory killer; the caches are.
   *
   * The full trim runs only on the levels that mean the system is actually short: RUNNING_LOW and
   * RUNNING_CRITICAL while on screen, and every background level from TRIM_MEMORY_BACKGROUND up.
   * The constants are not monotone in pressure (RUNNING_LOW=10 < UI_HIDDEN=20 < BACKGROUND=40),
   * which is why this cannot be one comparison.
   */
  override fun onTrimMemory(level: Int) {
    super.onTrimMemory(level)
    when {
      level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> viewModel.releaseMetadataCaches()
      level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> viewModel.onTrimMemory()
    }
  }

  override fun onStop() {
    super.onStop()
    // "Mark watched" and "Remove from row" change the watch state from the UI,
    // which the player's publish hook never sees. Leaving the app is the moment
    // the home screen is about to be looked at, so this one skips the throttle -
    // a row the viewer just dismissed must not still be there behind them.
    WatchNextSync.publish(this, force = true)
  }
}

private fun Bundle.putPendingLaunch(event: PendingLaunchEvent) {
  putLong(STATE_PENDING_ID, event.id)
  when (val request = event.request) {
    is PendingLaunch.WatchNext -> {
      putString(STATE_PENDING_KIND, PENDING_WATCH_NEXT)
      putString(STATE_PENDING_WATCH_TYPE, request.target.mediaType)
      putInt(STATE_PENDING_WATCH_TMDB, request.target.tmdbId)
      request.target.season?.let { putInt(STATE_PENDING_WATCH_SEASON, it) }
      request.target.episode?.let { putInt(STATE_PENDING_WATCH_EPISODE, it) }
      putLong(STATE_PENDING_WATCH_POSITION, request.target.resumePositionMs)
    }
    is PendingLaunch.Search -> {
      putString(STATE_PENDING_KIND, PENDING_SEARCH)
      putString(STATE_PENDING_SEARCH_QUERY, request.launch.query)
    }
    PendingLaunch.Settings ->
      putString(STATE_PENDING_KIND, PENDING_SETTINGS)
  }
}

private fun Bundle.pendingLaunchEvent(): PendingLaunchEvent? {
  val id = getLong(STATE_PENDING_ID, 0L)
  if (id <= 0L) return null
  val request = when (getString(STATE_PENDING_KIND)) {
    PENDING_WATCH_NEXT -> {
      val mediaType = getString(STATE_PENDING_WATCH_TYPE) ?: return null
      val tmdbId = getInt(STATE_PENDING_WATCH_TMDB, 0)
      if (
        tmdbId <= 0 ||
        (mediaType != WatchNextDeepLink.TYPE_MOVIE && mediaType != WatchNextDeepLink.TYPE_SHOW)
      ) {
        null
      } else {
        PendingLaunch.WatchNext(
          WatchNextTarget(
            mediaType = mediaType,
            tmdbId = tmdbId,
            season = getInt(STATE_PENDING_WATCH_SEASON)
              .takeIf { containsKey(STATE_PENDING_WATCH_SEASON) },
            episode = getInt(STATE_PENDING_WATCH_EPISODE)
              .takeIf { containsKey(STATE_PENDING_WATCH_EPISODE) },
            resumePositionMs = getLong(STATE_PENDING_WATCH_POSITION, 0L)
              .coerceAtLeast(0L),
          )
        )
      }
    }
    PENDING_SEARCH -> PendingLaunch.Search(
      SearchLaunch(getString(STATE_PENDING_SEARCH_QUERY).orEmpty())
    )
    PENDING_SETTINGS -> PendingLaunch.Settings
    else -> null
  }
  return request?.let { PendingLaunchEvent(id, it) }
}
