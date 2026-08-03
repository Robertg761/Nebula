package com.stremioshell.host.tv

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stremioshell.host.R
import com.stremioshell.host.tv.channel.WatchNextSync
import com.stremioshell.host.tv.data.AddonProbe
import com.stremioshell.host.tv.data.MetadataCache
import com.stremioshell.host.tv.data.MetadataCacheOwnership
import com.stremioshell.host.tv.data.NetworkErrorMessage
import com.stremioshell.host.tv.data.NetworkSource
import com.stremioshell.host.tv.data.PlayerPrefs
import com.stremioshell.host.tv.data.PlayerPrefsStore
import com.stremioshell.host.tv.data.RefreshCompletionPolicy
import com.stremioshell.host.tv.data.SettingsDraft
import com.stremioshell.host.tv.data.SettingsSaveGuard
import com.stremioshell.host.tv.data.SettingsStatus
import com.stremioshell.host.tv.data.SettingsStore
import com.stremioshell.host.tv.data.StalenessPolicy
import com.stremioshell.host.tv.data.StoredSettings
import com.stremioshell.host.tv.data.StreamPickStore
import com.stremioshell.host.tv.data.WatchEntry
import com.stremioshell.host.tv.data.WatchStateStore
import com.stremioshell.host.tv.data.WatchlistEntry
import com.stremioshell.host.tv.data.WatchlistStore
import com.stremioshell.host.tv.data.addon.AddonClient
import com.stremioshell.host.tv.data.addon.AddonList
import com.stremioshell.host.tv.data.addon.AddonStream
import com.stremioshell.host.tv.data.addon.StreamCatalog
import com.stremioshell.host.tv.data.addon.StreamQuality
import com.stremioshell.host.tv.data.addon.StreamSelection
import com.stremioshell.host.tv.data.persistenceScope
import com.stremioshell.host.tv.data.tmdb.CatalogQuery
import com.stremioshell.host.tv.data.tmdb.CatalogRails
import com.stremioshell.host.tv.data.tmdb.EpisodeItem
import com.stremioshell.host.tv.data.tmdb.MediaDetails
import com.stremioshell.host.tv.data.tmdb.MediaItem
import com.stremioshell.host.tv.data.tmdb.MediaPage
import com.stremioshell.host.tv.data.tmdb.MediaType
import com.stremioshell.host.tv.data.tmdb.RailPageState
import com.stremioshell.host.tv.data.tmdb.RailPaging
import com.stremioshell.host.tv.data.tmdb.SearchPageState
import com.stremioshell.host.tv.data.tmdb.SearchPaging
import com.stremioshell.host.tv.data.tmdb.SearchRequestGuard
import com.stremioshell.host.tv.data.tmdb.SearchRequestToken
import com.stremioshell.host.tv.data.tmdb.TmdbClient
import com.stremioshell.host.tv.data.tmdb.TmdbLoad
import com.stremioshell.host.tv.diagnostics.PerformanceTrace
import com.stremioshell.host.tv.pairing.ConfigMerge
import com.stremioshell.host.tv.pairing.ConfigPairingServer
import com.stremioshell.host.tv.pairing.PairingApplyResult
import com.stremioshell.host.tv.pairing.PairingConnectionCheck
import com.stremioshell.host.tv.pairing.PairingReceipt
import com.stremioshell.host.tv.pairing.PairingSubmission
import com.stremioshell.host.tv.pairing.PairingTokenGenerator
import com.stremioshell.host.tv.pairing.PairingValidation
import com.stremioshell.host.tv.pairing.PairingValidationPolicy
import com.stremioshell.host.tv.pairing.findLanIpv4
import com.stremioshell.host.tv.search.LaunchIntents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

/**
 * Immutable snapshot of one Home row. Paging replaces the list with a new snapshot; it never
 * mutates an existing row in place, which lets Compose skip unaffected keyed rails during a page
 * append or a different rail's partial response.
 */
@Immutable
data class HomeRail(val title: String, val items: List<MediaItem>)
data class DetailsRequestKey(val type: MediaType, val tmdbId: Int)
data class SeasonRequestKey(val tmdbId: Int, val seasonNumber: Int)
data class StreamsRequestKey(val imdbId: String, val season: Int?, val episode: Int?)
enum class SettingsMutationResult { Changed, Unchanged, Failed }
data class PlayerPrefsMutationResult(
  val outcome: SettingsMutationResult,
  /** The authoritative post-mutation value, absent only when the DataStore operation failed. */
  val prefs: PlayerPrefs? = null,
)

/** Network answers shared by Settings' connection test and phone pairing's save gate. */
private data class ConfigurationProbe(
  val hasTmdbKey: Boolean,
  val tmdbConnected: Boolean,
  val addons: List<AddonProbe>,
) {
  fun pairingValidation(): PairingValidation = PairingValidation(
    hasTmdbKey = hasTmdbKey,
    tmdbConnected = tmdbConnected,
    addons = addons.map { PairingConnectionCheck(it.label, it.name != null) },
  )
}

/**
 * Assembles Home's rails from a load that may still be in progress and whose individual endpoints
 * may have failed.
 *
 * Split out of the ViewModel so the rules that keep Home stable are unit-testable:
 *  - rails keep their declared order, whatever order they come back in;
 *  - a rail that failed falls back to the copy already on screen, because a refresh must never make
 *    a row vanish under the user;
 *  - only a rail with no data at all counts as a gap worth reporting;
 *  - a rail that has not answered yet holds back every rail below it, so rows only ever *append*.
 *    That last one is what lets Home paint the first rail the moment it lands: nothing already on
 *    screen can be pushed down later, so the focused row never moves.
 */
object HomeRailAssembly {
  data class Assembled(val rails: List<HomeRail>, val missingTitles: List<String>)

  /**
   * @param loaded rails that have come back with items during this load.
   * @param failed titles whose request came back empty-handed. A title in neither is still in
   *   flight; at the end of a load there are none of those left.
   * @param previous the rails already on screen, used as the fallback for both cases.
   */
  fun visible(
    order: List<String>,
    loaded: List<HomeRail>,
    failed: Set<String> = emptySet(),
    previous: List<HomeRail> = emptyList(),
  ): Assembled {
    val loadedByTitle = loaded.associateBy { it.title }
    val previousByTitle = previous.associateBy { it.title }
    val rails = mutableListOf<HomeRail>()
    val missing = mutableListOf<String>()
    for (title in order) {
      val rail = loadedByTitle[title] ?: previousByTitle[title]
      if (rail != null) {
        rails += rail
        continue
      }
      if (title in failed) {
        missing += title
        continue
      }
      break
    }
    return Assembled(rails, missing)
  }
}

sealed interface LoadState<out T> {
  data object Loading : LoadState<Nothing>
  data class Ready<T>(val value: T) : LoadState<T>
  data class Failed(val message: String) : LoadState<Nothing>
}

/**
 * A settings save has two useful milestones: the configuration is durably written, then its
 * connections have finished probing. These used to be inferred from status copy, which made the
 * persistence failure sentence look exactly like a successful terminal update to Settings.
 */
sealed interface SettingsSaveUpdate {
  val message: String

  data class Persisted(override val message: String) : SettingsSaveUpdate
  data class Complete(override val message: String) : SettingsSaveUpdate
  data class Failed(override val message: String) : SettingsSaveUpdate
}

/**
 * The observable state of one whole Settings save.
 *
 * This belongs to the ViewModel rather than the Settings composition because the operation writes
 * two DataStores and then probes the network. Activity recreation must replace the observer, not
 * cancel the work or leave a callback pointing at the disposed screen.
 */
@Immutable
data class SettingsSaveOperation(
  val requestId: Long,
  val update: SettingsSaveUpdate? = null,
  val savingPlaybackLanguages: Boolean = false,
  val playerPrefs: PlayerPrefs? = null,
  val submittedAudioLanguage: String? = null,
  val submittedSubtitleLanguage: String? = null,
) {
  val running: Boolean
    get() = update == null || update is SettingsSaveUpdate.Persisted
}

class TvAppViewModel(application: Application) : AndroidViewModel(application) {
  val settings = SettingsStore(application)
  val watchState = WatchStateStore(application)
  val watchlist = WatchlistStore(application)
  val streamPicks = StreamPickStore(application)
  private val playerPrefsStore = PlayerPrefsStore(application)
  private val addonClient = AddonClient()
  private val streamCatalog = StreamCatalog(addonClient)

