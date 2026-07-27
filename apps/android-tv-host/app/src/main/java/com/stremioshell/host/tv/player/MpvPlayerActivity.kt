package com.stremioshell.host.tv.player

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.stremioshell.host.tv.data.PlayerPrefs
import com.stremioshell.host.tv.data.PlayerPrefsStore
import com.stremioshell.host.tv.data.WatchEntry
import com.stremioshell.host.tv.data.WatchStateStore
import com.stremioshell.host.tv.data.addon.AddonStream
import com.stremioshell.host.tv.data.persistenceScope
import com.stremioshell.host.tv.ui.Screen
import com.stremioshell.host.tv.ui.theme.StremioTvTheme
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

class MpvPlayerActivity : ComponentActivity() {
  private var mpvCreated = false
  private lateinit var watchStore: WatchStateStore
  private lateinit var playerPrefsStore: PlayerPrefsStore
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
   * Held across every property read on [mpvWorker] and across [MPVLib.destroy].
   * A read already inside the JNI call when the core is torn down is exactly the
   * crash this exists to make impossible: the two can never overlap.
   */
  private val mpvLock = Any()

  /**
   * Whether the core is alive. Checked on the worker thread under [mpvLock]
   * immediately before a JNI call, and cleared on the main thread under the same
   * lock just before the core goes.
   */
  @Volatile
  private var mpvAlive = false

  // The mpv render surface and the detected content frame rate, used to retune
  // the display refresh rate so film/PAL content plays without pulldown judder.
  private var playbackSurface: Surface? = null

  /** Written on the main thread, read by the worker when it builds the OSD line. */
  @Volatile
  private var contentFps = 0f

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

  /**
   * Last error/fatal line mpv logged, used as the reason shown on the failure
   * overlay. Written from mpv's event thread, read on the main thread.
   */
  @Volatile
  private var lastErrorMessage: String? = null

  private var url = ""
  private var title = ""
  private var watchKey = ""
  private var tmdbId = 0
  private var mediaType = "movie"
  private var posterUrl: String? = null
  private var season: Int? = null
  private var episode: Int? = null
  private var resumeMs = 0L
  private var finishing = false

  /**
   * Audio focus is this activity's to manage: mpv's `ao=audiotrack` opens an
   * AudioTrack directly and never asks for focus, so without this a film starts
   * on top of whatever music was already playing, and keeps playing at full
   * volume under an Assistant answer or an alarm.
   */
  private val audioManager: AudioManager by lazy { getSystemService(AudioManager::class.java) }
  private var audioFocusRequest: AudioFocusRequest? = null
  private var hasAudioFocus = false

  /**
   * Whether the focus listener is the reason playback is paused, and therefore
   * owes a resume once focus comes back. Never set for a pause the viewer asked
   * for: returning from a phone call must not restart a film they had paused.
   */
  private var pausedForFocusLoss = false

  /** mpv's volume before ducking, or null when not ducked. */
  private var volumeBeforeDuck: Int? = null

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

  /** mpv's playback speed, so the OSD's countdown and end time follow it. */
  private val playbackSpeed = mutableDoubleStateOf(1.0)

  private val osdVisible = mutableStateOf(true)
  private val trackInfo = mutableStateOf("")
  private var osdHideAtMs = 0L

  /** mpv's track list as last read, backing both the OSD line and the menu. */
  private val tracks = mutableStateOf<List<MpvTrack>>(emptyList())

  // The in-player menu: which section is showing, and the option values it edits.
  private val menuVisible = mutableStateOf(false)
  private val menuTab = mutableStateOf(PlayerMenuTab.Audio)
  private val subtitleSize = mutableStateOf(SubtitleSize.DEFAULT)
  private val audioDelaySec = mutableDoubleStateOf(0.0)
  private val subtitleDelaySec = mutableDoubleStateOf(0.0)

  /**
   * Whether the next track list to arrive is the result of the audio-cycle key,
   * and should therefore update the stored language preference the same way a
   * pick from the menu does. Nothing else may: the list is also read at
   * FILE_LOADED, and learning a preference from whatever the container happened
   * to default to would overwrite the viewer's own choice.
   */
  private var audioCyclePending = false

  /**
   * Down time of the BACK press that closed the menu, so its own repeats cannot
   * be read as a second press asking to leave the player.
   */
  private var menuCloseBackDownTime = Long.MIN_VALUE

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
  private val loadFailedRunnable = Runnable {
    if (!playbackStarted) showPlaybackError(lastErrorMessage ?: DEFAULT_LOAD_ERROR)
  }

