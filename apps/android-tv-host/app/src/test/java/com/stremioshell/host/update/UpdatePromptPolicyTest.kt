package com.stremioshell.host.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdatePromptPolicyTest {
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
}
