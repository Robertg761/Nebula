package com.stremioshell.host.tv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stremioshell.host.tv.data.NetworkErrorMessage
import com.stremioshell.host.tv.data.NetworkSource
import com.stremioshell.host.tv.data.SettingsStore
import com.stremioshell.host.tv.data.StalenessPolicy
import com.stremioshell.host.tv.data.WatchEntry
import com.stremioshell.host.tv.data.WatchStateStore
import com.stremioshell.host.tv.data.addon.AddonClient
import com.stremioshell.host.tv.data.addon.AddonStream
import com.stremioshell.host.tv.data.persistenceScope
import com.stremioshell.host.tv.data.tmdb.EpisodeItem
import com.stremioshell.host.tv.data.tmdb.MediaDetails
import com.stremioshell.host.tv.data.tmdb.MediaItem
import com.stremioshell.host.tv.data.tmdb.MediaType
import com.stremioshell.host.tv.data.tmdb.TmdbClient
import com.stremioshell.host.tv.pairing.ConfigMerge
import com.stremioshell.host.tv.pairing.ConfigPairingServer
import com.stremioshell.host.tv.pairing.PairingSubmission
import com.stremioshell.host.tv.pairing.PairingTokenGenerator
import com.stremioshell.host.tv.pairing.findLanIpv4
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeRail(val title: String, val items: List<MediaItem>)

/**
 * Assembles Home's rails from a load where individual endpoints may have failed.
 *
 * Split out of the ViewModel so the partial-success rule is unit-testable: rails keep their
 * declared order, a rail that failed falls back to the copy already on screen (a refresh must
 * never make a row vanish under the user), and only a rail with no data at all counts as a gap
 * worth reporting.
 */
object HomeRailAssembly {
  data class Assembled(val rails: List<HomeRail>, val missingTitles: List<String>)

