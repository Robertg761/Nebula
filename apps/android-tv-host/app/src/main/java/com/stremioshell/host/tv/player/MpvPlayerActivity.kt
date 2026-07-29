package com.stremioshell.host.tv.player

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.stremioshell.host.R
import com.stremioshell.host.tv.channel.WatchNextSync
import com.stremioshell.host.tv.data.PlayerPrefs
import com.stremioshell.host.tv.data.PlayerPrefsStore
import com.stremioshell.host.tv.data.PlaybackUrlPolicy
import com.stremioshell.host.tv.data.PlaybackUrlValidation
import com.stremioshell.host.tv.data.PublicOnlyDns
import com.stremioshell.host.tv.data.SettingsStore
import com.stremioshell.host.tv.data.StreamPickStore
import com.stremioshell.host.tv.data.WatchEntry
import com.stremioshell.host.tv.data.WatchStateStore
import com.stremioshell.host.tv.data.SharedHttpClient
import com.stremioshell.host.tv.data.awaitAndConsumeResponse
import com.stremioshell.host.tv.data.addon.AddonStream
import com.stremioshell.host.tv.data.addon.AddonStreamSubtitle
import com.stremioshell.host.tv.data.addon.StreamAutoPick
import com.stremioshell.host.tv.data.addon.StreamCatalog
import com.stremioshell.host.tv.data.persistenceScope
import com.stremioshell.host.tv.data.subtitles.SubtitlesClient
import com.stremioshell.host.tv.data.tmdb.EpisodeItem
import com.stremioshell.host.tv.data.tmdb.MediaType
import com.stremioshell.host.tv.data.tmdb.TmdbClient
import com.stremioshell.host.tv.diagnostics.NebulaDiagnostics
import com.stremioshell.host.tv.ui.BadgeTone
import com.stremioshell.host.tv.ui.EmptyState
import com.stremioshell.host.tv.ui.InitialFocusTarget
import com.stremioshell.host.tv.ui.NebulaBadge
import com.stremioshell.host.tv.ui.NebulaButton
import com.stremioshell.host.tv.ui.NebulaButtonStyle
import com.stremioshell.host.tv.ui.RequestInitialFocus
import com.stremioshell.host.tv.ui.Screen
import com.stremioshell.host.tv.ui.initialFocusTarget
import com.stremioshell.host.tv.ui.rememberInitialFocusTarget
import com.stremioshell.host.tv.ui.theme.NebulaAccentBrush
import com.stremioshell.host.tv.ui.theme.NebulaBottomScrim
import com.stremioshell.host.tv.ui.theme.NebulaDimens
import com.stremioshell.host.tv.ui.theme.NebulaMotion
import com.stremioshell.host.tv.ui.theme.NebulaOsdScrim
import com.stremioshell.host.tv.ui.theme.NebulaPalette
import com.stremioshell.host.tv.ui.theme.NebulaShapes
import com.stremioshell.host.tv.ui.theme.NebulaTheme
import com.stremioshell.host.tv.ui.theme.nebulaButtonBorder
import com.stremioshell.host.tv.ui.theme.nebulaButtonGlow
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Date
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import okhttp3.Request

class MpvPlayerActivity : ComponentActivity() {
  private var mpvCreated = false
  /** True only when create/init failed and observer registration may be incomplete. */
  private var mpvInitializationIncomplete = false
  private lateinit var watchStore: WatchStateStore
  private lateinit var playerPrefsStore: PlayerPrefsStore

  // Only the binge loop needs these: the next episode's list, the release to look
  // for in it, and the key TMDB is asked for it with.
  private lateinit var settingsStore: SettingsStore
  private lateinit var streamPickStore: StreamPickStore
  private val streamCatalog = StreamCatalog()
  // Reads Settings at request time, not construction: settingsStore is only bound
  // in onCreate, and the search this feeds runs long after.
  private val subtitlesClient = SubtitlesClient(baseUrl = { settingsStore.subtitlesBaseUrl.first() })
  private val mainHandler = Handler(Looper.getMainLooper())

  /**
   * Where every `getProperty*` runs. A property read is a synchronous JNI call
   * that takes mpv's core lock, so one issued while the core is busy servicing a
   * seek on a stalled network stream blocks its caller for as long as the stall
   * lasts. On the main thread that is a visible freeze and, on a slow box, an
   * ANR: reading `track-list` (a whole JSON document, then parsed) straight out
   * of MENU/CAPTIONS key handling was the worst of them.
   */
  private var mpvWorker: HandlerThread? = null
  private var mpvWorkerHandler: Handler? = null

  /**
   * Held across every property read on [mpvWorker], core creation and [MPVLib.destroy].
   *
   * This must be process-global, not activity-local: libmpv itself is a singleton. A prior
   * activity can finish while a JNI read is still unwinding on its worker; without one shared
   * lock its delayed destroy can tear down the next activity's newly-created core.
   */
  private val mpvLock = MpvCoreCoordinator.lock
  private var mpvLease: MpvCoreCoordinator.Lease? = null

  /**
   * Serializes resume writes in call order. `CoroutineStart.UNDISPATCHED` is used at launch sites
   * so every caller either takes this mutex or joins its FIFO before returning; a periodic save
   * can therefore never land after the Finished save that followed it.
   */
  private val watchWriteMutex = Mutex()

  /**
   * Whether the core is alive. Checked on the worker thread under [mpvLock]
   * immediately before a JNI call, and cleared on the main thread under the same
   * lock just before the core goes.
   */
  @Volatile
  private var mpvAlive = false

  @Volatile
  private var destroying = false

  // The mpv render surface and the detected content frame rate, used to retune
  // the display refresh rate so film/PAL content plays without pulldown judder.
  private var playbackSurface: Surface? = null

  /**
   * Written on the main thread. Compose state rather than a plain field because the
   * OSD shows it as a chip: it is read back from the display-mode match, which
   * lands a beat after the track list the chip beside it comes from.
   */
  private var contentFps by mutableFloatStateOf(0f)

  /**
   * Whether a track-list read is already queued. Cycling subtitles or audio
   * queues one fetch-and-parse per press otherwise, and the presses come faster
   * than the reads complete on a stalled stream; the pending one already sees
   * every cycle issued before it runs.
   */
  private val trackInfoPending = AtomicBoolean(false)

  /**
   * The stream is loaded once per mpv instance. The render surface comes and
   * goes (backgrounding, display-mode switches), and reloading on every
   * `surfaceCreated` would restart the stream from scratch each time.
   */
  private var fileLoaded = false

  /**
   * Identity of the file currently being opened or played. Every delayed callback and asynchronous
   * read captures this value, so a retry or episode replacement cannot let the previous file's
   * timeout, cache reading, track list or metadata mutate the new file.
   */
  @Volatile
  private var loadGeneration = 0L

  /** False between issuing `loadfile` and mpv acknowledging the new file with START_FILE. */
  @Volatile
  private var acceptingFileProperties = false

  /**
   * Replacement emits END_FILE for the old file before START_FILE for the new one. While this is
   * true that END_FILE is transition bookkeeping, not a failure of the new stream.
   */
  private var awaitingStartFile = false

  /** The activity is visible and may acquire audio focus or unpause a newly loaded file. */
  private var activityStarted = false

  /**
   * Whether there is a surface to render into. Together with [prefsApplied] this
   * gates [maybeLoadFile]: `alang`/`slang` are read when the file is opened, so
   * a `loadfile` issued before the stored languages reach mpv gets the
   * container's default tracks and the preference silently does nothing.
   */
  private var surfaceReady = false

  /** Whether the stored player preferences have reached mpv (or timed out). */
  private var prefsApplied = false

  /**
   * Stops a preference read that never completes from holding the stream
   * hostage. Starting with the container defaults is a small loss; not starting
   * at all is the session.
   */
  private val prefsTimeoutRunnable = Runnable {
    if (prefsApplied) return@Runnable
    prefsApplied = true
    maybeLoadFile()
  }

  /**
   * The stored preferences as last read or written, kept so an explicit track
   * pick can be judged against them (turning subtitles on while the stored
   * preference says "off" has to clear it) without another read.
   */
  private var playerPrefs = PlayerPrefs()

  /**
   * Whether mpv ever reported the stream as playable (MPV_EVENT_FILE_LOADED).
   * Distinct from [fileLoaded], which only says `loadfile` was issued: a dead
   * debrid link sets that and never reaches a first frame.
   */
  private var playbackStarted = false

  /** Prefix-aware, URL-redacting diagnostic state for the current file. */
  private val playbackErrors = PlaybackErrorAccumulator()

  private var url = ""
  private var requestHeaders: Map<String, String> = emptyMap()
  private var embeddedSubtitles: List<AddonStreamSubtitle> = emptyList()
  private var streamVideoHash: String? = null
  private var streamFilename: String? = null
  private var streamVideoSize: Long? = null
  private var title = ""
  private var watchKey = ""
  private var tmdbId = 0
  private var mediaType = "movie"
  private var posterUrl: String? = null
  private var season: Int? = null
  private var episode: Int? = null
  private var resumeMs = 0L
  /** An explicit Restart must invalidate an older resume even if the viewer exits before 10s. */
  private var resumeResetRequested = false
  private var finishing = false

  /** The addon's id for the title, which is what the next episode is asked for by. */
  private var imdbId: String? = null

  /**
   * The release the playing stream came from, matched against the next episode's
   * streams so a binge stays on one debrid source, one encode and one set of
   * subtitle tracks. See [com.stremioshell.host.tv.data.addon.BingeGroupMatcher].
   */
  private var bingeGroup: String? = null

  /**
   * Whether the end of the video has already been acted on. `eof-reached` and
   * END_FILE can both arrive for one ending, and the up-next card keeps this
   * activity alive afterwards, so without this the watched record is written twice
   * and the countdown restarts under the viewer.
   */
  private var endHandled = false

  /**
   * The next episode, resolved from TMDB while the current one plays so the card
   * can appear on the last frame rather than after a round trip. Null means there
   * is nothing to go on to, or the lookup has not landed (or failed).
   *
   * Compose state because the transport row offers "Next episode" only once this
   * is non-null, and the lookup that fills it in lands minutes into the film.
   */
  private val upNextTarget = mutableStateOf<UpNextTarget?>(null)
  private var upNextLookupIssued = false
  private var upNextLookupAttempts = 0
  private var upNextRetryRunnable: Runnable? = null

  /** Non-null while the up-next card is on screen; also what redirects the remote. */
  private val upNextCard = mutableStateOf<UpNextCardState?>(null)
  private var upNextCountdownStartMs = 0L
  private var upNextCountdownTotalMs = UpNextPolicy.COUNTDOWN_MS

  /** Guards the next episode's stream lookup, so OK during it cannot start two. */
  private var nextEpisodeStarting = false
  private var retryInFlight = false

  /**
   * When the viewer last pressed something, as [SystemClock.uptimeMillis]. Zero
   * means "not this session", which is what makes an untouched ending count down
   * rather than ask - see [UpNextPolicy.offer].
   */
  private var lastInteractionMs = 0L

  /**
   * Audio focus is this activity's to manage: mpv's `ao=audiotrack` opens an
   * AudioTrack directly and never asks for focus, so without this a film starts
   * on top of whatever music was already playing, and keeps playing at full
   * volume under an Assistant answer or an alarm.
   */
  private val audioManager: AudioManager by lazy { getSystemService(AudioManager::class.java) }
  private val playbackAudioAttributes by lazy {
    AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
      .build()
  }
  private var audioFocusRequest: AudioFocusRequest? = null
  private var hasAudioFocus = false
  private var audioDeviceCallbackRegistered = false

