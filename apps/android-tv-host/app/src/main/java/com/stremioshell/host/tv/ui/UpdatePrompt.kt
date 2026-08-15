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
import androidx.compose.ui.text.style.TextOverflow
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
import com.stremioshell.host.update.InstallIntentResult
import com.stremioshell.host.update.UpdatePromptPolicy
import kotlinx.coroutines.CancellationException
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
  // UI never re-raises the dialog. Returning from the player is a foreground
  // return too, so this runs often; ApkUpdateManager remembers the archive
  // verdict per file, which is what keeps it from re-reading ~117 MB each time.
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshTick++ }

  LaunchedEffect(refreshTick, dismissedVersion) {
    val next = withContext(Dispatchers.IO) {
      try {
        coordinator.evaluate(context, currentVersionName, dismissedVersion)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        // Nothing in here is worth taking the app down for. The evaluation touches
        // SharedPreferences, DownloadManager (a separate process, which can be mid-restart) and
        // a ~117 MB file; this runs on every return to the foreground, so an unhandled throw
        // crashed the app on resume. No prompt is the right answer to "we could not tell".
        UpdatePromptCoordinator.State.None
      }
    }

    // The error belongs to the prompt that produced it. Without this it survives every
    // resolution to NONE and re-appears - stale text, and a "Check download" primary button -
    // on the next release's dialog.
    if (!UpdatePromptPolicy.retainsError(state.update, next.prompt, next.update)) {
      error = null
    }
    state = next
  }

  val version = state.versionName
  if (state.prompt == UpdatePromptPolicy.Prompt.NONE || version == null) {
    return
  }

  val dismiss = {
    dismissedVersion = version
    state = UpdatePromptCoordinator.State.None
    // Same rule as the evaluation above: the sheet is gone, so the report on it is gone with it.
    error = null
  }

  UpdateReadyDialog(
    version = version,
    currentVersionName = currentVersionName,
    releaseNotes = state.update?.releaseNotes.orEmpty(),
    needsUnknownSourcesPermission = state.prompt == UpdatePromptPolicy.Prompt.ENABLE_UNKNOWN_SOURCES,
    error = error?.message,
    canCheckDownload = error?.canCheckDownload == true,
    actionInProgress = actionInProgress,
    onConfirm = confirm@{
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
        // Capture the exact evaluated attempt before launching. A worker may publish a replacement
        // while this coroutine is waiting for IO; the manager will reject that stale identity
        // without verifying or clearing the replacement.
        val expectedUpdate = state.update ?: return@confirm
        actionInProgress = true
        coroutineScope.launch {
          val reportInstallError = { promptError: UpdatePromptError ->
            if (UpdatePromptPolicy.retainsError(expectedUpdate, state.prompt, state.update)) {
              error = promptError
            }
          }
          val installResult = try {
            withContext(Dispatchers.IO) {
              coordinator.buildInstallIntent(context, expectedUpdate)
            }
          } catch (_: IllegalArgumentException) {
            actionInProgress = false
            reportInstallError(UpdatePromptError(installerOpenFailed, canCheckDownload = false))
            return@launch
          } catch (_: SecurityException) {
            actionInProgress = false
            reportInstallError(UpdatePromptError(installerOpenFailed, canCheckDownload = false))
            return@launch
          }
          actionInProgress = false
          when (installResult) {
            is InstallIntentResult.Ready -> {
              try {
                context.startActivity(installResult.intent)
              } catch (_: ActivityNotFoundException) {
                reportInstallError(UpdatePromptError(installerOpenFailed, canCheckDownload = false))
              } catch (_: SecurityException) {
                reportInstallError(UpdatePromptError(installerOpenFailed, canCheckDownload = false))
              }
            }
            InstallIntentResult.StalePrompt -> {
              // A worker replaced the attempt while its old dialog was visible. That is a state
              // transition, not an install failure; refresh to the replacement without attaching
              // error text or a "Check download" action to the stale prompt.
              error = null
              refreshTick++
            }
            InstallIntentResult.Failed -> {
              // The APK went missing or failed verification. This used to re-evaluate immediately,
              // which is right when the only report is a Toast and wrong now that the report is in
              // the dialog: a re-evaluation that resolves to NONE closes the sheet and takes the
              // explanation with it. ON_RESUME re-checks anyway the next time the app comes back.
              reportInstallError(UpdatePromptError(installFailed, canCheckDownload = true))
            }
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
  releaseNotes: String,
  needsUnknownSourcesPermission: Boolean,
  error: String?,
  canCheckDownload: Boolean,
  actionInProgress: Boolean,
  onConfirm: () -> Unit,
  onCheckAgain: () -> Unit,
  onLater: () -> Unit,
) {
  val confirmFocus = rememberInitialFocusTarget()
  val readyTitle = stringResource(R.string.update_ready_title)

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
    NebulaDialogSurface(paneLabel = readyTitle) {
      // The version is stated once, in the body, next to the one fact a viewer actually wants -
      // what they are upgrading from. The title used to repeat both the number and the readiness
      // in the line above.
      Text(readyTitle, style = MaterialTheme.typography.headlineSmall)
      Text(
        if (needsUnknownSourcesPermission) {
          stringResource(
            R.string.update_ready_permission_body,
            version,
            currentVersionName,
          )
        } else {
          stringResource(R.string.update_ready_body, version, currentVersionName)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = NebulaPalette.TextMuted,
      )
      if (releaseNotes.isNotBlank()) {
        Text(
          stringResource(R.string.update_whats_new),
          style = MaterialTheme.typography.titleMedium,
        )
        Text(
          releaseNotes,
          style = MaterialTheme.typography.bodyMedium,
          color = NebulaPalette.TextMuted,
          maxLines = 5,
          overflow = TextOverflow.Ellipsis,
        )
      }
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
            actionInProgress -> stringResource(R.string.update_checking)
            error != null && canCheckDownload -> stringResource(R.string.update_check_download)
            needsUnknownSourcesPermission -> stringResource(R.string.update_open_android_settings)
            else -> stringResource(R.string.update_install)
          },
          onClick = if (error != null && canCheckDownload) onCheckAgain else onConfirm,
          enabled = !actionInProgress,
          style = NebulaButtonStyle.Primary,
          modifier = Modifier.initialFocusTarget(confirmFocus),
        )
        NebulaButton(
          text = stringResource(R.string.update_later),
          onClick = onLater,
          style = NebulaButtonStyle.Ghost,
          enabled = !actionInProgress,
        )
      }
      Text(
        stringResource(R.string.update_later_hint),
        style = MaterialTheme.typography.bodySmall,
        color = NebulaPalette.TextFaint,
      )
    }
  }
}

private data class UpdatePromptError(
  val message: String,
  val canCheckDownload: Boolean,
)
