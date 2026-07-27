package com.stremioshell.host.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.stremioshell.host.tv.TvAppViewModel
import com.stremioshell.host.tv.data.WatchEntry
import com.stremioshell.host.tv.data.addon.AddonStream
import com.stremioshell.host.tv.data.tmdb.MediaType

/** Launches playback of a resolved stream; provided by the hosting activity. */
fun interface StreamLauncher {
  fun play(screen: Screen.Streams, stream: AddonStream)
}

/**
 * Key under which a back-stack entry's UI state (scroll offsets, focus,
 * rememberSaveable values) is stashed while the entry is not on screen. The
 * stack index is part of the key so two visits to the same screen never share
 * state.
 */
private fun stateKeyFor(index: Int, screen: Screen): String = "$index:$screen"

/**
 * @param pendingStreams an episode the player handed back because it could not pick a
 *   stream for it on its own. Cleared through [onPendingStreamsHandled] once navigated
 *   to, so a BACK out of the picker does not immediately push it again.
 * @param pendingDeepLink a title a Watch Next row on the TV home asked to reopen.
 *   Cleared through [onPendingDeepLinkHandled] so a BACK out of Details does not push
 *   it straight back.
 */
@Composable
fun TvApp(
  streamLauncher: StreamLauncher = StreamLauncher { _, _ -> },
  pendingStreams: Screen.Streams? = null,
  onPendingStreamsHandled: () -> Unit = {},
  pendingDeepLink: Screen.Details? = null,
  onPendingDeepLinkHandled: () -> Unit = {},
) {
  val viewModel: TvAppViewModel = viewModel()
  // Saveable: Screen is Parcelable, so the stack outlives activity recreation
  // (display-mode switches behind the player, config changes, process death).
  val backstack = rememberSaveable(
    saver = listSaver<SnapshotStateList<Screen>, Screen>(
      save = { it.toList() },
      restore = { it.toMutableStateList() },
    ),
  ) { mutableStateListOf<Screen>(Screen.Home) }
  // Keeps each screen's state alive while it waits on the back stack, instead
  // of the bare `when` disposing it on every navigation.
  val stateHolder = rememberSaveableStateHolder()
  val current = backstack.last()

  fun push(screen: Screen) = backstack.add(screen)

  /** Truncates the stack to [size] entries, dropping the popped screens' state. */
  fun popTo(size: Int) {
    while (backstack.size > size) {
      val index = backstack.lastIndex
      val screen = backstack.removeAt(index)
      stateHolder.removeState(stateKeyFor(index, screen))
    }
  }

  /**
   * Drawer-level navigation. Home stays at the bottom of the stack so BACK from
   * Search or Settings returns Home and only Home exits to the launcher.
   */
  fun openRootDestination(screen: Screen) {
    if (screen == Screen.Home) {
      popTo(1)
      return
    }
    if (backstack.size > 1 && backstack[1] == screen) {
      // Already on this destination: keep its state, just drop anything above it.
      popTo(2)
      return
    }
    popTo(1)
    push(screen)
  }

  BackHandler(enabled = backstack.size > 1) { popTo(backstack.size - 1) }

  // The stream list the previous episode was started from is still on top when the
  // player hands the next one back, and replacing it keeps the stack the same depth
  // for episode 9 as it was for episode 1 - BACK still lands on Details.
  LaunchedEffect(pendingStreams) {
    val target = pendingStreams ?: return@LaunchedEffect
    if (backstack.last() is Screen.Streams) popTo(backstack.size - 1)
    push(target)
    onPendingStreamsHandled()
  }

  // A Watch Next row names a title, not a place in the stack. Rooting it on Home
  // means BACK out of it lands where the launcher icon would have, instead of
  // dropping straight out of the app.
  LaunchedEffect(pendingDeepLink) {
    val target = pendingDeepLink ?: return@LaunchedEffect
    popTo(1)
    push(target)
    onPendingDeepLinkHandled()
  }

  val openDetails: (MediaType, Int) -> Unit = { type, id -> push(Screen.Details(type, id)) }
  val openResume: (WatchEntry) -> Unit = { entry ->
    val type = if (entry.mediaType == "show") MediaType.Show else MediaType.Movie
    // The stopped-at episode rides along: without it Details opens on season 1 with no hint of
    // where the user was, which makes the Continue Watching card useless for shows.
    push(Screen.Details(type, entry.tmdbId, entry.season, entry.episode))
  }

  Surface(modifier = Modifier.fillMaxSize()) {
    Row(modifier = Modifier.fillMaxSize()) {
      NavigationDrawer(
        drawerContent = {
          Column(
            modifier = Modifier
              .fillMaxHeight()
              .background(MaterialTheme.colorScheme.surface)
              .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
          ) {
            NavigationDrawerItem(
              selected = current is Screen.Home,
              onClick = { openRootDestination(Screen.Home) },
              leadingContent = { Icon(Icons.Filled.Home, contentDescription = "Home") },
            ) { Text("Home") }
            NavigationDrawerItem(
              selected = current is Screen.Search,
              onClick = { openRootDestination(Screen.Search) },
              leadingContent = { Icon(Icons.Filled.Search, contentDescription = "Search") },
            ) { Text("Search") }
            NavigationDrawerItem(
              selected = current is Screen.Settings,
              onClick = { openRootDestination(Screen.Settings) },
              leadingContent = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
            ) { Text("Settings") }
          }
        },
      ) {
        Box(modifier = Modifier.fillMaxSize()) {
          stateHolder.SaveableStateProvider(stateKeyFor(backstack.lastIndex, current)) {
            when (val screen = current) {
              is Screen.Home -> HomeScreen(
                viewModel,
                onItemClick = openDetails,
                onResumeClick = openResume,
                onPairWithPhone = { push(Screen.Pair) },
                // Pushed, not rooted, so BACK returns to the welcome screen.
                onOpenSettings = { openRootDestination(Screen.Settings) },
              )
              is Screen.Search -> SearchScreen(viewModel, onItemClick = openDetails)
              is Screen.Settings -> SettingsScreen(viewModel, onPairWithPhone = { push(Screen.Pair) })
              is Screen.Pair -> PairScreen(viewModel, onPaired = { popTo(1) })
              // "More like this" pushes another Details, exactly as a Home card does: the same
              // screen at a deeper stack index, so BACK walks the trail back out.
              is Screen.Details -> DetailsScreen(
                viewModel = viewModel,
                screen = screen,
                onItemClick = openDetails,
                onPlay = { media, season, episode, startOver ->
                  // No IMDb id means no addon lookup is possible; the screen says so in place
                  // rather than pushing a picker that could only come back empty.
                  val imdbId = media.imdbId
                  if (imdbId != null) {
                    push(
                      Screen.Streams(
                        imdbId = imdbId,
                        title = media.item.title,
                        tmdbId = media.item.tmdbId,
                        mediaType = media.item.type,
                        posterUrl = media.item.posterUrl,
                        season = season,
                        episode = episode,
                        startOver = startOver,
                      )
                    )
                  }
                },
              )
              is Screen.Streams -> StreamsScreen(viewModel, screen) { stream ->
                streamLauncher.play(screen, stream)
              }
            }
          }
        }
      }
    }
  }
}