  fun merge(order: List<String>, fresh: List<HomeRail>, previous: List<HomeRail>): Assembled {
    val freshByTitle = fresh.associateBy { it.title }
    val previousByTitle = previous.associateBy { it.title }
    val rails = mutableListOf<HomeRail>()
    val missing = mutableListOf<String>()
    for (title in order) {
      val rail = freshByTitle[title] ?: previousByTitle[title]
      if (rail == null) missing += title else rails += rail
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
  private val addonClient = AddonClient()

  val tmdbApiKey: StateFlow<String?> = settings.tmdbApiKey
    .stateIn(viewModelScope, SharingStarted.Eagerly, null)
  val addonManifestUrl: StateFlow<String?> = settings.addonManifestUrl
    .stateIn(viewModelScope, SharingStarted.Eagerly, null)
  val continueWatching: StateFlow<List<WatchEntry>> = watchState.entries
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  private val _homeRails = MutableStateFlow<LoadState<List<HomeRail>>>(LoadState.Loading)
  val homeRails: StateFlow<LoadState<List<HomeRail>>> = _homeRails

  /**
   * Set when Home has usable rails but part of the load did not make it: a compact retry notice
   * belongs under the rails that did load, not over them.
   */
  private val _railsNotice = MutableStateFlow<String?>(null)
  val railsNotice: StateFlow<String?> = _railsNotice

  private val _searchResults = MutableStateFlow<LoadState<List<MediaItem>>>(LoadState.Ready(emptyList()))
  val searchResults: StateFlow<LoadState<List<MediaItem>>> = _searchResults

  private val _details = MutableStateFlow<LoadState<MediaDetails>>(LoadState.Loading)
  val details: StateFlow<LoadState<MediaDetails>> = _details

  private val _episodes = MutableStateFlow<LoadState<List<EpisodeItem>>>(LoadState.Ready(emptyList()))
  val episodes: StateFlow<LoadState<List<EpisodeItem>>> = _episodes

  private val _streams = MutableStateFlow<LoadState<List<AddonStream>>>(LoadState.Loading)
  val streams: StateFlow<LoadState<List<AddonStream>>> = _streams

  private var railsLoadedForKey: String? = null
  private var railsJob: Job? = null

  /** When the rails last loaded completely; null until they have, so a partial load retries. */
  private var railsLoadedAtMillis: Long? = null
  private val railsStaleness = StalenessPolicy()

  // Every per-screen loader keeps its Job plus the key it was asked for, so a newer request can
  // cancel the older one and a late response can be dropped instead of landing on the wrong screen.
  private var searchJob: Job? = null
  private var searchKey: String? = null
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

  /** The rails Home shows, in order. Each one is an independent TMDB endpoint. */
  private class RailSpec(val title: String, val load: suspend (TmdbClient) -> List<MediaItem>)

  private val railSpecs = listOf(
    RailSpec("Trending Movies") { it.trending(MediaType.Movie) },
    RailSpec("Trending Shows") { it.trending(MediaType.Show) },
    RailSpec("Popular Movies") { it.popular(MediaType.Movie) },
    RailSpec("Popular Shows") { it.popular(MediaType.Show) },
  )

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
   * The four endpoints are fetched concurrently (awaiting them in turn made cold Home latency the
   * sum of four round trips) and scored independently: one rail failing shows the other three with
   * a retry notice underneath, rather than blanking the ones that worked.
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
    val refreshingInPlace = sameKey && _homeRails.value is LoadState.Ready
    if (!refreshingInPlace) {
      _homeRails.value = LoadState.Loading
      _railsNotice.value = null
    }
    railsJob = viewModelScope.launch {
      val client = TmdbClient(key)
      val results = railSpecs
        .map { spec -> async { spec.title to catchingFailure { spec.load(client) } } }
        .awaitAll()
      if (!isActive || railsLoadedForKey != key) return@launch
      val assembled = HomeRailAssembly.merge(
        order = railSpecs.map { it.title },
        fresh = results.mapNotNull { (title, result) ->
          result.getOrNull()?.let { items -> HomeRail(title, items) }
        },
        previous = (_homeRails.value as? LoadState.Ready)?.value.orEmpty(),
      )
      val failure = results.firstNotNullOfOrNull { it.second.exceptionOrNull() }
      val message = failure?.let { NetworkErrorMessage.forThrowable(NetworkSource.Tmdb, it) }
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
      railsLoadedAtMillis = if (failure == null) System.currentTimeMillis() else null
    }
  }

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

  fun search(query: String) {
    val client = tmdb() ?: return
    // A newer query always wins; drop whatever is still in flight for the previous one.
    searchJob?.cancel()
    searchKey = query
    if (query.isBlank()) {
      _searchResults.value = LoadState.Ready(emptyList())
      return
    }
    _searchResults.value = LoadState.Loading
    searchJob = viewModelScope.launch {
      val result = runCatching {
        LoadState.Ready(client.search(query)) as LoadState<List<MediaItem>>
      }.getOrElse { LoadState.Failed(NetworkErrorMessage.forThrowable(NetworkSource.Tmdb, it)) }
      if (isActive && searchKey == query) _searchResults.value = result
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

  fun loadStreams(imdbId: String, season: Int?, episode: Int?) {
    val key = "$imdbId/${season ?: "-"}/${episode ?: "-"}"
    // Switching episodes must not let the previous episode's list render under the new header.
    streamsJob?.cancel()
    streamsKey = key
    val manifest = addonManifestUrl.value?.takeIf { it.isNotBlank() }
    if (manifest == null) {
      _streams.value = LoadState.Failed("No addon configured. Set your Comet manifest URL in Settings.")
      return
    }
    _streams.value = LoadState.Loading
    streamsJob = viewModelScope.launch {
      val result = runCatching {
        val streams = if (season != null && episode != null) {
          addonClient.episodeStreams(manifest, imdbId, season, episode)
        } else {
          addonClient.movieStreams(manifest, imdbId)
        }
        LoadState.Ready(streams) as LoadState<List<AddonStream>>
      }.getOrElse { LoadState.Failed(NetworkErrorMessage.forThrowable(NetworkSource.Addon, it)) }
      if (isActive && streamsKey == key) _streams.value = result
    }
  }

  fun saveSettings(tmdbKey: String, addonUrl: String, onStatus: (String) -> Unit) {
    viewModelScope.launch {
      settings.setTmdbApiKey(tmdbKey)
      settings.setAddonManifestUrl(addonUrl)
      // Persisting the key is what makes Home load its rails, so start that load here, with the key
      // we just wrote (the exposed flow has not caught up yet). loadRails de-dupes it against the
      // load Home asks for when it next composes, so the save produces exactly one. The connection
      // checks below can block for ~30s each, and a load kicked off after them would land long
      // after Home had rendered and blank it back to "Loading catalogs..." mid-scroll.
      if (tmdbKey.isNotBlank()) loadRails(tmdbKey, force = true)
      onStatus("Saved. Checking connections...")

      val tmdbStatus = if (tmdbKey.isBlank()) {
        "TMDB: no key"
      } else {
        runCatching { TmdbClient(tmdbKey).trending(MediaType.Movie) }
          .fold(
            onSuccess = { "TMDB: connected" },
            onFailure = { "TMDB: failed (check the key)" },
          )
      }

      val addonStatus = if (addonUrl.isBlank()) {
        "Addon: no URL"
      } else {
        runCatching { addonClient.manifest(addonUrl) }
          .fold(
            onSuccess = { manifest ->
              val name = manifest.name.ifBlank { "addon" }
              "Addon: connected ($name)"
            },
            onFailure = { "Addon: failed (check the URL)" },
          )
      }

      onStatus("$tmdbStatus   |   $addonStatus")
    }
  }
}