  /**
   * Fires when a stream that did start playing has been waiting on its cache for
   * [STALL_TIMEOUT_MS] without a byte arriving. mpv's own recovery cannot reach
   * this one: `reconnect` needs the connection to actually drop, and a debrid
   * host that keeps the socket open and simply stops sending never drops it, so
   * the load watchdog having disarmed at FILE_LOADED used to leave the spinner
   * turning for the rest of the evening.
   */
  private val stallRunnable = Runnable {
    if (!buffering.value || playbackError.value != null) return@Runnable
    // The viewer stopped where the data did; the same save a dropped stream gets.
    saveWatchState(SaveReason.Stopped)
    showPlaybackError(STALL_ERROR)
  }

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

  private val seeker = SeekCoalescer(
    endGuardSec = END_GUARD_SEC,
    repeatMinIntervalMs = SEEK_REPEAT_MIN_MS,
  )
  private val seekRunnable = Runnable { flushSeek() }

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

  private val observer = object : MPVLib.EventObserver {
    override fun eventProperty(property: String) {}
    override fun eventProperty(property: String, value: Long) {}
    override fun eventProperty(property: String, value: Double) {
      when (property) {
        "time-pos" -> {
          val whole = value.toLong()
          if (whole == lastPostedSecond) return
          lastPostedSecond = whole
          mainHandler.post { timePosSec.doubleValue = value }
        }
        "duration" -> mainHandler.post {
          durationSec.doubleValue = value
          publishMediaMetadata()
        }
        "speed" -> mainHandler.post { playbackSpeed.doubleValue = value }
      }
    }

    override fun eventProperty(property: String, value: Boolean) {
      mainHandler.post {
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
      when (eventId) {
        // A file has begun loading: the initial one, or the entry mpv redirected
        // to after resolving a playlist. Either way it gets a full window to
        // become playable before the watchdog calls it dead.
        MPVLib.MPV_EVENT_START_FILE -> mainHandler.post { armLoadWatchdog(LOAD_TIMEOUT_MS) }
        MPVLib.MPV_EVENT_FILE_LOADED -> mainHandler.post {
          playbackStarted = true
          mainHandler.removeCallbacks(loadFailedRunnable)
          // Startup noise (hwdec probing, codec fallbacks) must not be reported
          // later as the reason a mid-stream failure happened.
          lastErrorMessage = null
          buffering.value = false
          cancelStallWatchdog()
          refreshTracks()
          matchDisplayToContentFrameRate()
          // The stream is about to make noise, so take the speakers now. Denied
          // means another app owns them, and playing anyway would talk over it —
          // hold at a paused first frame instead and let the viewer decide.
          if (!requestAudioFocus()) pausePlayback()
          mediaSession?.isActive = true
          publishPlaybackState()
          // Playback is genuinely under way, so start recording where it gets to.
          scheduleProgressSave()
          syncPlayingState()
        }
        // Nothing else notices a dead stream: `keep-open=yes` leaves mpv idling
        // on a black frame, so without this a failed load or a fatal mid-stream
        // error spins under the busy indicator forever.
        MPVLib.MPV_EVENT_END_FILE -> mainHandler.post { onPlaybackEnded() }
        // Playback has actually resumed at the seek target, so the real
        // position is trustworthy again and the preview can go away. Restarts
        // also arrive from initial load, cache-stall recovery and earlier seeks,
        // so mirror whatever the coalescer keeps rather than blanking: a press
        // that landed since must stay on screen and still be flushed.
        MPVLib.MPV_EVENT_PLAYBACK_RESTART -> mainHandler.post {
          seeking.value = false
          seeker.settle()
          seekPreviewSec.doubleValue = seeker.previewSec ?: NO_SEEK
          lastPostedSecond = Long.MIN_VALUE
          publishPlaybackState()
        }
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
      if (level > MPVLib.MPV_LOG_LEVEL_ERROR) return
      val message = text.trim().take(MAX_ERROR_CHARS)
      if (message.isNotEmpty()) lastErrorMessage = message
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

    url = intent.getStringExtra(EXTRA_URL).orEmpty()
    title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
    watchKey = intent.getStringExtra(EXTRA_WATCH_KEY).orEmpty()
    tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, 0)
    mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE) ?: "movie"
    posterUrl = intent.getStringExtra(EXTRA_POSTER)
    season = intent.getIntExtra(EXTRA_SEASON, -1).takeIf { it >= 0 }
    episode = intent.getIntExtra(EXTRA_EPISODE, -1).takeIf { it >= 0 }
    // Prefer the live position over the intent's: if the activity is recreated
    // mid-playback, the intent still points at where this session started.
    resumeMs = savedInstanceState?.getLong(STATE_POSITION_MS, 0L)
      ?.takeIf { it > 0 }
      ?: intent.getLongExtra(EXTRA_RESUME_MS, 0L)

    if (url.isBlank()) {
      finish()
      return
    }

    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

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
    // Let the compositor know the source is HDR so an HDR-capable panel gets
    // the real signal instead of mpv tone-mapping it down to SDR.
    MPVLib.setOptionString("target-colorspace-hint", "yes")
    applyNetworkOptions()
    // The starting size, replaced by the stored one a moment later. Medium is 44,
    // which is the size the player used to give everyone.
    MPVLib.setOptionString("sub-font-size", SubtitleSize.DEFAULT.fontSize.toString())
    MPVLib.setOptionString("keep-open", "yes")
    MPVLib.setOptionString("force-window", "no")
    // Seek before the first frame instead of playing from 0:00 and jumping,
    // which buffers twice and looks like a glitch.
    if (resumeMs > MIN_RESUME_MS) {
      MPVLib.setOptionString("start", (resumeMs / 1000.0).toString())
    }
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

    loadPlayerPrefs()
    createMediaSession()

    setContent {
      StremioTvTheme {
        PlayerSurface()
      }
    }
    showOsd()
    // Arms the idle screen-on release: a stream that never opens leaves a
    // "Playback failed" panel that must not hold the panel awake either.
    syncPlayingState()
  }

  /**
   * Streams come from debrid/torrent resolvers over plain HTTP, where a stalled
   * or dropped connection is routine. Without reconnect options a single hiccup
   * ends playback for good, and a thin cache turns every wobble into a stall.
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
   * Reads the stored audio/subtitle languages and subtitle size, and hands them
   * to mpv before the file is opened.
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
    val size = SubtitleSize.fromStorage(prefs.subtitleSize)
    subtitleSize.value = size
    if (mpvCreated && !finishing) {
      // Options are properties once mpv is initialised, and these are read at
      // file open, so setting them here still lands ahead of `loadfile`.
      TrackPreferences.alangValue(prefs.audioLanguage)?.let {
        MPVLib.setPropertyString("alang", it)
      }
      TrackPreferences.slangValue(prefs.subtitleLanguage)?.let {
        MPVLib.setPropertyString("slang", it)
      }
      // `slang` has no way to say "none", so switched-off subtitles are carried
      // as a disabled track instead.
      if (TrackPreferences.subtitlesOff(prefs.subtitleLanguage)) {
        MPVLib.setPropertyString("sid", "no")
      }
      MPVLib.setPropertyString("sub-font-size", size.fontSize.toString())
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
    MPVLib.command(arrayOf("loadfile", url))
    // Some dead hosts accept the connection and then say nothing at all, so
    // there is no error to react to — only the absence of a first frame.
    armLoadWatchdog(LOAD_TIMEOUT_MS)
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
      finishPlayback(markFinished = true)
      return
    }
    // Keep the resume point: the viewer stopped where the stream died. The
    // MIN_SAVE_MS guard inside decides whether that is worth remembering.
    saveWatchState(SaveReason.Stopped)
    showPlaybackError(lastErrorMessage ?: DEFAULT_PLAYBACK_ERROR)
  }

  /**
   * Whether playback stopped at the actual end of the video, which is the only
   * thing that may mark it watched and drop the resume entry.
   *
   * Deliberately not mpv's `eof-reached`: debrid and torrent sources routinely
   * serve a truncated file whose container still claims the full runtime, so the
   * demuxer hits eof at, say, 55% and mpv raises the same flag it raises on a
   * real ending. Trusting it there wiped Continue Watching for a half-watched
   * film. The position against the duration is the check that can tell them
   * apart; with no duration reported nothing can be established, so such a
   * stream is always treated as stopped short and stays resumable.
   */
  private fun reachedEndOfFile(): Boolean {
    val duration = durationSec.doubleValue
    if (duration <= 0) return false
    val position = timePosSec.doubleValue
    // The absolute guard covers a duration that is a second or two out (VBR
    // estimates); the fraction covers coarser metadata, while still leaving a
    // file that stops well short of its claimed length resumable.
    return position >= duration - END_GUARD_SEC || position / duration >= END_FRACTION
  }

  private fun armLoadWatchdog(delayMs: Long) {
    mainHandler.removeCallbacks(loadFailedRunnable)
    if (!playbackStarted) mainHandler.postDelayed(loadFailedRunnable, delayMs)
  }

  /**
   * Starts the clock on a cache stall. Only meaningful once the stream has
   * played: before that the load watchdog owns the same failure, and arming both
   * would race to report it.
   */
  private fun armStallWatchdog() {
    mainHandler.removeCallbacks(stallRunnable)
    if (!playbackStarted || !transportAllowed()) return
    mainHandler.postDelayed(stallRunnable, STALL_TIMEOUT_MS)
  }

  private fun cancelStallWatchdog() {
    mainHandler.removeCallbacks(stallRunnable)
  }

  /**
   * Swaps the spinner for a readable failure. Deliberately does not finish the
   * activity: an instant bounce back to the stream list looks like a dropped
   * button press, so the reason stays on screen until the viewer leaves.
   */
  private fun showPlaybackError(reason: String) {
    if (finishing || playbackError.value != null) return
    mainHandler.removeCallbacks(loadFailedRunnable)
    mainHandler.removeCallbacks(seekRunnable)
    cancelStallWatchdog()
    // The position stops moving here, and [onPlaybackEnded] has already saved it.
    mainHandler.removeCallbacks(progressSaveRunnable)
    // Nothing left to pick a track from, and the failure panel needs the remote:
    // leaving the menu up would put a focus trap in front of Retry.
    menuVisible.value = false
    buffering.value = false
    seeking.value = false
    playbackError.value = reason
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
    if (!mpvCreated || finishing || playbackError.value == null) return

    // Where to come back in. Below the resume threshold the `start` option set at
    // load time still holds, which is the position this session was asked for:
    // exactly right for a stream that died before it ever produced a frame.
    val restartSec = resumePositionSec().takeIf { it * 1000 > MIN_RESUME_MS }
    if (restartSec != null) MPVLib.setPropertyString("start", restartSec.toString())

    // A seek issued against the file that just died cannot settle against the one
    // replacing it; `start` carries its target instead (see [resumePositionSec]).
    seeker.reset()
    seekPreviewSec.doubleValue = NO_SEEK
    lastPostedSecond = Long.MIN_VALUE

    playbackError.value = null
    lastErrorMessage = null
    playbackStarted = false
    buffering.value = true
    seeking.value = false

    MPVLib.command(arrayOf("loadfile", url, "replace"))
    // `keep-open=yes` left mpv paused on the dead file, and that pause survives
    // the reload; this is also where audio focus is taken back.
    playPlayback()
    armLoadWatchdog(LOAD_TIMEOUT_MS)
    publishPlaybackState()
    syncPlayingState()
    showOsd()
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
    }
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
      audioDelaySec = audioDelaySec.doubleValue,
      subtitleDelaySec = subtitleDelaySec.doubleValue,
    )
    val actions = remember {
      PlayerMenuActions(
        onTab = { menuTab.value = it },
        onSelectAudio = ::selectAudioTrack,
        onSelectSubtitle = ::selectSubtitleTrack,
        onSpeedStep = ::stepPlaybackSpeed,
        onSubtitleSizeStep = ::stepSubtitleSize,
        onAudioDelayStep = ::stepAudioDelay,
        onSubtitleDelayStep = ::stepSubtitleDelay,
      )
    }
    PlayerMenu(state, actions)
  }

  @Composable
  private fun BoxScope.BusyIndicator() {
    val error by playbackError
    val isBuffering by buffering
    val isSeeking by seeking
    if (error == null && (isBuffering || isSeeking)) {
      androidx.compose.material3.CircularProgressIndicator(
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.align(Alignment.Center),
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
    val retryFocus = remember { FocusRequester() }

    Column(
      modifier = Modifier
        .align(Alignment.Center)
        .fillMaxWidth()
        .background(Color(0xCC000000))
        .padding(horizontal = 40.dp, vertical = 28.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text("Playback failed", style = MaterialTheme.typography.titleLarge, color = Color.White)
      Text(
        message,
        modifier = Modifier.padding(top = 12.dp),
        color = Color(0xCCFFFFFF),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
      )
      Button(
        onClick = { retryPlayback() },
        modifier = Modifier.padding(top = 20.dp).focusRequester(retryFocus),
      ) {
        Text("Retry")
      }
      Text(
        "OK retries from where it stopped   |   BACK tries another stream",
        modifier = Modifier.padding(top = 18.dp),
        color = Color(0x99FFFFFF),
        style = MaterialTheme.typography.bodySmall,
      )
    }

    // The only focusable thing the player ever shows, so nothing else can hand
    // focus to it. A failed request is not worth reacting to: OK still reaches
    // [onKeyDown], which retries from there.
    LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
  }

  @Composable
  private fun BoxScope.Osd() {
    val error by playbackError
    val isPaused by paused
    val show by osdVisible
    // The transport hints are a lie once the stream is dead, and keep-open pauses
    // at an error end, which would otherwise pin the OSD open behind the panel.
    if (error != null) return
    if (!show && !isPaused) return

    Column(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .background(Color(0xB3000000))
        .padding(horizontal = 40.dp, vertical = 20.dp),
    ) {
      val suffix = if (season != null) "  S${season}E${episode}" else ""
      Text("$title$suffix", style = MaterialTheme.typography.titleLarge, color = Color.White)
      // Own composable so the per-second position updates recompose the
      // progress row alone, not the surface and OSD chrome around it.
      ProgressRow()
      val info = trackInfo.value
      if (info.isNotBlank()) {
        Text(info, color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodySmall)
      }
      Text(
        (if (isPaused) "Paused   -   " else "") +
          "OK play/pause   |   LEFT/RIGHT 10s   |   UP/DOWN 60s   |   MENU audio & subtitles",
        color = Color(0x99FFFFFF),
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }

  @Composable
  private fun ProgressRow() {
    val actual by timePosSec
    val preview by seekPreviewSec
    val duration by durationSec
    val speed by playbackSpeed
    val position = if (preview >= 0) preview else actual
    // Recomposed once a second by [position], which is also what keeps the end
    // time below current without a clock of its own.
    val remaining = PlaybackTimeline.remainingSec(position, duration, speed)
    Row(
      modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(formatTime(position), color = Color.White, style = MaterialTheme.typography.bodyMedium)
      Box(
        modifier = Modifier
          .weight(1f)
          .padding(horizontal = 14.dp)
          .height(5.dp)
          .background(Color(0x66FFFFFF)),
      ) {
        val fraction = if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
        Box(
          modifier = Modifier
            .fillMaxWidth(fraction)
            .height(5.dp)
            .background(MaterialTheme.colorScheme.primary),
        )
      }
      Text(formatTime(duration), color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
    if (remaining != null) {
      val endsAt = PlaybackTimeline.endsAtEpochMs(System.currentTimeMillis(), remaining)
      Text(
        "-${formatTime(remaining)}   |   Ends at ${formatClockTime(endsAt)}",
        modifier = Modifier.padding(top = 4.dp),
        color = Color(0xCCFFFFFF),
        style = MaterialTheme.typography.bodySmall,
      )
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
        if (contentFps > 0f) applyDisplayFrameRate(contentFps)
      }

      override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!mpvCreated) return
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
            if (keyCode == KeyEvent.KEYCODE_BACK) menuCloseBackDownTime = event.downTime
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
    if (keyCode == KeyEvent.KEYCODE_BACK && event.downTime == menuCloseBackDownTime) return true
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
      KeyEvent.KEYCODE_DPAD_UP -> return requestSeek(60.0, event.repeatCount > 0)
      KeyEvent.KEYCODE_DPAD_DOWN -> return requestSeek(-60.0, event.repeatCount > 0)
      // Was `cycle sub`, which on a fifteen-track remux meant pressing MENU up to
      // sixteen times to get back to the subtitles you started with, and left
      // audio tracks reachable only from a key most TV remotes do not have.
      KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAPTIONS -> {
        openMenu()
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

    seekPreviewSec.doubleValue = target
    showOsd()
    mainHandler.removeCallbacks(seekRunnable)
    mainHandler.postDelayed(seekRunnable, SEEK_DEBOUNCE_MS)
    return true
  }

  private fun flushSeek() {
    if (!mpvCreated) return
    val target = seeker.consumePending() ?: return
    seeking.value = true
    lastPostedSecond = Long.MIN_VALUE
    // Keyframe seeking: a scrub wants to land fast, and being a second or two
    // off is invisible next to the wait an exact seek costs over the network.
    MPVLib.command(arrayOf("seek", target.toString(), "absolute+keyframes"))
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
  }

  /** Whether transport control means anything right now. */
  private fun transportAllowed(): Boolean =
    mpvCreated && !finishing && playbackError.value == null

  /**
   * The one place playback is unpaused, so audio focus is always held before mpv
   * makes any noise. Any explicit play also cancels a pending auto-resume: from
   * here on the viewer's intent is the one that counts.
   */
  private fun playPlayback() {
    if (!mpvCreated) return
    pausedForFocusLoss = false
    // Refused means someone else has the speakers, so stay put rather than
    // playing over them. mpv is normally already paused here; asserting it covers
    // the initial-load call, where mpv starts unpaused on its own.
    MPVLib.setPropertyBoolean("pause", !requestAudioFocus())
  }

  /**
   * The one place playback is paused. [forFocusLoss] marks the pause as this
   * activity's own doing, which is what later allows an automatic resume; a pause
   * the viewer asked for never carries it.
   */
  private fun pausePlayback(forFocusLoss: Boolean = false) {
    if (!mpvCreated) return
    pausedForFocusLoss = forFocusLoss
    MPVLib.setPropertyBoolean("pause", true)
  }

  /**
   * Play/pause from OK or a media button. Reads mpv's own flag rather than
   * [paused], which lags a frame behind it: `cycle pause` would be simpler but
   * gives no chance to take audio focus on the way out of a pause.
   */
  private fun togglePause() {
    if (!mpvCreated) return
    val isPaused = MPVLib.getPropertyBoolean("pause") ?: paused.value
    if (isPaused) playPlayback() else pausePlayback()
  }

  /**
   * Takes audio focus, or reports that it could not be taken. libmpv's audiotrack
   * AO plays whether or not anything asked for focus, so every path that starts
   * playback has to come through here first.
   */
  private fun requestAudioFocus(): Boolean {
    if (hasAudioFocus) return true
    val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
          .build(),
      )
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
    val current = MPVLib.getPropertyInt("volume") ?: return
    volumeBeforeDuck = current
    MPVLib.setPropertyInt("volume", (current * DUCK_VOLUME_FRACTION).toInt())
  }

  private fun restoreDuckedVolume() {
    val previous = volumeBeforeDuck ?: return
    volumeBeforeDuck = null
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
        if (!transportAllowed()) return
        playPlayback()
        showOsd()
      }

      override fun onPause() {
        if (!transportAllowed()) return
        pausePlayback()
        showOsd()
      }

      // Stopping is leaving the player, so it saves what BACK saves.
      override fun onStop() {
        finishPlayback(markFinished = false)
      }

      override fun onSeekTo(pos: Long) {
        if (!transportAllowed()) return
        seekToSec(pos / 1000.0)
      }

      override fun onFastForward() {
        if (transportAllowed()) requestSeek(10.0, isRepeat = false)
      }

      override fun onRewind() {
        if (transportAllowed()) requestSeek(-10.0, isRepeat = false)
      }
    })
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
    val speed = if (state == PlaybackState.STATE_PLAYING) 1f else 0f
    // Nothing but leaving is on offer once the stream is dead.
    val actions =
      if (state == PlaybackState.STATE_ERROR) PlaybackState.ACTION_STOP else TRANSPORT_ACTIONS
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
   * Runs [read] on the worker thread and hands its result to [onResult] on the
   * main thread, where anything touching Compose state or the window has to
   * happen. Nothing runs at all once the core is gone, and a read that threw is
   * dropped rather than reported: none of these are load-bearing enough to fail
   * playback over.
   */
  private fun <T : Any> readOffMain(read: () -> T?, onResult: (T) -> Unit) {
    val worker = mpvWorkerHandler ?: return
    worker.post {
      val value = readWhileAlive(read) ?: return@post
      mainHandler.post { if (mpvCreated && !finishing) onResult(value) }
    }
  }

  /** Called on the worker thread: [read] touches mpv only while mpv is still there. */
  private fun <T : Any> readWhileAlive(read: () -> T?): T? = synchronized(mpvLock) {
    if (!mpvAlive) null else runCatching(read).getOrNull()
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
    worker.postDelayed({
      trackInfoPending.set(false)
      val json = readWhileAlive { MPVLib.getPropertyString("track-list") } ?: return@postDelayed
      val parsed = MpvTracks.parse(json)
      mainHandler.post { if (mpvCreated && !finishing) applyTracks(parsed) }
    }, TRACK_INFO_DEBOUNCE_MS)
  }

  /**
   * Publishes a freshly read track list: the OSD's summary line, and the lists
   * the menu is showing.
   */
  private fun applyTracks(parsed: List<MpvTrack>) {
    tracks.value = parsed
    trackInfo.value = MpvTracks.osdLine(parsed, contentFps)
    // Only ever set by the audio-cycle key, so this cannot learn a preference
    // from the track mpv chose on its own at file open.
    if (audioCyclePending) {
      audioCyclePending = false
      MpvTracks.selected(parsed, TrackKind.Audio)?.let {
        applyPreferenceUpdate(TrackPreferences.audioUpdate(it), audio = true)
      }
    }
  }

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

  private fun openMenu() {
    if (!transportAllowed()) return
    menuTab.value = PlayerMenuTab.Audio
    menuVisible.value = true
    // The list may be stale (or empty, if the menu is opened before the first
    // frame), and the OSD stays up for as long as the menu does.
    refreshTracks()
    showOsd()
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
  private fun matchDisplayToContentFrameRate() {
    if (!mpvCreated) return
    readOffMain({ readContentFps().takeIf { it > 0f } }) { fps ->
      contentFps = fps
      applyDisplayFrameRate(fps)
      refreshTracks()
    }
  }

  /** Called on the worker thread. */
  private fun readContentFps(): Float {
    val container = MPVLib.getPropertyString("container-fps")?.toFloatOrNull()
    if (container != null && container > 0f) return container
    return MPVLib.getPropertyString("estimated-vf-fps")?.toFloatOrNull()?.takeIf { it > 0f } ?: 0f
  }

  private fun applyDisplayFrameRate(fps: Float) {
    // Seamless path (API 30+): tell the compositor the source frame rate and let
    // it pick a compatible display mode (e.g. 60Hz -> 24Hz for film).
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      playbackSurface?.let { surface ->
        runCatching {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            surface.setFrameRate(
              fps,
              Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
              Surface.CHANGE_FRAME_RATE_ALWAYS,
            )
          } else {
            surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
          }
        }
      }
    }
    // Hard-switch fallback: request a same-resolution mode whose refresh rate is
    // an integer multiple of the content fps, for TVs where the surface vote
    // alone does not retune the panel.
    runCatching {
      val display = currentDisplay() ?: return@runCatching
      val mode = pickDisplayModeFor(display, fps) ?: return@runCatching
      if (mode.modeId != display.mode.modeId) {
        window.attributes = window.attributes.apply { preferredDisplayModeId = mode.modeId }
      }
    }
  }

  @Suppress("DEPRECATION")
  private fun currentDisplay(): Display? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay

  /** Best same-resolution mode whose refresh rate is an integer multiple of [fps]. */
  private fun pickDisplayModeFor(display: Display, fps: Float): Display.Mode? {
    val current = display.mode
    val tolerance = 0.75f // Hz
    var best: Display.Mode? = null
    var bestErr = Float.MAX_VALUE
    for (m in display.supportedModes) {
      if (m.physicalWidth != current.physicalWidth || m.physicalHeight != current.physicalHeight) continue
      val multiple = Math.round(m.refreshRate / fps)
      if (multiple < 1) continue
      val err = Math.abs(m.refreshRate - multiple * fps)
      if (err > tolerance) continue
      // Closest match wins; on a tie prefer the lowest refresh rate (less power/heat).
      val better = err < bestErr - 0.01f ||
        (Math.abs(err - bestErr) <= 0.01f && best != null && m.refreshRate < best!!.refreshRate)
      if (best == null || better) {
        bestErr = err
        best = m
      }
    }
    return best
  }

  private fun showOsd() {
    osdVisible.value = true
    val hideAt = SystemClock.uptimeMillis() + OSD_TIMEOUT_MS
    osdHideAtMs = hideAt
    mainHandler.postDelayed({
      // The menu is a deliberate stop in the middle of a film, and the OSD's
      // position is part of what makes a track choice make sense, so the
      // auto-hide waits for the menu to close — [closeMenu] re-arms it.
      if (osdHideAtMs == hideAt && !paused.value && !menuVisible.value) {
        osdVisible.value = false
      }
    }, OSD_TIMEOUT_MS)
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
    // removal and resurrect the entry it just dropped.
    mainHandler.removeCallbacks(progressSaveRunnable)
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
   *  - [Finished]: the video reached its actual end, so there is nothing left to
   *    continue and the entry goes.
   */
  private enum class SaveReason { Progress, Paused, Stopped, Finished }

  private fun saveWatchState(reason: SaveReason) {
    if (watchKey.isBlank() || tmdbId == 0) return
    val positionMs = (resumePositionSec() * 1000).toLong()
    val durationMs = (durationSec.doubleValue * 1000).toLong()
    val store = watchStore
    val key = watchKey
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
    // Deliberately not lifecycleScope: the exit saves run as the player is
    // finishing, and a cancelled write means the resume position is lost.
    persistenceScope.launch {
      if (reason == SaveReason.Finished) {
        store.remove(key)
      } else if (positionMs > MIN_SAVE_MS) {
        store.upsert(entry)
      }
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putLong(STATE_POSITION_MS, (resumePositionSec() * 1000).toLong())
  }

  override fun onPause() {
    if (!finishing) saveWatchState(SaveReason.Paused)
    super.onPause()
  }

  override fun onStop() {
    // Pause on onStop rather than onPause: onPause also fires for transient
    // overlays, where stopping playback is just an annoyance. Deliberately not
    // marked as a focus-loss pause: a player the viewer left in the background
    // must not start playing again on its own when focus comes back.
    pausePlayback()
    super.onStop()
  }

  override fun onStart() {
    super.onStart()
    // Came back to a stream we paused on the way out; surface the OSD so the
    // paused state is visible instead of looking like a frozen picture.
    if (mpvCreated && fileLoaded) showOsd()
  }

  override fun onDestroy() {
    // A never-flushed seek dies with these callbacks, which is only safe because
    // the save reads the target off [seeker] rather than off the runnable: both
    // save paths (finishPlayback and onPause) run before this point.
    if (!finishing && seeker.hasPendingPress) saveWatchState(SaveReason.Paused)
    mainHandler.removeCallbacksAndMessages(null)
    // Before mpvCreated goes false, which is what [syncPlayingState] reads.
    syncNoisyReceiver(playing = false)
    releaseMediaSession()
    // Before mpv goes: whatever was playing before this film is waiting on it.
    abandonAudioFocus()
    if (mpvCreated) {
      MPVLib.removeObserver(observer)
      MPVLib.removeLogObserver(logObserver)
      // Nothing new may reach the core from the worker after this, and the lock
      // below waits out a read that is already inside its JNI call: a property
      // read landing on a destroyed core is a native crash, not an exception.
      stopMpvWorker()
      synchronized(mpvLock) {
        mpvAlive = false
        MPVLib.destroy()
      }
      mpvCreated = false
    }
    super.onDestroy()
  }

  private fun formatTime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
  }

  companion object {
    private const val OSD_TIMEOUT_MS = 4_000L
    private const val SEEK_DEBOUNCE_MS = 350L
    private const val SEEK_REPEAT_MIN_MS = 120L
    private const val END_GUARD_SEC = 5.0

    /**
     * Fraction of a claimed runtime that still counts as having watched the
     * whole thing, for containers whose duration is out by more than
     * [END_GUARD_SEC]. Tight on purpose: everything below it must survive as a
     * resume point, because a truncated stream is indistinguishable from a real
     * ending except by how much of the runtime is missing.
     */
    private const val END_FRACTION = 0.98

    /** Below this, resuming is more disruptive than just starting over. */
    private const val MIN_RESUME_MS = 3_000L

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

    /** An mpv error line can carry a whole signed URL; the panel needs the gist. */
    private const val MAX_ERROR_CHARS = 160
    private const val DEFAULT_LOAD_ERROR = "The stream could not be opened."
    private const val DEFAULT_PLAYBACK_ERROR = "The stream stopped unexpectedly."
    private val STALL_ERROR =
      "The stream stalled: no data for ${STALL_TIMEOUT_MS / 1000} seconds."
    private const val NO_SEEK = -1.0

    /**
     * How long a track-list read waits before running, so stepping through
     * subtitle or audio tracks costs one fetch-and-parse rather than one per
     * press. Short enough that the OSD line still lands with the press.
     */
    private const val TRACK_INFO_DEBOUNCE_MS = 150L

    /**
     * How long the stream waits for the stored audio/subtitle preferences before
     * opening without them. A DataStore read of three strings is a few
     * milliseconds and normally lands well before the surface does, so this only
     * exists so that a read that somehow never completes costs the viewer their
     * preferred language rather than the whole session.
     */
    private const val PREFS_READ_TIMEOUT_MS = 1_500L

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
      KeyEvent.KEYCODE_DPAD_UP,
      KeyEvent.KEYCODE_DPAD_DOWN,
      KeyEvent.KEYCODE_MEDIA_REWIND,
      KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
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
      KeyEvent.KEYCODE_MENU,
      KeyEvent.KEYCODE_CAPTIONS,
      KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK,
    )

    private const val STATE_POSITION_MS = "positionMs"
    private const val EXTRA_URL = "url"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_WATCH_KEY = "watchKey"
    private const val EXTRA_TMDB_ID = "tmdbId"
    private const val EXTRA_MEDIA_TYPE = "mediaType"
    private const val EXTRA_POSTER = "posterUrl"
    private const val EXTRA_SEASON = "season"
    private const val EXTRA_EPISODE = "episode"
    private const val EXTRA_RESUME_MS = "resumeMs"

    fun watchKeyFor(screen: Screen.Streams): String {
      return if (screen.season != null) {
        "episode:${screen.tmdbId}:${screen.season}:${screen.episode}"
      } else {
        "movie:${screen.tmdbId}"
      }
    }

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
      }
    }
  }
}
