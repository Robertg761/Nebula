package com.stremioshell.host.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.stremioshell.host.tv.TvAppViewModel
import com.stremioshell.host.tv.data.WatchEntry
import com.stremioshell.host.tv.data.addon.AddonStream
import com.stremioshell.host.tv.data.tmdb.MediaType
import com.stremioshell.host.tv.search.SearchLaunch
import com.stremioshell.host.tv.ui.theme.NebulaAccentBrush
import com.stremioshell.host.tv.ui.theme.NebulaDimens
import com.stremioshell.host.tv.ui.theme.NebulaPalette
import com.stremioshell.host.tv.ui.theme.NebulaShapes

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
 * @param pendingSearch a spoken query, or the bare search key. Cleared through
 *   [onPendingSearchHandled] on the same terms as the deep link.
 */
@Composable
fun TvApp(
  streamLauncher: StreamLauncher = StreamLauncher { _, _ -> },
  pendingStreams: Screen.Streams? = null,
  onPendingStreamsHandled: () -> Unit = {},
  pendingDeepLink: Screen.Details? = null,
  onPendingDeepLinkHandled: () -> Unit = {},
  pendingSearch: SearchLaunch? = null,
  onPendingSearchHandled: () -> Unit = {},
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

  // The query runs before the screen exists, so results are already in flight by the time
  // it paints - and the field seeds itself from the ViewModel, which is why this does not
  // have to reach into SearchScreen. Rooted on Home like the deep link, and through the
  // same drawer helper, so a search key press while already searching keeps the screen the
  // viewer is on rather than resetting it.
  LaunchedEffect(pendingSearch) {
    val request = pendingSearch ?: return@LaunchedEffect
    if (request.query.isNotEmpty()) viewModel.submitVoiceQuery(request.query)
    openRootDestination(Screen.Search)
    onPendingSearchHandled()
  }

  val openDetails: (MediaType, Int) -> Unit = { type, id -> push(Screen.Details(type, id)) }
  val openResume: (WatchEntry) -> Unit = { entry ->
    val type = if (entry.mediaType == "show") MediaType.Show else MediaType.Movie
    // The stopped-at episode rides along: without it Details opens on season 1 with no hint of
    // where the user was, which makes the Continue Watching card useless for shows.
    push(Screen.Details(type, entry.tmdbId, entry.season, entry.episode))
  }

  // Void rather than the scheme's surface: this Surface is what shows through everywhere a screen
  // does not paint its own background, and the rail beside it is the surface tone. Two different
  // near-blacks meeting down the middle of the screen is exactly the seam the rail hairline exists
  // to draw deliberately.
  Surface(
    colors = SurfaceDefaults.colors(containerColor = NebulaPalette.Void),
    modifier = Modifier.fillMaxSize(),
  ) {
    Row(modifier = Modifier.fillMaxSize()) {
      NavigationDrawer(
        drawerContent = { drawerValue ->
          Row(modifier = Modifier.fillMaxHeight().background(NebulaPalette.Surface)) {
            Column(
              modifier = Modifier.fillMaxHeight().padding(vertical = 26.dp, horizontal = RailInset),
            ) {
              NebulaMark(expanded = drawerValue == DrawerValue.Open)
              Spacer(Modifier.weight(1f))
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NavigationDrawerItem(
                  selected = current is Screen.Home,
                  onClick = { openRootDestination(Screen.Home) },
                  leadingContent = { Icon(Icons.Filled.Home, contentDescription = "Home") },
                  colors = railItemColors(),
                  shape = NavigationDrawerItemDefaults.shape(shape = NebulaShapes.small),
                  glow = RailItemGlow,
                ) { Text("Home") }
                NavigationDrawerItem(
                  selected = current is Screen.Search,
                  onClick = { openRootDestination(Screen.Search) },
                  leadingContent = { Icon(Icons.Filled.Search, contentDescription = "Search") },
                  colors = railItemColors(),
                  shape = NavigationDrawerItemDefaults.shape(shape = NebulaShapes.small),
                  glow = RailItemGlow,
                ) { Text("Search") }
                NavigationDrawerItem(
                  selected = current is Screen.Settings,
                  onClick = { openRootDestination(Screen.Settings) },
                  leadingContent = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                  colors = railItemColors(),
                  shape = NavigationDrawerItemDefaults.shape(shape = NebulaShapes.small),
                  glow = RailItemGlow,
                ) { Text("Settings") }
              }
              // Heavier weight below than above, so the items sit on the optical centre line
              // rather than the geometric one - the mark up top already pulls the eye high.
              Spacer(Modifier.weight(1.25f))
            }
            // The rail and the page are one step apart in tone, which at three metres on a dim
            // panel is no edge at all. The hairline is what makes the rail a panel instead of a
            // slightly lighter smear down the left of the screen.
            Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(NebulaPalette.Outline))
          }
        },
      ) {
        Box(modifier = Modifier.fillMaxSize().background(NebulaPalette.Void)) {
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

/**
 * Padding that lands the collapsed rail on [NebulaDimens.NavRailWidth].
 *
 * The item widths are the drawer's, not ours - it animates them between its own collapsed and
 * expanded constants - so the only way the rail matches the width every other screen reserves for
 * it is to derive the inset from that constant rather than to guess a pretty number. The extra
 * 1dp is the hairline, which is part of the rail as far as the layout is concerned.
 */
private val RailInset =
  (NebulaDimens.NavRailWidth - NavigationDrawerItemDefaults.CollapsedDrawerItemWidth - 1.dp) / 2f

/** Same bloom a focused card gets, so the rail belongs to the same UI as the content beside it. */
private val RailItemGlow = NavigationDrawerItemDefaults.glow(
  focusedGlow = Glow(elevationColor = NebulaPalette.Violet, elevation = 12.dp),
  focusedSelectedGlow = Glow(elevationColor = NebulaPalette.Violet, elevation = 12.dp),
)

/**
 * The rail's two states, kept independent of each other.
 *
 * Selected-but-unfocused carries a tinted plate and a bright icon because focus lives in the
 * content area almost all of the time: a rail that only marks the current screen while it holds
 * focus answers "where am I" exactly when the viewer already knows. Focused then flips to a solid
 * bright fill rather than adding an outline to the tint, so the two never have to be told apart by
 * degree.
 */
@Composable
private fun railItemColors() = NavigationDrawerItemDefaults.colors(
  containerColor = Color.Transparent,
  contentColor = NebulaPalette.TextMuted,
  // What the icons use while the rail is collapsed, which is its resting state. The default drops
  // to 40% alpha - on a 62dp strip of near-black that is a smudge rather than an icon.
  inactiveContentColor = NebulaPalette.TextMuted,
  focusedContainerColor = NebulaPalette.VioletBright,
  focusedContentColor = Color(0xFF120A2E),
  pressedContainerColor = NebulaPalette.Violet,
  pressedContentColor = Color.White,
  selectedContainerColor = NebulaPalette.Violet.copy(alpha = 0.22f),
  selectedContentColor = NebulaPalette.VioletBright,
  focusedSelectedContainerColor = NebulaPalette.VioletBright,
  focusedSelectedContentColor = Color(0xFF120A2E),
  pressedSelectedContainerColor = NebulaPalette.Violet,
  pressedSelectedContentColor = Color.White,
)

/**
 * The app's mark, at the top of the rail.
 *
 * Drawn rather than shipped as a drawable so it inherits the accent gradient and the app font - the
 * launcher icon is a separate asset and the two only stay in step if this one is built from the
 * same tokens. Collapsed it is the glyph alone; the wordmark rides in with the drawer, which is
 * what gives the expansion something to be about beyond wider hit targets.
 *
 * Cleared from semantics: it is branding on a screen whose every other element is a destination,
 * and a screen reader announcing the app's own name on entry to the rail is noise.
 */
@Composable
private fun NebulaMark(expanded: Boolean) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.clearAndSetSemantics {},
  ) {
    Box(
      contentAlignment = Alignment.Center,
      // Matched to the item width so the glyph sits on the same vertical line as the icons under
      // it; a mark that is a few pixels off that line is the sort of thing that reads as "unfinished"
      // without the viewer being able to say why.
      modifier = Modifier.width(NavigationDrawerItemDefaults.CollapsedDrawerItemWidth),
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(40.dp).background(NebulaAccentBrush, NebulaShapes.small),
      ) {
        Text(
          text = "N",
          style = MaterialTheme.typography.titleLarge,
          color = Color(0xFF120A2E),
        )
      }
    }
    AnimatedVisibility(visible = expanded) {
      Text(
        text = "NEBULA",
        // Wide tracking is the whole wordmark: it is six letters at title size, and without it
        // the mark reads as a heading that happens to be shouting.
        style = MaterialTheme.typography.titleLarge.copy(letterSpacing = 5.sp),
        color = NebulaPalette.TextHigh,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.padding(start = 12.dp),
      )
    }
  }
}
