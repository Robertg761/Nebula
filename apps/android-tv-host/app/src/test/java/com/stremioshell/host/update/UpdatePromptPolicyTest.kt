package com.stremioshell.host.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePromptPolicyTest {
  private fun snapshot(
    version: String,
    downloadId: Long = 41L,
    assetIdentity: String? = "github-asset:1001",
  ) = DownloadedUpdateSnapshot(downloadId, version, assetIdentity)

  @Test
  fun `no prompt when nothing is downloaded`() {
    assertEquals(
      UpdatePromptPolicy.Prompt.NONE,
      UpdatePromptPolicy.decide(
        downloadedVersionName = null,
        currentVersionName = "0.4.0",
        needsUnknownSourcesPermission = false,
        dismissedVersionName = null
      )
    )
  }

  @Test
  fun `no prompt when the downloaded apk is the installed version`() {
    assertEquals(
      UpdatePromptPolicy.Prompt.NONE,
      UpdatePromptPolicy.decide(
        downloadedVersionName = "0.4.0",
        currentVersionName = "0.4.0",
        needsUnknownSourcesPermission = false,
        dismissedVersionName = null
      )
    )
  }

  @Test
  fun `no prompt when the downloaded apk is older than the installed version`() {
    assertEquals(
      UpdatePromptPolicy.Prompt.NONE,
      UpdatePromptPolicy.decide(
        downloadedVersionName = "0.3.9",
        currentVersionName = "0.4.0",
        needsUnknownSourcesPermission = false,
        dismissedVersionName = null
      )
    )
  }

  @Test
  fun `install prompt when a newer apk is ready`() {
    assertEquals(
      UpdatePromptPolicy.Prompt.INSTALL,
      UpdatePromptPolicy.decide(
        downloadedVersionName = "0.4.1",
        currentVersionName = "0.4.0",
        needsUnknownSourcesPermission = false,
        dismissedVersionName = null
      )
    )
  }

  @Test
  fun `permission prompt takes precedence when installs are blocked`() {
    assertEquals(
      UpdatePromptPolicy.Prompt.ENABLE_UNKNOWN_SOURCES,
      UpdatePromptPolicy.decide(
        downloadedVersionName = "0.4.1",
        currentVersionName = "0.4.0",
        needsUnknownSourcesPermission = true,
        dismissedVersionName = null
      )
    )
  }

  @Test
  fun `no prompt after the user chose later for this version`() {
    assertEquals(
      UpdatePromptPolicy.Prompt.NONE,
      UpdatePromptPolicy.decide(
        downloadedVersionName = "0.4.1",
        currentVersionName = "0.4.0",
        needsUnknownSourcesPermission = false,
        dismissedVersionName = "0.4.1"
      )
    )
  }

  @Test
  fun `later is not nagged around by a v prefix or flavor suffix`() {
    assertEquals(
      UpdatePromptPolicy.Prompt.NONE,
      UpdatePromptPolicy.decide(
        downloadedVersionName = "v0.4.1-tv",
        currentVersionName = "0.4.0",
        needsUnknownSourcesPermission = false,
        dismissedVersionName = "0.4.1"
      )
    )
  }

  @Test
  fun `dismissing one version still prompts for the next release`() {
    assertEquals(
      UpdatePromptPolicy.Prompt.INSTALL,
      UpdatePromptPolicy.decide(
        downloadedVersionName = "0.4.2",
        currentVersionName = "0.4.0",
        needsUnknownSourcesPermission = false,
        dismissedVersionName = "0.4.1"
      )
    )
  }

  @Test
  fun `blank downloaded version is treated as nothing downloaded`() {
    assertEquals(
      UpdatePromptPolicy.Prompt.NONE,
      UpdatePromptPolicy.decide(
        downloadedVersionName = "   ",
        currentVersionName = "0.4.0",
        needsUnknownSourcesPermission = true,
        dismissedVersionName = null
      )
    )
  }

  @Test
  fun `the pre-release is not the release, so the release is still offered`() {
    assertEquals(
      UpdatePromptPolicy.Prompt.INSTALL,
      UpdatePromptPolicy.decide(
        downloadedVersionName = "0.4.1",
        currentVersionName = "0.4.1-beta.1",
        needsUnknownSourcesPermission = false,
        dismissedVersionName = null
      )
    )
  }

  @Test
  fun `later on the beta does not silence the stable that follows it`() {
    assertEquals(
      UpdatePromptPolicy.Prompt.INSTALL,
      UpdatePromptPolicy.decide(
        downloadedVersionName = "0.4.1",
        currentVersionName = "0.4.0",
        needsUnknownSourcesPermission = false,
        dismissedVersionName = "0.4.1-beta.1"
      )
    )
  }

  // --- Error retention --------------------------------------------------------------------------

  @Test
  fun `an error survives a re-evaluation of the same version`() {
    // Returning from a failed installer fires ON_RESUME, which re-evaluates. The explanation and
    // its "Check download" button have to still be there.
    assertTrue(
      UpdatePromptPolicy.retainsError(
        previousUpdate = snapshot("0.4.1"),
        nextPrompt = UpdatePromptPolicy.Prompt.INSTALL,
        nextUpdate = snapshot("0.4.1"),
      )
    )
    assertTrue(
      UpdatePromptPolicy.retainsError(
        previousUpdate = snapshot("v0.4.1-tv"),
        nextPrompt = UpdatePromptPolicy.Prompt.ENABLE_UNKNOWN_SOURCES,
        nextUpdate = snapshot("0.4.1"),
      )
    )
  }

  @Test
  fun `an error does not survive the prompt resolving to nothing`() {
    // The dialog is gone but the composable is not, so the message used to sit in state and
    // re-surface on the next release's prompt.
    assertFalse(
      UpdatePromptPolicy.retainsError(
        previousUpdate = snapshot("0.4.1"),
        nextPrompt = UpdatePromptPolicy.Prompt.NONE,
        nextUpdate = null,
      )
    )
    assertFalse(
      UpdatePromptPolicy.retainsError(
        previousUpdate = snapshot("0.4.1"),
        nextPrompt = UpdatePromptPolicy.Prompt.INSTALL,
        nextUpdate = null,
      )
    )
  }

  @Test
  fun `an error does not follow one version onto another`() {
    assertFalse(
      UpdatePromptPolicy.retainsError(
        previousUpdate = snapshot("0.4.1"),
        nextPrompt = UpdatePromptPolicy.Prompt.INSTALL,
        nextUpdate = snapshot("0.4.2"),
      )
    )
    assertFalse(
      UpdatePromptPolicy.retainsError(
        previousUpdate = snapshot("0.4.1-beta.1"),
        nextPrompt = UpdatePromptPolicy.Prompt.INSTALL,
        nextUpdate = snapshot("0.4.1"),
      )
    )
  }

  @Test
  fun `there is nothing to retain before a version has been prompted for`() {
    assertFalse(
      UpdatePromptPolicy.retainsError(
        previousUpdate = null,
        nextPrompt = UpdatePromptPolicy.Prompt.INSTALL,
        nextUpdate = snapshot("0.4.1"),
      )
    )
  }

  @Test
  fun `an error does not follow a same-version replacement attempt`() {
    val previous = snapshot("0.4.1", downloadId = 41L, assetIdentity = "github-asset:1001")

    assertFalse(
      UpdatePromptPolicy.retainsError(
        previousUpdate = previous,
        nextPrompt = UpdatePromptPolicy.Prompt.INSTALL,
        nextUpdate = previous.copy(downloadId = 42L),
      ),
    )
    assertFalse(
      UpdatePromptPolicy.retainsError(
        previousUpdate = previous,
        nextPrompt = UpdatePromptPolicy.Prompt.INSTALL,
        nextUpdate = previous.copy(assetIdentity = "github-asset:1002"),
      ),
    )
  }
}