  /**
   * Null only until the player's separate DataStore has answered for the first time.
   *
   * Settings is the only screen that reads this, and Settings is several presses away from the
   * first frame, so it is collected only while something is looking. The last value survives the
   * gap, so a return to Settings paints from it rather than from null.
   */
  val playerPrefs: StateFlow<PlayerPrefs?> = playerPrefsStore.prefs
    .map<PlayerPrefs, PlayerPrefs?> { it }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(IDLE_UNSUBSCRIBE_MS), null)

  val tmdbApiKey: StateFlow<String?> = settings.tmdbApiKey
    .stateIn(viewModelScope, SharingStarted.Eagerly, null)

  /** Every configured stream addon, in the viewer's order. Null until DataStore answers. */
  val addonManifestUrls: StateFlow<List<String>?> = settings.addonManifestUrls
    .stateIn(viewModelScope, SharingStarted.Eagerly, null)

  /** Settings-only, like [playerPrefs], and shared on the same terms. */
  val subtitlesBaseUrl: StateFlow<String?> = settings.subtitlesBaseUrl
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(IDLE_UNSUBSCRIBE_MS), null)

  /** Everything ever played, watched records included: what episode lists mark from. */
  val watchEntries: StateFlow<List<WatchEntry>> = watchState.entries
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  /**
   * The rail, which is resume points only. A finished video keeps its record now
   * instead of being deleted, so the rail has to filter rather than just render
   * whatever is stored.
   *
   * Filtered off [watchEntries] rather than off the store, so the 30-second progress save decodes
   * and sorts several hundred entries once instead of once per collector.
   */
  val continueWatching: StateFlow<List<WatchEntry>> = watchEntries
    .map { entries -> entries.filterNot { it.watched } }
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  /** "My List", newest first. Local snapshots, so the row survives a TMDB outage. */
  val watchlistEntries: StateFlow<List<WatchlistEntry>> = watchlist.entries
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  /**
   * Membership only, for the Details toggle. Derived from [watchlistEntries] rather than
   * collected separately so that adding an unrelated title cannot recompose the button.
   */
  val watchlistKeys: StateFlow<Set<String>> = watchlistEntries
    .map { entries -> entries.mapTo(mutableSetOf()) { it.key } }
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

  /**
   * Read only by the stream picker, which is at least a full network round trip away from being
   * able to use it: the DataStore read this starts on arrival has finished long before the addons
   * have answered, so nothing renders against an empty map.
   */
  val rememberedPicks: StateFlow<Map<String, StreamSelection>> = streamPicks.selections
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(IDLE_UNSUBSCRIBE_MS), emptyMap())

  private val _homeRails = MutableStateFlow<LoadState<List<HomeRail>>>(LoadState.Loading)
  val homeRails: StateFlow<LoadState<List<HomeRail>>> = _homeRails

  /**
   * Set when Home has usable rails but part of the load did not make it: a compact retry notice
   * belongs under the rails that did load, not over them.
   */
  private val _railsNotice = MutableStateFlow<String?>(null)
  val railsNotice: StateFlow<String?> = _railsNotice

  /**
   * How far each rail has paged, by rail title. Exposed only so a row can show that its next page
   * is on the way; the decision to fetch one lives in [paginateRail].
   */
  private val _railPaging = MutableStateFlow<Map<String, RailPageState>>(emptyMap())
  val railPaging: StateFlow<Map<String, RailPageState>> = _railPaging

  private val _searchResults = MutableStateFlow<LoadState<List<MediaItem>>>(LoadState.Ready(emptyList()))
  val searchResults: StateFlow<LoadState<List<MediaItem>>> = _searchResults
  private val _searchPaging = MutableStateFlow(SearchPageState())
  val searchPaging: StateFlow<SearchPageState> = _searchPaging

  /**
   * The query [searchResults] belongs to. Exposed because the field is debounced and so runs ahead
   * of it: without knowing which query the results answer, the screen cannot tell "no results for
   * what you typed" from "the request for what you typed has not started yet".
   */
  private val _searchQuery = MutableStateFlow("")
  val searchQuery: StateFlow<String> = _searchQuery

  /**
   * A query that arrived from outside the app - the Assistant, or `am start` - for the
   * search field to adopt. Null once the field has taken it, so returning to Search later
   * does not re-fill it with something said an hour ago.
   *
   * The field cannot simply mirror [searchQuery]: it is the field that drives that flow,
   * through a debounce, so mirroring it would fight the viewer's typing.
   */
  private val _voiceQuery = MutableStateFlow<String?>(null)
  val voiceQuery: StateFlow<String?> = _voiceQuery

  private val _details = MutableStateFlow<LoadState<MediaDetails>>(LoadState.Loading)
  val details: StateFlow<LoadState<MediaDetails>> = _details
  private val _detailsRequest = MutableStateFlow<DetailsRequestKey?>(null)
  val detailsRequest: StateFlow<DetailsRequestKey?> = _detailsRequest

  /** See [loadHeroArt]. Null whenever the featured title has no logo, which Home renders as type. */
  private val _heroLogoUrl = MutableStateFlow<String?>(null)
  val heroLogoUrl: StateFlow<String?> = _heroLogoUrl
  private var heroArtKey: Pair<MediaType, Int>? = null
  private var heroArtJob: Job? = null

  private val _episodes = MutableStateFlow<LoadState<List<EpisodeItem>>>(LoadState.Ready(emptyList()))
  val episodes: StateFlow<LoadState<List<EpisodeItem>>> = _episodes
  private val _seasonRequest = MutableStateFlow<SeasonRequestKey?>(null)
  val seasonRequest: StateFlow<SeasonRequestKey?> = _seasonRequest

  private val _streams = MutableStateFlow<LoadState<List<AddonStream>>>(LoadState.Loading)
  val streams: StateFlow<LoadState<List<AddonStream>>> = _streams
  private val _streamsRequest = MutableStateFlow<StreamsRequestKey?>(null)
  val streamsRequest: StateFlow<StreamsRequestKey?> = _streamsRequest

  /**
   * Set when some but not all addons answered. Like [railsNotice], it belongs
   * beside the rows that did load rather than instead of them: a viewer with three
   * addons and one outage still has streams to play.
   */
  private val _streamsNotice = MutableStateFlow<String?>(null)
  val streamsNotice: StateFlow<String?> = _streamsNotice

  private var railsLoadedForKey: String? = null
  private var railsJob: Job? = null

  /** One in-flight next-page fetch per rail, so a rails reload can drop them all. */
  private val railPageJobs = mutableMapOf<String, Job>()

  /** When the rails last loaded completely; null until they have, so a partial load retries. */
  private var railsLoadedAtMillis: Long? = null
  private val railsStaleness = StalenessPolicy()

  // Every per-screen loader keeps its Job plus the key it was asked for, so a newer request can
  // cancel the older one and a late response can be dropped instead of landing on the wrong screen.
  private var searchJob: Job? = null
  private var searchPageJob: Job? = null
  private val searchRequests = SearchRequestGuard()
  private var detailsJob: Job? = null
  private var detailsKey: Pair<MediaType, Int>? = null
  private var seasonJob: Job? = null
  private var seasonKey: Pair<Int, Int>? = null
  private var streamsJob: Job? = null
  private var streamsKey: String? = null

  /**
   * Serialises every configuration read-modify-write. Rapid D-pad presses used to launch
   * overlapping DataStore reads and let the last coroutine overwrite reorders/removals that had
   * already succeeded.
   */
  private val settingsMutationMutex = Mutex()
  private val playerPrefsMutationMutex = Mutex()

  private val _settingsSaveOperation = MutableStateFlow<SettingsSaveOperation?>(null)
  val settingsSaveOperation: StateFlow<SettingsSaveOperation?> = _settingsSaveOperation
  private var settingsSaveJob: Job? = null
  private var nextSettingsSaveRequestId = 0L

  // What Details and its episode lists have already shown, so BACK into a title the viewer just
  // left paints it rather than spinning at them. Sized for a browsing session, not for a library:
  // ten titles covers going back up a row of similar-to and down another, and twenty seasons
  // covers tabbing across every season of the longest show that exists plus the shows around it.
  private val detailsCache = MetadataCache<Pair<MediaType, Int>, MediaDetails>(maxEntries = 10)
  private val seasonCache = MetadataCache<Pair<Int, Int>, List<EpisodeItem>>(maxEntries = 20)

  /** Which TMDB key the two caches above hold data for; null also owns the cleared state. */
  private var metadataCacheKey: String? = null

  /** See [clientFor]. */
  private var cachedClientKey: String? = null
  private var cachedClient: TmdbClient? = null

  init {
    // Search state can outlive the Search screen. Invalidate it at the source as soon as DataStore
    // publishes a different key, rather than relying solely on the screen's debounced re-submit.
    viewModelScope.launch {
      tmdbApiKey.collect { synchronizeSearchCredential(it) }
    }
  }

  // Phone pairing.
  sealed interface PairingState {
    data object Idle : PairingState
    data class Ready(val url: String) : PairingState
    data class Validating(val addonCount: Int) : PairingState
    data class ValidationFailed(
      val message: String,
      val checks: List<PairingConnectionCheck>,
    ) : PairingState
    data class Received(
      val tmdbKeyChanged: Boolean,
      val addonUrlsChanged: Boolean,
      val hasTmdbKey: Boolean,
      val addonCount: Int,
      val checks: List<PairingConnectionCheck>,
    ) : PairingState
    data class Failed(val message: String) : PairingState
  }

  private val _pairing = MutableStateFlow<PairingState>(PairingState.Idle)
  val pairing: StateFlow<PairingState> = _pairing

  /**
   * One pairing attempt. The session exists from the moment [startPairing] is
   * called - before the socket is bound - so a stop request can never arrive
   * "too early" to be noticed: it flips [stopped], and whichever side gets
   * there second closes the socket.
   */
  private class PairingSession {
    val token: String = PairingTokenGenerator.generate()

    @Volatile
    var server: ConfigPairingServer? = null

    @Volatile
    var stopped: Boolean = false
  }

  private sealed interface PairingStartOutcome {
    data class Ready(val url: String) : PairingStartOutcome
    data class Failed(val message: String) : PairingStartOutcome
    data object Aborted : PairingStartOutcome
  }

  @Volatile
  private var pairingSession: PairingSession? = null

  fun startPairing() {
    if (pairingSession != null) return
    val session = PairingSession()
    pairingSession = session
    _pairing.value = PairingState.Idle
    viewModelScope.launch {
      // NonCancellable so that a cancelled viewModelScope (onCleared) cannot strand a
      // half-started server: the block below always runs to its own cleanup check.
      val outcome = withContext(Dispatchers.IO + NonCancellable) { bindPairingServer(session) }
      if (pairingSession !== session) return@launch
      when (outcome) {
        is PairingStartOutcome.Ready -> _pairing.value = PairingState.Ready(outcome.url)
        is PairingStartOutcome.Failed -> {
          pairingSession = null
          _pairing.value = PairingState.Failed(outcome.message)
        }
        PairingStartOutcome.Aborted -> pairingSession = null
      }
    }
  }

  /**
   * Runs on [Dispatchers.IO]: both the NetworkInterface enumeration and NanoHTTPD's
   * start() (which busy-waits for the bind) are blocking, and used to stutter the
   * pairing screen's entry animation from the main thread.
   */
  private fun bindPairingServer(session: PairingSession): PairingStartOutcome {
    val ip = findLanIpv4() ?: return PairingStartOutcome.Failed(
      "Connect your TV to Wi-Fi or Ethernet first.",
    )
    val server = ConfigPairingServer(session.token) { submission ->
      applyPairedConfig(session, submission)
    }
    session.server = server
    if (session.stopped) return PairingStartOutcome.Aborted
    val started = runCatching { server.start() }
    started.exceptionOrNull()?.let { error ->
      runCatching { server.stop() }
      return PairingStartOutcome.Failed(error.message ?: "Could not start pairing.")
    }
    if (session.stopped) {
      // The pairing screen went away while we were binding. A stop request always
      // wins, so undo the start here rather than leaving the port open.
      runCatching { server.stop() }
      return PairingStartOutcome.Aborted
    }
    // The token is what keeps the rest of the LAN out, so it travels in the QR URL.
    return PairingStartOutcome.Ready(
      "http://$ip:${server.listeningPort}/?${ConfigPairingServer.TOKEN_FIELD}=${session.token}",
    )
  }

  /**
   * Called on a NanoHTTPD worker thread. It deliberately blocks that one request through connection
   * validation and the atomic DataStore edit: the phone must never receive a success page for
   * untested, queued or partially-written configuration. The bounded wait also prevents a dead
   * network or storage backend from pinning the server worker indefinitely.
   */
  private fun applyPairedConfig(
    session: PairingSession,
    submission: PairingSubmission,
  ): PairingApplyResult =
    runCatching {
      runBlocking { withTimeout(PAIRING_APPLY_TIMEOUT_MS) { applyPairedConfigChecked(session, submission) } }
    }.getOrElse {
      val message = "The connection checks did not finish. Check the TV's network and try again."
      if (pairingSession === session) {
        _pairing.value = PairingState.ValidationFailed(message, checks = emptyList())
      }
      PairingApplyResult.Failed(message)
    }

  /**
   * Probes outside [settingsMutationMutex], then confirms the values did not move before writing.
   * A pairing POST can stay in flight while the viewer backs out to Settings; holding the mutex
   * across a 40-second network timeout would otherwise make every settings button appear broken.
   */
  private suspend fun applyPairedConfigChecked(
    session: PairingSession,
    submission: PairingSubmission,
  ): PairingApplyResult {
    while (true) {
      if (!pairingActive(session)) return PairingApplyResult.Failed(PAIRING_CLOSED_MESSAGE)
      val candidate = settingsMutationMutex.withLock {
        ConfigMerge.merge(
          submission,
          currentTmdbKey = settings.tmdbApiKey.first(),
          currentAddonUrls = settings.addonManifestUrls.first(),
        )
      }
      if (pairingSession === session) {
        _pairing.value = PairingState.Validating(candidate.addonUrls.size)
      }

      val probe = probeConfiguration(candidate.tmdbKey, candidate.addonUrls)
      if (!pairingActive(session)) return PairingApplyResult.Failed(PAIRING_CLOSED_MESSAGE)
      val validation = probe.pairingValidation()
      val displayChecks = listOf(
        PairingConnectionCheck("TMDB", validation.hasTmdbKey && validation.tmdbConnected),
      ) + validation.addons
      if (!validation.complete) {
        val message = PairingValidationPolicy.failureMessage(validation)
        if (pairingSession === session) {
          _pairing.value = PairingState.ValidationFailed(message, displayChecks)
        }
        return PairingApplyResult.Failed(message)
      }

      // Values from a phone's blank field come from storage. If one changed while the network was
      // being tested, discard this verdict and test the newly-merged candidate instead of saving
      // an untested value or overwriting the viewer's newer Settings edit.
      val committed = settingsMutationMutex.withLock {
        if (!pairingActive(session)) return@withLock null
        val latest = ConfigMerge.merge(
          submission,
          currentTmdbKey = settings.tmdbApiKey.first(),
          currentAddonUrls = settings.addonManifestUrls.first(),
        )
        if (latest.tmdbKey != candidate.tmdbKey || latest.addonUrls != candidate.addonUrls) {
          null
        } else {
          if (latest.changed) settings.setPairedConfiguration(latest.tmdbKey, latest.addonUrls)
          latest
        }
      }
      if (committed == null) {
        if (!pairingActive(session)) return PairingApplyResult.Failed(PAIRING_CLOSED_MESSAGE)
        continue
      }

      // A stop that wins before the commit gate aborts the request. If the atomic DataStore edit
      // has already begun, "Leave pairing" remains honest: it leaves the screen rather than
      // promising that an accepted phone submission can be rolled back.
      if (pairingSession === session) {
        _pairing.value = PairingState.Received(
          tmdbKeyChanged = committed.tmdbKeyChanged,
          addonUrlsChanged = committed.addonUrlsChanged,
          hasTmdbKey = committed.tmdbKey.isNotBlank(),
          addonCount = committed.addonUrls.size,
          checks = displayChecks,
        )
        // Use the just-received key: the exposed tmdbApiKey flow may not have caught up yet.
        // loadRails mutates ViewModel-owned jobs, so hop back to the main-scoped coroutine.
        viewModelScope.launch { loadRails(committed.tmdbKey, force = true) }
      }
      return PairingApplyResult.Saved(
        PairingReceipt(
          tmdbKeyChanged = committed.tmdbKeyChanged,
          addonUrlsChanged = committed.addonUrlsChanged,
          hasTmdbKey = committed.tmdbKey.isNotBlank(),
          addonCount = committed.addonUrls.size,
        ),
      )
    }
  }

  fun stopPairing() {
    shutdownPairing()
    _pairing.value = PairingState.Idle
  }

  /**
   * Hands back everything held only to make a revisit cheap, when the system says memory is
   * genuinely short (see TvAppActivity.onTrimMemory for which levels ask).
   *
   * Nothing here is state a screen cannot rebuild: both metadata caches are read-through, and a
   * picker whose list this drops re-issues its own load (see StreamsScreen). What is on screen is
   * untouched - [details] and [episodes] hold their own copies of what they are drawing, so a trim
   * behind the player does not blank the screen the viewer comes back to.
   */
  fun onTrimMemory() {
    releaseMetadataCaches()
    clearStreams()
  }

  /**
   * The cheap half of [onTrimMemory], for UI_HIDDEN: dropping these costs a silent refetch on a
   * later navigation, never a visible spinner on the screen the viewer returns to - which is why
   * the stream list is deliberately not included here.
   */
  fun releaseMetadataCaches() {
    detailsCache.clear()
    seasonCache.clear()
  }

  override fun onCleared() {
    shutdownPairing()
    super.onCleared()
  }

  private fun shutdownPairing() {
    val session = pairingSession ?: return
    pairingSession = null
    // Marked before the socket is touched: if the bind is still in flight it will
    // see this and close the server itself.
    session.stopped = true
    val server = session.server ?: return
    // stop() joins the listener thread; never on the caller's (main) thread. Uses the
    // app-scoped IO scope because onCleared has already cancelled viewModelScope.
    persistenceScope.launch { runCatching { server.stop() } }
  }

  private fun pairingActive(session: PairingSession): Boolean =
    !session.stopped && pairingSession === session

  private companion object {
    /** One shared HTTP call is capped at 40s; every source is probed in parallel. */
    const val PAIRING_APPLY_TIMEOUT_MS = 50_000L
    const val SETTINGS_SAVE_TIMEOUT_MS = 60_000L
    const val PAIRING_CLOSED_MESSAGE = "Pairing was closed before the settings could be saved."
    const val SEARCH_KEY_REQUIRED_MESSAGE = "Add your TMDB API key in Settings to search."

    /**
     * How long a screen-scoped flow keeps collecting after its last reader leaves. Long enough to
     * ride out a configuration change or a there-and-back through Details, short enough that
     * leaving a screen for good releases the DataStore collector.
     */
    const val IDLE_UNSUBSCRIBE_MS = 5_000L
  }

  private fun synchronizeSearchCredential(value: String?): String? {
    val key = value?.takeIf { it.isNotBlank() }
    if (!searchRequests.updateCredential(key)) return key

    searchJob?.cancel()
    searchPageJob?.cancel()
    _searchPaging.value = SearchPageState()
    _searchResults.value = when {
      _searchQuery.value.isBlank() -> LoadState.Ready(emptyList())
      key == null -> LoadState.Failed(SEARCH_KEY_REQUIRED_MESSAGE)
      else -> LoadState.Loading
    }
    return key
  }

  private fun ownsSearch(token: SearchRequestToken): Boolean {
    val key = synchronizeSearchCredential(tmdbApiKey.value)
    return searchRequests.isCurrent(token, _searchQuery.value, key)
  }

  /**
   * Resolves TMDB for the two callers that cache what they load. Cached metadata belongs to the key
   * that fetched it, so a changed key empties both caches before it is used for anything: a viewer
   * who swapped keys must not be served the previous account's payloads.
   */
  private fun metadataClient(): TmdbClient? {
    val key = MetadataCacheOwnership.credential(tmdbApiKey.value)
    if (MetadataCacheOwnership.changed(metadataCacheKey, key)) {
      // These jobs write through the key-owned caches. Cancellation is the fast path; every
      // completion also checks its captured owner because a response may already be past a
      // cancellable suspension when the setting changes.
      heroArtJob?.cancel()
      detailsJob?.cancel()
      seasonJob?.cancel()
      metadataCacheKey = key
      detailsCache.clear()
      seasonCache.clear()
    }
    return key?.let { clientFor(it) }
  }

  /** A disk fallback stays immediately refreshable until a genuinely fresh response replaces it. */
  private fun <K : Any, V : Any> MetadataCache<K, V>.putLoad(
    key: K,
    load: TmdbLoad<V>,
    nowMillis: Long,
  ) {
    if (load.staleFallback) putStale(key, load.value) else put(key, load.value, nowMillis)
  }

  /**
   * The one client per stored key.
   *
   * A [TmdbClient] is a key, a locale and the shared fetcher, and one was being built for every
   * press: nine on a cold Home, one per rail page, one per Details arrival. Reached only from the
   * main-scoped callers above, so the field needs no synchronisation. Deliberately not used by
   * [probeConfiguration], which builds clients for candidate keys that are not stored yet.
   */
  private fun clientFor(key: String): TmdbClient =
    cachedClient?.takeIf { cachedClientKey == key } ?: TmdbClient(key).also {
      cachedClientKey = key
      cachedClient = it
    }

  private suspend fun TmdbClient.loadResult(
    query: CatalogQuery,
    page: Int,
  ): TmdbLoad<MediaPage> = when (query) {
    is CatalogQuery.Trending -> trendingLoad(query.type, page)
    is CatalogQuery.Popular -> popularLoad(query.type, page)
    is CatalogQuery.Genre -> discoverLoad(query.type, query.genreId, page)
  }

  /** [loadResult]'s first page as the shared disk cache already holds it, or null when absent. */
  private suspend fun TmdbClient.loadCached(query: CatalogQuery): MediaPage? = when (query) {
    is CatalogQuery.Trending -> cachedTrending(query.type)
    is CatalogQuery.Popular -> cachedPopular(query.type)
    is CatalogQuery.Genre -> cachedDiscover(query.type, query.genreId)
  }

  fun loadHomeRails(force: Boolean = false) {
    val key = tmdbApiKey.value?.takeIf { it.isNotBlank() } ?: return
    // Catalogs change far less often than Home is revisited, so they are kept across visits - but
    // this morning's "trending" should not still be up tonight. Aging out triggers an in-place
    // refresh; the user keeps their rails and scroll position while it runs.
    val stale = railsStaleness.isStale(railsLoadedAtMillis, System.currentTimeMillis())
    loadRails(key, force = force || stale)
  }

  /**
   * Loads the home rails for [key].
   *
   * Callers may ask more than once for the same key without racing: a load that is still in flight
   * is reused rather than restarted, and a refresh of the key already on screen swaps the rails in
   * place instead of blanking Home back to Loading (which would throw away the row the user is on
   * plus their scroll position). Only an actual key change starts over from Loading.
   *
   * With nothing on screen the load opens with [primeRailsFromCache], so the skeleton is what a
   * genuinely empty disk cache looks like rather than what every cold open looks like. Everything
   * below then runs as an in-place refresh over those rails.
   *
   * Endpoints within a wave are fetched concurrently (awaiting them in turn made cold Home latency
   * the sum of every round trip) and scored independently: one rail failing shows the others with a
   * retry notice underneath, rather than blanking the ones that worked. Each rail is published the
   * moment it lands, so Home paints its first row without waiting on the slowest of nine.
   *
   * Waves exist because the genre rails are below the fold: firing all nine at once would put the
   * four headline rails behind them in OkHttp's five-per-host queue, making the part of Home the
   * user is actually looking at slower to arrive.
   */
  private fun loadRails(key: String, force: Boolean) {
    val sameKey = railsLoadedForKey == key
    if (sameKey) {
      // Whatever is already in flight for this key produces exactly the data a refresh would.
      if (railsJob?.isActive == true) return
      if (!force && _homeRails.value is LoadState.Ready) return
    } else {
      railsJob?.cancel()
      railsLoadedAtMillis = null
    }
    railsLoadedForKey = key
    // A page fetch for the rails we are about to replace would land on a row that no longer exists.
    cancelRailPaging()
    val refreshingInPlace = sameKey && _homeRails.value is LoadState.Ready
    if (!refreshingInPlace) {
      _homeRails.value = LoadState.Loading
      _railsNotice.value = null
      _railPaging.value = emptyMap()
    }
    railsJob = viewModelScope.launch {
      val client = clientFor(key)
      // Ahead of the snapshots below, so anything it publishes is what the network load refreshes
      // in place rather than something it would overwrite from scratch.
      if (!refreshingInPlace) primeRailsFromCache(client, key)
      if (!isActive || railsLoadedForKey != key) return@launch
      // Snapshots: everything below publishes into _homeRails as it goes, so the fallback copy has
      // to be the one from before this load started.
      val previous = (_homeRails.value as? LoadState.Ready)?.value.orEmpty()
      val carriedPaging = _railPaging.value
      val loaded = LinkedHashMap<String, HomeRail>()
      val failed = mutableSetOf<String>()
      val failures = mutableListOf<Throwable>()
      var usedStaleFallback = false
      // Every rail's paging state waits here until the emission that makes its row visible. The
      // asyncs below all run on this coroutine's (main) dispatcher and only suspend inside the
      // request, so these three collections are single-threaded despite the fan-out.
      val pendingPaging = LinkedHashMap<String, RailPageState>()

      fun commitPaging() {
        if (pendingPaging.isEmpty()) return
        // One replacement map per emission rather than one per rail: the old code rebuilt the whole
        // map nine times on a cold load, and every rebuild is a new value for the screen to read.
        _railPaging.value = _railPaging.value + pendingPaging
        pendingPaging.clear()
      }

      fun publishPartial() {
        val assembled = HomeRailAssembly.visible(CatalogRails.ORDER, loaded.values.toList(), failed, previous)
        // Mid-load emptiness is just "nothing has answered yet"; only the completed load below
        // may call a load failed.
        if (assembled.rails.isEmpty()) return
        // Rails within a wave land moments apart, and one that arrives before the rails above it
        // changes nothing on screen: HomeRailAssembly holds it back so rows only ever append. So
        // "did this landing make a row visible?" is a real question, and it is the one that decides
        // whether the paging above is due. (LoadState.Ready is a data class, so the StateFlow
        // conflates an unchanged list either way; this is about the paging map, which does not
        // conflate because every rail genuinely changes it.) The comparison is a handful of
        // reference checks - an unchanged rail is the identical HomeRail instance.
        if ((_homeRails.value as? LoadState.Ready)?.value == assembled.rails) return
        commitPaging()
        PerformanceTrace.section("home.rails.publish") {
          _homeRails.value = LoadState.Ready(assembled.rails)
        }
      }

      for (wave in CatalogRails.WAVES) {
        wave.map { spec ->
          async {
            val result = PerformanceTrace.suspendSection("home.rail.${spec.title}") {
              catchingFailure { client.loadResult(spec.query, page = 1) }
            }
            if (!isActive || railsLoadedForKey != key) return@async
            val loadedPage = result.getOrNull()
            if (loadedPage == null) {
              failed += spec.title
              result.exceptionOrNull()?.let { failures += it }
            } else {
              if (loadedPage.staleFallback) usedStaleFallback = true
              val page = loadedPage.value
              // The depth the row has reached, not the depth it had when this load started: rails
              // served from the cache can be paged into while the refresh behind them is still in
              // flight, and the tail is what stops that refresh from cutting the row back to one
              // page under a viewer who is already past it.
              val items = RailPaging.merge(page.items, railItemsOnScreen(spec.title))
              loaded[spec.title] = HomeRail(spec.title, items)
              pendingPaging[spec.title] =
                RailPaging.afterFirstPage(page, items.size, carriedPaging[spec.title])
            }
            publishPartial()
          }
        }.awaitAll()
        if (!isActive || railsLoadedForKey != key) return@launch
      }

      val assembled = HomeRailAssembly.visible(CatalogRails.ORDER, loaded.values.toList(), failed, previous)
      val message = failures.firstOrNull()
        ?.let { NetworkErrorMessage.forThrowable(NetworkSource.Tmdb, it) }
      if (assembled.rails.isEmpty()) {
        // Nothing loaded and nothing to fall back on: the one case Home reports as an outright
        // failure.
        _homeRails.value = LoadState.Failed(message ?: "Couldn't load catalogs from TMDB.")
        return@launch
      }
      // A rail that answered but is still held back by one above it never got its paging committed;
      // the completed load is where that stops being true.
      commitPaging()
      _homeRails.value = LoadState.Ready(assembled.rails)
      // Only mention a failure that actually left a gap; a rail still covered by the copy already
      // on screen needs no notice, just a retry on the next visit.
      _railsNotice.value = if (assembled.missingTitles.isEmpty()) null else message
      // Only a complete non-fallback load counts as fresh, so partial/offline loads retry later.
      railsLoadedAtMillis = RefreshCompletionPolicy.loadedAtMillis(
        nowMillis = System.currentTimeMillis(),
        hasFailures = failed.isNotEmpty(),
        usedStaleFallback = usedStaleFallback,
      )
    }
  }

  /**
   * Publishes whatever of Home the shared HTTP disk cache already holds, before anything is asked
   * of the network.
   *
   * The cache almost always has the last visit's catalogs, and reading nine of them off disk is
   * tens of milliseconds against the one to three seconds TMDB takes to answer over a TV's Wi-Fi -
   * which until now was time the viewer spent looking at a skeleton. What it publishes is the same
   * shape a load produces, so the waves that follow are an ordinary in-place refresh: rows swap as
   * fresh pages land, nothing blanks, and the focused row stays where it is.
   *
   * Deliberately leaves [railsLoadedAtMillis] alone. A cache hit is not a load - it says nothing
   * about whether the body is still current - so staleness stays measured from the last time the
   * network actually answered, and a cache-only Home is always followed by a real one.
   *
   * A rail the cache cannot answer counts as *not answered yet* rather than as failed, so
   * [HomeRailAssembly] publishes only the unbroken run of rails from the top. A rail that arrives
   * from the network afterwards then appends below what is on screen instead of pushing it down,
   * which is the one thing that would move the row under the viewer's thumb. An empty cached page
   * is a miss on the same terms: a row with no cards is worse than no row.
   */
  private suspend fun primeRailsFromCache(client: TmdbClient, key: String) {
    val cached = PerformanceTrace.suspendSection("home.rails.cache") {
      coroutineScope {
        // All at once rather than in waves: these are disk reads, so there is no round trip for a
        // second wave to hide behind, and one publish is one composition.
        CatalogRails.ALL.map { spec ->
          async { spec.title to catchingFailure { client.loadCached(spec.query) }.getOrNull() }
        }.awaitAll()
      }
    }
    if (railsLoadedForKey != key) return
    val loaded = mutableListOf<HomeRail>()
    val paging = mutableMapOf<String, RailPageState>()
    for ((title, page) in cached) {
      if (page == null || page.items.isEmpty()) continue
      loaded += HomeRail(title, page.items)
      // Computed exactly as the network path computes it, so a rail served from disk can be paged
      // into immediately rather than only after the refresh lands.
      paging[title] = RailPaging.afterFirstPage(page, page.items.size)
    }
    val assembled = HomeRailAssembly.visible(CatalogRails.ORDER, loaded)
    if (assembled.rails.isEmpty()) return
    // Only the rails that actually became visible: a rail held back above cannot be paged yet.
    _railPaging.value = _railPaging.value +
      assembled.rails.mapNotNull { rail -> paging[rail.title]?.let { rail.title to it } }
    _homeRails.value = LoadState.Ready(assembled.rails)
  }

  /**
   * Fetches the next page of the rail titled [title] when its row has been scrolled close enough to
   * the end.
   *
   * Called from the row on every change to its last visible card, so the cheap rejections come
   * first. The result replaces exactly one entry of the rail list: the other rails keep their
   * identity, so LazyColumn recomposes only this row, and the row's own keyed items leave every card
   * already on screen at the index it was at - which is what keeps focus and scroll offset put.
   */
  fun paginateRail(title: String, lastVisibleIndex: Int) {
    val key = railsLoadedForKey ?: return
    val state = _railPaging.value[title] ?: return
    val rails = (_homeRails.value as? LoadState.Ready)?.value ?: return
    val items = rails.firstOrNull { it.title == title }?.items ?: return
    if (!RailPaging.shouldFetchNext(state, items.size, lastVisibleIndex)) return
    val spec = CatalogRails.specFor(title) ?: return
    setRailPaging(title, state.copy(loading = true))
    railPageJobs[title] = viewModelScope.launch {
      val result = PerformanceTrace.suspendSection("home.page.$title") {
        catchingFailure { clientFor(key).loadResult(spec.query, state.nextPage) }
      }
      if (!isActive || railsLoadedForKey != key) return@launch
      val loadedPage = result.getOrNull()
      if (loadedPage == null) {
        setRailPaging(title, RailPaging.failed(state))
        return@launch
      }
      if (loadedPage.staleFallback) {
        // The cached tail may predate the first page now on screen. Treat it like a failed append:
        // preserve the row and cursor, then make the next Home visit run a full refresh that can
        // reopen this same page rather than permanently carrying the stale tail forward.
        railsLoadedAtMillis = null
        setRailPaging(title, RailPaging.failed(state))
        return@launch
      }
      val page = loadedPage.value
      val current = (_homeRails.value as? LoadState.Ready)?.value ?: return@launch
      val index = current.indexOfFirst { it.title == title }
      if (index < 0) return@launch
      val merged = RailPaging.merge(current[index].items, page.items)
      _homeRails.value = LoadState.Ready(
        current.toMutableList().also { it[index] = HomeRail(title, merged) },
      )
      setRailPaging(title, RailPaging.afterNextPage(state, page, merged.size))
    }
  }

  private fun setRailPaging(title: String, state: RailPageState) {
    _railPaging.value = _railPaging.value + (title to state)
  }

  private fun cancelRailPaging() {
    railPageJobs.values.forEach { it.cancel() }
    railPageJobs.clear()
    // A cancelled fetch never clears its own flag, and a rail left marked as loading would show a
    // placeholder that never resolves and would refuse to page again.
    if (_railPaging.value.values.any { it.loading }) {
      _railPaging.value = _railPaging.value.mapValues { (_, state) -> state.copy(loading = false) }
    }
  }

  private fun List<HomeRail>.itemsFor(title: String): List<MediaItem> =
    firstOrNull { it.title == title }?.items.orEmpty()

  private fun railItemsOnScreen(title: String): List<MediaItem> =
    (_homeRails.value as? LoadState.Ready)?.value.orEmpty().itemsFor(title)

  /**
   * [runCatching] would swallow CancellationException too, letting a cancelled rail look like a
   * failed one - and leaving its error message on a Home that has already moved on.
   */
  private suspend fun <T> catchingFailure(block: suspend () -> T): Result<T> =
    try {
      Result.success(block())
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (error: Throwable) {
      Result.failure(error)
    }

  /**
   * Runs [query] against TMDB, replacing whatever the last one produced.
   *
   * A newer query always wins: the previous fetch is cancelled, and even if it were already past
   * the point of cancellation its answer is dropped rather than published over a query the viewer
   * has moved on from. [searchQuery] is set before the fetch starts, so the screen can always tell
   * which query the state it is rendering belongs to.
   */
  fun search(query: String) {
    val key = synchronizeSearchCredential(tmdbApiKey.value)
    // The search field re-submits what it holds whenever its collector is re-armed (a key
    // arriving, a voice query seeding it), and the answer to a query already in hand or
    // already on its way is the same answer. A failure is not: Retry sends the same string
    // back through here and must actually retry.
    if (searchRequests.canReuse(query, key) && _searchResults.value !is LoadState.Failed) return
    searchJob?.cancel()
    searchPageJob?.cancel()
    _searchQuery.value = query
    _searchPaging.value = SearchPageState()
    val request = searchRequests.begin(query, key)
    if (query.isBlank()) {
      _searchResults.value = LoadState.Ready(emptyList())
      return
    }
    // Reported rather than ignored: search with no key used to leave the last state on screen,
    // which read as "nothing matched" instead of "this needs setting up".
    val client = key?.let { clientFor(it) }
    if (client == null) {
      _searchResults.value = LoadState.Failed(SEARCH_KEY_REQUIRED_MESSAGE)
      return
    }
    _searchResults.value = LoadState.Loading
    searchJob = viewModelScope.launch {
      val page = catchingFailure { client.searchPage(query) }
      if (!isActive || !ownsSearch(request)) return@launch
      page.onSuccess {
        _searchResults.value = LoadState.Ready(it.items)
        _searchPaging.value = SearchPaging.afterFirstPage(it)
      }.onFailure {
        _searchResults.value =
          LoadState.Failed(NetworkErrorMessage.forThrowable(NetworkSource.Tmdb, it))
      }
    }
  }

  /** Appends one search page without replacing or reordering cards already under focus. */
  fun loadNextSearchPage() {
    val key = synchronizeSearchCredential(tmdbApiKey.value)
    val paging = _searchPaging.value
    if (!SearchPaging.canLoad(paging) || searchPageJob?.isActive == true) return
    val query = _searchQuery.value
    if (_searchResults.value !is LoadState.Ready) return
    val request = searchRequests.current(query, key) ?: return
    val client = key?.let { clientFor(it) } ?: return
    val requestedPage = paging.nextPage
    _searchPaging.value = SearchPaging.begin(paging)
    searchPageJob = viewModelScope.launch {
      val page = catchingFailure { client.searchPage(query, requestedPage) }
      if (!isActive || !ownsSearch(request)) return@launch
      page.onSuccess {
        val latest = (_searchResults.value as? LoadState.Ready)?.value ?: return@onSuccess
        val merged = SearchPaging.merge(latest, it.items)
        _searchResults.value = LoadState.Ready(merged)
        _searchPaging.value = SearchPaging.afterPage(paging, it, merged.size)
      }.onFailure {
        _searchPaging.value = SearchPaging.failed(
          paging,
          NetworkErrorMessage.forThrowable(NetworkSource.Tmdb, it),
        )
      }
    }
  }

  /** Clears a paging failure only on a deliberate press, then retries the same page. */
  fun retryNextSearchPage() {
    _searchPaging.value = SearchPaging.retry(_searchPaging.value)
    loadNextSearchPage()
  }

  /**
   * A query spoken to the remote, or handed over by another app. Runs immediately rather
   * than waiting for the field to adopt it and its debounce to expire: the viewer has
   * already finished asking, so there is nothing left to debounce and the results can be
   * in flight while the screen is still being built.
   */
  fun submitVoiceQuery(query: String) {
    val cleaned = LaunchIntents.sanitize(query)
    if (cleaned.isEmpty()) return
    _voiceQuery.value = cleaned
    search(cleaned)
  }

  /** Called by the search field once it has taken [voiceQuery] into itself. */
  fun clearVoiceQuery() {
    _voiceQuery.value = null
  }

  /**
   * Loads a title's details, serving the copy from [detailsCache] first when there is one.
   *
   * Home -> Details -> BACK -> the same Details is the commonest move on this app, and it used to
   * cost a full round trip with a spinner over content the viewer had been reading a second
   * earlier. A hit paints immediately; an aged-out hit still paints immediately and refreshes
   * underneath, exactly as the rails do. A refresh that fails leaves the cached copy on screen,
   * because a revisit must never be worse than not caching at all.
   */
  /**
   * The billboard title's logotype, when TMDB has one for it.
   *
   * Home's hero is built from a catalog entry, and a catalog entry carries no logo - only the
   * details response does. Rather than typeset the one title on screen at the size where
   * typesetting shows most, this fetches that title's details once.
   *
   * It is deliberately *not* [loadDetails]: that publishes into `_details` and moves `detailsKey`,
   * which is the Details screen's state, and a background prefetch that quietly repointed it would
   * be a genuinely nasty bug to find. It shares only the cache - which is the point. Pressing OK on
   * the billboard is the single most likely next action in the app, and it now opens on a warm
   * cache instead of a spinner, so the request is not really an extra one at all.
   */
  fun loadHeroArt(type: MediaType, tmdbId: Int) {
    val key = type to tmdbId
    val previousCredential = metadataCacheKey
    val client = metadataClient()
    val credentialChanged = MetadataCacheOwnership.changed(previousCredential, metadataCacheKey)
    if (client == null) {
      heroArtJob?.cancel()
      heroArtKey = key
      _heroLogoUrl.value = null
      return
    }
    val now = System.currentTimeMillis()
    val sameHero = heroArtKey == key
    val cached = detailsCache.get(key, now)
    if (
      MetadataCacheOwnership.canReuseHero(
        sameHero = sameHero,
        credentialChanged = credentialChanged,
        cachedFresh = cached?.stale == false,
        requestActive = heroArtJob?.isActive == true,
      )
    ) return
    heroArtKey = key
    if (!sameHero || credentialChanged) _heroLogoUrl.value = null
    if (cached != null) {
      _heroLogoUrl.value = cached.value.logoUrl
      if (!cached.stale) return
    }
    heroArtJob?.cancel()
    val credentialOwner = metadataCacheKey
    heroArtJob = viewModelScope.launch {
      val loaded = catchingFailure { client.detailsLoad(type, tmdbId) }.getOrNull()
        ?: return@launch
      // The billboard rotates and the rails reload; a result for a title that is no longer featured
      // must not paint over the one that is.
      if (
        heroArtKey != key ||
        !MetadataCacheOwnership.isCurrent(
          owner = credentialOwner,
          cacheOwner = metadataCacheKey,
          liveCredential = tmdbApiKey.value,
        )
      ) return@launch
      detailsCache.putLoad(key, loaded, System.currentTimeMillis())
      _heroLogoUrl.value = loaded.value.logoUrl
    }
  }

  fun loadDetails(type: MediaType, tmdbId: Int) {
    val client = metadataClient() ?: return
    val credentialOwner = metadataCacheKey
    val key = type to tmdbId
    // Opening another title invalidates the details *and* the season list of the previous one.
    detailsJob?.cancel()
    seasonJob?.cancel()
    detailsKey = key
    _detailsRequest.value = DetailsRequestKey(type, tmdbId)
    seasonKey = null
    _seasonRequest.value = null
    val cached = detailsCache.get(key, System.currentTimeMillis())
    _details.value = if (cached == null) LoadState.Loading else LoadState.Ready(cached.value)
    _episodes.value = LoadState.Ready(emptyList())
    if (cached != null && !cached.stale) return
    detailsJob = viewModelScope.launch {
      val result = PerformanceTrace.suspendSection("details.load") {
        catchingFailure { client.detailsLoad(type, tmdbId) }
      }
      if (
        !isActive ||
        detailsKey != key ||
        !MetadataCacheOwnership.isCurrent(
          owner = credentialOwner,
          cacheOwner = metadataCacheKey,
          liveCredential = tmdbApiKey.value,
        )
      ) return@launch
      val loaded = result.getOrNull()
      if (loaded != null) {
        detailsCache.putLoad(key, loaded, System.currentTimeMillis())
        _details.value = LoadState.Ready(loaded.value)
        return@launch
      }
      // Nothing to fall back on is the only case the screen reports as a failure; a stale copy is
      // still the right thing to be looking at.
      if (cached != null) return@launch
      _details.value = LoadState.Failed(
        NetworkErrorMessage.forThrowable(NetworkSource.Tmdb, result.exceptionOrNull()),
      )
    }
  }

  /** An episode list, on the same terms as [loadDetails]: cached copy first, refresh in place. */
  fun loadSeason(tmdbId: Int, seasonNumber: Int) {
    val client = metadataClient() ?: return
    val credentialOwner = metadataCacheKey
    // The details screen can ask for a season while it is still showing the previous title (its
    // effects run before the new details land); that request is stale by definition.
    val requestedDetails = detailsKey
    if (requestedDetails != null && requestedDetails.second != tmdbId) return
    val key = tmdbId to seasonNumber
    seasonJob?.cancel()
    seasonKey = key
    _seasonRequest.value = SeasonRequestKey(tmdbId, seasonNumber)
    val cached = seasonCache.get(key, System.currentTimeMillis())
    _episodes.value = if (cached == null) LoadState.Loading else LoadState.Ready(cached.value)
    if (cached != null && !cached.stale) return
    seasonJob = viewModelScope.launch {
      val result = PerformanceTrace.suspendSection("season.load") {
        catchingFailure { client.seasonLoad(tmdbId, seasonNumber) }
      }
      if (
        !isActive ||
        seasonKey != key ||
        !MetadataCacheOwnership.isCurrent(
          owner = credentialOwner,
          cacheOwner = metadataCacheKey,
          liveCredential = tmdbApiKey.value,
        )
      ) return@launch
      val loaded = result.getOrNull()
      if (loaded != null) {
        seasonCache.putLoad(key, loaded, System.currentTimeMillis())
        _episodes.value = LoadState.Ready(loaded.value)
        return@launch
      }
      if (cached != null) return@launch
      _episodes.value = LoadState.Failed(
        NetworkErrorMessage.forThrowable(NetworkSource.Tmdb, result.exceptionOrNull()),
      )
    }
  }

  /**
   * Asks every configured addon for this title at once (see [StreamCatalog]) and shows one merged
   * list. Never cached: a debrid stream URL is signed and short-lived, and replaying one hands the
   * player a dead link.
   */
  fun loadStreams(imdbId: String, season: Int?, episode: Int?) {
    val key = "$imdbId/${season ?: "-"}/${episode ?: "-"}"
    // Switching episodes must not let the previous episode's list render under the new header.
    streamsJob?.cancel()
    streamsKey = key
    _streamsRequest.value = StreamsRequestKey(imdbId, season, episode)
    _streams.value = LoadState.Loading
    _streamsNotice.value = null
    streamsJob = viewModelScope.launch {
      // Read inside the coroutine rather than off the eagerly-shared StateFlow: on a
      // cold start that flow is still null, and the old code reported "no addon
      // configured" to a viewer who had one.
      val addons = settings.addonManifestUrls.first()
      if (!isActive || streamsKey != key) return@launch
      if (addons.isEmpty()) {
        _streams.value = LoadState.Failed("No addon configured. Add a stream addon in Settings.")
        return@launch
      }
      val fetch = streamCatalog.fetch(addons, imdbId, season, episode)
      if (!isActive || streamsKey != key) return@launch
      if (fetch.merged.allFailed) {
        // Every addon down is the same dead end a single addon's failure always was,
        // so it keeps that screen: one message and a Retry, not an empty list.
        _streams.value = LoadState.Failed(
          NetworkErrorMessage.forThrowable(NetworkSource.Addon, fetch.failures.firstOrNull()),
        )
        return@launch
      }
      _streamsNotice.value = fetch.merged.notice
      _streams.value = LoadState.Ready(fetch.streams)
    }
  }

  /**
   * Drops the merged stream list, which is the largest thing this ViewModel holds: as many as
   * StreamMerge.MAX_MERGED_STREAMS releases, each with its own title, URL and description. Shared
   * rather than screen-scoped, so without this it stayed reachable for the rest of the session
   * after the viewer left the picker.
   *
   * Cancels the fetch as well, so one still in flight cannot put the list back afterwards. Losing
   * it costs nothing that has to be preserved - streams are never cached (see [loadStreams]) - but
   * it does leave a picker that is still on the back stack with nothing to draw, which is why
   * StreamsScreen re-issues its load when it comes back to a [streamsRequest] that is no longer
   * the one it asked for.
   */
  fun clearStreams() {
    streamsJob?.cancel()
    streamsJob = null
    streamsKey = null
    _streamsRequest.value = null
    _streamsNotice.value = null
    _streams.value = LoadState.Loading
  }

  /**
   * Records the release picked for a series so its next episode can start on the
   * same one. Series only: a movie has no next episode to spend one of the store's
   * slots on.
   */
  fun rememberStreamPick(seriesId: String, stream: AddonStream) {
    if (seriesId.isBlank()) return
    val quality = StreamQuality.parse(stream)
    val selection = StreamSelection(
      seriesId = seriesId,
      bingeGroup = stream.bingeGroup,
      resolutionHeight = quality.resolutionHeight,
      hdr = quality.hdr,
      dolbyVision = quality.dolbyVision,
      label = stream.name ?: stream.title,
      updatedAtMs = System.currentTimeMillis(),
    )
    // persistenceScope, not viewModelScope: this fires as the picker hands off to the
    // player, and the ViewModel's scope can be torn down with the activity behind it.
    persistenceScope.launch { runCatching { streamPicks.remember(selection) } }
  }

  /** "Mark watched" from the Continue Watching row: keeps the record, drops the resume point. */
  fun markWatched(entry: WatchEntry) {
    persistenceScope.launch {
      runCatching {
        watchState.markWatched(entry.key, System.currentTimeMillis())
        WatchNextSync.publish(getApplication(), force = true)
      }
    }
  }

  /**
   * "Remove from row": forgets the video entirely rather than marking it watched,
   * which is what a viewer who started the wrong thing is asking for.
   */
  fun forgetWatchEntry(entry: WatchEntry) {
    val removedAtMs = System.currentTimeMillis()
    persistenceScope.launch {
      runCatching {
        watchState.remove(entry.key, removedAtMs)
        WatchNextSync.publish(getApplication(), force = true)
      }
    }
  }

  /**
   * Saves or unsaves a title from the Details toggle. The stored copy is a snapshot of
   * [item], which is what lets the Home row draw before - or without - TMDB answering.
   */
  fun toggleWatchlist(item: MediaItem) {
    val entry = WatchlistEntry.of(item, System.currentTimeMillis())
    // persistenceScope for the same reason the other writes use it: the press can be the
    // last thing that happens on a screen that is already going away.
    persistenceScope.launch { runCatching { watchlist.toggle(entry) } }
  }

  fun removeFromWatchlist(entry: WatchlistEntry) {
    persistenceScope.launch { runCatching { watchlist.remove(entry.key) } }
  }

  /** Appends an addon and persists immediately; see [AddonList.added] for what is rejected. */
  fun addAddon(
    rawUrl: String,
    onResult: (SettingsMutationResult) -> Unit = {},
  ) = mutateSettings(onResult) {
    val current = settings.addonManifestUrls.first()
    val next = AddonList.added(current, rawUrl)
    if (next == current) return@mutateSettings false
    settings.setAddonManifestUrls(next)
    true
  }

  /** Reorders addon priority and persists immediately, just like add and remove. */
  fun moveAddon(
    url: String,
    direction: Int,
    onResult: (SettingsMutationResult) -> Unit = {},
  ) = mutateSettings(onResult) {
    val current = settings.addonManifestUrls.first()
    // Resolve the row's current position only after taking settingsMutationMutex. Compose can
    // enqueue several D-pad presses before its collected list recomposes; captured indices would
    // then reorder whichever addon happened to move into that old slot.
    val next = AddonList.moved(current, url, direction)
    if (next == current) return@mutateSettings false
    settings.setAddonManifestUrls(next)
    true
  }

  /**
   * Removes one addon and persists immediately.
   *
   * Deliberately not staged behind Save: a list whose edits only take effect later
   * shows the viewer a state that is not the one being used, and removing the last
   * addon that way would collide with [SettingsSaveGuard], which refuses to write
   * an empty list over a stored one. Pressing Remove is the explicit clear.
   */
  fun removeAddon(
    url: String,
    onResult: (SettingsMutationResult) -> Unit = {},
  ) = mutateSettings(onResult) {
    val current = settings.addonManifestUrls.first()
    val next = AddonList.removed(current, url)
    if (next == current) return@mutateSettings false
    settings.setAddonManifestUrls(next)
    true
  }

  /** The explicit clear the blank-save guard points at. */
  fun clearTmdbKey(onResult: (SettingsMutationResult) -> Unit = {}) =
    mutateSettings(onResult) {
      if (settings.tmdbApiKey.first().isBlank()) return@mutateSettings false
      settings.setTmdbApiKey("")
      true
    }

  private fun mutateSettings(
    onResult: (SettingsMutationResult) -> Unit,
    mutation: suspend () -> Boolean,
  ) {
    viewModelScope.launch {
      val result = runCatching {
        settingsMutationMutex.withLock {
          if (mutation()) SettingsMutationResult.Changed else SettingsMutationResult.Unchanged
        }
      }.getOrElse { SettingsMutationResult.Failed }
      onResult(result)
    }
  }

  fun savePlaybackLanguages(
    audioLanguage: String,
    subtitleLanguage: String,
    onResult: (PlayerPrefsMutationResult) -> Unit = {},
  ) = mutatePlayerPrefs(onResult) {
    playerPrefsStore.setLanguages(audioLanguage, subtitleLanguage)
  }

  fun setPlaybackSubtitleSize(
    storageName: String,
    onResult: (PlayerPrefsMutationResult) -> Unit = {},
  ) = mutatePlayerPrefs(onResult) {
    playerPrefsStore.setSubtitleSize(storageName)
  }

  fun setPlaybackAudioOutput(
    storageName: String,
    onResult: (PlayerPrefsMutationResult) -> Unit = {},
  ) = mutatePlayerPrefs(onResult) {
    playerPrefsStore.setAudioOutput(storageName)
  }

  fun setAutoPlayNext(
    enabled: Boolean,
    onResult: (PlayerPrefsMutationResult) -> Unit = {},
  ) = mutatePlayerPrefs(onResult) {
    playerPrefsStore.setAutoPlayNext(enabled)
  }

  fun setUpNextCountdownSeconds(
    seconds: Int,
    onResult: (PlayerPrefsMutationResult) -> Unit = {},
  ) = mutatePlayerPrefs(onResult) {
    playerPrefsStore.setUpNextCountdownSeconds(seconds)
  }

  fun resetPlaybackPreferences(
    onResult: (PlayerPrefsMutationResult) -> Unit = {},
  ) = mutatePlayerPrefs(onResult) {
    playerPrefsStore.resetPlaybackPreferences()
  }

  /**
   * Player preferences have their own DataStore and never wait behind a network settings probe.
   * Their own mutex preserves press order, and returning the authoritative value prevents a fast
   * second D-pad press from computing against a StateFlow emission that has not reached Compose yet.
   */
  private fun mutatePlayerPrefs(
    onResult: (PlayerPrefsMutationResult) -> Unit,
    mutation: suspend () -> Unit,
  ) {
    viewModelScope.launch {
      onResult(mutatePlayerPrefsNow(mutation))
    }
  }

  private suspend fun mutatePlayerPrefsNow(
    mutation: suspend () -> Unit,
  ): PlayerPrefsMutationResult = try {
    playerPrefsMutationMutex.withLock {
      val before = playerPrefsStore.get()
      mutation()
      val after = playerPrefsStore.get()
      PlayerPrefsMutationResult(
        outcome = if (after == before) {
          SettingsMutationResult.Unchanged
        } else {
          SettingsMutationResult.Changed
        },
        prefs = after,
      )
    }
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Throwable) {
    PlayerPrefsMutationResult(SettingsMutationResult.Failed)
  }

  /** Runs the TMDB and every-addon checks concurrently for Settings and pairing alike. */
  private suspend fun probeConfiguration(
    tmdbKey: String,
    addonUrls: List<String>,
  ): ConfigurationProbe = coroutineScope {
    val tmdbProbe = async {
      if (tmdbKey.isBlank()) {
        false
      } else {
        catchingFailure { TmdbClient(tmdbKey).probeCredentials() }.isSuccess
      }
    }
    val labels = AddonList.labels(addonUrls)
    val addonProbes = addonUrls.mapIndexed { index, url ->
      async {
        AddonProbe(
          labels[index],
          catchingFailure { addonClient.manifest(url) }.getOrNull()?.name,
        )
      }
    }
    ConfigurationProbe(
      hasTmdbKey = tmdbKey.isNotBlank(),
      tmdbConnected = tmdbProbe.await(),
      addons = addonProbes.awaitAll(),
    )
  }

  /**
   * Starts one complete Settings transaction and returns the id the UI should observe.
   *
   * The initial state is published synchronously, before the coroutine can run, so a recreation
   * between the button press and the first suspension can always reconnect to the same request.
   */
  fun startSettingsSave(
    tmdbKey: String,
    subtitlesBaseUrl: String,
    audioLanguage: String? = null,
    subtitleLanguage: String? = null,
  ): Long {
    settingsSaveJob?.cancel()
    val requestId = ++nextSettingsSaveRequestId
    val initial = SettingsSaveOperation(
      requestId = requestId,
      savingPlaybackLanguages = audioLanguage != null && subtitleLanguage != null,
      submittedAudioLanguage = audioLanguage,
      submittedSubtitleLanguage = subtitleLanguage,
    )
    _settingsSaveOperation.value = initial
    settingsSaveJob = viewModelScope.launch {
      var operation = initial
      var persisted = false
      try {
        withTimeout(SETTINGS_SAVE_TIMEOUT_MS) {
          if (operation.savingPlaybackLanguages) {
            val playbackResult = mutatePlayerPrefsNow {
              playerPrefsStore.setLanguages(audioLanguage!!, subtitleLanguage!!)
            }
            if (playbackResult.outcome == SettingsMutationResult.Failed) {
              publishSettingsSave(
                operation.copy(
                  savingPlaybackLanguages = false,
                  update = SettingsSaveUpdate.Failed(
                    getApplication<Application>().getString(
                      R.string.settings_save_playback_languages_failed,
                    ),
                  ),
                ),
              )
              return@withTimeout
            }
            operation = operation.copy(
              savingPlaybackLanguages = false,
              playerPrefs = playbackResult.prefs,
            )
            publishSettingsSave(operation)
          }

          val resolved = settingsMutationMutex.withLock {
            // Add/remove/reorder persist immediately. Read that list inside the same lock instead
            // of accepting a compositional snapshot: a rapid Save after a list edit must not
            // overwrite the edit with the previous frame's value.
            val currentAddons = settings.addonManifestUrls.first()
            SettingsSaveGuard.resolve(
              SettingsDraft(tmdbKey, currentAddons, subtitlesBaseUrl),
              StoredSettings(settings.tmdbApiKey.first(), currentAddons),
            ).also { result ->
              settings.setConfiguration(
                result.tmdbKey,
                result.addonUrls,
                result.subtitlesBaseUrl,
              )
            }
          }
          persisted = true
          // Persisting the key is what makes Home load its rails, so start that load here, with the
          // key we just wrote (the exposed flow has not caught up yet). loadRails de-dupes it
          // against the load Home asks for when it next composes, so the save produces exactly one.
          if (resolved.tmdbKey.isNotBlank()) loadRails(resolved.tmdbKey, force = true)
          val kept = SettingsSaveGuard.keptNotice(resolved)
          operation = operation.copy(
            update = SettingsSaveUpdate.Persisted(
              listOfNotNull(kept, "Saved. Checking connections...").joinToString("  "),
            ),
          )
          publishSettingsSave(operation)

          val probe = probeConfiguration(resolved.tmdbKey, resolved.addonUrls)
          val status = SettingsStatus.tmdbStatus(resolved.tmdbKey, probe.tmdbConnected) +
            "   |   " + SettingsStatus.addonStatus(probe.addons)
          publishSettingsSave(
            operation.copy(
              update = SettingsSaveUpdate.Complete(
                listOfNotNull(kept, status).joinToString("  "),
              ),
            ),
          )
        }
      } catch (_: TimeoutCancellationException) {
        val message = if (persisted) {
          getApplication<Application>().getString(R.string.settings_save_watchdog_persisted)
        } else {
          getApplication<Application>().getString(
            R.string.settings_save_watchdog_unconfirmed,
            getApplication<Application>().getString(R.string.app_name),
          )
        }
        publishSettingsSave(
          operation.copy(
            savingPlaybackLanguages = false,
            update = if (persisted) {
              SettingsSaveUpdate.Complete(message)
            } else {
              SettingsSaveUpdate.Failed(message)
            },
          ),
        )
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Throwable) {
        publishSettingsSave(
          operation.copy(
            savingPlaybackLanguages = false,
            update = SettingsSaveUpdate.Failed(
              "Couldn't save settings. Check the TV's available storage and try again.",
            ),
          ),
        )
      }
    }
    return requestId
  }

  /** Cancels only the request the caller still owns; a stale screen cannot cancel a newer save. */
  fun cancelSettingsSave(requestId: Long) {
    if (_settingsSaveOperation.value?.requestId != requestId) return
    settingsSaveJob?.cancel()
    settingsSaveJob = null
    _settingsSaveOperation.value = null
  }

  private fun publishSettingsSave(operation: SettingsSaveOperation) {
    if (_settingsSaveOperation.value?.requestId == operation.requestId) {
      _settingsSaveOperation.value = operation
    }
  }

}
