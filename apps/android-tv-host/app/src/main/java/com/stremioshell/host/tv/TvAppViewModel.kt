package com.stremioshell.host.tv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stremioshell.host.tv.data.SettingsStore
import com.stremioshell.host.tv.data.WatchEntry
import com.stremioshell.host.tv.data.WatchStateStore
import com.stremioshell.host.tv.data.addon.AddonClient
import com.stremioshell.host.tv.data.addon.AddonStream
import com.stremioshell.host.tv.data.tmdb.EpisodeItem
import com.stremioshell.host.tv.data.tmdb.MediaDetails
import com.stremioshell.host.tv.data.tmdb.MediaItem
import com.stremioshell.host.tv.data.tmdb.MediaType
import com.stremioshell.host.tv.data.tmdb.TmdbClient
import com.stremioshell.host.tv.pairing.ConfigPairingServer
import com.stremioshell.host.tv.pairing.findLanIpv4
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class HomeRail(val title: String, val items: List<MediaItem>)

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
  private var pairingServer: ConfigPairingServer? = null

  fun startPairing() {
    if (pairingServer != null) return
    val ip = findLanIpv4()
    if (ip == null) {
      _pairing.value = PairingState.Failed("Connect your TV to Wi-Fi or Ethernet first.")
      return
    }
    viewModelScope.launch {
      val currentKey = settings.tmdbApiKey.first()
      val currentAddon = settings.addonManifestUrl.first()
      val server = ConfigPairingServer(currentKey, currentAddon) { tmdbKey, addonUrl ->
        // Called on a server thread; hop back to persist and validate.
        viewModelScope.launch {
          settings.setTmdbApiKey(tmdbKey)
          settings.setAddonManifestUrl(addonUrl)
          _pairing.value = PairingState.Received
          // Use the just-received key: the exposed tmdbApiKey flow may not have
          // caught up yet, and loadHomeRails would resolve the stale value.
          if (tmdbKey.isNotBlank()) loadRails(tmdbKey, force = true)
        }
      }
      runCatching { server.start() }.fold(
        onSuccess = {
          pairingServer = server
          _pairing.value = PairingState.Ready("http://$ip:${server.listeningPort}/")
        },
        onFailure = { _pairing.value = PairingState.Failed(it.message ?: "Could not start pairing.") },
      )
    }
  }

  fun stopPairing() {
    runCatching { pairingServer?.stop() }
    pairingServer = null
    _pairing.value = PairingState.Idle
  }

  override fun onCleared() {
    runCatching { pairingServer?.stop() }
    pairingServer = null
    super.onCleared()
  }

  private fun tmdb(): TmdbClient? = tmdbApiKey.value?.takeIf { it.isNotBlank() }?.let { TmdbClient(it) }

  fun loadHomeRails(force: Boolean = false) {
    val key = tmdbApiKey.value?.takeIf { it.isNotBlank() } ?: return
    loadRails(key, force)
  }

  /**
   * Loads the home rails for [key].
   *
   * Callers may ask more than once for the same key without racing: a load that is still in flight
   * is reused rather than restarted, and a refresh of the key already on screen swaps the rails in
   * place instead of blanking Home back to Loading (which would throw away the row the user is on
   * plus their scroll position). Only an actual key change starts over from Loading.
   */
  private fun loadRails(key: String, force: Boolean) {
    val sameKey = railsLoadedForKey == key
    if (sameKey) {
      // Whatever is already in flight for this key produces exactly the data a refresh would.
      if (railsJob?.isActive == true) return
      if (!force && _homeRails.value is LoadState.Ready) return
    } else {
      railsJob?.cancel()
    }
    railsLoadedForKey = key
    val refreshingInPlace = sameKey && _homeRails.value is LoadState.Ready
    if (!refreshingInPlace) _homeRails.value = LoadState.Loading
    railsJob = viewModelScope.launch {
      val result = runCatching {
        val client = TmdbClient(key)
        LoadState.Ready(
          listOf(
            HomeRail("Trending Movies", client.trending(MediaType.Movie)),
            HomeRail("Trending Shows", client.trending(MediaType.Show)),
            HomeRail("Popular Movies", client.popular(MediaType.Movie)),
            HomeRail("Popular Shows", client.popular(MediaType.Show)),
          )
        ) as LoadState<List<HomeRail>>
      }.getOrElse { LoadState.Failed(it.message ?: "TMDB request failed") }
      if (!isActive || railsLoadedForKey != key) return@launch
      // A failed in-place refresh keeps the rails that are already up; replacing a working Home
      // with an error screen is worse than quietly serving slightly older catalogs.
      if (result is LoadState.Failed && _homeRails.value is LoadState.Ready) return@launch
      _homeRails.value = result
    }
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
      }.getOrElse { LoadState.Failed(it.message ?: "Search failed") }
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
      }.getOrElse { LoadState.Failed(it.message ?: "Failed to load details") }
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
      }.getOrElse { LoadState.Failed(it.message ?: "Failed to load season") }
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
      }.getOrElse { LoadState.Failed(it.message ?: "Addon request failed") }
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
