package com.stremioshell.host.tv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stremioshell.host.tv.data.AddonProbe
import com.stremioshell.host.tv.data.NetworkErrorMessage
import com.stremioshell.host.tv.data.NetworkSource
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
import com.stremioshell.host.tv.data.addon.AddonFetch
import com.stremioshell.host.tv.data.addon.AddonList
import com.stremioshell.host.tv.data.addon.AddonStream
import com.stremioshell.host.tv.data.addon.StreamMerge
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
import com.stremioshell.host.tv.data.tmdb.TmdbClient
import com.stremioshell.host.tv.pairing.ConfigMerge
import com.stremioshell.host.tv.pairing.ConfigPairingServer
import com.stremioshell.host.tv.pairing.PairingSubmission
import com.stremioshell.host.tv.pairing.PairingTokenGenerator
import com.stremioshell.host.tv.pairing.findLanIpv4
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class HomeRail(val title: String, val items: List<MediaItem>)

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

class TvAppViewModel(application: Application) : AndroidViewModel(application) {
  val settings = SettingsStore(application)
  val watchState = WatchStateStore(application)
  val watchlist = WatchlistStore(application)
  val streamPicks = StreamPickStore(application)
  private val addonClient = AddonClient()

  val tmdbApiKey: StateFlow<String?> = settings.tmdbApiKey
    .stateIn(viewModelScope, SharingStarted.Eagerly, null)

  /** Every configured stream addon, in the viewer's order. Null until DataStore answers. */
  val addonManifestUrls: StateFlow<List<String>?> = settings.addonManifestUrls
    .stateIn(viewModelScope, SharingStarted.Eagerly, null)
  val addonManifestUrl: StateFlow<String?> = settings.addonManifestUrl
    .stateIn(viewModelScope, SharingStarted.Eagerly, null)
  val subtitlesBaseUrl: StateFlow<String?> = settings.subtitlesBaseUrl
    .stateIn(viewModelScope, SharingStarted.Eagerly, null)

  /** Everything ever played, watched records included: what episode lists mark from. */
  val watchEntries: StateFlow<List<WatchEntry>> = watchState.entries
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  /**
   * The rail, which is resume points only. A finished video keeps its record now
   * instead of being deleted, so the rail has to filter rather than just render
   * whatever is stored.
   */
  val continueWatching: StateFlow<List<WatchEntry>> = watchState.entries
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

  val rememberedPicks: StateFlow<Map<String, StreamSelection>> = streamPicks.selections
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

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

  /**
   * The query [searchResults] belongs to. Exposed because the field is debounced and so runs ahead
   * of it: without knowing which query the results answer, the screen cannot tell "no results for
   * what you typed" from "the request for what you typed has not started yet".
   */
  private val _searchQuery = MutableStateFlow("")
  val searchQuery: StateFlow<String> = _searchQuery

  private val _details = MutableStateFlow<LoadState<MediaDetails>>(LoadState.Loading)
  val details: StateFlow<LoadState<MediaDetails>> = _details

  private val _episodes = MutableStateFlow<LoadState<List<EpisodeItem>>>(LoadState.Ready(emptyList()))
  val episodes: StateFlow<LoadState<List<EpisodeItem>>> = _episodes

  private val _streams = MutableStateFlow<LoadState<List<AddonStream>>>(LoadState.Loading)
  val streams: StateFlow<LoadState<List<AddonStream>>> = _streams

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
  private var detailsJob: Job? = null
  private var detailsKey: Pair<MediaType, Int>? = null
  private var seasonJob: Job? = null
  private var seasonKey: Pair<Int, Int>? = null
  private var streamsJob: Job? = null
  private var streamsKey: String? = null

  // Phone pairing.
  sealed interface PairingState {
    data object Idle : PairingState
    data class Ready(val url: String) : PairingState
    data object Received : PairingState
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
    val server = ConfigPairingServer(session.token, ::applyPairedConfig)
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

