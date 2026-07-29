package com.stremioshell.host.tv.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import com.stremioshell.host.BuildConfig
import com.stremioshell.host.R
import com.stremioshell.host.tv.ui.theme.NebulaDimens
import com.stremioshell.host.tv.ui.theme.NebulaIcon
import com.stremioshell.host.tv.ui.theme.NebulaPalette
import com.stremioshell.host.tv.ui.theme.NebulaSpace
import com.stremioshell.host.update.UpdatePromptCoordinator
import com.stremioshell.host.update.UpdatePromptPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
  val coroutineScope = rememberCoroutineScope()
  // Saveable so a config change (the player's display-mode switch, HDR colorMode
  // flip) does not resurrect a prompt the user already declined.
  var dismissedVersion by rememberSaveable { mutableStateOf<String?>(null) }
  var state by remember { mutableStateOf(UpdatePromptCoordinator.State.None) }
  var refreshTick by remember { mutableIntStateOf(0) }
  var actionInProgress by remember { mutableStateOf(false) }
  // The install failure used to be reported by a platform Toast: system type, system colours,
  // often inside the overscan region, and drawn *while* this dialog was still up, so the app's
  // worst moment was also the only place it stopped looking like itself.
  var error by remember { mutableStateOf<UpdatePromptError?>(null) }
  val installFailed = stringResource(R.string.update_install_failed)
  val installerOpenFailed = stringResource(R.string.update_installer_open_failed)
  val settingsOpenFailed = stringResource(R.string.update_settings_open_failed)

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
    currentVersionName = currentVersionName,
    needsUnknownSourcesPermission = state.prompt == UpdatePromptPolicy.Prompt.ENABLE_UNKNOWN_SOURCES,
    error = error?.message,
    canCheckDownload = error?.canCheckDownload == true,
    actionInProgress = actionInProgress,
    onConfirm = {
      error = null
      if (state.prompt == UpdatePromptPolicy.Prompt.ENABLE_UNKNOWN_SOURCES) {
        try {
          context.startActivity(coordinator.buildUnknownSourcesIntent(context))
          // Returning from Settings fires ON_RESUME, which re-evaluates into
          // the install prompt once the permission is granted.
        } catch (_: ActivityNotFoundException) {
          error = UpdatePromptError(settingsOpenFailed, canCheckDownload = false)
        } catch (_: SecurityException) {
          error = UpdatePromptError(settingsOpenFailed, canCheckDownload = false)
        }
      } else {
        actionInProgress = true
        coroutineScope.launch {
          val installIntent = try {
            withContext(Dispatchers.IO) {
              coordinator.buildInstallIntent(context)
            }
          } catch (_: IllegalArgumentException) {
            actionInProgress = false
            error = UpdatePromptError(installerOpenFailed, canCheckDownload = false)
            return@launch
          } catch (_: SecurityException) {
            actionInProgress = false
            error = UpdatePromptError(installerOpenFailed, canCheckDownload = false)
            return@launch
          }
          actionInProgress = false
          if (installIntent != null) {
            try {
              context.startActivity(installIntent)
            } catch (_: ActivityNotFoundException) {
              error = UpdatePromptError(installerOpenFailed, canCheckDownload = false)
            } catch (_: SecurityException) {
              error = UpdatePromptError(installerOpenFailed, canCheckDownload = false)
            }
          } else {
            // The APK went missing or failed verification. This used to re-evaluate immediately,
            // which is right when the only report is a Toast and wrong now that the report is in
            // the dialog: a re-evaluation that resolves to NONE closes the sheet and takes the
            // explanation with it. ON_RESUME re-checks anyway the next time the app comes back.
            error = UpdatePromptError(installFailed, canCheckDownload = true)
          }
        }
      }
    },
    onCheckAgain = {
      error = null
      refreshTick++
    },
    onLater = dismiss,
  )
}

@Composable
private fun UpdateReadyDialog(
  version: String,
  currentVersionName: String,
  needsUnknownSourcesPermission: Boolean,
  error: String?,
  canCheckDownload: Boolean,
  actionInProgress: Boolean,
  onConfirm: () -> Unit,
  onCheckAgain: () -> Unit,
  onLater: () -> Unit,
) {
  val confirmFocus = rememberInitialFocusTarget()

  Dialog(
    onDismissRequest = {
      if (!actionInProgress) {
        onLater()
      }
    },
    // Own window, so D-pad focus is trapped here and BACK dismisses instead of
    // navigating the app behind the dialog.
    properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
  ) {
    RequestInitialFocus(
      target = confirmFocus,
      key = Triple(version, needsUnknownSourcesPermission, error),
      label = "Update prompt confirm button",
    )

    // The shared sheet: one width, one padding, one hairline, one entrance. This was a
    // hand-rolled Surface 20dp wider than the app's other dialog, carrying a byte-identical
    // copy of the comment explaining why a near-black sheet on a near-black page needs an edge.
    NebulaDialogSurface(paneLabel = "Update ready") {
      // The version is stated once, in the body, next to the one fact a viewer actually wants -
      // what they are upgrading from. The title used to repeat both the number and the readiness
      // in the line above.
      Text("Update ready", style = MaterialTheme.typography.headlineSmall)
      Text(
        if (needsUnknownSourcesPermission) {
          "Nebula $version is downloaded. Android needs your permission to let Nebula install " +
            "apps - we'll open that setting, then come back here. You're on $currentVersionName."
        } else {
          "Nebula $version is downloaded and ready. You're on $currentVersionName."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = NebulaPalette.TextMuted,
      )
      if (error != null) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(NebulaSpace.xs),
        ) {
          Icon(
            Icons.Filled.Warning,
            // Decorative: the sentence beside it is the message.
            contentDescription = null,
            tint = NebulaPalette.Danger,
            modifier = Modifier.size(NebulaIcon.sm),
          )
          Text(
            error,
            style = MaterialTheme.typography.bodyMedium,
            color = NebulaPalette.Danger,
          )
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(NebulaDimens.ControlGap)) {
        NebulaButton(
          // Says that the button leaves the app, which "Enable installs" did not.
          text = when {
            actionInProgress -> "Checking update..."
            error != null && canCheckDownload -> "Check download"
            needsUnknownSourcesPermission -> "Open Android settings"
            else -> "Install"
          },
          onClick = if (error != null && canCheckDownload) onCheckAgain else onConfirm,
          enabled = !actionInProgress,
          style = NebulaButtonStyle.Primary,
          modifier = Modifier.initialFocusTarget(confirmFocus),
        )
        NebulaButton(
          text = "Later",
          onClick = onLater,
          style = NebulaButtonStyle.Ghost,
          enabled = !actionInProgress,
        )
      }
    }
  }
}

private data class UpdatePromptError(
  val message: String,
  val canCheckDownload: Boolean,
)