  /**
   * A connected sink can disappear while mpv is handing it an encoded bitstream.
   * The callback is delivered on [mainHandler], so falling back can update both
   * the Compose model and mpv without crossing threads.
   */
  private val audioDeviceCallback = object : AudioDeviceCallback() {
    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
      revalidateAudioOutput()
    }

    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
      revalidateAudioOutput()
    }
  }

  /**
   * Whether the focus listener is the reason playback is paused, and therefore
   * owes a resume once focus comes back. Never set for a pause the viewer asked
   * for: returning from a phone call must not restart a film they had paused.
   */
  private var pausedForFocusLoss = false

  /**
   * Whether the pause on screen is one this player asked for. mpv pauses itself at
   * the end of a file (`keep-open=yes`) and that arrives as the same `pause`
   * property change, so the flag - not the property - is what says a viewer was
   * sitting on a paused frame when the credits ran out.
   */
  private var pauseRequested = false

  /** mpv's volume before ducking, or null when not ducked. */
  private var volumeBeforeDuck: Int? = null
  private var observedVolume = 100

  /**
   * Publishes transport state so the platform has something to route HDMI-CEC and
   * Bluetooth media buttons at, and something for Assistant's "pause" to talk to.
   * Without a session those all reach nothing and appear to be ignored.
   */
  private var mediaSession: MediaSession? = null

  /** Duration last written into the session metadata, so it is published once. */
  private var publishedDurationMs = -1L

  // Observed playback state, updated from mpv events on the main thread.
  private val paused = mutableStateOf(false)
  private val buffering = mutableStateOf(true)
  private val seeking = mutableStateOf(false)
  private val timePosSec = mutableDoubleStateOf(0.0)
  private val durationSec = mutableDoubleStateOf(0.0)

  /**
   * How far into the file the demuxer has read, in absolute seconds.
   *
   * `demuxer-cache-time` rather than `demuxer-cache-duration`: the absolute form is what the scrub
   * bar needs, and mpv reports the relative one against a play head that has already moved. Drives
   * the buffered range under the scrub fill, which is the difference between a stream the viewer
   * can see is struggling and one that has simply stopped for no reason they can tell.
   */
  private val cacheAheadSec = mutableStateOf<Double?>(null)

  /** mpv's playback speed, so the OSD's countdown and end time follow it. */
  private val playbackSpeed = mutableDoubleStateOf(1.0)

  private val osdVisible = mutableStateOf(true)

  /**
   * How many times the transport panel has been opened on this install, saturating once the key
   * legend has done its teaching. See [OsdHintPolicy]; seeded from PlayerPrefs at startup.
   */
  private val osdOpens = mutableIntStateOf(OsdHintPolicy.OPENS_WITH_HINT)
  private var osdHideAtMs = 0L

  /**
   * Which of the OSD's two rows the D-pad is driving. Both rows report their own
   * focus into it rather than it being set only by the keys that move between
   * them, so a focus move the OSD did not ask for cannot leave the key map
   * pointed at a row the viewer is not on.
   */
  private val osdRow = mutableStateOf(OsdRow.Scrub)

  /**
   * Whether focus is inside the OSD. The controls only own the D-pad once it is:
   * if the focus request never lands, [onKeyDown] still seeks and still opens the
   * menu, so a viewer is never left with a remote that does nothing.
   *
   * Set through [setOsdFocus], which re-times the auto-hide around it; [hideOsd]
   * is the one exception, having no countdown left to re-time.
   */
  private var osdHasFocus = false

  /**
   * The season and episode as the OSD shows them. Mirrored into composition state
   * because the binge loop moves [season] and [episode] under an OSD that has no
   * other reason to recompose, and it would go on naming the previous episode.
   */
  private val episodeLabel = mutableStateOf<String?>(null)

  /**
   * A transient line under the OSD's track chips: a subtitle that has just been
   * added, or one that could not be. Separate from the chips because those are
   * derived from the track list and are rewritten every time it is read.
   */
  private val osdMessage = mutableStateOf("")
  private var osdMessageAtMs = 0L

  /** mpv's track list as last read, backing both the OSD line and the menu. */
  private val tracks = mutableStateOf<List<MpvTrack>>(emptyList())

  // The in-player menu: which section is showing, and the option values it edits.
  private val menuVisible = mutableStateOf(false)
  private val menuTab = mutableStateOf(PlayerMenuTab.Audio)
  private val subtitleSize = mutableStateOf(SubtitleSize.DEFAULT)
  private val audioOutput = mutableStateOf(AudioOutputMode.DEFAULT)
  private val audioDelaySec = mutableDoubleStateOf(0.0)
  private val subtitleDelaySec = mutableDoubleStateOf(0.0)

  /**
   * Whether this file's Dolby Vision notice has been shown. Per file, and reset in
   * [startNextEpisode] with the rest of the per-file state: the track list is read
   * again on every track pick, and repeating the notice each time would turn one
   * piece of information into nagging.
   */
  private var dolbyVisionWarned = false

  /**
   * What a subtitles addon has for the playing file, and whether it has been asked
   * yet. Per file, like everything else about external subtitles: mpv drops them on
   * `loadfile ... replace`, so the next episode of a binge starts from [Idle] again.
   */
  private val externalSubtitles = mutableStateOf<ExternalSubtitlesState>(
    ExternalSubtitlesState.Idle,
  )

  /**
   * Whether a `sub-add` is still fetching its file. Stops a viewer who presses OK
   * twice from spending two downloads on the same subtitle.
   */
  private var externalSubAdding = false

  /** Files whose worker callback has not claimed ownership yet. Guarded by itself. */
  private val queuedSubtitleFiles = mutableSetOf<File>()

  /**
   * Whether the next track list to arrive is the result of the audio-cycle key,
   * and should therefore update the stored language preference the same way a
   * pick from the menu does. Nothing else may: the list is also read at
   * FILE_LOADED, and learning a preference from whatever the container happened
   * to default to would overwrite the viewer's own choice.
   */
  private var audioCyclePending = false

  /**
   * Down time of the BACK press that has already been spent - on closing the menu,
   * or on hiding the controls - so its own repeats cannot be read as a second
   * press asking to leave the player.
   */
  private var consumedBackDownTime = Long.MIN_VALUE

  /**
   * Non-null once the stream is known to be dead: replaces the spinner with a
   * readable failure and a way out.
   */
  private val playbackError = mutableStateOf<String?>(null)

  /**
   * Fires when the stream never became playable: either nothing loaded inside
   * [LOAD_TIMEOUT_MS], or mpv ended the file before FILE_LOADED and the short
   * grace after it passed without a new file starting.
   */
  private var loadFailedRunnable: Runnable? = null

  /**
   * Fires when a stream that did start playing has been waiting on its cache for
   * [STALL_TIMEOUT_MS] without a byte arriving. mpv's own recovery cannot reach
   * this one: `reconnect` needs the connection to actually drop, and a debrid
   * host that keeps the socket open and simply stops sending never drops it, so
   * the load watchdog having disarmed at FILE_LOADED used to leave the spinner
   * turning for the rest of the evening.
   */
  private var stallRunnable: Runnable? = null

  /**
   * Clears [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] once nothing has been
   * moving for [IDLE_SCREEN_ON_MS]. Holding it for the whole session is right
   * while a film plays and wrong the moment one is paused and forgotten: a static
   * frame pinned on an OLED overnight is burn-in, and the screensaver the panel
   * would otherwise run is exactly the protection being suppressed.
   */
  private val releaseScreenOnRunnable = Runnable {
    screenOnReleaseArmed = false
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  private var screenOnReleaseArmed = false

  /**
   * Unplugging headphones (or a Bluetooth headset going out of range) must not
   * dump the soundtrack out of the TV's speakers at whatever volume the room was
   * not expecting. Registered only while playing, because that is the only state
   * this has anything to do.
   */
  private val becomingNoisyReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
      if (!transportAllowed() || paused.value) return
      // Not a focus-loss pause: nothing is coming back to hand the audio route
      // over, so this must never resume on its own.
      pausePlayback()
      showOsd()
    }
  }

  private var noisyReceiverRegistered = false

  /**
   * Writes the resume position while the film is running, then re-arms itself.
   * The lifecycle saves alone cover only an orderly exit: an OOM kill on a
   * low-RAM box, a firmware crash or a pulled plug all bypass them, and a viewer
   * 80 minutes in used to come back to no Continue Watching entry at all.
   */
  private val progressSaveRunnable = Runnable {
    if (progressSaveAllowed()) {
      saveWatchState(SaveReason.Progress)
      scheduleProgressSave()
    }
  }

  /**
   * Drives the up-next countdown, and starts the next episode when it runs out.
   * Ticks faster than once a second so the number on screen is never a whole
   * second stale, which reads as a stuck countdown.
   */
  private val upNextTickRunnable = object : Runnable {
    override fun run() {
      val state = upNextCard.value ?: return
      val elapsedMs = SystemClock.uptimeMillis() - upNextCountdownStartMs
      if (UpNextPolicy.isDue(elapsedMs, upNextCountdownTotalMs)) {
        playNextEpisode()
        return
      }
      upNextCard.value =
        state.copy(
          secondsLeft = UpNextPolicy.secondsLeft(elapsedMs, upNextCountdownTotalMs),
          progress = PlaybackFrameRate.progressRemaining(elapsedMs, upNextCountdownTotalMs),
        )
      mainHandler.postDelayed(this, UP_NEXT_TICK_MS)
    }
  }

  private val seeker = SeekCoalescer(
    endGuardSec = END_GUARD_SEC,
    repeatMinIntervalMs = SEEK_REPEAT_MIN_MS,
  )
  private val seekRunnable = Runnable { flushSeek() }
  private val seekSettleTimeoutRunnable = Runnable {
    if (!mpvCreated || finishing) return@Runnable
    // PLAYBACK_RESTART carries no command identity and mpv may coalesce several seeks into one
    // restart. Never leave an unmatchable preview pinned forever: after a bounded window the
    // player position becomes authoritative again.
    seeker.reset()
    seekPreviewSec.doubleValue = NO_SEEK
    seeking.value = false
    publishPlaybackState()
  }

  /**
   * Compose-visible mirror of [SeekCoalescer.previewSec], negative when no seek
   * is outstanding. The OSD shows this instead of `time-pos` so scrubbing tracks
   * the remote immediately, while mpv is still reporting the old position.
   */
  private val seekPreviewSec = mutableDoubleStateOf(NO_SEEK)

  /**
   * Last whole second pushed to [timePosSec]. mpv reports `time-pos` on every
   * video frame; the OSD renders whole seconds, so anything finer is pure
   * recomposition churn during playback.
   */
  @Volatile
  private var lastPostedSecond = Long.MIN_VALUE
  @Volatile
  private var lastPostedCacheSecond = Long.MIN_VALUE

  private var frameRateRetryRunnable: Runnable? = null

  /**
   * Rechecks lifetime on the main thread, not only on mpv's callback thread. An observer can race
   * `onDestroy()` after its first check and enqueue after the handler queue was cleared; this gate
   * makes that late runnable inert and prevents it from touching a replacement generation.
   */
  private fun postMpvEvent(action: () -> Unit) {
    mainHandler.post {
      if (!destroying && mpvCreated) action()
    }
  }

  private val observer = object : MPVLib.EventObserver {
    override fun eventProperty(property: String) {}
    override fun eventProperty(property: String, value: Long) {
      if (destroying) return
      if (property == "volume") {
        postMpvEvent { observedVolume = value.toInt().coerceIn(0, 100) }
      }
    }
    override fun eventProperty(property: String, value: Double) {
      if (destroying) return
      if (!acceptingFileProperties) return
      val generation = loadGeneration
      when (property) {
        "time-pos" -> {
          val whole = value.toLong()
          if (whole == lastPostedSecond) return
          lastPostedSecond = whole
          postMpvEvent {
            if (generation == loadGeneration && acceptingFileProperties) {
              timePosSec.doubleValue = value
            }
          }
        }
        "duration" -> postMpvEvent {
          if (generation == loadGeneration && acceptingFileProperties) {
            durationSec.doubleValue = value
            publishMediaMetadata()
          }
        }
        "speed" -> postMpvEvent {
          if (generation == loadGeneration && acceptingFileProperties) {
            playbackSpeed.doubleValue = value
            publishPlaybackState()
            if (!paused.value) applyDisplayFrameRateVote()
          }
        }
        // Throttled to whole seconds like time-pos above: mpv reports this as the demuxer fills,
        // which on a healthy debrid stream is many times a second, and every one of those would
        // otherwise recompose the scrub row.
        "demuxer-cache-time" -> {
          val whole = value.toLong()
          if (whole == lastPostedCacheSecond) return
          lastPostedCacheSecond = whole
          postMpvEvent {
            if (generation == loadGeneration && acceptingFileProperties) {
              cacheAheadSec.value = value.takeIf { it.isFinite() && it >= 0.0 }
              // A buffering stream whose demuxer frontier is still advancing is slow, not dead.
              // Restart the no-progress window on every whole second received.
              if (buffering.value && !paused.value) armStallWatchdog()
            }
          }
        }
      }
    }

    override fun eventProperty(property: String, value: Boolean) {
      if (destroying) return
      if (!acceptingFileProperties) return
      val generation = loadGeneration
      postMpvEvent {
        if (generation != loadGeneration || !acceptingFileProperties) return@postMpvEvent
        when (property) {
          // Every state the OSD reflects is also a state a MediaSession client
          // reflects, so these are the natural points to republish.
          // Also the one place the progress timer starts and stops: a paused
          // position is not moving, and the pause itself is already saved.
          "pause" -> {
            paused.value = value
            publishPlaybackState()
            scheduleProgressSave()
            // A stall nobody is waiting on is not a stall: a viewer who pauses
            // mid-buffer must not come back to a failure they never saw happen.
            if (value) cancelStallWatchdog() else if (buffering.value) armStallWatchdog()
            if (value) clearDisplayFrameRateVote() else applyDisplayFrameRateVote()
            syncPlayingState()
          }
          "paused-for-cache" -> {
            buffering.value = value
            if (value && !paused.value) armStallWatchdog() else cancelStallWatchdog()
            publishPlaybackState()
          }
          "seeking" -> {
            seeking.value = value
            publishPlaybackState()
          }
          // `keep-open=yes` turns the end of a file into this flag rather than an
          // END_FILE event, so it is the usual way a completed video is noticed —
          // but only [onPlaybackEnded] can tell a completion from a stream that
          // ran out of data early.
          "eof-reached" -> if (value) onPlaybackEnded()
        }
      }
    }

    override fun eventProperty(property: String, value: String) {}

    override fun event(eventId: Int) {
      if (destroying) return
      val generation = loadGeneration
      when (eventId) {
        // A file has begun loading: the initial one, or the entry mpv redirected
        // to after resolving a playlist. Either way it gets a full window to
        // become playable before the watchdog calls it dead.
        MPVLib.MPV_EVENT_START_FILE -> postMpvEvent {
          if (generation != loadGeneration) return@postMpvEvent
          awaitingStartFile = false
          acceptingFileProperties = true
          playbackErrors.reset(generation)
          armLoadWatchdog(LOAD_TIMEOUT_MS)
        }
        MPVLib.MPV_EVENT_FILE_LOADED -> postMpvEvent {
          if (
            generation != loadGeneration ||
            !acceptingFileProperties ||
            playbackError.value != null
          ) {
            return@postMpvEvent
          }
          playbackStarted = true
          cancelLoadWatchdog()
          // Startup noise (hwdec probing, codec fallbacks) must not be reported
          // later as the reason a mid-stream failure happened.
          playbackErrors.reset(generation)
          buffering.value = false
          cancelStallWatchdog()
          refreshTracks()
          matchDisplayToContentFrameRate()
          // Every file is opened paused. Only a visible player whose viewer did not explicitly
          // pause is allowed to acquire focus and expose its first sample.
          if (activityStarted && !pauseRequested) playPlayback() else {
            paused.value = true
            MPVLib.setPropertyBoolean("pause", true)
          }
          // A backgrounded activity must not remain the platform's media-button
          // target. onStart reactivates the session when the viewer returns.
          mediaSession?.isActive = activityStarted
          publishPlaybackState()
          // Playback is genuinely under way, so start recording where it gets to.
          scheduleProgressSave()
          syncPlayingState()
          // Two or three TMDB requests, spent now rather than on the last frame:
          // the card has to be on screen the moment the credits start.
          prefetchUpNext()
        }
        // Nothing else notices a dead stream: `keep-open=yes` leaves mpv idling
        // on a black frame, so without this a failed load or a fatal mid-stream
        // error spins under the busy indicator forever.
        MPVLib.MPV_EVENT_END_FILE -> postMpvEvent {
          if (generation != loadGeneration) return@postMpvEvent
          if (!awaitingStartFile && playbackError.value == null) onPlaybackEnded()
        }
        // Playback has actually resumed at the seek target, so the real
        // position is trustworthy again and the preview can go away. Restarts
        // also arrive from initial load, cache-stall recovery and earlier seeks,
        // so mirror whatever the coalescer keeps rather than blanking: a press
        // that landed since must stay on screen and still be flushed.
        MPVLib.MPV_EVENT_PLAYBACK_RESTART -> postMpvEvent {
          if (
            generation != loadGeneration ||
            !acceptingFileProperties ||
            playbackError.value != null
          ) {
            return@postMpvEvent
          }
          seeking.value = false
          seeker.settle()
          seekPreviewSec.doubleValue = seeker.previewSec ?: NO_SEEK
          if (seeker.previewSec == null) {
            mainHandler.removeCallbacks(seekSettleTimeoutRunnable)
          }
          lastPostedSecond = Long.MIN_VALUE
          publishPlaybackState()
        }
        // The binding delivers these through the typed property/log callbacks above, or they
        // carry no state this activity consumes. Listing them is intentional: a new libmpv event
        // will then produce a lint warning instead of being silently swallowed by an `else`.
        MPVLib.MPV_EVENT_AUDIO_RECONFIG,
        MPVLib.MPV_EVENT_CLIENT_MESSAGE,
        MPVLib.MPV_EVENT_COMMAND_REPLY,
        MPVLib.MPV_EVENT_GET_PROPERTY_REPLY,
        MPVLib.MPV_EVENT_HOOK,
        MPVLib.MPV_EVENT_LOG_MESSAGE,
        MPVLib.MPV_EVENT_NONE,
        MPVLib.MPV_EVENT_PROPERTY_CHANGE,
        MPVLib.MPV_EVENT_QUEUE_OVERFLOW,
        MPVLib.MPV_EVENT_SEEK,
        MPVLib.MPV_EVENT_SET_PROPERTY_REPLY,
        MPVLib.MPV_EVENT_SHUTDOWN,
        MPVLib.MPV_EVENT_VIDEO_RECONFIG,
        -> Unit
      }
    }
  }

  /**
   * mpv's own diagnostics are the only place the cause of a failure is spelled
   * out (HTTP status, "Failed to open", codec errors), so the last error line is
   * kept as the reason for the failure overlay. The native binding already asks
   * mpv for verbose-and-above messages, hence the level filter here.
   *
   * Called on mpv's event thread.
   */
  private val logObserver = object : MPVLib.LogObserver {
    override fun logMessage(prefix: String, level: Int, text: String) {
      if (destroying) return
      if (level > MPVLib.MPV_LOG_LEVEL_ERROR) return
      // The accumulator compares and records under one monitor. If a replacement resets it while
      // this old-file callback is in flight, the old diagnostic is either cleared by that reset or
      // rejected afterward; it can never become the new file's failure reason.
      playbackErrors.record(prefix, text, loadGeneration)
    }
  }

  /**
   * Called on the main thread (the handler handed to the focus request), so it can
   * drive the same pause path as the remote: an auto-pause has to move `pause`,
   * the OSD and the published state exactly as a viewer's pause does, and must
   * never look like a failure or like the video finished.
   */
  private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
    when (change) {
      // Gone for good — another player took over. Pause, owing nothing: whoever
      // holds the speakers now is staying, and resuming later would be a surprise.
      AudioManager.AUDIOFOCUS_LOSS -> {
        hasAudioFocus = false
        restoreDuckedVolume()
        pausePlayback()
        showOsd()
      }
      // A call, an alarm, an Assistant answer: pause and come back afterwards.
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
        hasAudioFocus = false
        restoreDuckedVolume()
        pausePlayback(forFocusLoss = true)
        showOsd()
      }
      // A notification chirp, not worth interrupting a film for. mpv's own volume
      // is the only thing that can duck here: the audiotrack AO sits outside the
      // platform's volume shaping, so the framework cannot duck it for us.
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> duckVolume()
      AudioManager.AUDIOFOCUS_GAIN -> {
        hasAudioFocus = true
        restoreDuckedVolume()
        // Resumes an auto-pause only, and never onto a stream that has since died.
        val owed = pausedForFocusLoss
        pausedForFocusLoss = false
        if (owed && transportAllowed()) {
          playPlayback()
          showOsd()
        }
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    watchStore = WatchStateStore(applicationContext)
    playerPrefsStore = PlayerPrefsStore(applicationContext)
    settingsStore = SettingsStore(applicationContext)
    streamPickStore = StreamPickStore(applicationContext)

    // Zero is a real restored position after Restart. `takeIf { > 0 }` used to turn it back into
    // "no restored state", causing the old resume extra to win after process recreation.
    val restoredPosition = savedInstanceState
      ?.takeIf { it.containsKey(STATE_POSITION_MS) }
      ?.getLong(STATE_POSITION_MS)
    if (!applyLaunchIntent(intent, restoredPosition)) {
      Toast.makeText(this, R.string.playback_url_blocked, Toast.LENGTH_LONG).show()
      finish()
      return
    }
    resumeResetRequested =
      savedInstanceState?.getBoolean(STATE_RESUME_RESET_REQUESTED, false) ?: false
    // Retry before FILE_LOADED/time-pos must still return to the requested resume point.
    timePosSec.doubleValue = resumeMs.coerceAtLeast(0L) / 1000.0

    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    val lease = MpvCoreCoordinator.acquire(MPV_CREATE_WAIT_MS)
    if (lease == null) {
      NebulaDiagnostics.record("player", "launch blocked: previous native core still owned")
      finish()
      return
    }
    mpvLease = lease
    val coreLockAcquired = runCatching {
      mpvLock.tryLock(MPV_CREATE_WAIT_MS, TimeUnit.MILLISECONDS)
    }.getOrDefault(false)
    if (!coreLockAcquired) {
      // A vendor JNI call in the previous activity has not unwound. Creating another process-wide
      // core would let its delayed destroy kill this one, so fail this launch safely.
      NebulaDiagnostics.record("player", "launch blocked: native core lock timed out")
      MpvCoreCoordinator.abandon(lease)
      mpvLease = null
      finish()
      return
    }
    var nativeCreateAttempted = false
    var initializationFailure: Throwable? = null
    try {
      nativeCreateAttempted = true
      MPVLib.create(this)
      mpvCreated = true
      mpvAlive = true
      startMpvWorker()
      MPVLib.setOptionString("vo", "gpu")
      MPVLib.setOptionString("gpu-context", "android")
      MPVLib.setOptionString("opengl-es", "yes")
      // Direct-surface mediacodec first, then copy-back, then software; without a
      // fallback chain a codec the device cannot hwdec fails hard.
      MPVLib.setOptionString("hwdec", "mediacodec,mediacodec-copy,no")
      // mpeg2/mpeg4 are unreliable in direct-surface mode on TV SoCs and are
      // cheap to decode in software, so leave them off the hwdec list.
      MPVLib.setOptionString("hwdec-codecs", "h264,hevc,vp8,vp9,av1")
      MPVLib.setOptionString("ao", "audiotrack")
      // This build is mpv 0.39 with vo=gpu. `target-colorspace-hint` only applies to gpu-next in
      // that version, so setting it would be a false HDR guarantee. Android HDR output remains an
      // explicit hardware-validation item; do not claim the display entered HDR from this option.
      MPVLib.setOptionString("ytdl", "no")
      applyNetworkOptions()
      // The starting size, replaced by the stored one a moment later. Medium is 44,
      // which is the size the player used to give everyone.
      MPVLib.setOptionString("sub-font-size", SubtitleSize.DEFAULT.fontSize.toString())
      MPVLib.setOptionString("keep-open", "yes")
      MPVLib.setOptionString("force-window", "no")
      MPVLib.init()
      MPVLib.addObserver(observer)
      MPVLib.addLogObserver(logObserver)
      MPVLib.observeProperty("time-pos", MPVLib.MPV_FORMAT_DOUBLE)
      MPVLib.observeProperty("duration", MPVLib.MPV_FORMAT_DOUBLE)
      MPVLib.observeProperty("pause", MPVLib.MPV_FORMAT_FLAG)
      MPVLib.observeProperty("paused-for-cache", MPVLib.MPV_FORMAT_FLAG)
      MPVLib.observeProperty("seeking", MPVLib.MPV_FORMAT_FLAG)
      MPVLib.observeProperty("eof-reached", MPVLib.MPV_FORMAT_FLAG)
      MPVLib.observeProperty("speed", MPVLib.MPV_FORMAT_DOUBLE)
      MPVLib.observeProperty("demuxer-cache-time", MPVLib.MPV_FORMAT_DOUBLE)
      MPVLib.observeProperty("volume", MPVLib.MPV_FORMAT_INT64)
      NebulaDiagnostics.record("player", "native core initialized")
    } catch (error: Throwable) {
      // A failed JNI/linkage initialization is recoverable at the Activity boundary. VM-wide
      // failures are not: attempting UI or native cleanup while the runtime is dying is unsafe.
      if (error is VirtualMachineError || error is ThreadDeath) throw error
      initializationFailure = error
    } finally {
      mpvLock.unlock()
    }

    val failedInitialization = initializationFailure
    if (failedInitialization != null) {
      mpvInitializationIncomplete = true
      destroying = true
      stopMpvWorker()
      mpvAlive = false
      // `create()` itself can throw after allocating part of the singleton. Treat an attempted
      // create as owned native state and retire that exact lease. If thread creation is temporarily
      // unavailable, keep the lease/mpvCreated invariant intact so normal onDestroy retries.
      mpvCreated = nativeCreateAttempted
      val cleanupScheduled = nativeCreateAttempted &&
        MpvCoreCoordinator.destroyAsync(lease, threadName = "mpv-failed-init-destroy") {
          destroyMpvCoreAfterFailedInitialization()
        }
      if (cleanupScheduled) {
        mpvCreated = false
        mpvLease = null
      } else if (!nativeCreateAttempted) {
        MpvCoreCoordinator.abandon(lease)
        mpvLease = null
      }
      NebulaDiagnostics.record(
        "player",
        "native core initialization failed: ${failedInitialization.javaClass.simpleName}",
      )
      Toast.makeText(this, R.string.player_start_failed, Toast.LENGTH_LONG).show()
      finish()
      return
    }

    loadPlayerPrefs()
    createMediaSession()

    setContent {
      NebulaTheme {
        PlayerSurface()
      }
    }
    showOsd()
    // Arms the idle screen-on release: a stream that never opens leaves a
    // "Playback failed" panel that must not hold the panel awake either.
    syncPlayingState()
  }

  /**
   * Reads one immutable player launch into activity state. Kept in one place because `singleTop`
   * delivers a second selection through [onNewIntent]; ignoring that path leaves the old stream on
   * screen while the caller believes the new one started.
   */
  private fun applyLaunchIntent(source: Intent, restoredPositionMs: Long? = null): Boolean {
    val nextUrl = validatedPlaybackUrl(source.getStringExtra(EXTRA_URL).orEmpty()) ?: return false
    url = nextUrl
    title = source.getStringExtra(EXTRA_TITLE).orEmpty()
    watchKey = source.getStringExtra(EXTRA_WATCH_KEY).orEmpty()
    tmdbId = source.getIntExtra(EXTRA_TMDB_ID, 0)
    mediaType = source.getStringExtra(EXTRA_MEDIA_TYPE) ?: "movie"
    posterUrl = source.getStringExtra(EXTRA_POSTER)
    season = source.getIntExtra(EXTRA_SEASON, -1).takeIf { it >= 0 }
    episode = source.getIntExtra(EXTRA_EPISODE, -1).takeIf { it >= 0 }
    imdbId = source.getStringExtra(EXTRA_IMDB_ID)
    bingeGroup = source.getStringExtra(EXTRA_BINGE_GROUP)
    resumeMs = restoredPositionMs ?: source.getLongExtra(EXTRA_RESUME_MS, 0L)
    resumeResetRequested = false
    requestHeaders = readHeaderExtras(source)
    embeddedSubtitles = readSubtitleExtras(source)
    streamVideoHash = source.getStringExtra(EXTRA_VIDEO_HASH)
    streamFilename = source.getStringExtra(EXTRA_FILENAME)
    streamVideoSize = source.getLongExtra(EXTRA_VIDEO_SIZE, -1L).takeIf { it > 0L }
    syncEpisodeLabel()
    externalSubtitles.value = initialExternalSubtitlesState()
    return true
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    if (!mpvCreated || finishing) return
    // Finish the bookkeeping for the file being replaced before any launch fields move.
    if (playbackStarted && !endHandled) saveWatchState(SaveReason.Stopped)
    dismissUpNext()
    val previousIntent = getIntent()
    setIntent(intent)
    if (!applyLaunchIntent(intent)) {
      setIntent(previousIntent)
      showOsdMessage(getString(R.string.playback_url_blocked))
      return
    }
    resetFileRuntime(clearUpNext = true, resetDelays = true, initialPositionMs = resumeMs)
    fileLoaded = true
    loadCurrentFile(replace = true, startMs = resumeMs)
    publishMediaMetadata()
    showOsd()
  }

  private fun readHeaderExtras(source: Intent): Map<String, String> {
    val names = source.getStringArrayListExtra(EXTRA_HEADER_NAMES).orEmpty()
    val values = source.getStringArrayListExtra(EXTRA_HEADER_VALUES).orEmpty()
    if (names.size != values.size) return emptyMap()
    return StreamRequestHeaders.sanitize(names.indices.associate { names[it] to values[it] })
  }

  /** Narrows untrusted addon input to one public HTTPS URL before native mpv can interpret it. */
  private fun validatedPlaybackUrl(candidate: String): String? =
    when (val validation = PlaybackUrlPolicy.validate(candidate)) {
      is PlaybackUrlValidation.Allowed -> validation.url
      is PlaybackUrlValidation.Rejected -> {
        NebulaDiagnostics.record("player", "stream URL rejected: ${validation.reason}")
        null
      }
    }

  private fun readSubtitleExtras(source: Intent): List<AddonStreamSubtitle> {
    val urls = source.getStringArrayListExtra(EXTRA_SUBTITLE_URLS).orEmpty()
    val languages = source.getStringArrayListExtra(EXTRA_SUBTITLE_LANGUAGES).orEmpty()
    val ids = source.getStringArrayListExtra(EXTRA_SUBTITLE_IDS).orEmpty()
    val candidates = urls.asSequence()
      .take(EmbeddedSubtitles.MAX_CANDIDATES)
      .mapIndexed { index, rawUrl ->
        AddonStreamSubtitle(
          id = ids.getOrNull(index)?.takeIf { it.isNotBlank() },
          url = rawUrl,
          lang = languages.getOrNull(index)?.takeIf { it.isNotBlank() },
        )
      }
      .toList()
    return EmbeddedSubtitles.sanitize(candidates)
  }

  private fun initialExternalSubtitlesState(): ExternalSubtitlesState {
    val exact = EmbeddedSubtitles.sanitize(embeddedSubtitles).mapIndexed { index, subtitle ->
      val languageCode = LanguageCodes.normalize(subtitle.lang)
      ExternalSubtitleOption(
        url = subtitle.url,
        lang = languageCode,
        label = LanguageCodes.displayName(languageCode)
          .ifBlank { getString(R.string.player_unknown_language) },
        detail = getString(R.string.player_subtitle_included),
        trackTitle = subtitle.id?.trim()?.takeIf { it.isNotEmpty() }
          ?: getString(R.string.player_stream_subtitle_number, index + 1),
      )
    }
    return when {
      exact.isNotEmpty() -> ExternalSubtitlesState.Ready(exact)
      imdbId.isNullOrBlank() -> ExternalSubtitlesState.Unavailable
      else -> ExternalSubtitlesState.Idle
    }
  }

  /**
   * Drops every event and piece of UI that belongs to the file being replaced. The selected
   * playback speed and stored language/size/output preferences intentionally survive an episode
   * transition; delays and track lists do not.
   */
  private fun resetFileRuntime(
    clearUpNext: Boolean,
    resetDelays: Boolean,
    initialPositionMs: Long,
  ) {
    cancelLoadWatchdog()
    cancelStallWatchdog()
    mainHandler.removeCallbacks(progressSaveRunnable)
    mainHandler.removeCallbacks(seekRunnable)
    mainHandler.removeCallbacks(seekSettleTimeoutRunnable)
    frameRateRetryRunnable?.let(mainHandler::removeCallbacks)
    frameRateRetryRunnable = null
    playbackError.value = null
    playbackStarted = false
    acceptingFileProperties = false
    endHandled = false
    buffering.value = true
    paused.value = true
    seeking.value = false
    contentFps = 0f
    timePosSec.doubleValue = initialPositionMs.coerceAtLeast(0L) / 1000.0
    durationSec.doubleValue = 0.0
    cacheAheadSec.value = null
    lastPostedSecond = Long.MIN_VALUE
    lastPostedCacheSecond = Long.MIN_VALUE
    publishedDurationMs = -1L
    seeker.reset()
    seekPreviewSec.doubleValue = NO_SEEK
    tracks.value = emptyList()
    osdMessage.value = ""
    dolbyVisionWarned = false
    externalSubtitles.value = initialExternalSubtitlesState()
    externalSubAdding = false
    discardQueuedSubtitleFiles()
    audioCyclePending = false
    nextEpisodeStarting = false
    retryInFlight = false
    menuVisible.value = false
    if (resetDelays) {
      audioDelaySec.doubleValue = 0.0
      subtitleDelaySec.doubleValue = 0.0
      if (mpvCreated) {
        MPVLib.setPropertyDouble("audio-delay", 0.0)
        MPVLib.setPropertyDouble("sub-delay", 0.0)
      }
    }
    if (clearUpNext) {
      upNextTarget.value = null
      upNextLookupIssued = false
      upNextLookupAttempts = 0
      upNextRetryRunnable?.let(mainHandler::removeCallbacks)
      upNextRetryRunnable = null
    }
  }

  /**
   * Streams come from debrid/torrent resolvers, where a stalled or dropped connection is routine.
   * Without reconnect options a single hiccup ends playback for good, and a thin cache turns every
   * wobble into a stall. TLS verification is explicit because older libavformat builds can have a
   * weaker default; addon-provided playback URLs are HTTPS-only.
   */
  private fun applyNetworkOptions() {
    MPVLib.setOptionString("cache", "yes")
    MPVLib.setOptionString("cache-secs", "120")
    MPVLib.setOptionString("demuxer-readahead-secs", "20")
    // Byte caps, not preallocation: cache-secs decides the normal working set,
    // except on high-bitrate remuxes where the cap is what actually binds — see
    // [DemuxerCacheSizing] for why it has to follow the device's RAM.
    val cache = DemuxerCacheSizing.forDeviceRam(totalDeviceRamBytes())
    MPVLib.setOptionString("demuxer-max-bytes", cache.forwardBytes.toString())
    MPVLib.setOptionString("demuxer-max-back-bytes", cache.backBytes.toString())
    MPVLib.setOptionString("network-timeout", "30")
    MPVLib.setOptionString("tls-verify", "yes")
    MPVLib.setOptionString(
      "stream-lavf-o",
      "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=10",
    )
  }

  /**
   * Physical RAM, not the Java heap limit: the demuxer cache is native memory,
   * so `Runtime.maxMemory` (a couple of hundred MiB on a TV box regardless of
   * the hardware) says nothing about how much of it the device can carry.
   */
  private fun totalDeviceRamBytes(): Long = runCatching {
    val info = ActivityManager.MemoryInfo()
    getSystemService(ActivityManager::class.java).getMemoryInfo(info)
    info.totalMem
  }.getOrDefault(0L)

  /**
   * Reads the stored audio/subtitle languages, subtitle size and audio output
   * mode, and hands them to mpv before the file is opened.
   *
   * The read is asynchronous and `loadfile` cannot run ahead of it — mpv reads
   * `alang`/`slang` when it opens a file, so applying them afterwards would do
   * nothing until the next episode — hence the gate in [maybeLoadFile] and the
   * timeout that opens it regardless.
   */
  private fun loadPlayerPrefs() {
    mainHandler.postDelayed(prefsTimeoutRunnable, PREFS_READ_TIMEOUT_MS)
    val store = playerPrefsStore
    persistenceScope.launch {
      val prefs = runCatching { store.get() }.getOrDefault(PlayerPrefs())
      mainHandler.post { applyPlayerPrefs(prefs) }
    }
  }

  private fun applyPlayerPrefs(prefs: PlayerPrefs) {
    mainHandler.removeCallbacks(prefsTimeoutRunnable)
    playerPrefs = prefs
    // Seeded pessimistically at OPENS_WITH_HINT, so a prefs read that never answers hides the
    // legend rather than showing it forever - the failure this counter exists to prevent.
    osdOpens.intValue = prefs.osdOpens
    val size = SubtitleSize.fromStorage(prefs.subtitleSize)
    subtitleSize.value = size
    val requestedOutput = AudioOutputMode.fromStorage(prefs.audioOutput)
    // A persisted passthrough choice is a preference, never proof that this particular route can
    // carry it. Honour it only when Android reports compatible codecs for the active media route;
    // otherwise decode for this session without erasing the preference (an AVR may simply be off).
    val output = if (
      requestedOutput == AudioOutputMode.Passthrough && supportedSpdifCodecs().isNotEmpty()
    ) {
      AudioOutputMode.Passthrough
    } else {
      AudioOutputMode.Decode
    }
    audioOutput.value = output
    if (requestedOutput == AudioOutputMode.Passthrough && output == AudioOutputMode.Decode) {
      NebulaDiagnostics.record("player", "passthrough preference fell back to decode for this route")
    }
    if (mpvCreated && !finishing) {
      applyTrackPreferencesForNextFile()
      MPVLib.setPropertyString("sub-font-size", size.fontSize.toString())
      // Read when mpv builds the audio chain, which is at file open — so like
      // `alang` this only lands because the gate below holds `loadfile` back
      // until it has. A mid-file change needs [reselectAudioTrack] instead.
      applyAudioOutput(output)
    }
    if (prefsApplied) return
    prefsApplied = true
    maybeLoadFile()
  }

  /**
   * Opens the stream, once there is both a surface to draw it on and a set of
   * preferences for mpv to open it with. Called from both, and idempotent: the
   * one that arrives second is the one that loads.
   */
  private fun maybeLoadFile() {
    if (fileLoaded || !mpvCreated || finishing) return
    if (!surfaceReady || !prefsApplied) return
    fileLoaded = true
    loadCurrentFile(replace = false, startMs = resumeMs)
  }

  /**
   * The single entrypoint for opening a file. Opening paused closes the short but audible gap
   * between mpv starting its AudioTrack and FILE_LOADED giving this activity a chance to request
   * audio focus. Resume is attached to this file's load command, never left as global state.
   */
  private fun loadCurrentFile(replace: Boolean, startMs: Long) {
    if (!mpvCreated || finishing || url.isBlank()) return
    loadGeneration += 1
    val generation = loadGeneration
    val command = PlaybackLoadCommand.build(url, replace = replace, resumeMs = startMs)
    val audioLanguage = playerPrefs.audioLanguage
    val subtitleLanguage = playerPrefs.subtitleLanguage
    val headerSnapshot = requestHeaders.toMap()
    awaitingStartFile = true
    acceptingFileProperties = false
    cancelLoadWatchdog()
    cancelStallWatchdog()
    frameRateRetryRunnable?.let(mainHandler::removeCallbacks)
    frameRateRetryRunnable = null
    playbackStarted = false
    playbackErrors.reset(generation)
    buffering.value = true
    paused.value = true
    seeking.value = false
    cacheAheadSec.value = null
    lastPostedSecond = Long.MIN_VALUE
    lastPostedCacheSecond = Long.MIN_VALUE
    pauseRequested = false
    clearDisplayFrameRateVote()
    abandonAudioFocus()
    // File loads and subtitle additions share one worker. If an old subtitle mutation is already
    // running, it finishes before this queued replacement; if it has not started, the generation
    // check drops it. Native work never blocks the main thread.
    runMpvMutationOffMain(generation) {
      // This has to precede loadfile. A global pause is intentional; unlike `start` it is an
      // invariant of every load and is explicitly released only after focus is granted.
      MPVLib.setPropertyBoolean("pause", true)
      applyTrackPreferencesForNextFile(audioLanguage, subtitleLanguage)
      applyStreamRequestHeaders(headerSnapshot)
      MPVLib.command(command)
    }
    // START_FILE normally re-arms this. Keeping the first arm covers a native command failure or
    // worker handoff that produces no event at all.
    armLoadWatchdog(LOAD_TIMEOUT_MS)
    publishPlaybackState()
    syncPlayingState()
  }

  /**
   * Re-applies learned selection before every file, not just the first one in this activity.
   *
   * `loadfile replace` otherwise carries a numeric aid/sid from the previous episode; track 2 can
   * be English in one file and commentary in the next. Clearing those selectors back to auto lets
   * the stored language list choose against the new file's own tracks.
   */
  private fun applyTrackPreferencesForNextFile(
    audioLanguage: String = playerPrefs.audioLanguage,
    subtitleLanguage: String = playerPrefs.subtitleLanguage,
  ) {
    MPVLib.setPropertyString(
      "alang",
      TrackPreferences.alangValue(audioLanguage).orEmpty(),
    )
    MPVLib.setPropertyString("aid", "auto")
    MPVLib.setPropertyString(
      "slang",
      TrackPreferences.slangValue(subtitleLanguage).orEmpty(),
    )
    MPVLib.setPropertyString(
      "sid",
      if (TrackPreferences.subtitlesOff(subtitleLanguage)) "no" else "auto",
    )
  }

  private fun applyStreamRequestHeaders(headers: Map<String, String> = requestHeaders) {
    // Reset on every file, including an empty map, so credentials from one add-on can never leak
    // into a retry resolved by another or into the next episode.
    MPVLib.setPropertyString("http-header-fields", StreamRequestHeaders.mpvValue(headers))
  }

  /**
   * Decides what the end of playback means. Reached from both ends mpv reports:
   * the `eof-reached` flag that `keep-open=yes` raises instead of stopping, and
   * END_FILE. This binding's `event(Int)` callback carries no end-file reason —
   * the JNI bridge forwards the event id alone — so the cause is inferred from
   * what the session got as far as:
   *
   *  - already finishing: the user backed out, or the end was handled, nothing to do;
   *  - FILE_LOADED never arrived: the stream never opened (an expired debrid link
   *    404/403 is the common one), though mpv also ends a file to follow a
   *    playlist redirect, so that verdict waits out a short grace;
   *  - the position sits at the end of a known duration: a real end of playback;
   *  - anything else: the stream ran out early — a truncated debrid/torrent file,
   *    or a fatal mid-stream error after mpv's reconnects ran out.
   */
  private fun onPlaybackEnded() {
    if (finishing || playbackError.value != null) return
    if (!playbackStarted) {
      armLoadWatchdog(END_FILE_GRACE_MS)
      return
    }
    if (reachedEndOfFile()) {
      onVideoFinished()
      return
    }
    // Keep the resume point: the viewer stopped where the stream died. The
    // MIN_SAVE_MS guard inside decides whether that is worth remembering.
    saveWatchState(SaveReason.Stopped)
    showPlaybackError(playbackErrors.messageOr(getString(R.string.player_stream_stopped)))
  }

  /**
   * Whether playback stopped at the actual end of the video, which is what marks
   * it watched and clears its resume point.
   *
   * Deliberately not mpv's `eof-reached`: debrid and torrent sources routinely
   * serve a truncated file whose container still claims the full runtime, so the
   * demuxer hits eof at, say, 55% and mpv raises the same flag it raises on a
   * real ending. Trusting it there wiped Continue Watching for a half-watched
   * film. The position against the duration is the check that can tell them
   * apart; with no duration reported nothing can be established, so such a
   * stream is always treated as stopped short and stays resumable.
   */
  private fun reachedEndOfFile(): Boolean =
    WatchedThreshold.isFinished(timePosSec.doubleValue, durationSec.doubleValue)

  /**
   * The video ran to its end. The watched record is written here rather than on
   * the way out, because the up-next card can keep this activity alive for another
   * episode - and because a viewer who leaves during the credits has already
   * watched the thing.
   */
  private fun onVideoFinished() {
    if (endHandled) return
    // Preserve the viewer's state before marking mpv paused on its final frame.
    // Feeding the player-owned pause into UpNextPolicy would turn every untouched
    // ending into a prompt and make the countdown unreachable.
    val pausedByViewer = pauseRequested
    endHandled = true
    // Before the watched record: a periodic tick landing after it would put the
    // resume position it just cleared straight back.
    mainHandler.removeCallbacks(progressSaveRunnable)
    cancelStallWatchdog()
    buffering.value = false
    seeking.value = false
    paused.value = true
    pauseRequested = true
    MPVLib.setPropertyBoolean("pause", true)
    abandonAudioFocus()
    clearDisplayFrameRateVote()
    saveWatchState(SaveReason.Finished)
    seedNextEpisodeEntry()
    offerUpNext(pausedByViewer)
    publishPlaybackState()
    syncPlayingState()
  }

  private fun armLoadWatchdog(delayMs: Long) {
    cancelLoadWatchdog()
    if (playbackStarted) return
    val generation = loadGeneration
    val runnable = Runnable {
      if (generation != loadGeneration || playbackStarted || playbackError.value != null) {
        return@Runnable
      }
      showPlaybackError(playbackErrors.messageOr(getString(R.string.player_stream_open_failed)))
    }
    loadFailedRunnable = runnable
    mainHandler.postDelayed(runnable, delayMs)
  }

  private fun cancelLoadWatchdog() {
    loadFailedRunnable?.let(mainHandler::removeCallbacks)
    loadFailedRunnable = null
  }

  /**
   * Starts the clock on a cache stall. Only meaningful once the stream has
   * played: before that the load watchdog owns the same failure, and arming both
   * would race to report it.
   */
  private fun armStallWatchdog() {
    cancelStallWatchdog()
    if (!playbackStarted || !transportAllowed()) return
    val generation = loadGeneration
    val runnable = Runnable {
      if (generation != loadGeneration || !buffering.value || playbackError.value != null) {
        return@Runnable
      }
      saveWatchState(SaveReason.Stopped)
      showPlaybackError(
        resources.getQuantityString(
          R.plurals.player_stream_stalled,
          (STALL_TIMEOUT_MS / 1_000).toInt(),
          STALL_TIMEOUT_MS / 1_000,
        ),
      )
    }
    stallRunnable = runnable
    mainHandler.postDelayed(runnable, STALL_TIMEOUT_MS)
  }

  private fun cancelStallWatchdog() {
    stallRunnable?.let(mainHandler::removeCallbacks)
    stallRunnable = null
  }

  /**
   * Swaps the spinner for a readable failure. Deliberately does not finish the
   * activity: an instant bounce back to the stream list looks like a dropped
   * button press, so the reason stays on screen until the viewer leaves.
   */
  private fun showPlaybackError(reason: String) {
    if (finishing || playbackError.value != null) return
    NebulaDiagnostics.record("player", "playback failed: $reason")
    cancelLoadWatchdog()
    mainHandler.removeCallbacks(seekRunnable)
    mainHandler.removeCallbacks(seekSettleTimeoutRunnable)
    cancelStallWatchdog()
    // The position stops moving here, and [onPlaybackEnded] has already saved it.
    mainHandler.removeCallbacks(progressSaveRunnable)
    // Nothing left to pick a track from, and the failure panel needs the remote:
    // leaving the menu up would put a focus trap in front of Retry.
    menuVisible.value = false
    buffering.value = false
    seeking.value = false
    paused.value = true
    playbackError.value = PlaybackErrorAccumulator.redact(reason)
    clearDisplayFrameRateVote()
    restoreDuckedVolume()
    abandonAudioFocus()
    MPVLib.setPropertyBoolean("pause", true)
    // A late FILE_LOADED is ignored by the generation/error guards, and stop releases the socket
    // and decoder resources rather than letting a dead request continue behind the panel.
    runMpvMutationOffMain(loadGeneration) { MPVLib.command(arrayOf("stop")) }
    // keep-open leaves mpv paused on a dead stream, which would otherwise be
    // published as a pause a client could offer to resume.
    publishPlaybackState()
    syncPlayingState()
  }

  /**
   * Loads the same URL again from where playback got to. Most of what kills a
   * debrid stream mid-film — a stalled cache, a host dropping the connection, a
   * link that expired between the list and the first frame — is over by the time
   * the viewer reads the panel, and the alternative was backing out to the stream
   * list and starting the whole pick again from 0:00.
   */
  private fun retryPlayback() {
    val error = playbackError.value
    if (!mpvCreated || finishing || error == null || retryInFlight) return
    NebulaDiagnostics.record("player", "retry requested")

    // Where to come back in, captured before reset drops an outstanding seek target.
    val restartMs = (resumePositionSec() * 1000).toLong()
    val retryGeneration = loadGeneration
    val retryUrl = url
    resetFileRuntime(clearUpNext = false, resetDelays = false, initialPositionMs = restartMs)
    val shouldRefresh = error.contains("expired", ignoreCase = true) ||
      error.contains("no longer available", ignoreCase = true)
    if (shouldRefresh && !imdbId.isNullOrBlank()) {
      retryInFlight = true
      buffering.value = true
      showOsdMessage(getString(R.string.player_refreshing_stream))
      val forWatchKey = watchKey
      lifecycleScope.launch {
        val refreshed = suspendRunCatching {
          withContext(Dispatchers.IO) { resolveCurrentStream() }
        }.getOrNull()
        if (
          finishing ||
          watchKey != forWatchKey ||
          loadGeneration != retryGeneration ||
          url != retryUrl
        ) {
          return@launch
        }
        retryInFlight = false
        val refreshedStream = refreshed?.takeIf { !it.url.isNullOrBlank() }
        if (refreshedStream != null && !applyResolvedStream(refreshedStream)) {
          showPlaybackError(getString(R.string.player_refreshed_stream_blocked))
          return@launch
        }
        loadCurrentFile(replace = true, startMs = restartMs)
        showOsd()
      }
      return
    }
    loadCurrentFile(replace = true, startMs = restartMs)
    showOsd()
  }

  /** Re-resolves an expired signed link while retaining the viewer's release preference. */
  private suspend fun resolveCurrentStream(): AddonStream? {
    val imdb = imdbId?.takeIf { it.isNotBlank() } ?: return null
    val addons = settingsStore.addonManifestUrls.first()
    if (addons.isEmpty()) return null
    val fetch = streamCatalog.fetch(addons, imdb, season, episode)
    return StreamAutoPick.pick(fetch.streams, bingeGroup, streamPickStore.get(imdb))
  }

  private fun applyResolvedStream(stream: AddonStream): Boolean {
    url = validatedPlaybackUrl(stream.url.orEmpty()) ?: return false
    bingeGroup = stream.bingeGroup ?: bingeGroup
    requestHeaders = StreamRequestHeaders.sanitize(
      stream.behaviorHints?.proxyHeaders?.request.orEmpty(),
    )
    embeddedSubtitles = EmbeddedSubtitles.sanitize(stream.subtitles)
    streamVideoHash = stream.behaviorHints?.videoHash
    streamFilename = stream.behaviorHints?.filename
    streamVideoSize = stream.behaviorHints?.videoSize
    externalSubtitles.value = initialExternalSubtitlesState()
    return true
  }

  /**
   * Looks up the episode after this one while there is still time to spend on it.
   * Costs one TMDB season request, or two plus a details request when the season
   * runs out here; all of it off the main thread, and none of it load-bearing -
   * a failure just means no card at the end.
   */
  private fun prefetchUpNext() {
    if (upNextLookupIssued || mediaType != "show" || tmdbId == 0) return
    if (season == null || episode == null) return
    upNextLookupIssued = true
    upNextLookupAttempts += 1
    val generation = loadGeneration
    val forSeason = season
    val forEpisode = episode
    lifecycleScope.launch {
      val result = suspendRunCatching { withContext(Dispatchers.IO) { resolveNextEpisode() } }
      if (
        finishing || generation != loadGeneration ||
        season != forSeason || episode != forEpisode
      ) return@launch
      result.onSuccess { target ->
        upNextTarget.value = target
        publishPlaybackState()
      }.onFailure {
        upNextLookupIssued = false
        if (upNextLookupAttempts < UP_NEXT_LOOKUP_ATTEMPTS) scheduleUpNextRetry(generation)
      }
    }
  }

  private fun scheduleUpNextRetry(generation: Long) {
    upNextRetryRunnable?.let(mainHandler::removeCallbacks)
    val runnable = Runnable {
      upNextRetryRunnable = null
      if (
        !finishing && activityStarted && generation == loadGeneration && playbackStarted &&
        upNextTarget.value == null
      ) {
        prefetchUpNext()
      }
    }
    upNextRetryRunnable = runnable
    mainHandler.postDelayed(runnable, UP_NEXT_LOOKUP_RETRY_MS)
  }

  /** Called off the main thread. */
  private suspend fun resolveNextEpisode(): UpNextTarget? {
    val currentSeason = season ?: return null
    val currentEpisode = episode ?: return null
    // A special chains to nothing (see [NextEpisodeFinder]); asking for the season
    // after season 0 would drop a viewer who watched one at the start of series.
    if (currentSeason <= 0) return null
    val apiKey = settingsStore.tmdbApiKey.first().takeIf { it.isNotBlank() } ?: return null
    val client = TmdbClient(apiKey)
    val thisSeason = client.season(tmdbId, currentSeason)
    val current = NextEpisodeFinder.EpisodeRef(currentSeason, currentEpisode)
    NextEpisodeFinder.next(current, thisSeason.map { it.ref() })?.let { next ->
      return thisSeason.first { it.ref() == next }.target()
    }
    // The season ran out here, and TMDB's season list is the only place the number
    // of the next one can come from: seasons are not always consecutive.
    val seasons = client.details(MediaType.Show, tmdbId).seasons.map { it.seasonNumber }
    val nextSeason = NextEpisodeFinder.nextSeason(currentSeason, seasons) ?: return null
    val episodes = client.season(tmdbId, nextSeason)
    val first = NextEpisodeFinder.firstOfSeason(episodes.map { it.ref() }, nextSeason) ?: return null
    return episodes.first { it.ref() == first }.target()
  }

  private fun EpisodeItem.ref() = NextEpisodeFinder.EpisodeRef(seasonNumber, episodeNumber)

  private fun EpisodeItem.target() =
    UpNextTarget(seasonNumber, episodeNumber, name, stillUrl = stillUrl)

  /**
   * Puts the next episode into Continue Watching at zero progress, so a viewer who
   * finishes an episode and leaves comes back to a row that offers the one they
   * have not seen instead of dropping the series off it entirely.
   */
  private fun seedNextEpisodeEntry() {
    val target = upNextTarget.value ?: return
    if (tmdbId == 0) return
    val store = watchStore
    val entry = WatchEntry(
      key = watchKeyFor(tmdbId, target.season, target.episode),
      tmdbId = tmdbId,
      mediaType = mediaType,
      title = title,
      posterUrl = posterUrl,
      season = target.season,
      episode = target.episode,
      updatedAtMs = System.currentTimeMillis(),
    )
    persistenceScope.launch(start = CoroutineStart.UNDISPATCHED) {
      watchWriteMutex.withLock {
        val saved = runCatching { store.upsertIfAbsent(entry) }.isSuccess
        if (saved) WatchNextSync.publish(applicationContext, force = true)
      }
    }
  }

  /**
   * Decides what the end of an episode offers, and puts the card up. Nothing to
   * play next - a film, the last episode, a lookup that failed - leaves the player
   * exactly as it always did.
   */
  private fun offerUpNext(pausedByViewer: Boolean) {
    val target = upNextTarget.value
    val offer = UpNextPolicy.offer(
      hasNext = target != null,
      paused = pausedByViewer,
      msSinceInteractionMs = SystemClock.uptimeMillis() - lastInteractionMs,
      autoPlayNext = playerPrefs.autoPlayNext,
      countdownMs = playerPrefs.upNextCountdownSeconds * 1_000L,
    )
    if (target == null || offer == UpNextPolicy.Offer.None) {
      finishPlayback(markFinished = true)
      return
    }
    // The card is the only thing on screen now: the menu edits tracks of a video
    // that has ended, and it would hold focus in front of the card.
    menuVisible.value = false
    val countdown = offer as? UpNextPolicy.Offer.Countdown
    upNextCard.value = UpNextCardState(
      seriesTitle = title,
      target = target,
      secondsLeft = countdown?.let { UpNextPolicy.secondsLeft(0L, it.totalMs) },
      resolving = false,
      progress = 1f,
    )
    if (countdown != null) {
      upNextCountdownTotalMs = countdown.totalMs
      upNextCountdownStartMs = SystemClock.uptimeMillis()
      mainHandler.postDelayed(upNextTickRunnable, UP_NEXT_TICK_MS)
    }
  }

  /**
   * Leaves the card up but stops it acting on its own. Used when the player goes
   * to the background: a countdown that ran there would start an episode into an
   * empty room, and finding it half-watched later is worse than the extra press.
   */
  private fun freezeUpNextCountdown() {
    val state = upNextCard.value ?: return
    mainHandler.removeCallbacks(upNextTickRunnable)
    if (state.secondsLeft != null) upNextCard.value = state.copy(secondsLeft = null)
  }

  private fun dismissUpNext() {
    mainHandler.removeCallbacks(upNextTickRunnable)
    upNextCard.value = null
  }

  /**
   * Resolves a stream for the next episode and plays it in this activity. It has
   * to be this activity: MPVLib is a process-global singleton whose second
   * `create()` takes the process with it, so a second player is not an option -
   * hence `loadfile ... replace` rather than a new intent.
   */
  private fun playNextEpisode() {
    val target = upNextTarget.value ?: return
    if (nextEpisodeStarting || finishing || !mpvCreated) return
    val forGeneration = loadGeneration
    val forWatchKey = watchKey
    val forImdb = imdbId
    nextEpisodeStarting = true
    mainHandler.removeCallbacks(upNextTickRunnable)
    // The transport's Next button can enter this path before an end card exists.
    // Give a slow addon lookup somewhere to report progress and failure, and
    // stop the old episode continuing underneath it.
    pausePlayback()
    menuVisible.value = false
    upNextCard.value = (upNextCard.value ?: UpNextCardState(
      seriesTitle = title,
      target = target,
      secondsLeft = null,
      resolving = true,
    )).copy(
      secondsLeft = null,
      resolving = true,
      failure = null,
    )
    publishPlaybackState()
    syncPlayingState()
    lifecycleScope.launch {
      val result = suspendRunCatching {
        withContext(Dispatchers.IO) { resolveNextStream(target) }
      }
      // A Retry, a replacement file or another episode can invalidate this
      // answer while its addon requests are in flight. Never let that late
      // answer replace the newer file or mutate its card.
      if (
        finishing ||
        forGeneration != loadGeneration ||
        forWatchKey != watchKey ||
        forImdb != imdbId ||
        upNextTarget.value != target ||
        upNextCard.value?.target != target
      ) {
        return@launch
      }
      nextEpisodeStarting = false
      if (!activityStarted) {
        // The viewer left while resolution was running. Keep the offer but never
        // start a new episode into the background.
        upNextCard.value = upNextCard.value?.copy(resolving = false, secondsLeft = null)
        publishPlaybackState()
        return@launch
      }
      when (
        val resolution = result.getOrElse {
          UpNextStreamResolution.Retry(UpNextStreamPolicy.DEFAULT_RETRY_MESSAGE)
        }
      ) {
        is UpNextStreamResolution.Ready -> {
          val nextUrl = resolution.stream.url.orEmpty()
          startNextEpisode(target, resolution.stream, nextUrl)
        }
        UpNextStreamResolution.NeedsPicker -> handOffToPicker(target)
        is UpNextStreamResolution.Retry -> {
          upNextCard.value = upNextCard.value?.copy(
            resolving = false,
            secondsLeft = null,
            failure = UpNextFailure(resolution.message),
          )
          publishPlaybackState()
        }
      }
    }
  }

  /**
   * Called off the main thread.
   *
   * Every configured addon, through the same merge the picker uses, not just the
   * first one: an episode whose release only addon #2 carries used to look to the
   * binge loop like an episode nothing had, and got handed to the picker for the
   * viewer to pick the release they had already picked. An addon that fails or
   * runs out of time is absorbed by [StreamCatalog]. [UpNextStreamPolicy]
   * distinguishes "every addon failed" from a healthy empty/mismatched answer,
   * because only the latter belongs in the picker.
   */
  private suspend fun resolveNextStream(target: UpNextTarget): UpNextStreamResolution {
    val imdb = imdbId?.takeIf { it.isNotBlank() }
      ?: return UpNextStreamResolution.NeedsPicker
    val addons = settingsStore.addonManifestUrls.first()
    if (addons.isEmpty()) return UpNextStreamResolution.NeedsPicker
    val fetch = streamCatalog.fetch(addons, imdb, target.season, target.episode)
    val picked = StreamAutoPick.pick(fetch.streams, bingeGroup, streamPickStore.get(imdb))
    return UpNextStreamPolicy.classify(fetch, picked)
  }

  /**
   * Swaps the playing file for the next episode's. Everything the previous episode
   * left behind has to go with it - the watch key above all, or the next progress
   * save records this episode's position against the one just finished.
   */
  private fun startNextEpisode(target: UpNextTarget, stream: AddonStream, nextUrl: String) {
    if (!mpvCreated || finishing) return
    val validatedUrl = validatedPlaybackUrl(nextUrl)
    if (validatedUrl == null) {
      upNextCard.value = upNextCard.value?.copy(
        resolving = false,
        secondsLeft = null,
        failure = UpNextFailure(getString(R.string.playback_url_blocked)),
      )
      publishPlaybackState()
      return
    }
    dismissUpNext()
    url = validatedUrl
    requestHeaders = StreamRequestHeaders.sanitize(
      stream.behaviorHints?.proxyHeaders?.request.orEmpty(),
    )
    embeddedSubtitles = EmbeddedSubtitles.sanitize(stream.subtitles)
    streamVideoHash = stream.behaviorHints?.videoHash
    streamFilename = stream.behaviorHints?.filename
    streamVideoSize = stream.behaviorHints?.videoSize
    bingeGroup = stream.bingeGroup
    season = target.season
    episode = target.episode
    syncEpisodeLabel()
    watchKey = watchKeyFor(tmdbId, target.season, target.episode)
    resumeMs = 0L
    resumeResetRequested = false
    resetFileRuntime(clearUpNext = true, resetDelays = true, initialPositionMs = 0L)
    loadCurrentFile(replace = true, startMs = 0L)
    publishMediaMetadata()
    showOsd()
  }

  /**
   * Leaves the player with the next episode's stream list as the result, so the
   * viewer lands on the picker rather than back on the episode they just watched.
   */
  private fun handOffToPicker(target: UpNextTarget) {
    dismissUpNext()
    val imdb = imdbId?.takeIf { it.isNotBlank() }
    if (imdb != null && tmdbId != 0) {
      val next = Screen.Streams(
        imdbId = imdb,
        title = title,
        tmdbId = tmdbId,
        mediaType = MediaType.Show,
        posterUrl = posterUrl,
        season = target.season,
        episode = target.episode,
      )
      setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_STREAMS, next))
    }
    // Automatic up-next follows a finished episode. The transport's manual Next
    // path can arrive halfway through one and must not mark that episode watched.
    finishPlayback(markFinished = endHandled)
  }

  /** Notes that the remote is in someone's hand; see [UpNextPolicy.offer]. */
  private fun noteInteraction() {
    lastInteractionMs = SystemClock.uptimeMillis()
  }

  /**
   * Applies everything that is only true while pictures are actually moving: the
   * headphone-disconnect receiver is registered, and the screen is kept awake.
   * Idempotent, so every state change that might have started or stopped
   * playback can just call it.
   */
  private fun syncPlayingState() {
    val playing = mpvCreated && !finishing && playbackError.value == null &&
      playbackStarted && !paused.value
    syncNoisyReceiver(playing)
    syncScreenOn(playing)
  }

  private fun syncNoisyReceiver(playing: Boolean) {
    if (playing == noisyReceiverRegistered) return
    noisyReceiverRegistered = playing
    if (playing) {
      // NOT_EXPORTED is right even though the sender is the system: only the
      // platform broadcasts this action, and it is exempt from the restriction.
      ContextCompat.registerReceiver(
        this,
        becomingNoisyReceiver,
        IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
        ContextCompat.RECEIVER_NOT_EXPORTED,
      )
    } else {
      runCatching { unregisterReceiver(becomingNoisyReceiver) }
    }
  }

  /**
   * Note the asymmetry: playing re-adds the flag every time, while the release is
   * armed once and left to run. Re-arming on each call would let a state that
   * changes more often than [IDLE_SCREEN_ON_MS] — a paused film whose
   * MediaSession clients keep poking it — hold the screen on forever.
   */
  private fun syncScreenOn(playing: Boolean) {
    if (playing) {
      mainHandler.removeCallbacks(releaseScreenOnRunnable)
      screenOnReleaseArmed = false
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else if (!screenOnReleaseArmed) {
      screenOnReleaseArmed = true
      mainHandler.postDelayed(releaseScreenOnRunnable, IDLE_SCREEN_ON_MS)
    }
  }

  @Composable
  private fun PlayerSurface() {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
      AndroidView(factory = { context -> createSurfaceView(context) }, modifier = Modifier.fillMaxSize())
      BusyIndicator()
      PlaybackErrorPanel()
      Osd()
      // Last, so the panel sits over the OSD rather than under it.
      PlayerMenuHost()
      UpNextCardHost()
    }
  }

  @Composable
  private fun BoxScope.UpNextCardHost() {
    val state by upNextCard
    UpNextCard(
      state = state ?: return,
      onPlay = ::playNextEpisode,
      onRetry = ::playNextEpisode,
      onCancel = {
        dismissUpNext()
        finishPlayback(markFinished = endHandled)
      },
    )
  }

  /**
   * Bridges the activity's playback state into the stateless [PlayerMenu]. Its own
   * composable so the per-selection state it reads recomposes the menu alone,
   * leaving the surface and the OSD out of it.
   */
  @Composable
  private fun BoxScope.PlayerMenuHost() {
    val visible by menuVisible
    val error by playbackError
    // A dead stream has nothing to choose between, and the failure panel owns the
    // remote from that point on.
    if (!visible || error != null) return
    val state = PlayerMenuState(
      tab = menuTab.value,
      audioRows = MpvTracks.audioRows(tracks.value),
      subtitleRows = MpvTracks.subtitleRows(tracks.value),
      speed = playbackSpeed.doubleValue,
      subtitleSize = subtitleSize.value,
      audioOutput = audioOutput.value,
      audioDelaySec = audioDelaySec.doubleValue,
      subtitleDelaySec = subtitleDelaySec.doubleValue,
      externalSubtitles = externalSubtitles.value,
    )
    val actions = remember {
      PlayerMenuActions(
        onTab = { noteInteraction(); menuTab.value = it },
        onSelectAudio = { noteInteraction(); selectAudioTrack(it) },
        onSelectSubtitle = { noteInteraction(); selectSubtitleTrack(it) },
        onSpeedStep = { noteInteraction(); stepPlaybackSpeed(it) },
        onSubtitleSizeStep = { noteInteraction(); stepSubtitleSize(it) },
        onAudioOutputStep = { noteInteraction(); stepAudioOutput(it) },
        onAudioDelayStep = { noteInteraction(); stepAudioDelay(it) },
        onSubtitleDelayStep = { noteInteraction(); stepSubtitleDelay(it) },
        onFetchExternalSubtitles = { noteInteraction(); fetchExternalSubtitles() },
        onSelectExternalSubtitle = { noteInteraction(); addExternalSubtitle(it) },
        onInteraction = ::noteInteraction,
      )
    }
    PlayerMenu(state, actions)
  }

  /**
   * The wait, named. A bare spinner over black leaves "buffering" and "seeking"
   * looking identical, and they are the difference between a stream that is
   * struggling and one that is doing what it was just asked to.
   */
  @Composable
  private fun BoxScope.BusyIndicator() {
    val error by playbackError
    val isBuffering by buffering
    val isSeeking by seeking
    if (error != null || !(isBuffering || isSeeking)) return
    Row(
      modifier = Modifier
        .align(Alignment.Center)
        .background(NebulaPalette.Surface.copy(alpha = 0.92f), NebulaShapes.large)
        .border(1.dp, NebulaPalette.Outline, NebulaShapes.large)
        .padding(horizontal = 24.dp, vertical = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      androidx.compose.material3.CircularProgressIndicator(
        color = NebulaPalette.VioletBright,
        trackColor = NebulaPalette.Outline,
        strokeWidth = 3.dp,
        modifier = Modifier.size(28.dp),
      )
      Text(
        stringResource(if (isSeeking) R.string.player_seeking else R.string.player_buffering),
        modifier = Modifier.padding(start = 14.dp),
        color = NebulaPalette.TextMuted,
        style = MaterialTheme.typography.bodyMedium,
      )
    }
  }

  /**
   * The dead-stream state. Without it a failed load or a mid-stream drop leaves a
   * spinner over black with no explanation and no hint that BACK is the way out.
   */
  @Composable
  private fun BoxScope.PlaybackErrorPanel() {
    val reason by playbackError
    val message = reason ?: return
    // The only focusable thing the player shows on a dead stream, so nothing else
    // can hand focus to it. Keyed on the message so a retry that fails differently
    // takes focus back off the panel it replaced.
    val retryTarget = rememberInitialFocusTarget()
    RequestInitialFocus(retryTarget, key = message, label = "player retry")

    Column(
      modifier = Modifier
        .align(Alignment.Center)
        .fillMaxWidth(ERROR_PANEL_WIDTH_FRACTION)
        .background(NebulaPalette.Surface, NebulaShapes.extraLarge)
        .border(1.dp, NebulaPalette.Outline, NebulaShapes.extraLarge)
        .padding(horizontal = 44.dp, vertical = 40.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      EmptyState(
        title = stringResource(R.string.player_playback_failed),
        hint = message,
        icon = Icons.Filled.Warning,
      )
      NebulaButton(
        text = stringResource(R.string.action_retry),
        onClick = { retryPlayback() },
        style = NebulaButtonStyle.Primary,
        icon = Icons.Filled.Refresh,
        modifier = Modifier.padding(top = 26.dp).initialFocusTarget(retryTarget),
      )
      Text(
        stringResource(R.string.player_retry_hint),
        modifier = Modifier.padding(top = 20.dp),
        color = NebulaPalette.TextFaint,
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
      )
    }
  }

  /**
   * The transport panel: what is playing, how far in, and every control the remote
   * can reach.
   *
   * Genuinely focusable, in two rows, because the remote this app is built for has
   * no MENU key, no CAPTIONS key and no transport keys at all - so the D-pad
   * walking these buttons is the only route to the track menu, and a panel that
   * was purely decorative left half the player unreachable.
   */
  @Composable
  private fun BoxScope.Osd() {
    val error by playbackError
    val show by osdVisible
    val upNext by upNextCard
    val menuOpen by menuVisible
    val row by osdRow

    // The transport hints are a lie once the stream is dead, and keep-open pauses at an error end,
    // which would otherwise pin the OSD open behind the panel. Same at the other end: the video has
    // finished, so a full-width transport bar under the up-next card would offer controls that no
    // longer do anything.
    val visible = error == null && upNext == null && show

    // The panel used to appear and vanish in a single frame, which over moving video is the most
    // noticeable "this is an app" tell the player had. This is one alpha on one node - a
    // compositing parameter, so it neither recomposes the panel's children nor re-measures
    // anything - which is the only kind of animation this hardware can afford over live playback.
    val alpha by animateFloatAsState(
      targetValue = if (visible) 1f else 0f,
      animationSpec = if (visible) NebulaMotion.osdEnter() else NebulaMotion.osdExit(),
      label = "osdAlpha",
    )
    // Kept in the tree while it fades out, and only then dropped.
    if (!visible && alpha <= 0.01f) return

    val scrubTarget = rememberInitialFocusTarget()
    val buttonsTarget = rememberInitialFocusTarget()
    // The menu is these buttons opened out, and it owns the remote while it is up: leaving the rows
    // focusable behind it would let a press at the end of the track list walk focus out of the
    // panel and onto a button underneath it.
    //
    // Gated on the *logical* state rather than the animated one, so a panel that is still fading
    // cannot take or hold focus. Getting that wrong would strand the remote on a node that is on
    // its way out, which is a worse bug than the pop this animation replaces.
    val focusable = visible && !menuOpen
    RequestInitialFocus(
      target = scrubTarget,
      key = row,
      label = "player scrub bar",
      enabled = focusable && row == OsdRow.Scrub,
    )
    RequestInitialFocus(
      target = buttonsTarget,
      key = row,
      label = "player transport row",
      enabled = focusable && row == OsdRow.Buttons,
    )

    Column(
      modifier = Modifier
        .align(Alignment.BottomStart)
        .then(
          if (menuOpen) {
            Modifier.fillMaxWidth(1f - PANEL_WIDTH_FRACTION)
          } else {
            Modifier.fillMaxWidth()
          },
        )
        .graphicsLayer { this.alpha = alpha }
        // Stops at 0.88 rather than opaque black: the thing under this scrim is the film, and
        // taking it all the way deleted the bottom fifth of the picture every time the panel came
        // up.
        .background(NebulaOsdScrim)
        .onFocusChanged { setOsdFocus(it.hasFocus) }
        // Ahead of the focus search, so the keys these controls define cannot also
        // move focus, and ahead of the activity, so they cannot also seek.
        .onPreviewKeyEvent { onOsdKey(it) }
        .focusProperties { canFocus = focusable }
        .focusGroup()
        .padding(
          start = NebulaDimens.ScreenEdge,
          end = NebulaDimens.ScreenEdge,
          top = 56.dp,
          bottom = NebulaDimens.ScreenEdgeVertical,
        ),
      verticalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap),
    ) {
      OsdTitleRow()
      OsdTrackChips()
      // Above the bar because that is where UP from the bar goes: the layout and
      // the key map have to agree or the D-pad reads as broken.
      if (focusable) TransportRow(buttonsTarget)
      // Own composable so the per-second position updates recompose the scrub row
      // alone, not the surface and the OSD chrome around it.
      ScrubRow(scrubTarget, focusable)
      OsdFooter(row, focusable)
    }
  }

  @Composable
  private fun OsdTitleRow() {
    val episodeText by episodeLabel
    val isPaused by paused
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        color = NebulaPalette.TextHigh,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      episodeText?.let { NebulaBadge(it, tone = BadgeTone.Accent) }
      // A paused film and a stalled one look the same on screen; the badge is what
      // tells a viewer which of the two they are looking at.
      if (isPaused) {
        NebulaBadge(stringResource(R.string.player_paused), tone = BadgeTone.Warn)
      }
    }
  }

  /**
   * What is playing, as chips. Chips rather than the pipe-separated line this used
   * to be because the two tracks are things a viewer changes, and a run-on string
   * is read word by word where three pills are read at a glance.
   */
  @Composable
  private fun OsdTrackChips() {
    val list by tracks
    val message by osdMessage
    val fps = contentFps
    val audio = MpvTracks.selected(list, TrackKind.Audio)?.osdLabel
    val subtitle = MpvTracks.selected(list, TrackKind.Subtitle)?.osdLabel
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Weighted, and the frame rate not: a remux's track label runs to a
        // language, a channel layout and a codec, and unweighted it would push the
        // shortest and most fixed chip of the three off the end of the row.
        NebulaBadge(
          stringResource(
            R.string.player_audio_track,
            audio ?: stringResource(R.string.player_none),
          ),
          modifier = Modifier.weight(1f, fill = false),
        )
        NebulaBadge(
          stringResource(
            R.string.player_subtitle_track,
            subtitle ?: stringResource(R.string.player_off),
          ),
          modifier = Modifier.weight(1f, fill = false),
          tone = if (subtitle != null) BadgeTone.Accent else BadgeTone.Neutral,
        )
        MpvTracks.fpsLabel(fps)?.let { NebulaBadge(it) }
      }
      if (message.isNotBlank()) {
        Text(
          message,
          color = NebulaPalette.VioletBright,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }

  /**
   * The button row: every action the remote has no key for.
   *
   * Its own focus group so LEFT/RIGHT walk the buttons under Compose's own focus
   * search - [onOsdKey] only claims the way back down - and so the row can report
   * where focus actually is rather than where the key map last aimed it.
   *
   * Sideways exits are cancelled because the search does not respect the row's
   * ends: the scrub bar underneath is full-width, so a LEFT off the first button
   * found it a better candidate than nothing and dropped focus onto it, and the
   * press after that seeked. Cancelling leaves the key unhandled, which is what
   * [onKeyDown] swallows once the panel holds focus, so the ends of the row are
   * dead ends rather than a way out.
   */
  @OptIn(ExperimentalComposeUiApi::class)
  @Composable
  private fun TransportRow(target: InitialFocusTarget) {
    val isPaused by paused
    val next by upNextTarget
    Row(
      modifier = Modifier
        .onFocusChanged {
          if (it.hasFocus) {
            osdRow.value = OsdRow.Buttons
            setOsdFocus(true)
          }
        }
        .focusProperties {
          exit = { direction ->
            if (direction == FocusDirection.Left || direction == FocusDirection.Right) {
              FocusRequester.Cancel
            } else {
              FocusRequester.Default
            }
          }
        }
        .focusGroup(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TransportButton(
        label = stringResource(if (isPaused) R.string.action_play else R.string.player_pause),
        onClick = { togglePause(); showOsd() },
        modifier = Modifier.initialFocusTarget(target),
        primary = true,
        showLabel = false,
      ) { tint ->
        if (isPaused) {
          Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp),
          )
        } else {
          PauseGlyph(tint)
        }
      }
      TransportButton(
        label = stringResource(R.string.player_restart),
        onClick = { seekToSec(0.0); showOsd() },
        showLabel = false,
      ) { tint ->
        Icon(
          Icons.Filled.Refresh,
          contentDescription = null,
          tint = tint,
          modifier = Modifier.size(22.dp),
        )
      }
      TransportButton(
        label = stringResource(R.string.player_audio_and_subtitles),
        onClick = { openMenu(PlayerMenuTab.Audio) },
      ) { tint ->
        Icon(
          Icons.AutoMirrored.Filled.List,
          contentDescription = null,
          tint = tint,
          modifier = Modifier.size(20.dp),
        )
      }
      TransportButton(
        label = stringResource(R.string.player_playback_options),
        onClick = { openMenu(PlayerMenuTab.Options) },
      ) { tint ->
        Icon(
          Icons.Filled.Settings,
          contentDescription = null,
          tint = tint,
          modifier = Modifier.size(20.dp),
        )
      }
      // Offered only once the lookup has actually produced an episode: a button
      // that answers "there is nothing next" after the press is worse than no
      // button, and on a film there is never anything next.
      if (next != null) {
        TransportButton(
          label = stringResource(R.string.player_next_episode),
          onClick = { skipToNextEpisode() },
        ) { tint -> SkipNextGlyph(tint) }
      }
    }
  }

  /**
   * One transport button. [showLabel] false is for the two whose glyph needs no
   * word - play/pause and restart - and is what keeps the row inside the overscan
   * margin, where five fully labelled pills do not fit.
   */
  @Composable
  private fun TransportButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    showLabel: Boolean = true,
    glyph: @Composable (Color) -> Unit,
  ) {
    var focused by remember { mutableStateOf(false) }
    val shape = NebulaShapes.large
    Button(
      onClick = onClick,
      colors = ButtonDefaults.colors(
        containerColor = if (primary) NebulaPalette.Violet else NebulaPalette.SurfaceVariant,
        contentColor = if (primary) Color.White else NebulaPalette.TextHigh,
        focusedContainerColor = NebulaPalette.VioletBright,
        focusedContentColor = NebulaPalette.OnAccent,
      ),
      shape = ButtonDefaults.shape(shape = shape),
      border = nebulaButtonBorder(shape),
      glow = nebulaButtonGlow(),
      scale = ButtonDefaults.scale(focusedScale = 1.05f),
      contentPadding = PaddingValues(
        horizontal = if (showLabel) 20.dp else 16.dp,
        vertical = 12.dp,
      ),
      modifier = modifier
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        // The glyph is the whole button when there is no label, so the name has to
        // reach a screen reader some other way.
        .then(if (showLabel) Modifier else Modifier.semantics { contentDescription = label }),
    ) {
      glyph(
        when {
          focused -> NebulaPalette.OnAccent
          primary -> Color.White
          else -> NebulaPalette.TextHigh
        },
      )
      if (showLabel) {
        Box(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
      }
    }
  }

  /** Two rounded bars: material-icons-core ships no pause glyph. */
  @Composable
  private fun PauseGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
      val barWidth = size.width * 0.30f
      val radius = CornerRadius(barWidth * 0.45f)
      drawRoundRect(tint, Offset.Zero, Size(barWidth, size.height), radius)
      drawRoundRect(tint, Offset(size.width - barWidth, 0f), Size(barWidth, size.height), radius)
    }
  }

  /** Triangle into a bar, for the same reason as [PauseGlyph]. */
  @Composable
  private fun SkipNextGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
      val barWidth = size.width * 0.18f
      val triangleWidth = size.width - barWidth - size.width * 0.12f
      drawPath(
        Path().apply {
          moveTo(0f, 0f)
          lineTo(triangleWidth, size.height / 2f)
          lineTo(0f, size.height)
          close()
        },
        tint,
      )
      drawRoundRect(
        tint,
        Offset(size.width - barWidth, 0f),
        Size(barWidth, size.height),
        CornerRadius(barWidth * 0.45f),
      )
    }
  }

  /**
   * The scrub bar, and the row focus lands on first: LEFT/RIGHT seek from here,
   * which is the one thing a viewer does mid-film without wanting to read anything.
   */
  @Composable
  private fun ScrubRow(target: InitialFocusTarget, focusable: Boolean) {
    val actual by timePosSec
    val preview by seekPreviewSec
    val duration by durationSec
    val speed by playbackSpeed
    var focused by remember { mutableStateOf(false) }
    // A seek that has not landed yet: the position under the thumb is where the
    // presses have got to, not where mpv is.
    val scrubbing = preview >= 0
    val position = if (scrubbing) preview else actual
    val fraction = if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
    val shape = NebulaShapes.large
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .onFocusChanged {
          focused = it.isFocused
          if (it.isFocused) {
            osdRow.value = OsdRow.Scrub
            setOsdFocus(true)
          }
        }
        .initialFocusTarget(if (focusable) target else null)
        .focusable(enabled = focusable)
        .background(
          if (focused) NebulaPalette.Violet.copy(alpha = 0.14f) else Color.Transparent,
          shape,
        )
        .border(2.dp, if (focused) NebulaPalette.VioletBright else Color.Transparent, shape)
        .padding(horizontal = 16.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        formatTime(position),
        modifier = Modifier.width(TIME_WIDTH),
        color = if (scrubbing) NebulaPalette.VioletBright else NebulaPalette.TextHigh,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
      )
      ScrubBar(
        fraction = fraction,
        // Where the demuxer has read to, as a fraction of the file. Clamped below the play head so
        // a stale reading from before a seek can never draw the buffer behind the fill.
        bufferedFraction = cacheAheadSec.value?.takeIf { duration > 0 }?.let { cachedUntil ->
          (cachedUntil / duration).toFloat().coerceIn(fraction, 1f)
        },
        focused = focused,
        scrubbing = scrubbing,
        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
      )
      // Mid-seek the useful number is how far the presses have moved, not how much
      // film is left: the viewer is aiming, and the target is what they are aiming
      // at. Fixed width either side so neither ever shifts the bar.
      val trailing = if (scrubbing) {
        formatSignedTime(preview - actual)
      } else {
        PlaybackTimeline.remainingSec(position, duration, speed)
          ?.let { "-${formatTime(it)}" }
          .orEmpty()
      }
      Text(
        trailing,
        modifier = Modifier.width(TIME_WIDTH),
        color = if (scrubbing) NebulaPalette.VioletBright else NebulaPalette.TextMuted,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.End,
        maxLines = 1,
      )
    }
  }

  /**
   * The bar itself. [BoxWithConstraints] rather than a fraction-width thumb because
   * the thumb has to stay inside the track at both ends, and a zero-width box with
   * a circle in it hangs half of that circle off the left of the bar at 0:00.
   */
  @Composable
  private fun ScrubBar(
    fraction: Float,
    bufferedFraction: Float?,
    focused: Boolean,
    scrubbing: Boolean,
    modifier: Modifier = Modifier,
  ) {
    val thumbSize = if (focused || scrubbing) 18.dp else 12.dp
    BoxWithConstraints(
      modifier = modifier.height(24.dp),
      contentAlignment = Alignment.CenterStart,
    ) {
      val track = maxWidth
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(SCRUB_BAR_HEIGHT)
          // Not Outline: at 1.53:1 on the page the unplayed remainder was invisible, so the bar
          // said how much had been watched but not how much was left.
          .background(NebulaPalette.TrackInactive, CircleShape),
      )
      // How far ahead the demuxer has read. Every premium player draws this and none of them
      // explains it, because it needs no explaining: a fill that has stopped short of the thumb is
      // a stream in trouble, and one that runs to the end is a film that will not stall again.
      if (bufferedFraction != null) {
        Box(
          modifier = Modifier
            .fillMaxWidth(bufferedFraction)
            .height(SCRUB_BAR_HEIGHT)
            .background(NebulaPalette.VioletBright.copy(alpha = 0.32f), CircleShape),
        )
      }
      Box(
        modifier = Modifier
          .fillMaxWidth(fraction)
          .height(SCRUB_BAR_HEIGHT)
          // The one bar in the app that spans a known full width, so the accent ramp actually
          // reads across it - which is why NebulaProgressBar's fill is solid and this one is not.
          .background(NebulaAccentBrush, CircleShape),
      )
      Box(
        modifier = Modifier
          .offset(x = (track - thumbSize) * fraction)
          .size(thumbSize)
          .background(
            if (scrubbing) NebulaPalette.VioletBright else NebulaPalette.TextHigh,
            CircleShape,
          ),
      )
    }
  }

  /** What the keys do from here, and when the film ends by the clock on the wall. */
  @Composable
  private fun OsdFooter(row: OsdRow, focusable: Boolean) {
    val actual by timePosSec
    val preview by seekPreviewSec
    val duration by durationSec
    val speed by playbackSpeed
    val position = if (preview >= 0) preview else actual
    // Recomposed once a second by [position], which is what keeps the end time
    // current without a clock of its own.
    val remaining = PlaybackTimeline.remainingSec(position, duration, speed)
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        // The menu has its own hint line and is the thing being read while it is
        // open; two sets of instructions on one screen contradict each other.
        if (!focusable || !OsdHintPolicy.showsHint(osdOpens.intValue)) {
          // Taught and retired. "Ends at" keeps the row, so nothing under the bar shifts.
          ""
        } else if (row == OsdRow.Buttons) {
          stringResource(R.string.player_controls_hint)
        } else {
          stringResource(R.string.player_scrub_hint)
        },
        modifier = Modifier.weight(1f),
        color = NebulaPalette.TextFaint,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (remaining != null) {
        val endsAt = PlaybackTimeline.endsAtEpochMs(System.currentTimeMillis(), remaining)
        Text(
          stringResource(R.string.player_ends_at, formatClockTime(endsAt)),
          modifier = Modifier.padding(start = 16.dp),
          color = NebulaPalette.TextFaint,
          style = MaterialTheme.typography.labelMedium,
          maxLines = 1,
        )
      }
    }
  }

  /** The device's own 12/24-hour setting decides the shape of this. */
  @Composable
  private fun formatClockTime(epochMs: Long): String {
    val format = remember {
      android.text.format.DateFormat.getTimeFormat(this@MpvPlayerActivity)
    }
    return format.format(Date(epochMs))
  }

  private fun createSurfaceView(context: Context): SurfaceView {
    val view = SurfaceView(context)
    view.holder.addCallback(object : SurfaceHolder.Callback {
      override fun surfaceCreated(holder: SurfaceHolder) {
        if (!mpvCreated) return
        playbackSurface = holder.surface
        surfaceReady = true
        MPVLib.attachSurface(holder.surface)
        MPVLib.setOptionString("force-window", "yes")
        if (fileLoaded) {
          // Returning to an already-loaded stream: revive the video output that
          // surfaceDestroyed switched off, or playback continues blind.
          MPVLib.setPropertyString("vo", "gpu")
          applyDisplayFrameRateVote()
        } else {
          maybeLoadFile()
        }
      }

      override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!mpvCreated) return
        MPVLib.setPropertyString("android-surface-size", "${width}x${height}")
        playbackSurface = holder.surface
        // Re-assert the frame-rate vote if we already know the content fps (the
        // surface can be recreated, e.g. after a display-mode switch).
        if (contentFps > 0f) applyDisplayFrameRateVote()
      }

      override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!mpvCreated) return
        clearDisplayFrameRateVote()
        playbackSurface = null
        // Nothing to render into, so a load still waiting on preferences waits
        // for the next surface rather than opening the file blind.
        surfaceReady = false
        MPVLib.setPropertyString("vo", "null")
        MPVLib.detachSurface()
      }
    })
    return view
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    if (!mpvCreated) return super.onKeyDown(keyCode, event)
    noteInteraction()
    // The up-next card owns the remote: the video has ended, so there is no
    // transport left to drive, and OK means "play it" rather than "pause".
    if (upNextCard.value != null) {
      if (event.repeatCount == 0) {
        when (keyCode) {
          in UP_NEXT_PLAY_KEYS -> {
            playNextEpisode()
            return true
          }
          KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MEDIA_STOP -> {
            // End cards have already recorded a finished episode. A card opened
            // by the transport's manual Next path has not.
            dismissUpNext()
            finishPlayback(markFinished = endHandled)
            return true
          }
        }
      }
      // Everything else is swallowed rather than passed on, media keys included: an
      // unhandled one reaches the MediaSession, which would ask to resume a video
      // that has finished.
      if (keyCode in TRANSPORT_KEYS) return true
      return super.onKeyDown(keyCode, event)
    }
    // On a dead stream the only useful keys are the ones that retry or leave.
    if (playbackError.value != null) {
      if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
        finishPlayback(markFinished = false)
        return true
      }
      // The panel's Retry button normally has focus and handles OK itself; this
      // is the fallback for when the focus request did not land, and for the
      // media keys, which never reach a button.
      if (event.repeatCount == 0 && keyCode in RETRY_KEYS) {
        retryPlayback()
        return true
      }
      // Transport keys are consumed, not passed on: the coalescer has no duration
      // to clamp against, so presses would pile up into a resume position for
      // something that never played — and an unhandled media key now falls
      // through to the MediaSession, which would ask to resume the dead stream.
      if (keyCode in TRANSPORT_KEYS) return true
      return super.onKeyDown(keyCode, event)
    }
    // With the menu open the D-pad belongs to it. Compose consumes the presses
    // that move focus or activate a row before they ever reach here, so what
    // arrives is what it could not use — an UP at the top of the list, an OK on
    // nothing — and letting those through would scrub the film behind the menu.
    if (menuVisible.value) {
      if (event.repeatCount == 0) {
        when (keyCode) {
          // BACK closes the menu instead of leaving the player: the one thing a
          // viewer who opened it by accident will press.
          KeyEvent.KEYCODE_BACK,
          KeyEvent.KEYCODE_MENU,
          KeyEvent.KEYCODE_CAPTIONS -> {
            if (keyCode == KeyEvent.KEYCODE_BACK) consumedBackDownTime = event.downTime
            closeMenu()
            return true
          }
          // Transport that cannot be confused with driving the menu still works,
          // so a lip-sync adjustment can be made against a paused frame.
          KeyEvent.KEYCODE_MEDIA_PLAY -> {
            playPlayback(); return true
          }
          KeyEvent.KEYCODE_MEDIA_PAUSE -> {
            pausePlayback(); return true
          }
          KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
            togglePause(); return true
          }
          KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> {
            cycleAudioTrack(); return true
          }
          // Stop means stop, menu or no menu.
          KeyEvent.KEYCODE_MEDIA_STOP -> {
            finishPlayback(markFinished = false)
            return true
          }
        }
      }
      // Everything else the player would otherwise act on is swallowed, media
      // keys included: an unhandled one falls through to the MediaSession.
      if (keyCode in TRANSPORT_KEYS) return true
      return super.onKeyDown(keyCode, event)
    }
    // One press, one action: BACK held long enough to repeat used to close the
    // menu and then, on the repeats that arrived after it had closed, leave the
    // film as well. Repeats of a press carry its original down time, so the press
    // that closed the menu can be recognised and ignored for the rest of its life.
    if (keyCode == KeyEvent.KEYCODE_BACK && event.downTime == consumedBackDownTime) return true
    // The controls are up and hold focus, so their own key map has already had
    // these. What arrives here is what that map left to Compose's focus search and
    // the search could not use either - a RIGHT off the end of the button row.
    // Seeking on it would move the film while the viewer was only moving the
    // highlight, which is the one thing a focusable OSD must not do.
    if (osdVisible.value && osdHasFocus) {
      if (keyCode == KeyEvent.KEYCODE_BACK) {
        // BACK puts the controls away rather than leaving the film. The second
        // press, with nothing on screen, is the one that exits.
        consumedBackDownTime = event.downTime
        hideOsd()
        return true
      }
      if (keyCode in DPAD_KEYS) return true
    }
    // A held key repeats around twenty times a second. Only the seek keys have
    // anything to do with that — [requestSeek] folds repeats into the coalescer —
    // and for the rest a repeat is meaningless: resting a thumb on OK toggled
    // pause a dozen times, and holding MENU cycled blindly through every subtitle
    // track and back. The first press is the only one that counts.
    if (event.repeatCount > 0 && keyCode !in SEEK_KEYS) {
      return keyCode in TRANSPORT_KEYS || super.onKeyDown(keyCode, event)
    }
    when (keyCode) {
      KeyEvent.KEYCODE_DPAD_CENTER,
      KeyEvent.KEYCODE_ENTER,
      KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
        togglePause()
        showOsd()
        return true
      }
      KeyEvent.KEYCODE_MEDIA_PLAY -> {
        playPlayback(); showOsd(); return true
      }
      KeyEvent.KEYCODE_MEDIA_PAUSE -> {
        pausePlayback(); showOsd(); return true
      }
      KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND ->
        return requestSeek(-10.0, event.repeatCount > 0)
      KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
        return requestSeek(10.0, event.repeatCount > 0)
      // Were ±60s, which nothing asked for on a remote whose LEFT/RIGHT already
      // seek and can be held. The vertical axis is worth far more as the way in to
      // the controls: on the remote this app is built for it is the only way in.
      KeyEvent.KEYCODE_DPAD_UP -> {
        showOsd(OsdRow.Buttons)
        return true
      }
      KeyEvent.KEYCODE_DPAD_DOWN -> {
        showOsd(OsdRow.Scrub)
        return true
      }
      // Was `cycle sub`, which on a fifteen-track remux meant pressing MENU up to
      // sixteen times to get back to the subtitles you started with, and left
      // audio tracks reachable only from a key most TV remotes do not have.
      KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAPTIONS -> {
        openMenu()
        return true
      }
      // For the remotes that have it, and for HDMI-CEC. Does nothing on a film or
      // before the lookup has found anything, which is what [skipToNextEpisode]
      // reports rather than silently swallowing the press.
      KeyEvent.KEYCODE_MEDIA_NEXT -> {
        skipToNextEpisode()
        return true
      }
      // Kept for the remotes that do have it: one press, one audio track on, no
      // menu in the way.
      KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> {
        cycleAudioTrack()
        return true
      }
      // MEDIA_STOP is what a TV remote's stop button and Assistant's "stop" send;
      // leaving the player is the same thing BACK does.
      KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MEDIA_STOP -> {
        finishPlayback(markFinished = false)
        return true
      }
    }
    return super.onKeyDown(keyCode, event)
  }

  /**
   * The controls' own key map, ahead of Compose's focus search and of the
   * activity's transport handling.
   *
   * It has to run as a preview from the panel's root: the scrub row is a
   * full-width focusable node, and a plain key handler on it would see LEFT only
   * after the focus search had already decided to walk sideways out of it.
   */
  private fun onOsdKey(event: ComposeKeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val native = event.nativeKeyEvent
    val isRepeat = native.repeatCount > 0
    noteInteraction()
    // Any press is a viewer with the remote in hand, including the ones handed on
    // to Compose below, so the countdown to hiding starts again from all of them.
    armOsdHide()
    if (osdRow.value == OsdRow.Buttons) {
      return when (native.keyCode) {
        // Back down to the bar, which is where the layout puts it.
        KeyEvent.KEYCODE_DPAD_DOWN -> {
          showOsd(OsdRow.Scrub)
          true
        }
        // Nothing above the buttons, and swallowing it stops the focus search
        // walking out of the panel onto whatever else the window holds.
        KeyEvent.KEYCODE_DPAD_UP -> true
        // LEFT, RIGHT and OK belong to the row itself: the focus search moves the
        // highlight and the button under it handles the press.
        else -> false
      }
    }
    return when (native.keyCode) {
      KeyEvent.KEYCODE_DPAD_LEFT -> requestSeek(-10.0, isRepeat)
      KeyEvent.KEYCODE_DPAD_RIGHT -> requestSeek(10.0, isRepeat)
      KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
        // Repeats dropped: a thumb resting on OK would otherwise toggle pause a
        // dozen times a second.
        if (!isRepeat) {
          togglePause()
          showOsd()
        }
        true
      }
      KeyEvent.KEYCODE_DPAD_UP -> {
        showOsd(OsdRow.Buttons)
        true
      }
      // Out of the controls altogether, which is the quickest way back to an
      // unobstructed picture without waiting out the auto-hide.
      KeyEvent.KEYCODE_DPAD_DOWN -> {
        hideOsd()
        true
      }
      else -> false
    }
  }

  /**
   * Folds the press into [seeker] and (re)arms the flush, so a burst of presses
   * costs one seek once the remote goes quiet. Always consumes the key: dropped
   * repeats must not fall through to focus navigation.
   */
  private fun requestSeek(deltaSec: Double, isRepeat: Boolean): Boolean {
    val target = seeker.press(
      deltaSec = deltaSec,
      positionSec = timePosSec.doubleValue,
      durationSec = durationSec.doubleValue,
      isRepeat = isRepeat,
      nowMs = SystemClock.uptimeMillis(),
    ) ?: return true

    // This press supersedes the timeout for the last flushed command. The new target owns the
    // preview now, and its own timeout is armed only once it is handed to mpv.
    mainHandler.removeCallbacks(seekSettleTimeoutRunnable)
    seekPreviewSec.doubleValue = target
    showOsd()
    mainHandler.removeCallbacks(seekRunnable)
    mainHandler.postDelayed(seekRunnable, SEEK_DEBOUNCE_MS)
    return true
  }

  private fun flushSeek() {
    if (!mpvCreated) return
    val request = seeker.consumePendingRequest() ?: return
    seeking.value = true
    lastPostedSecond = Long.MIN_VALUE
    // The burst has already been coalesced, so its one committed target should be the position the
    // OSD promised rather than the nearest earlier keyframe in a long GOP.
    MPVLib.command(
      arrayOf("seek", request.targetSec.toString(), request.precision.mpvMode),
    )
    mainHandler.removeCallbacks(seekSettleTimeoutRunnable)
    mainHandler.postDelayed(seekSettleTimeoutRunnable, SEEK_SETTLE_TIMEOUT_MS)
    publishPlaybackState()
  }

  /**
   * An absolute seek asked for by a MediaSession client. Expressed as a delta from
   * wherever the position currently is so it runs through the same coalescer as
   * the D-pad: the OSD preview, the end guard and the saved resume position all
   * behave exactly as they do for a remote-driven seek.
   */
  private fun seekToSec(targetSec: Double) {
    requestSeek(targetSec - resumePositionSec(), isRepeat = false)
    if (targetSec <= 0.0) {
      resumeResetRequested = true
      // Persist the reset immediately. Otherwise leaving during the first ten seconds would retain
      // the old (possibly hour-long) resume point because short brand-new plays are normally noise.
      saveWatchState(SaveReason.Paused)
    }
  }

  /** Whether transport control means anything right now. */
  private fun transportAllowed(): Boolean =
    mpvCreated && !finishing && !retryInFlight && playbackError.value == null

  /**
   * The one place playback is unpaused, so audio focus is always held before mpv
   * makes any noise. Any explicit play also cancels a pending auto-resume: from
   * here on the viewer's intent is the one that counts.
   */
  private fun playPlayback() {
    if (!transportAllowed() || !activityStarted) return
    revalidateAudioOutput()
    pausedForFocusLoss = false
    // Refused means someone else has the speakers, so stay put rather than
    // playing over them. mpv is normally already paused here; asserting it covers
    // the initial-load call, where mpv starts unpaused on its own.
    val granted = requestAudioFocus()
    pauseRequested = !granted
    MPVLib.setPropertyBoolean("pause", !granted)
    if (granted) {
      paused.value = false
      applyDisplayFrameRateVote()
    }
  }

  /**
   * The one place playback is paused. [forFocusLoss] marks the pause as this
   * activity's own doing, which is what later allows an automatic resume; a pause
   * the viewer asked for never carries it.
   */
  private fun pausePlayback(forFocusLoss: Boolean = false) {
    if (!mpvCreated) return
    pausedForFocusLoss = forFocusLoss
    pauseRequested = true
    MPVLib.setPropertyBoolean("pause", true)
    paused.value = true
    clearDisplayFrameRateVote()
    if (!forFocusLoss) {
      restoreDuckedVolume()
      abandonAudioFocus()
    }
  }

  /**
   * Play/pause from OK or a media button. Reads mpv's own flag rather than
   * [paused], which lags a frame behind it: `cycle pause` would be simpler but
   * gives no chance to take audio focus on the way out of a pause.
   */
  private fun togglePause() {
    if (!mpvCreated) return
    if (paused.value) playPlayback() else pausePlayback()
  }

  /**
   * Takes audio focus, or reports that it could not be taken. libmpv's audiotrack
   * AO plays whether or not anything asked for focus, so every path that starts
   * playback has to come through here first.
   */
  private fun requestAudioFocus(): Boolean {
    if (hasAudioFocus) return true
    val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
      .setAudioAttributes(playbackAudioAttributes)
      // Ducking is handled here instead of being left to the framework, which
      // cannot reach an AudioTrack mpv opened for itself.
      .setWillPauseWhenDucked(false)
      .setOnAudioFocusChangeListener(audioFocusListener, mainHandler)
      .build()
      .also { audioFocusRequest = it }
    hasAudioFocus =
      audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    return hasAudioFocus
  }

  private fun abandonAudioFocus() {
    hasAudioFocus = false
    audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    audioFocusRequest = null
  }

  private fun duckVolume() {
    if (!mpvCreated || volumeBeforeDuck != null) return
    val current = observedVolume
    volumeBeforeDuck = current
    val ducked = (current * DUCK_VOLUME_FRACTION).toInt()
    observedVolume = ducked
    MPVLib.setPropertyInt("volume", ducked)
  }

  private fun restoreDuckedVolume() {
    val previous = volumeBeforeDuck ?: return
    volumeBeforeDuck = null
    observedVolume = previous
    if (mpvCreated) MPVLib.setPropertyInt("volume", previous)
  }

  /**
   * The framework MediaSession rather than media3: this is not a media browser
   * service, it just needs somewhere for the platform to route HDMI-CEC and
   * Bluetooth transport keys and Assistant's playback commands.
   */
  private fun createMediaSession() {
    val session = MediaSession(this, MEDIA_SESSION_TAG)
    session.setCallback(object : MediaSession.Callback() {
      override fun onPlay() {
        if (!activityStarted || finishing) return
        noteInteraction()
        // Play against a finished video is the card's offer, not a resume.
        if (upNextCard.value != null) {
          playNextEpisode()
          return
        }
        if (!transportAllowed()) return
        playPlayback()
        showOsd()
      }

      override fun onPause() {
        if (!activityStarted || finishing) return
        noteInteraction()
        if (!transportAllowed()) return
        pausePlayback()
        showOsd()
      }

      // Stopping is leaving the player, so it saves what BACK saves.
      override fun onStop() {
        if (!activityStarted || finishing) return
        noteInteraction()
        dismissUpNext()
        finishPlayback(markFinished = endHandled)
      }

      override fun onSeekTo(pos: Long) {
        if (!activityStarted || finishing) return
        noteInteraction()
        if (!transportAllowed()) return
        seekToSec(pos / 1000.0)
      }

      override fun onFastForward() {
        if (!activityStarted || finishing) return
        noteInteraction()
        if (transportAllowed()) requestSeek(10.0, isRepeat = false)
      }

      override fun onRewind() {
        if (!activityStarted || finishing) return
        noteInteraction()
        if (transportAllowed()) requestSeek(-10.0, isRepeat = false)
      }

      override fun onSkipToNext() {
        if (!activityStarted || finishing) return
        noteInteraction()
        if (upNextCard.value != null) playNextEpisode() else skipToNextEpisode()
      }
    })
    session.isActive = false
    mediaSession = session
    publishMediaMetadata()
    publishPlaybackState()
  }

  /**
   * What a client shows as "now playing". Republished when mpv reports the
   * duration, which is a beat after the session exists.
   */
  private fun publishMediaMetadata() {
    val session = mediaSession ?: return
    val durationMs = (durationSec.doubleValue * 1000).toLong()
    if (durationMs == publishedDurationMs) return
    publishedDurationMs = durationMs
    val suffix = if (season != null) "  S${season}E${episode}" else ""
    session.setMetadata(
      MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, "$title$suffix")
        .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
        .build(),
    )
  }

  /**
   * Mirrors the state the OSD is showing. The position comes from
   * [resumePositionSec] for the same reason the OSD uses it: while a seek is
   * outstanding, mpv's `time-pos` still reports where the viewer seeked away from.
   */
  private fun publishPlaybackState() {
    val session = mediaSession ?: return
    val state = when {
      playbackError.value != null -> PlaybackState.STATE_ERROR
      buffering.value || seeking.value -> PlaybackState.STATE_BUFFERING
      paused.value -> PlaybackState.STATE_PAUSED
      else -> PlaybackState.STATE_PLAYING
    }
    val positionMs = (resumePositionSec() * 1000).toLong()
    val speed =
      if (state == PlaybackState.STATE_PLAYING) playbackSpeed.doubleValue.toFloat() else 0f
    // Nothing but leaving is on offer once the stream is dead.
    val actions = if (state == PlaybackState.STATE_ERROR) {
      PlaybackState.ACTION_STOP
    } else {
      TRANSPORT_ACTIONS or
        (PlaybackState.ACTION_SKIP_TO_NEXT.takeIf {
          upNextTarget.value != null || upNextCard.value != null
        } ?: 0L)
    }
    session.setPlaybackState(
      PlaybackState.Builder()
        .setState(state, positionMs, speed)
        .setActions(actions)
        .build(),
    )
  }

  private fun releaseMediaSession() {
    mediaSession?.let { session ->
      session.isActive = false
      session.release()
    }
    mediaSession = null
  }

  private fun startMpvWorker() {
    val thread = HandlerThread("mpv-property-reader")
    thread.start()
    mpvWorker = thread
    mpvWorkerHandler = Handler(thread.looper)
  }

  /**
   * Stops the worker taking new work and drops whatever is still queued. An
   * in-flight read is left to finish under [mpvLock], which is what the caller
   * then takes before destroying the core.
   */
  private fun stopMpvWorker() {
    mpvWorkerHandler?.removeCallbacksAndMessages(null)
    mpvWorkerHandler = null
    mpvWorker?.quit()
    mpvWorker = null
  }

  /**
   * Deletes files whose worker callbacks were removed before they could claim them.
   *
   * A callback that has already claimed its file removes it from this set first and owns deletion
   * in its own finally block, so lifecycle cleanup cannot remove the path underneath `sub-add`.
   */
  private fun discardQueuedSubtitleFiles() {
    val abandoned = synchronized(queuedSubtitleFiles) {
      queuedSubtitleFiles.toList().also { queuedSubtitleFiles.clear() }
    }
    abandoned.forEach(File::delete)
  }

  /**
   * Runs [read] on the worker thread and hands its result to [onResult] on the
   * main thread, where anything touching Compose state or the window has to
   * happen. Nothing runs at all once the core is gone, and a read that threw is
   * dropped rather than reported: none of these are load-bearing enough to fail
   * playback over.
   */
  private fun <T : Any> readOffMain(read: () -> T?, onResult: (T) -> Unit) {
    val worker = mpvWorkerHandler ?: return
    val generation = loadGeneration
    worker.post {
      val value = readWhileAlive(read) ?: return@post
      mainHandler.post {
        if (mpvCreated && !finishing && generation == loadGeneration) onResult(value)
      }
    }
  }

  /** Called on the worker thread: [read] touches mpv only while mpv is still there. */
  private fun <T : Any> readWhileAlive(read: () -> T?): T? {
    mpvLock.lock()
    return try {
      if (!mpvAlive) null else runCatching(read).getOrNull()
    } finally {
      mpvLock.unlock()
    }
  }

  /**
   * Runs a command that may wait for mpv's core off the UI thread. [generation] prevents a queued
   * stop or subtitle mutation for an old file from landing after Retry has opened a new one.
   */
  private fun runMpvMutationOffMain(generation: Long = loadGeneration, action: () -> Unit) {
    val worker = mpvWorkerHandler ?: return
    worker.post {
      mpvLock.lock()
      try {
        if (!mpvAlive || generation != loadGeneration) return@post
        runCatching(action)
      } finally {
        mpvLock.unlock()
      }
    }
  }

  /**
   * Refreshes the track list off the main thread, coalescing a burst of presses
   * into one read. Deliberately delayed: a selection has to reach mpv before the
   * list is worth reading, and a viewer stepping through tracks gets one
   * fetch-and-parse for the whole run rather than one per press.
   *
   * `track-list` is a whole JSON document that has to be fetched across JNI and
   * then parsed, which is far too much to do between two remote presses — so the
   * parse happens out here on the worker too, and only the finished model goes
   * back to the main thread.
   */
  private fun refreshTracks() {
    if (!mpvCreated) return
    val worker = mpvWorkerHandler ?: return
    if (!trackInfoPending.compareAndSet(false, true)) return
    val generation = loadGeneration
    worker.postDelayed({
      trackInfoPending.set(false)
      if (generation != loadGeneration) {
        // FILE_LOADED for the replacement may have asked while this old read
        // still owned the coalescing flag. Queue its generation now that the flag
        // is clear, or the new file can keep an empty/stale menu indefinitely.
        mainHandler.post {
          if (mpvCreated && !finishing && generation != loadGeneration) refreshTracks()
        }
        return@postDelayed
      }
      val json = readWhileAlive { MPVLib.getPropertyString("track-list") } ?: return@postDelayed
      val parsed = MpvTracks.parse(json)
      mainHandler.post {
        if (mpvCreated && !finishing && generation == loadGeneration) applyTracks(parsed)
      }
    }, TRACK_INFO_DEBOUNCE_MS)
  }

  /**
   * Publishes a freshly read track list: the OSD's track chips, and the lists the
   * menu is showing.
   */
  private fun applyTracks(parsed: List<MpvTrack>) {
    tracks.value = parsed
    maybeWarnDolbyVision(parsed)
    // Only ever set by the audio-cycle key, so this cannot learn a preference
    // from the track mpv chose on its own at file open.
    if (audioCyclePending) {
      audioCyclePending = false
      MpvTracks.selected(parsed, TrackKind.Audio)?.let {
        applyPreferenceUpdate(TrackPreferences.audioUpdate(it), audio = true)
      }
    }
  }

  /**
   * Says once, per file, that a Dolby Vision stream is playing on a screen that
   * cannot render it. See [DolbyVisionNotice] for why this is a line in the OSD
   * and not something that stops playback.
   */
  private fun maybeWarnDolbyVision(parsed: List<MpvTrack>) {
    val warn = DolbyVisionNotice.shouldWarn(
      isDolbyVision = MpvTracks.isDolbyVision(parsed),
      display = displayHdrSupport(),
      alreadyWarned = dolbyVisionWarned,
    )
    if (!warn) return
    dolbyVisionWarned = true
    showOsdMessage(DolbyVisionNotice.MESSAGE)
  }

  /**
   * What the panel on the other end of the cable says it can take. A query that
   * throws or reports nothing at all is [DisplayHdrSupport.Unknown], not "no": TV
   * boxes vary in what they answer here, and a wrong "no" would warn about every
   * DV stream on hardware that plays them properly.
   */
  private fun displayHdrSupport(): DisplayHdrSupport = runCatching {
    val display = currentDisplay() ?: return DisplayHdrSupport.Unknown
    val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      // getHdrCapabilities is deprecated from 34 and can report the display's
      // full set rather than the current mode's; the mode is what is on air.
      display.mode.supportedHdrTypes
    } else {
      @Suppress("DEPRECATION")
      display.hdrCapabilities?.supportedHdrTypes
    }
    if (types == null) return DisplayHdrSupport.Unknown
    if (types.any { it == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION }) {
      DisplayHdrSupport.DolbyVision
    } else {
      DisplayHdrSupport.NoDolbyVision
    }
  }.getOrDefault(DisplayHdrSupport.Unknown)

  /** The quick audio cycle, for remotes with a dedicated audio-track key. */
  private fun cycleAudioTrack() {
    if (!mpvCreated) return
    MPVLib.command(arrayOf("cycle", "audio"))
    // The chosen track is only known once the list has been read back, and the
    // choice is as explicit as one made from the menu, so it carries the same way.
    audioCyclePending = true
    refreshTracks()
    showOsd()
  }

  /**
   * [tab] is which of the two transport buttons was pressed, so the menu opens on
   * what the viewer asked for rather than always on the track list and a second
   * press to reach the rest.
   */
  private fun openMenu(tab: PlayerMenuTab = PlayerMenuTab.Audio) {
    if (!transportAllowed()) return
    menuTab.value = tab
    menuVisible.value = true
    // The list may be stale (or empty, if the menu is opened before the first
    // frame), and the OSD stays up for as long as the menu does.
    refreshTracks()
    showOsd()
  }

  /**
   * Jumps to the next episode without waiting for the credits, from the transport
   * button or a remote's skip key. Says so when there is nothing to jump to,
   * because on a film and on a last episode the press is otherwise silent.
   *
   * Saves first: [startNextEpisode] moves the watch key, so a save after it would
   * record this episode's position against the next one - the same reason the
   * binge path saves before it hands over.
   */
  private fun skipToNextEpisode() {
    if (!transportAllowed()) return
    if (upNextTarget.value == null) {
      showOsdMessage(getString(R.string.player_no_next_episode))
      return
    }
    saveWatchState(SaveReason.Stopped)
    playNextEpisode()
    showOsdMessage(getString(R.string.player_starting_next_episode))
  }

  private fun closeMenu() {
    if (!menuVisible.value) return
    menuVisible.value = false
    // The auto-hide was suppressed while the menu was open, so it needs re-arming
    // or the OSD would sit there for the rest of the film.
    showOsd()
  }

  private fun selectAudioTrack(trackId: Int) {
    if (!mpvCreated) return
    MPVLib.setPropertyString("aid", trackId.toString())
    val picked = tracks.value.firstOrNull { it.kind == TrackKind.Audio && it.id == trackId }
    // Optimistic, so the menu's marker moves with the press rather than with the
    // debounced read that follows it.
    markSelected(TrackKind.Audio, trackId)
    if (picked != null) applyPreferenceUpdate(TrackPreferences.audioUpdate(picked), audio = true)
    refreshTracks()
    showOsd()
  }

  /** [trackId] null is the "Off" row, which is `sid=no`. */
  private fun selectSubtitleTrack(trackId: Int?) {
    if (!mpvCreated) return
    MPVLib.setPropertyString("sid", trackId?.toString() ?: "no")
    val picked = trackId?.let { id ->
      tracks.value.firstOrNull { it.kind == TrackKind.Subtitle && it.id == id }
    }
    markSelected(TrackKind.Subtitle, trackId)
    // A track the viewer asked for but that carries no language tag still has to
    // be judged against the stored preference — see [TrackPreferences.subtitleUpdate].
    if (trackId == null || picked != null) {
      applyPreferenceUpdate(
        TrackPreferences.subtitleUpdate(picked, playerPrefs.subtitleLanguage),
        audio = false,
      )
    }
    refreshTracks()
    showOsd()
  }

  /**
   * Asks a subtitles addon what it has for this file.
   *
   * On the viewer's press rather than automatically at file open: it is a request
   * to a third party for something most files do not need, and the answer is only
   * worth having in front of someone who is looking at the list. Nothing about a
   * failure is load-bearing - the file's own tracks are all still there.
   */
  private fun fetchExternalSubtitles() {
    val imdb = imdbId?.takeIf { it.isNotBlank() } ?: return
    if (externalSubtitles.value == ExternalSubtitlesState.Loading) return
    externalSubtitles.value = ExternalSubtitlesState.Loading
    // Captured, so a response that lands after a binge has moved on can be
    // recognised as belonging to the episode before this one.
    val forGeneration = loadGeneration
    val forWatchKey = watchKey
    val forImdb = imdb
    val forSeason = season
    val forEpisode = episode
    val preferred = playerPrefs.subtitleLanguage
    val releaseExtra = buildMap {
      streamVideoHash?.trim()?.takeIf { it.isNotEmpty() }?.let { put("videoHash", it) }
      streamFilename?.trim()?.takeIf { it.isNotEmpty() }?.let { put("filename", it) }
      streamVideoSize?.takeIf { it > 0L }?.let { put("videoSize", it.toString()) }
    }
    lifecycleScope.launch {
      val found = suspendRunCatching {
        withContext(Dispatchers.IO) {
          if (forSeason != null && forEpisode != null) {
            subtitlesClient.episodeSubtitles(imdb, forSeason, forEpisode, releaseExtra)
          } else {
            subtitlesClient.movieSubtitles(imdb, releaseExtra)
          }
        }
      }.getOrNull()
      if (
        finishing ||
        loadGeneration != forGeneration ||
        watchKey != forWatchKey ||
        imdbId != forImdb ||
        season != forSeason ||
        episode != forEpisode
      ) {
        return@launch
      }
      if (found == null) {
        externalSubtitles.value = ExternalSubtitlesState.Failed
        showOsdMessage(getString(R.string.player_subtitle_fetch_failed))
        return@launch
      }
      externalSubtitles.value =
        ExternalSubtitlesState.Ready(ExternalSubtitles.options(found, preferred))
    }
  }

  /**
   * Hands mpv an external subtitle file. Only ever from an explicit pick: the
   * fetched list is offered, never applied, so a file's own tracks and the
   * `alang`/`slang` preferences keep deciding what playback starts with.
   *
   * On the mpv worker thread, because `sub-add` downloads and demuxes the file
   * inside the call - seconds over a slow link, and an ANR on the main thread.
   */
  private fun addExternalSubtitle(option: ExternalSubtitleOption) {
    if (!mpvCreated || externalSubAdding) return
    val generation = loadGeneration
    val sourceStreamUrl = url
    val sourceRequestHeaders = requestHeaders.toMap()
    externalSubAdding = true
    showOsdMessage(getString(R.string.player_loading_subtitles, option.label))
    lifecycleScope.launch {
      val localFile = suspendRunCatching {
        downloadSubtitle(
          rawUrl = option.url,
          generation = generation,
          sourceStreamUrl = sourceStreamUrl,
          sourceRequestHeaders = sourceRequestHeaders,
        )
      }.getOrNull()
      if (
        localFile == null || finishing || !mpvCreated || generation != loadGeneration
      ) {
        localFile?.delete()
        if (!finishing && generation == loadGeneration) {
          externalSubAdding = false
          showOsdMessage(getString(R.string.player_subtitle_add_failed))
        }
        return@launch
      }
      val worker = mpvWorkerHandler
      if (worker == null) {
        localFile.delete()
        externalSubAdding = false
        return@launch
      }
      synchronized(queuedSubtitleFiles) { queuedSubtitleFiles += localFile }
      val accepted = worker.post {
        // onDestroy or a file replacement can discard a callback before it starts. Claiming under
        // the same monitor makes exactly one side own deletion; a callback that lost that race
        // must not try to feed a path the lifecycle already removed to mpv.
        val claimed = synchronized(queuedSubtitleFiles) { queuedSubtitleFiles.remove(localFile) }
        if (!claimed) return@post
        // Network I/O has already completed through cancellable OkHttp. This native command only
        // parses a bounded local file, so onDestroy cannot wait on an unresponsive subtitle host.
        val result = try {
          readWhileAlive {
            if (generation != loadGeneration) return@readWhileAlive null
            val beforeIds = MpvTracks.parse(MPVLib.getPropertyString("track-list"))
              .asSequence()
              .filter { it.kind == TrackKind.Subtitle && it.external }
              .mapTo(mutableSetOf()) { it.id }
            MPVLib.command(
              arrayOf("sub-add", localFile.absolutePath, "cached", option.trackTitle, option.lang),
            )
            beforeIds to MPVLib.getPropertyString("track-list")
          }
        } finally {
          localFile.delete()
        }
        val beforeIds = result?.first.orEmpty()
        val parsed = MpvTracks.parse(result?.second)
        mainHandler.post mainPost@{
          if (!mpvCreated || finishing || generation != loadGeneration) return@mainPost
          externalSubAdding = false
          onExternalSubtitleAdded(option, parsed, beforeIds)
        }
      }
      if (!accepted) {
        val stillQueued = synchronized(queuedSubtitleFiles) {
          queuedSubtitleFiles.remove(localFile)
        }
        if (stillQueued) localFile.delete()
        if (!finishing && generation == loadGeneration) {
          externalSubAdding = false
          showOsdMessage(getString(R.string.player_subtitle_add_failed))
        }
      }
    }
  }

  /** Downloads one subtitle with cancellation, a strict size ceiling and the stream's safe headers. */
  private suspend fun downloadSubtitle(
    rawUrl: String,
    generation: Long,
    sourceStreamUrl: String,
    sourceRequestHeaders: Map<String, String>,
  ): File {
    var currentUrl = SubtitleUrlPolicy.allowedUrlOrNull(rawUrl)
      ?: throw IOException("Unsafe subtitle URL")
    var redirectCount = 0
    while (true) {
      if (generation != loadGeneration) throw CancellationException("Subtitle request replaced")
      val requestBuilder = Request.Builder().url(currentUrl)
      // Recomputed for every hop. A same-origin endpoint may redirect to an unrelated CDN, and
      // custom Cookie/X-* headers are not all guaranteed to be stripped by OkHttp automatically.
      StreamRequestHeaders.forSameOrigin(
        sourceStreamUrl,
        currentUrl,
        sourceRequestHeaders,
      ).forEach { (name, value) -> requestBuilder.header(name, value) }
      val hop = SUBTITLE_HTTP_CLIENT.newCall(requestBuilder.build()).awaitAndConsumeResponse(
        onCancellation = { result ->
          if (result is SubtitleDownloadHop.Complete) result.file.delete()
        },
      ) { response ->
        if (response.code in 300..399) {
          if (redirectCount >= MAX_SUBTITLE_REDIRECTS) {
            throw IOException("Too many subtitle redirects")
          }
          val next = SubtitleUrlPolicy.redirectUrlOrNull(
            from = response.request.url,
            location = response.header("Location"),
          ) ?: throw IOException("Unsafe subtitle redirect")
          SubtitleDownloadHop.Redirect(next)
        } else {
          SubtitleDownloadHop.Complete(
            downloadSubtitleBody(
              response = response,
              sourceUrl = currentUrl,
              generation = generation,
            ),
          )
        }
      }
      when (hop) {
        is SubtitleDownloadHop.Complete -> return hop.file
        is SubtitleDownloadHop.Redirect -> {
          redirectCount += 1
          currentUrl = hop.url
        }
      }
    }
  }

  private fun downloadSubtitleBody(
    response: okhttp3.Response,
    sourceUrl: String,
    generation: Long,
  ): File {
    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
    val body = response.body ?: throw IOException("Empty subtitle response")
    if (body.contentLength() > MAX_SUBTITLE_BYTES) throw IOException("Subtitle is too large")
    val suffix = Uri.parse(sourceUrl).lastPathSegment
      ?.substringAfterLast('.', "")
      ?.lowercase(Locale.ROOT)
      ?.takeIf { it in SUBTITLE_EXTENSIONS }
      ?.let { ".$it" }
      ?: ".sub"
    val destination = File.createTempFile("nebula-sub-$generation-", suffix, cacheDir)
    try {
      body.byteStream().use { input ->
        destination.outputStream().use { output ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          var total = 0L
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_SUBTITLE_BYTES) throw IOException("Subtitle is too large")
            output.write(buffer, 0, read)
          }
        }
      }
    } catch (error: Throwable) {
      destination.delete()
      throw error
    }
    return destination
  }

  /**
   * Reports what became of a `sub-add`. The track list is the only evidence there
   * is: this binding hands back nothing from a command, so a URL that 404s or a
   * file mpv cannot demux leaves the list exactly as it was and the viewer with a
   * subtitle they asked for that never appeared.
   */
  private fun onExternalSubtitleAdded(
    option: ExternalSubtitleOption,
    parsed: List<MpvTrack>,
    externalTrackIdsBeforeAdd: Set<Int>,
  ) {
    applyTracks(parsed)
    // Require a newly-created selected track. Titles repeat ("Online") across languages, so title
    // equality alone can mistake a previously-loaded English subtitle for a failed Spanish add.
    val loaded = parsed.any {
      it.kind == TrackKind.Subtitle && it.external && it.selected &&
        it.id !in externalTrackIdsBeforeAdd
    }
    if (!loaded) {
      showOsdMessage(getString(R.string.player_subtitle_add_failed))
      return
    }
    // Exactly what picking an embedded track does: the language the viewer chose is
    // the one the next episode should start looking for.
    applyPreferenceUpdate(
      TrackPreferences.subtitleLanguageUpdate(option.lang, playerPrefs.subtitleLanguage),
      audio = false,
    )
    showOsdMessage(getString(R.string.player_subtitles_added, option.label))
  }

  /** Moves the selection marker locally, ahead of mpv reporting it back. */
  private fun markSelected(kind: TrackKind, trackId: Int?) {
    tracks.value = tracks.value.map { track ->
      if (track.kind != kind) track else track.copy(selected = track.id == trackId)
    }
  }

  /**
   * Writes a learned language preference, which is how a choice carries to the
   * next episode. [TrackPreferences.Update.Unchanged] deliberately writes
   * nothing: an untagged track says nothing about what to prefer next time.
   */
  private fun applyPreferenceUpdate(update: TrackPreferences.Update, audio: Boolean) {
    val value = (update as? TrackPreferences.Update.Set)?.value ?: return
    playerPrefs = if (audio) {
      playerPrefs.copy(audioLanguage = value)
    } else {
      playerPrefs.copy(subtitleLanguage = value)
    }
    val store = playerPrefsStore
    persistenceScope.launch {
      runCatching {
        if (audio) store.setAudioLanguage(value) else store.setSubtitleLanguage(value)
      }
    }
  }

  private fun stepPlaybackSpeed(steps: Int) {
    if (!mpvCreated) return
    val next = PlaybackSpeeds.stepped(playbackSpeed.doubleValue, steps)
    // Not persisted on purpose: a speed set for one film is rarely wanted for the
    // next. mpv's `speed` observer is what moves the OSD and the menu's label.
    MPVLib.setPropertyDouble("speed", next)
    playbackSpeed.doubleValue = next
    showOsd()
  }

  private fun stepSubtitleSize(steps: Int) {
    if (!mpvCreated) return
    val next = SubtitleSize.stepped(subtitleSize.value, steps)
    if (next == subtitleSize.value) return
    subtitleSize.value = next
    MPVLib.setPropertyString("sub-font-size", next.fontSize.toString())
    playerPrefs = playerPrefs.copy(subtitleSize = next.storageName)
    val store = playerPrefsStore
    persistenceScope.launch { runCatching { store.setSubtitleSize(next.storageName) } }
  }

  /**
   * Switches between decoding here and handing the bitstream to the sink, and
   * remembers the choice: an AVR is a property of the room, not of the film.
   */
  private fun stepAudioOutput(steps: Int) {
    if (!mpvCreated) return
    val next = AudioOutputMode.stepped(audioOutput.value, steps)
    if (next == audioOutput.value) return
    if (next == AudioOutputMode.Passthrough && supportedSpdifCodecs().isEmpty()) {
      showOsdMessage(getString(R.string.player_passthrough_unavailable))
      return
    }
    audioOutput.value = next
    applyAudioOutput(next)
    reselectAudioTrack()
    // The explicit choice persists in either direction. Every later launch and route change
    // revalidates the active sink and falls back to Decode for that session without erasing the
    // preference, so a temporarily powered-off AVR cannot turn this into silent playback.
    playerPrefs = playerPrefs.copy(audioOutput = next.storageName)
    val store = playerPrefsStore
    persistenceScope.launch { runCatching { store.setAudioOutput(next.storageName) } }
    // The same confirmation a subtitle add gets, and for the same reason: what
    // changed is off screen, and passthrough's failure mode is silence.
    showOsdMessage(next.osdMessage)
  }

  private fun applyAudioOutput(mode: AudioOutputMode) {
    val codecs = if (mode == AudioOutputMode.Passthrough) supportedSpdifCodecs() else ""
    MPVLib.setPropertyString("audio-spdif", codecs)
  }

  /**
   * Keeps passthrough tied to the route Android selected for media, not merely a
   * digital device that happens to be connected. API 33 is the first public API
   * that exposes that route; older versions stay on Decode rather than risk
   * silence by guessing from the complete connected-device list.
   */
  private fun supportedSpdifCodecs(): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return ""
    val devices = runCatching {
      audioManager.getAudioDevicesForAttributes(playbackAudioAttributes)
    }.getOrDefault(emptyList())
    if (devices.isEmpty() || devices.any { !isDigitalSink(it) }) return ""
    // Duplicated output can have more than one selected sink. Only advertise the
    // codecs every sink accepts: a union would make the least-capable route
    // silent as soon as a richer AVR happened to be selected beside it.
    val encodings = devices
      .map { it.encodings.toSet() }
      .reduce { common, device -> common.intersect(device) }
    return buildList {
      if (AudioFormat.ENCODING_AC3 in encodings) add("ac3")
      if (
        AudioFormat.ENCODING_E_AC3 in encodings ||
        AudioFormat.ENCODING_E_AC3_JOC in encodings
      ) {
        add("eac3")
      }
      if (AudioFormat.ENCODING_DTS in encodings) add("dts")
      if (AudioFormat.ENCODING_DTS_HD in encodings) add("dts-hd")
      if (AudioFormat.ENCODING_DOLBY_TRUEHD in encodings) add("truehd")
    }.joinToString(",")
  }

  /**
   * A wired route that can carry an encoded bitstream. Kept separate from the
   * codec mapping so an active Bluetooth/headphone route cannot borrow
   * capabilities from an idle HDMI device.
   */
  private fun isDigitalSink(device: AudioDeviceInfo): Boolean =
    device.type == AudioDeviceInfo.TYPE_HDMI ||
      device.type == AudioDeviceInfo.TYPE_HDMI_ARC ||
      device.type == AudioDeviceInfo.TYPE_LINE_DIGITAL ||
      (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        device.type == AudioDeviceInfo.TYPE_HDMI_EARC)

  private fun revalidateAudioOutput() {
    if (!mpvCreated || audioOutput.value != AudioOutputMode.Passthrough) return
    if (supportedSpdifCodecs().isNotEmpty()) return
    audioOutput.value = AudioOutputMode.Decode
    applyAudioOutput(AudioOutputMode.Decode)
    reselectAudioTrack()
    showOsdMessage(getString(R.string.player_audio_route_decode))
  }

  private fun syncAudioDeviceCallback(register: Boolean) {
    if (register == audioDeviceCallbackRegistered) return
    if (register) {
      val registered = runCatching {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
      }.isSuccess
      audioDeviceCallbackRegistered = registered
      if (registered) revalidateAudioOutput()
    } else {
      runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
      audioDeviceCallbackRegistered = false
    }
  }

  /**
   * Makes a mid-file `audio-spdif` change take effect. mpv reads the option when
   * it builds the audio chain, so without this the switch would sit there doing
   * nothing until the next episode.
   *
   * Off and back on rather than a write of the current id: mpv's track-switch
   * handler returns early when the requested track is the one already playing, so
   * the obvious nudge is the one thing that does not work. The cost is a fraction
   * of a second of silence while the AO is reopened — and if the sink will not
   * take the bitstream, that silence is permanent until Decode is chosen again,
   * which is what the OSD line and the menu's footnote are for.
   */
  private fun reselectAudioTrack() {
    val aid = MpvTracks.selected(tracks.value, TrackKind.Audio)?.id ?: return
    MPVLib.setPropertyString("aid", "no")
    MPVLib.setPropertyString("aid", aid.toString())
  }

  private fun stepAudioDelay(steps: Int) {
    if (!mpvCreated) return
    val next = DelaySteps.stepped(audioDelaySec.doubleValue, steps)
    audioDelaySec.doubleValue = next
    MPVLib.setPropertyDouble("audio-delay", next)
  }

  private fun stepSubtitleDelay(steps: Int) {
    if (!mpvCreated) return
    val next = DelaySteps.stepped(subtitleDelaySec.doubleValue, steps)
    subtitleDelaySec.doubleValue = next
    MPVLib.setPropertyDouble("sub-delay", next)
  }

  /**
   * Reads the source frame rate from mpv and retunes the display refresh rate to
   * match, so 23.976/24/25/30 fps content plays with even cadence instead of the
   * uneven 3:2 pulldown you get forcing film onto a fixed 60Hz panel.
   *
   * The read runs on the worker — it lands at FILE_LOADED, where mpv is at its
   * busiest opening the stream — and only the display-mode change comes back to
   * the main thread, which is where the window and surface calls must happen.
   */
  private fun matchDisplayToContentFrameRate(attempt: Int = 0) {
    if (!mpvCreated) return
    readOffMain({ readContentFps() }) { fps ->
      if (fps > 0f) {
        contentFps = fps
        if (!paused.value) applyDisplayFrameRateVote()
        refreshTracks()
      } else if (attempt + 1 < FRAME_RATE_READ_ATTEMPTS) {
        val generation = loadGeneration
        val retry = Runnable {
          frameRateRetryRunnable = null
          if (generation == loadGeneration && playbackStarted) {
            matchDisplayToContentFrameRate(attempt + 1)
          }
        }
        frameRateRetryRunnable?.let(mainHandler::removeCallbacks)
        frameRateRetryRunnable = retry
        mainHandler.postDelayed(retry, FRAME_RATE_READ_RETRY_MS)
      }
    }
  }

  /** Called on the worker thread. */
  private fun readContentFps(): Float {
    val measured = MPVLib.getPropertyString("estimated-vf-fps")?.toFloatOrNull()
    if (measured != null && measured > 0f) return measured
    return MPVLib.getPropertyString("container-fps")?.toFloatOrNull()?.takeIf { it > 0f } ?: 0f
  }

  /**
   * Votes through the Surface only. The previous code then unconditionally forced a display mode,
   * overriding both this vote and the viewer's system match-content preference and allowing a
   * multi-second black screen. On Android 12+ only seamless changes are requested.
   */
  private fun applyDisplayFrameRateVote() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || paused.value || !playbackStarted) return
    val fps = PlaybackFrameRate.effective(contentFps, playbackSpeed.doubleValue)
    if (fps <= 0f) return
    playbackSurface?.let { surface ->
      runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          surface.setFrameRate(
            fps,
            Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
            Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
          )
        } else {
          surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        }
      }
    }
  }

  private fun clearDisplayFrameRateVote() {
    frameRateRetryRunnable?.let(mainHandler::removeCallbacks)
    frameRateRetryRunnable = null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      playbackSurface?.let { surface ->
        runCatching {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            surface.setFrameRate(
              0f,
              Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
              Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
            )
          } else {
            surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
          }
        }
      }
    }
    // Clear a mode preference left by an older activity implementation or a restored window.
    if (window.attributes.preferredDisplayModeId != 0) {
      window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
    }
  }

  @Suppress("DEPRECATION")
  private fun currentDisplay(): Display? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay

  /**
   * Puts a one-off line in the OSD and brings the OSD up to carry it. Cleared on a
   * timer rather than left up: it reports something that has already happened, and
   * a stale "added" under a track list that says otherwise is worse than nothing.
   */
  private fun showOsdMessage(text: String) {
    osdMessage.value = text
    val shownAt = SystemClock.uptimeMillis()
    osdMessageAtMs = shownAt
    mainHandler.postDelayed({
      if (osdMessageAtMs == shownAt) osdMessage.value = ""
    }, OSD_MESSAGE_MS)
    showOsd()
  }

  /**
   * Brings the controls up and restarts the auto-hide. [row] null leaves the
   * focused row where it was, which is what every incidental caller wants - a
   * seek, a track change, an OSD message all mean "and show this", not "and move
   * the viewer's place in the controls".
   */
  private fun showOsd(row: OsdRow? = null) {
    if (row != null) osdRow.value = row
    // Counted on the transition only: showOsd is called by a dozen events - a pause, a seek, a
    // track change - and counting every one would burn the key legend's five appearances inside
    // the first film.
    if (!osdVisible.value) noteOsdOpened()
    osdVisible.value = true
    armOsdHide()
  }

  /**
   * Advances the count of panel opens, until it saturates.
   *
   * Persisted rather than kept per-session: teaching a viewer the D-pad map is a once-per-install
   * job, and a legend that came back every time they relaunched would never stop being noise.
   */
  private fun noteOsdOpened() {
    val seen = osdOpens.intValue
    if (!OsdHintPolicy.showsHint(seen)) return
    val next = OsdHintPolicy.advance(seen)
    osdOpens.intValue = next
    lifecycleScope.launch { runCatching { playerPrefsStore.setOsdOpens(next) } }
  }

  /**
   * (Re)starts the countdown to the controls hiding themselves.
   *
   * Re-arms rather than gives up when the moment is wrong, so the OSD comes down
   * on its own once the reason is gone: a pause the viewer never explicitly ended
   * — resumed from a media key, or by audio focus coming back — used to leave the
   * panel over the picture for the rest of the film.
   */
  private fun armOsdHide() {
    // Longer once the panel holds focus: five seconds is the right life for a bar
    // that only reports the position, and far too short for a row of buttons a
    // viewer is reading before choosing one. Losing the panel mid-decision is
    // worse than it lingering, because the next press then seeks the film instead
    // of moving the highlight.
    val timeout = if (osdHasFocus) OSD_FOCUSED_TIMEOUT_MS else OSD_TIMEOUT_MS
    val hideAt = SystemClock.uptimeMillis() + timeout
    osdHideAtMs = hideAt
    mainHandler.postDelayed({
      if (osdHideAtMs != hideAt) return@postDelayed
      // A paused film says so nowhere else on screen; the menu is a deliberate
      // stop, and the position under it is part of what makes a track choice make
      // sense; and the up-next card owns the screen once the video has ended.
      if (paused.value || menuVisible.value || upNextCard.value != null) {
        armOsdHide()
        return@postDelayed
      }
      hideOsd()
    }, timeout)
  }

  /**
   * Records focus arriving in or leaving the controls, and re-times the auto-hide
   * to match: focus lands a frame or two *after* whatever brought the panel up
   * armed the short countdown, so without this a press of UP would put the
   * buttons on screen and then take them away five seconds later however plainly
   * the highlight said the viewer was using them.
   */
  private fun setOsdFocus(hasFocus: Boolean) {
    if (osdHasFocus == hasFocus) return
    osdHasFocus = hasFocus
    if (osdVisible.value) armOsdHide()
  }

  /**
   * Puts the controls away. Clears [osdHasFocus] with them: the focused node goes
   * with the panel, and leaving the flag set would have the activity swallow
   * D-pad presses that now have nothing to act on.
   */
  private fun hideOsd() {
    osdHideAtMs = Long.MIN_VALUE
    osdVisible.value = false
    osdHasFocus = false
    // Back to the bar, so the controls always reopen on the row where LEFT/RIGHT
    // seek. Coming back up on the buttons because that is where they were last
    // left would silently change what the viewer's next press does.
    osdRow.value = OsdRow.Scrub
  }

  /**
   * Leaves the player for good. [markFinished] is the caller's verdict that the
   * video ran to its end; a viewer who backs out at the closing credits gets the
   * same verdict from [reachedEndOfFile], so watching to the end counts either
   * way, while backing out anywhere before it stays resumable.
   */
  private fun finishPlayback(markFinished: Boolean) {
    if (finishing) return
    finishing = true
    // Before the verdict below: a periodic upsert must not land after a Finished
    // save and put back the resume point it just cleared.
    mainHandler.removeCallbacks(progressSaveRunnable)
    mainHandler.removeCallbacks(upNextTickRunnable)
    clearDisplayFrameRateVote()
    restoreDuckedVolume()
    abandonAudioFocus()
    syncPlayingState()
    val finished = markFinished || reachedEndOfFile()
    saveWatchState(if (finished) SaveReason.Finished else SaveReason.Stopped)
    finish()
  }

  /**
   * The position a resume entry should record. An outstanding seek — still
   * waiting on the coalesce window, or handed to mpv and not yet settled — is
   * where the viewer asked to be, so it wins over `time-pos`, which still
   * reports the pre-seek position. Backing out mid-seek used to save that stale
   * position and resume the next session before the seek.
   */
  private fun resumePositionSec(): Double = seeker.previewSec ?: timePosSec.doubleValue

  /**
   * Whether a periodic save is worth arming: somewhere to write it, mpv alive and
   * not on its way out, a stream that actually loaded, and a position that is
   * still moving.
   */
  private fun progressSaveAllowed(): Boolean =
    watchKey.isNotBlank() && tmdbId != 0 && transportAllowed() && playbackStarted && !paused.value

  /**
   * (Re)arms the periodic save, or leaves it off when playback is not
   * progressing. Idempotent, so it can be called from every state change that
   * might have started or stopped progress.
   */
  private fun scheduleProgressSave() {
    mainHandler.removeCallbacks(progressSaveRunnable)
    if (!progressSaveAllowed()) return
    val positionMs = (resumePositionSec() * 1000).toLong()
    // Below the minimum a save is dropped, so aim the first tick just past the
    // threshold rather than spending a whole interval on a position that cannot
    // be written yet — otherwise a fresh start has no entry for 30s longer than
    // it needs to, and that is exactly the window this is here to close.
    val delayMs = if (positionMs > MIN_SAVE_MS) {
      PROGRESS_SAVE_INTERVAL_MS
    } else {
      (MIN_SAVE_MS - positionMs + PROGRESS_SAVE_LEAD_MS).coerceAtMost(PROGRESS_SAVE_INTERVAL_MS)
    }
    mainHandler.postDelayed(progressSaveRunnable, delayMs)
  }

  /**
   * Why the watch state is being written, which is what decides whether the
   * resume entry may be dropped:
   *
   *  - [Progress]: a periodic tick while the film is running, so a resume point
   *    exists even if the process never gets to run any exit code. Never removes.
   *  - [Paused]: the activity lost the foreground, which on a TV is as likely to
   *    be a voice-search or notification overlay as a real exit. Never removes:
   *    an overlay at 96% of a film used to wipe Continue Watching out from under
   *    a viewer who was still watching it.
   *  - [Stopped]: playback ended before the end of the video — the viewer backed
   *    out, the stream died, or a truncated file ran out of data. Keeps the
   *    position so the next session resumes there.
   *  - [Finished]: the video crossed the watched threshold, so the record becomes
   *    a watched one: no resume position, but still a record. Deleting it was what
   *    left a finished episode indistinguishable from one nobody had opened.
   */
  private enum class SaveReason { Progress, Paused, Stopped, Finished }

  private fun saveWatchState(reason: SaveReason) {
    if (watchKey.isBlank() || tmdbId == 0) return
    val positionMs = (resumePositionSec() * 1000).toLong()
    val durationMs = (durationSec.doubleValue * 1000).toLong()
    val store = watchStore
    val key = watchKey
    // Read here, not inside the coroutine: these saves run while the activity is
    // finishing, and touching it after onDestroy is what this avoids.
    val appContext = applicationContext
    val entry = WatchEntry(
      key = key,
      tmdbId = tmdbId,
      mediaType = mediaType,
      title = title,
      posterUrl = posterUrl,
      season = season,
      episode = episode,
      positionMs = positionMs,
      durationMs = durationMs,
      updatedAtMs = System.currentTimeMillis(),
    )
    // [endHandled] and not the reason alone: the position is still sitting at the
    // end of the video, so a Paused save from an overlay - or the Stopped save the
    // way out takes - would otherwise put a finished episode back in Continue
    // Watching at 100%.
    val finished = reason == SaveReason.Finished || endHandled
    val resetRequested = resumeResetRequested
    if (!finished && positionMs > MIN_SAVE_MS) resumeResetRequested = false
    // Deliberately not lifecycleScope: the exit saves run as the player is
    // finishing, and a cancelled write means the resume position is lost.
    persistenceScope.launch(start = CoroutineStart.UNDISPATCHED) {
      watchWriteMutex.withLock {
        if (finished) {
          // The threshold was crossed, so there is nothing left to resume - only
          // something to remember having watched.
          store.upsert(entry.copy(positionMs = 0, watchedAtMs = entry.updatedAtMs))
        } else if (positionMs > MIN_SAVE_MS) {
          store.upsert(entry)
        } else if (resetRequested) {
          // Explicit Restart is not a noisy two-second first play: it must replace an older
          // resume/watched record even though the new position is below the usual threshold.
          store.upsert(entry.copy(positionMs = 0, watchedAtMs = null))
        } else {
          return@withLock
        }
        // Keep the TV home's Watch Next row in step with what was just written.
        // Forced on the saves that end a session so a finished episode leaves the
        // home screen immediately; the periodic ticks go through the throttle so a
        // running film does not rewrite the provider every 30 seconds.
        WatchNextSync.publish(appContext, force = reason != SaveReason.Progress)
      }
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putLong(STATE_POSITION_MS, (resumePositionSec() * 1000).toLong())
    outState.putBoolean(STATE_RESUME_RESET_REQUESTED, resumeResetRequested)
  }

  override fun onPause() {
    if (!finishing) saveWatchState(SaveReason.Paused)
    super.onPause()
  }

  override fun onStop() {
    activityStarted = false
    // Remove this activity from the platform's media-button candidates before
    // any delayed callback can ask it to play again in the background.
    mediaSession?.isActive = false
    syncAudioDeviceCallback(register = false)
    // A countdown must not start an episode into an empty room, but the card stays
    // up: coming back to the offer is exactly what a viewer left it for.
    freezeUpNextCountdown()
    // Pause on onStop rather than onPause: onPause also fires for transient
    // overlays, where stopping playback is just an annoyance. Deliberately not
    // marked as a focus-loss pause: a player the viewer left in the background
    // must not start playing again on its own when focus comes back.
    pausePlayback()
    clearDisplayFrameRateVote()
    abandonAudioFocus()
    super.onStop()
  }

  override fun onStart() {
    super.onStart()
    activityStarted = true
    syncAudioDeviceCallback(register = true)
    mediaSession?.isActive = playbackStarted
    publishPlaybackState()
    // Came back to a stream we paused on the way out; surface the OSD so the
    // paused state is visible instead of looking like a frozen picture.
    if (mpvCreated && fileLoaded) {
      showOsd()
      if (playbackStarted && upNextTarget.value == null && !upNextLookupIssued) prefetchUpNext()
    }
  }

  override fun onDestroy() {
    // A never-flushed seek dies with these callbacks, which is only safe because
    // the save reads the target off [seeker] rather than off the runnable: both
    // save paths (finishPlayback and onPause) run before this point.
    if (!finishing && seeker.hasPendingPress) saveWatchState(SaveReason.Paused)
    destroying = true
    mainHandler.removeCallbacksAndMessages(null)
    syncAudioDeviceCallback(register = false)
    // Before mpvCreated goes false, which is what [syncPlayingState] reads.
    syncNoisyReceiver(playing = false)
    clearDisplayFrameRateVote()
    releaseMediaSession()
    // Before mpv goes: whatever was playing before this film is waiting on it.
    abandonAudioFocus()
    if (mpvCreated) {
      // Nothing new may reach the core. An in-flight read still owns [mpvLock]; destruction always
      // continues on a background thread because vendor JNI gives MPVLib.destroy() no useful upper
      // latency bound and Activity teardown must never turn BACK into an ANR.
      stopMpvWorker()
      discardQueuedSubtitleFiles()
      mpvAlive = false
      val lease = mpvLease
      val destroyOwnedCore: () -> Unit = if (mpvInitializationIncomplete) {
        ::destroyMpvCoreAfterFailedInitialization
      } else {
        ::destroyMpvCore
      }
      var retirementStarted = lease != null &&
        MpvCoreCoordinator.destroyAsync(lease, destroy = destroyOwnedCore)
      if (!retirementStarted && lease != null) {
        // Thread creation failed before the coordinator handed ownership off. There is no later
        // lifecycle callback to retry from, so the exceptional fallback retires under the same
        // lock now. A throwing destroy deliberately leaves the lease owned, preventing a second
        // singleton from being created over native state whose fate is unknown.
        NebulaDiagnostics.record("player", "async native destroy unavailable; using fallback")
        retirementStarted = runCatching {
          MpvCoreCoordinator.destroyBlocking(lease, destroy = destroyOwnedCore)
        }.onFailure { failure ->
          NebulaDiagnostics.record(
            "player",
            "native destroy failed: ${failure.javaClass.simpleName}",
          )
        }.getOrDefault(false)
      } else if (lease == null) {
        NebulaDiagnostics.record("player", "native core had no ownership lease")
      }
      mpvCreated = false
      if (retirementStarted) mpvLease = null
    } else {
      mpvLease?.let(MpvCoreCoordinator::abandon)
      mpvLease = null
    }
    super.onDestroy()
  }

  /** Called exactly once while [mpvLock] is held. */
  private fun destroyMpvCore() {
    MPVLib.removeObserver(observer)
    MPVLib.removeLogObserver(logObserver)
    MPVLib.destroy()
  }

  /**
   * Best-effort cleanup for a partially initialized binding.
   *
   * Observer registration and even `MPVLib.create()` itself may be the failing operation, so each
   * call must stand alone. Observer-removal failures are secondary, but `destroy()` must propagate:
   * only its successful return lets the coordinator prove the singleton is gone and release the
   * lease for a replacement.
   */
  private fun destroyMpvCoreAfterFailedInitialization() {
    runCatching { MPVLib.removeObserver(observer) }
    runCatching { MPVLib.removeLogObserver(logObserver) }
    MPVLib.destroy()
  }

  private fun formatTime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
  }

  /** How far a pending seek has travelled, which is what a scrub is aiming with. */
  private fun formatSignedTime(deltaSec: Double): String {
    val sign = if (deltaSec < 0) "-" else "+"
    return sign + formatTime(kotlin.math.abs(deltaSec))
  }

  /**
   * Held rather than derived in the composition because [season] and [episode] are
   * plain fields the binge loop rewrites, and a plain field cannot tell Compose it
   * has changed - the badge used to keep the previous episode's number for the
   * whole of the next one.
   */
  private fun syncEpisodeLabel() {
    episodeLabel.value = season?.let { "S${it}E$episode" }
  }

  companion object {
    /**
     * How long the controls stay up with nobody pressing anything. Longer than the
     * old four seconds because they are now something to read and walk around
     * rather than a bar that flashed by.
     */
    private const val OSD_TIMEOUT_MS = 5_000L

    /** The auto-hide once the panel holds focus; see [armOsdHide]. */
    private const val OSD_FOCUSED_TIMEOUT_MS = 15_000L
    private const val SEEK_DEBOUNCE_MS = 350L
    private const val SEEK_REPEAT_MIN_MS = 120L
    private const val SEEK_SETTLE_TIMEOUT_MS = 15_000L

    /**
     * How close to the end a seek may land. Shared with the watched verdict on
     * purpose: a seek that stops short of the same margin cannot accidentally
     * finish the video, and one that crosses it means to.
     */
    private const val END_GUARD_SEC = WatchedThreshold.END_GUARD_SEC

    /** How often the up-next countdown redraws; see [upNextTickRunnable]. */
    private const val UP_NEXT_TICK_MS = 250L
    private const val UP_NEXT_LOOKUP_ATTEMPTS = 3
    private const val UP_NEXT_LOOKUP_RETRY_MS = 30_000L
    private const val FRAME_RATE_READ_ATTEMPTS = 3
    private const val FRAME_RATE_READ_RETRY_MS = 1_000L

    /** Below this, there is nothing worth remembering as a resume point. */
    private const val MIN_SAVE_MS = 10_000L

    /**
     * How often the resume position is written while playback runs. This is the
     * only save an OOM kill, a firmware crash or a pulled plug cannot skip, so
     * the interval is the most progress a viewer can lose; half a minute against
     * one small DataStore write off the main thread is a fair trade.
     */
    private const val PROGRESS_SAVE_INTERVAL_MS = 30_000L

    /**
     * Margin on the first tick, which is aimed at [MIN_SAVE_MS] rather than a
     * whole interval away. Without it a tick can land a few milliseconds under
     * the threshold, get dropped, and push the first save out by a full interval.
     */
    private const val PROGRESS_SAVE_LEAD_MS = 1_000L

    /**
     * How long a stream gets to produce its first frame. Generous on purpose: a
     * torrent still gathering peers can take a while, and a false alarm here
     * would kill a stream that was about to play.
     */
    private const val LOAD_TIMEOUT_MS = 60_000L

    /**
     * Grace after an end-of-file that arrived before the stream ever loaded. mpv
     * also ends a file to follow a playlist redirect, and the entry it moves to
     * raises START_FILE within milliseconds, which re-arms the full window.
     */
    private const val END_FILE_GRACE_MS = 1_500L

    /**
     * How long playback may sit waiting on its cache before the stream is called
     * dead. Well clear of the worst honest rebuffer on a slow debrid host, and
     * far short of the forever the spinner used to turn for. Not shortened for
     * being "obviously" stuck: a torrent re-seeking to a rare piece can take a
     * good half minute to produce the next byte, and killing that would be worse
     * than the wait.
     */
    private const val STALL_TIMEOUT_MS = 45_000L

    /**
     * How long the player may show a still frame before it stops keeping the
     * screen awake — a paused film, or the failure panel. Long enough to survive
     * a trip to the kitchen, short enough to leave the panel's own screensaver
     * and burn-in protection a chance to run.
     */
    private const val IDLE_SCREEN_ON_MS = 5 * 60_000L

    private const val NO_SEEK = -1.0

    /**
     * How long a track-list read waits before running, so stepping through
     * subtitle or audio tracks costs one fetch-and-parse rather than one per
     * press. Short enough that the OSD line still lands with the press.
     */
    private const val TRACK_INFO_DEBOUNCE_MS = 150L

    /** How long a one-off OSD line stays up; see [showOsdMessage]. */
    private const val OSD_MESSAGE_MS = 5_000L

    private const val MAX_SUBTITLE_BYTES = 5L * 1024 * 1024
    private const val MAX_SUBTITLE_REDIRECTS = 5
    private val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub")
    private val SUBTITLE_HTTP_CLIENT = SharedHttpClient.client.newBuilder()
      .dns(PublicOnlyDns())
      .followRedirects(false)
      .followSslRedirects(false)
      .build()

    /**
     * How long the stream waits for the stored audio/subtitle preferences before
     * opening without them. A DataStore read of three strings is a few
     * milliseconds and normally lands well before the surface does, so this only
     * exists so that a read that somehow never completes costs the viewer their
     * preferred language rather than the whole session.
     */
    private const val PREFS_READ_TIMEOUT_MS = 1_500L

    /** Bounded wait for a previous activity's global libmpv teardown. */
    private const val MPV_CREATE_WAIT_MS = 2_000L

    /** How far mpv's volume drops for a duckable focus loss. */
    private const val DUCK_VOLUME_FRACTION = 0.3

    private const val MEDIA_SESSION_TAG = "StremioTvPlayer"

    private val TRANSPORT_ACTIONS = PlaybackState.ACTION_PLAY or
      PlaybackState.ACTION_PAUSE or
      PlaybackState.ACTION_PLAY_PAUSE or
      PlaybackState.ACTION_STOP or
      PlaybackState.ACTION_SEEK_TO or
      PlaybackState.ACTION_FAST_FORWARD or
      PlaybackState.ACTION_REWIND

    /**
     * The keys whose auto-repeat is meaningful, because [SeekCoalescer] turns a
     * held key into one seek rather than one per repeat. Every other key the
     * player handles ignores repeats outright.
     */
    private val SEEK_KEYS = setOf(
      KeyEvent.KEYCODE_DPAD_LEFT,
      KeyEvent.KEYCODE_DPAD_RIGHT,
      KeyEvent.KEYCODE_MEDIA_REWIND,
      KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
    )

    /**
     * The keys the controls claim while they hold focus, so that whatever the
     * panel's own map and Compose's focus search both declined dies there instead
     * of falling through to the transport underneath.
     */
    private val DPAD_KEYS = setOf(
      KeyEvent.KEYCODE_DPAD_LEFT,
      KeyEvent.KEYCODE_DPAD_RIGHT,
      KeyEvent.KEYCODE_DPAD_UP,
      KeyEvent.KEYCODE_DPAD_DOWN,
      KeyEvent.KEYCODE_DPAD_CENTER,
      KeyEvent.KEYCODE_ENTER,
    )

    /** What accepts the up-next offer, when the card is up. */
    private val UP_NEXT_PLAY_KEYS = setOf(
      KeyEvent.KEYCODE_DPAD_CENTER,
      KeyEvent.KEYCODE_ENTER,
      KeyEvent.KEYCODE_MEDIA_PLAY,
      KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
      KeyEvent.KEYCODE_MEDIA_NEXT,
    )

    /** What asks a dead stream to be tried again, when the panel is up. */
    private val RETRY_KEYS = setOf(
      KeyEvent.KEYCODE_DPAD_CENTER,
      KeyEvent.KEYCODE_ENTER,
      KeyEvent.KEYCODE_MEDIA_PLAY,
      KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    )

    /**
     * Every key the player treats as transport control, listed so the dead-stream
     * state can swallow them rather than let them reach the MediaSession.
     */
    private val TRANSPORT_KEYS = setOf(
      KeyEvent.KEYCODE_DPAD_CENTER,
      KeyEvent.KEYCODE_ENTER,
      KeyEvent.KEYCODE_DPAD_LEFT,
      KeyEvent.KEYCODE_DPAD_RIGHT,
      KeyEvent.KEYCODE_DPAD_UP,
      KeyEvent.KEYCODE_DPAD_DOWN,
      KeyEvent.KEYCODE_MEDIA_PLAY,
      KeyEvent.KEYCODE_MEDIA_PAUSE,
      KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
      KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
      KeyEvent.KEYCODE_MEDIA_REWIND,
      KeyEvent.KEYCODE_MEDIA_NEXT,
      KeyEvent.KEYCODE_MENU,
      KeyEvent.KEYCODE_CAPTIONS,
      KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK,
    )

    private const val STATE_POSITION_MS = "positionMs"
    private const val STATE_RESUME_RESET_REQUESTED = "resumeResetRequested"
    private const val EXTRA_URL = "url"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_WATCH_KEY = "watchKey"
    private const val EXTRA_TMDB_ID = "tmdbId"
    private const val EXTRA_MEDIA_TYPE = "mediaType"
    private const val EXTRA_POSTER = "posterUrl"
    private const val EXTRA_SEASON = "season"
    private const val EXTRA_EPISODE = "episode"
    private const val EXTRA_RESUME_MS = "resumeMs"
    private const val EXTRA_IMDB_ID = "imdbId"
    private const val EXTRA_BINGE_GROUP = "bingeGroup"
    private const val EXTRA_HEADER_NAMES = "requestHeaderNames"
    private const val EXTRA_HEADER_VALUES = "requestHeaderValues"
    private const val EXTRA_SUBTITLE_URLS = "streamSubtitleUrls"
    private const val EXTRA_SUBTITLE_LANGUAGES = "streamSubtitleLanguages"
    private const val EXTRA_SUBTITLE_IDS = "streamSubtitleIds"
    private const val EXTRA_VIDEO_HASH = "streamVideoHash"
    private const val EXTRA_FILENAME = "streamFilename"
    private const val EXTRA_VIDEO_SIZE = "streamVideoSize"

    /**
     * The stream list the player could not pick from for the next episode, handed
     * back so the caller can put the viewer on the picker instead of on the episode
     * they have just watched. A [Screen.Streams].
     */
    const val EXTRA_RESULT_STREAMS = "resultStreams"

    fun watchKeyFor(tmdbId: Int, season: Int?, episode: Int?): String {
      return if (season != null) "episode:$tmdbId:$season:$episode" else "movie:$tmdbId"
    }

    fun watchKeyFor(screen: Screen.Streams): String =
      watchKeyFor(screen.tmdbId, screen.season, screen.episode)

    fun createIntent(
      context: Context,
      screen: Screen.Streams,
      stream: AddonStream,
      resumeMs: Long = 0L,
    ): Intent {
      val watchKey = watchKeyFor(screen)
      return Intent(context, MpvPlayerActivity::class.java).apply {
        putExtra(EXTRA_URL, stream.url)
        putExtra(EXTRA_TITLE, screen.title)
        putExtra(EXTRA_WATCH_KEY, watchKey)
        putExtra(EXTRA_TMDB_ID, screen.tmdbId)
        putExtra(EXTRA_MEDIA_TYPE, if (screen.mediaType.name == "Show") "show" else "movie")
        putExtra(EXTRA_POSTER, screen.posterUrl)
        screen.season?.let { putExtra(EXTRA_SEASON, it) }
        screen.episode?.let { putExtra(EXTRA_EPISODE, it) }
        putExtra(EXTRA_RESUME_MS, resumeMs)
        // Both only mean anything to the binge loop: the id the next episode's
        // streams are asked for by, and the release to prefer among them.
        putExtra(EXTRA_IMDB_ID, screen.imdbId)
        stream.bingeGroup?.let { putExtra(EXTRA_BINGE_GROUP, it) }
        val headers = StreamRequestHeaders.sanitize(
          stream.behaviorHints?.proxyHeaders?.request.orEmpty(),
        )
        putStringArrayListExtra(EXTRA_HEADER_NAMES, ArrayList(headers.keys))
        putStringArrayListExtra(EXTRA_HEADER_VALUES, ArrayList(headers.values))
        val subtitles = EmbeddedSubtitles.sanitize(stream.subtitles)
        putStringArrayListExtra(
          EXTRA_SUBTITLE_URLS,
          ArrayList(subtitles.map { it.url }),
        )
        putStringArrayListExtra(
          EXTRA_SUBTITLE_LANGUAGES,
          ArrayList(subtitles.map { it.lang.orEmpty() }),
        )
        putStringArrayListExtra(
          EXTRA_SUBTITLE_IDS,
          ArrayList(subtitles.map { it.id.orEmpty() }),
        )
        stream.behaviorHints?.videoHash?.let { putExtra(EXTRA_VIDEO_HASH, it) }
        stream.behaviorHints?.filename?.let { putExtra(EXTRA_FILENAME, it) }
        stream.behaviorHints?.videoSize?.let { putExtra(EXTRA_VIDEO_SIZE, it) }
      }
    }
  }
}

private sealed interface SubtitleDownloadHop {
  data class Redirect(val url: String) : SubtitleDownloadHop
  data class Complete(val file: File) : SubtitleDownloadHop
}

/** Result-catching for suspend work that never turns lifecycle cancellation into a normal error. */
private suspend fun <T> suspendRunCatching(block: suspend () -> T): Result<T> =
  try {
    Result.success(block())
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (error: Throwable) {
    Result.failure(error)
  }

/**
 * Which row of the controls the D-pad is in.
 *
 * Tracked rather than inferred from focus because it is also where the controls
 * should open: UP from a bare picture goes straight to the buttons, DOWN to the
 * bar, and both have to be decided before anything is on screen to focus.
 */
private enum class OsdRow { Scrub, Buttons }

/** Dark ink for the bright violet a focused control fills with. */

/** Fixed either side of the scrub bar so a changing digit never nudges the bar. */
private val TIME_WIDTH = 96.dp

private val SCRUB_BAR_HEIGHT = 8.dp

/** Wide enough for an addon's error text, narrow enough to read across a room. */
private const val ERROR_PANEL_WIDTH_FRACTION = 0.62f