  /** Called on a NanoHTTPD worker thread when the phone submits the form. */
  private fun applyPairedConfig(submission: PairingSubmission) {
    viewModelScope.launch {
      val merged = ConfigMerge.merge(
        submission,
        currentTmdbKey = settings.tmdbApiKey.first(),
        currentAddonUrl = settings.addonManifestUrl.first(),
      )
      // A field the phone left blank keeps its stored value instead of being erased.
      if (merged.tmdbKeyChanged) settings.setTmdbApiKey(merged.tmdbKey)
      if (merged.addonUrlChanged) settings.setAddonManifestUrl(merged.addonUrl)
      _pairing.value = PairingState.Received
      // Use the just-received key: the exposed tmdbApiKey flow may not have
      // caught up yet, and loadHomeRails would resolve the stale value.
      if (merged.tmdbKey.isNotBlank()) loadRails(merged.tmdbKey, force = true)
    }
  }

  fun stopPairing() {
    shutdownPairing()
    _pairing.value = PairingState.Idle
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

  private fun tmdb(): TmdbClient? = tmdbApiKey.value?.takeIf { it.isNotBlank() }?.let { TmdbClient(it) }

  private suspend fun TmdbClient.load(query: CatalogQuery, page: Int): MediaPage = when (query) {
    is CatalogQuery.Trending -> trending(query.type, page)
    is CatalogQuery.Popular -> popular(query.type, page)
    is CatalogQuery.Genre -> discover(query.type, query.genreId, page)
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
      val client = TmdbClient(key)
      // Snapshots: everything below publishes into _homeRails as it goes, so the fallback copy has
      // to be the one from before this load started.
      val previous = (_homeRails.value as? LoadState.Ready)?.value.orEmpty()
      val carriedPaging = _railPaging.value
      val loaded = LinkedHashMap<String, HomeRail>()
      val failed = mutableSetOf<String>()
      val failures = mutableListOf<Throwable>()

      fun publishPartial() {
        val assembled = HomeRailAssembly.visible(CatalogRails.ORDER, loaded.values.toList(), failed, previous)
        // Mid-load emptiness is just "nothing has answered yet"; only the completed load below
        // may call a load failed.
        if (assembled.rails.isNotEmpty()) _homeRails.value = LoadState.Ready(assembled.rails)
      }

      for (wave in CatalogRails.WAVES) {
        wave.map { spec ->
          async {
            val result = catchingFailure { client.load(spec.query, page = 1) }
            if (!isActive || railsLoadedForKey != key) return@async
            val page = result.getOrNull()
            if (page == null) {
              failed += spec.title
              result.exceptionOrNull()?.let { failures += it }
            } else {
              val items = RailPaging.merge(page.items, previous.itemsFor(spec.title))
              loaded[spec.title] = HomeRail(spec.title, items)
              setRailPaging(
                spec.title,
                RailPaging.afterFirstPage(page, items.size, carriedPaging[spec.title]),
              )
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
      _homeRails.value = LoadState.Ready(assembled.rails)
      // Only mention a failure that actually left a gap; a rail still covered by the copy already
      // on screen needs no notice, just a retry on the next visit.
      _railsNotice.value = if (assembled.missingTitles.isEmpty()) null else message
      // Only a complete load counts as fresh, so a partial one is retried on the next visit.
      railsLoadedAtMillis = if (failed.isEmpty()) System.currentTimeMillis() else null
    }
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
      val result = catchingFailure { TmdbClient(key).load(spec.query, state.nextPage) }
      if (!isActive || railsLoadedForKey != key) return@launch
      val page = result.getOrNull()
      if (page == null) {
        setRailPaging(title, RailPaging.failed(state))
        return@launch
      }
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
    searchJob?.cancel()
    _searchQuery.value = query
    if (query.isBlank()) {
      _searchResults.value = LoadState.Ready(emptyList())
      return
    }
    // Reported rather than ignored: search with no key used to leave the last state on screen,
    // which read as "nothing matched" instead of "this needs setting up".
    val client = tmdb()
    if (client == null) {
      _searchResults.value = LoadState.Failed("Add your TMDB API key in Settings to search.")
      return
    }
    _searchResults.value = LoadState.Loading
    searchJob = viewModelScope.launch {
      val result = catchingFailure {
        LoadState.Ready(client.search(query)) as LoadState<List<MediaItem>>
      }.getOrElse { LoadState.Failed(NetworkErrorMessage.forThrowable(NetworkSource.Tmdb, it)) }
      if (isActive && _searchQuery.value == query) _searchResults.value = result
    }
  }

  fun loadDetails(type: MediaType, tmdbId: Int) {
    val client = tmdb() ?: return
    val key = type to tmdbId
    // Opening another title invalidates the details *and* the season list of the previous one.
    detailsJob?.cancel()
    seasonJob?.cancel()
    detailsKey = key
    seasonKey = null
    _details.value = LoadState.Loading
    _episodes.value = LoadState.Ready(emptyList())
    detailsJob = viewModelScope.launch {
      val result = runCatching {
        LoadState.Ready(client.details(type, tmdbId)) as LoadState<MediaDetails>
      }.getOrElse { LoadState.Failed(NetworkErrorMessage.forThrowable(NetworkSource.Tmdb, it)) }
      if (isActive && detailsKey == key) _details.value = result
    }
  }

  fun loadSeason(tmdbId: Int, seasonNumber: Int) {
    val client = tmdb() ?: return
    // The details screen can ask for a season while it is still showing the previous title (its
    // effects run before the new details land); that request is stale by definition.
    val requestedDetails = detailsKey
    if (requestedDetails != null && requestedDetails.second != tmdbId) return
    val key = tmdbId to seasonNumber
    seasonJob?.cancel()
    seasonKey = key
    _episodes.value = LoadState.Loading
    seasonJob = viewModelScope.launch {
      val result = runCatching {
        LoadState.Ready(client.season(tmdbId, seasonNumber)) as LoadState<List<EpisodeItem>>
      }.getOrElse { LoadState.Failed(NetworkErrorMessage.forThrowable(NetworkSource.Tmdb, it)) }
      if (isActive && seasonKey == key) _episodes.value = result
    }
  }

  /**
   * Asks every configured addon for this title at once and shows one merged list.
   *
   * Parallel with a per-addon budget, because the list is only as fast as its
   * slowest member otherwise: one addon whose debrid backend is wedged would hold
   * the picker on "Asking..." for OkHttp's full 40s call timeout while three
   * healthy ones sat finished. A partial answer beats a complete one that arrives
   * after the viewer has given up, so a timed-out addon is reported as a failure
   * alongside the rows that did land.
   */
  fun loadStreams(imdbId: String, season: Int?, episode: Int?) {
    val key = "$imdbId/${season ?: "-"}/${episode ?: "-"}"
    // Switching episodes must not let the previous episode's list render under the new header.
    streamsJob?.cancel()
    streamsKey = key
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
      val labels = AddonList.labels(addons)
      val failures = mutableListOf<Throwable>()
      val fetches = addons.mapIndexed { index, url ->
        async {
          // withTimeoutOrNull around the catch, not inside it: the timeout arrives as a
          // CancellationException, which catchingFailure deliberately rethrows.
          val outcome = withTimeoutOrNull(ADDON_STREAM_TIMEOUT_MS) {
            catchingFailure {
              if (season != null && episode != null) {
                addonClient.episodeStreams(url, imdbId, season, episode)
              } else {
                addonClient.movieStreams(url, imdbId)
              }
            }
          }
          // Named by label, never by URL: an addon's configured path can carry a
          // debrid key, and exception messages get logged.
          // Collected on the ViewModel's own (main) dispatcher, so no locking.
          val error = if (outcome == null) {
            SocketTimeoutException("${labels[index]} timed out")
          } else {
            outcome.exceptionOrNull()
          }
          error?.let { failures += it }
          AddonFetch(labels[index], outcome?.getOrNull())
        }
      }.awaitAll()
      if (!isActive || streamsKey != key) return@launch
      val merged = StreamMerge.merge(fetches)
      if (merged.allFailed) {
        // Every addon down is the same dead end a single addon's failure always was,
        // so it keeps that screen: one message and a Retry, not an empty list.
        _streams.value = LoadState.Failed(
          NetworkErrorMessage.forThrowable(NetworkSource.Addon, failures.firstOrNull()),
        )
        return@launch
      }
      _streamsNotice.value = merged.notice
      _streams.value = LoadState.Ready(merged.streams)
    }
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
      runCatching { watchState.markWatched(entry.key, System.currentTimeMillis()) }
    }
  }

  /**
   * "Remove from row": forgets the video entirely rather than marking it watched,
   * which is what a viewer who started the wrong thing is asking for.
   */
  fun forgetWatchEntry(entry: WatchEntry) {
    persistenceScope.launch { runCatching { watchState.remove(entry.key) } }
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
  fun addAddon(rawUrl: String) {
    viewModelScope.launch {
      val current = settings.addonManifestUrls.first()
      val next = AddonList.added(current, rawUrl)
      if (next != current) settings.setAddonManifestUrls(next)
    }
  }

  /**
   * Removes one addon and persists immediately.
   *
   * Deliberately not staged behind Save: a list whose edits only take effect later
   * shows the viewer a state that is not the one being used, and removing the last
   * addon that way would collide with [SettingsSaveGuard], which refuses to write
   * an empty list over a stored one. Pressing Remove is the explicit clear.
   */
  fun removeAddon(url: String) {
    viewModelScope.launch {
      settings.setAddonManifestUrls(AddonList.removed(settings.addonManifestUrls.first(), url))
    }
  }

  /** The explicit clear the blank-save guard points at. */
  fun clearTmdbKey() {
    viewModelScope.launch { settings.setTmdbApiKey("") }
  }

  fun saveSettings(
    tmdbKey: String,
    addonUrls: List<String>,
    subtitlesBaseUrl: String,
    onStatus: (String) -> Unit,
  ) {
    viewModelScope.launch {
      val resolved = SettingsSaveGuard.resolve(
        SettingsDraft(tmdbKey, addonUrls, subtitlesBaseUrl),
        StoredSettings(settings.tmdbApiKey.first(), settings.addonManifestUrls.first()),
      )
      settings.setTmdbApiKey(resolved.tmdbKey)
      settings.setAddonManifestUrls(resolved.addonUrls)
      settings.setSubtitlesBaseUrl(resolved.subtitlesBaseUrl)
      // Persisting the key is what makes Home load its rails, so start that load here, with the key
      // we just wrote (the exposed flow has not caught up yet). loadRails de-dupes it against the
      // load Home asks for when it next composes, so the save produces exactly one. The connection
      // checks below can block for ~30s each, and a load kicked off after them would land long
      // after Home had rendered and blank it back to "Loading catalogs..." mid-scroll.
      if (resolved.tmdbKey.isNotBlank()) loadRails(resolved.tmdbKey, force = true)
      val kept = SettingsSaveGuard.keptNotice(resolved)
      onStatus(listOfNotNull(kept, "Saved. Checking connections...").joinToString("  "))

      // Every addon at once, for the reason the stream loader does it: a viewer with
      // four addons should not wait four timeouts to find out which one is broken.
      val labels = AddonList.labels(resolved.addonUrls)
      val probes = resolved.addonUrls.mapIndexed { index, url ->
        async { AddonProbe(labels[index], runCatching { addonClient.manifest(url) }.getOrNull()?.name) }
      }.awaitAll()

      val tmdbConnected = resolved.tmdbKey.takeIf { it.isNotBlank() }?.let {
        runCatching { TmdbClient(it).trending(MediaType.Movie) }.isSuccess
      }
      val status = SettingsStatus.tmdbStatus(resolved.tmdbKey, tmdbConnected) +
        "   |   " + SettingsStatus.addonStatus(probes)
      onStatus(listOfNotNull(kept, status).joinToString("  "))
    }
  }

  private companion object {
    /**
     * Per-addon budget for a stream request. Well under OkHttp's 40s call timeout,
     * because that ceiling is for one request and this is a race between several.
     */
    const val ADDON_STREAM_TIMEOUT_MS = 20_000L
  }
}
