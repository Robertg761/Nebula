package com.stremioshell.host.tv.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.BorderStroke
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.stremioshell.host.BuildConfig
import com.stremioshell.host.R
import com.stremioshell.host.tv.ui.theme.NebulaDimens
import com.stremioshell.host.tv.ui.theme.NebulaPalette
import com.stremioshell.host.tv.ui.theme.NebulaShapes
import com.stremioshell.host.update.UpdatePromptCoordinator
import com.stremioshell.host.update.UpdatePromptPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offers the APK the background worker already downloaded. Without this the
 * native app downloads every release and never installs one: the install flow
 * used to live only in the WebView shell.
 */
@Composable
fun UpdatePromptHost(currentVersionName: String = BuildConfig.VERSION_NAME) {
  val context = LocalContext.current
  val coordinator = remember { UpdatePromptCoordinator() }
  // Saveable so a config change (the player's display-mode switch, HDR colorMode
  // flip) does not resurrect a prompt the user already declined.
  var dismissedVersion by rememberSaveable { mutableStateOf<String?>(null) }
  var state by remember { mutableStateOf(UpdatePromptCoordinator.State.None) }
  var refreshTick by remember { mutableStateOf(0) }

  // Only re-checked when the app comes back to the foreground, so browsing the
  // UI never re-raises the dialog.
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshTick++ }

  LaunchedEffect(refreshTick, dismissedVersion) {
    state = withContext(Dispatchers.IO) {
      coordinator.evaluate(context, currentVersionName, dismissedVersion)
    }
  }

  val version = state.versionName
  if (state.prompt == UpdatePromptPolicy.Prompt.NONE || version == null) {
    return
  }

  val dismiss = {
    dismissedVersion = version
    state = UpdatePromptCoordinator.State.None
  }

  UpdateReadyDialog(
    version = version,
    needsUnknownSourcesPermission = state.prompt == UpdatePromptPolicy.Prompt.ENABLE_UNKNOWN_SOURCES,
    onConfirm = {
      if (state.prompt == UpdatePromptPolicy.Prompt.ENABLE_UNKNOWN_SOURCES) {
        context.startActivity(coordinator.buildUnknownSourcesIntent(context))
        // Returning from Settings fires ON_RESUME, which re-evaluates into the
        // install prompt once the permission is granted.
      } else {
        val installIntent = coordinator.buildInstallIntent(context)
        if (installIntent != null) {
          context.startActivity(installIntent)
        } else {
          Toast.makeText(context, R.string.update_install_failed, Toast.LENGTH_LONG).show()
          // The APK went missing or failed verification; re-evaluate rather
          // than leave a dialog whose button does nothing.
          refreshTick++
        }
      }
    },
    onLater = dismiss,
  )
}

@Composable
private fun UpdateReadyDialog(
  version: String,
  needsUnknownSourcesPermission: Boolean,
  onConfirm: () -> Unit,
  onLater: () -> Unit,
) {
  val confirmFocus = rememberInitialFocusTarget()

  Dialog(
    onDismissRequest = onLater,
    // Own window, so D-pad focus is trapped here and BACK dismisses instead of
    // navigating the app behind the dialog.
    properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
  ) {
    RequestInitialFocus(
      target = confirmFocus,
      key = version to needsUnknownSourcesPermission,
      label = "Update prompt confirm button",
    )

    Surface(
      shape = NebulaShapes.extraLarge,
      colors = SurfaceDefaults.colors(containerColor = NebulaPalette.Surface),
      // A hairline is the only thing separating a dialog from the page behind it once both are
      // near-black; without it the sheet has no edge at all on a dark scene.
      border = Border(
        border = BorderStroke(1.dp, NebulaPalette.Outline),
        shape = NebulaShapes.extraLarge,
      ),
      modifier = Modifier.width(560.dp),
    ) {
      Column(
        modifier = Modifier.padding(34.dp),
        verticalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap),
      ) {
        Text("Update $version ready", style = MaterialTheme.typography.headlineSmall)
        Text(
          if (needsUnknownSourcesPermission) {
            "Nebula $version has been downloaded. Android needs one-time permission " +
              "to install apps from Nebula before it can be installed."
          } else {
            "Nebula $version has been downloaded and is ready to install."
          },
          style = MaterialTheme.typography.bodyMedium,
          color = NebulaPalette.TextMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          NebulaButton(
            text = if (needsUnknownSourcesPermission) "Enable installs" else "Install",
            onClick = onConfirm,
            style = NebulaButtonStyle.Primary,
            modifier = Modifier.initialFocusTarget(confirmFocus),
          )
          NebulaButton(
            text = "Later",
            onClick = onLater,
            style = NebulaButtonStyle.Ghost,
          )
        }
      }
    }
  }
}
