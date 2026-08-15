package com.stremioshell.host.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateStatusReducerTest {

  @Test
  fun `queueing a check does not invent a successful check time`() {
    val queued = UpdateStatusReducer.queued(UpdateStatus())

    assertEquals(UpdateStatusPhase.CHECK_QUEUED, queued.phase)
    assertNull(queued.lastSuccessfulCheckAtMs)
    assertNull(queued.lastFailedCheckAtMs)
  }

  @Test
  fun `queueing preserves historical result times without calling either result current`() {
    val previous = UpdateStatus(
      phase = UpdateStatusPhase.FAILED,
      lastSuccessfulCheckAtMs = 100L,
      lastFailedCheckAtMs = 200L,
      failureKind = UpdateFailureKind.NETWORK,
    )

    val queued = UpdateStatusReducer.queued(previous)

    assertEquals(UpdateStatusPhase.CHECK_QUEUED, queued.phase)
    assertEquals(100L, queued.lastSuccessfulCheckAtMs)
    assertEquals(200L, queued.lastFailedCheckAtMs)
    assertNull(queued.failureKind)
  }

  @Test
  fun `duplicate manual press cannot move an active check backward`() {
    val checking = UpdateStatus(
      phase = UpdateStatusPhase.CHECKING,
      lastSuccessfulCheckAtMs = 100L,
    )

    assertEquals(checking, UpdateStatusReducer.queued(checking))
  }

  @Test
  fun `late scheduler callback cannot overwrite a check that already completed`() {
    val completed = UpdateStatus(
      phase = UpdateStatusPhase.UP_TO_DATE,
      lastSuccessfulCheckAtMs = 301L,
    )

    assertEquals(completed, UpdateStatusReducer.queued(completed, requestedAtMs = 300L))
  }

  @Test
  fun `download queue failure records a successful repository check and failed attempt`() {
    val failed = UpdateStatusReducer.failedCheck(
      previous = UpdateStatus(phase = UpdateStatusPhase.CHECKING),
      failureKind = UpdateFailureKind.DOWNLOAD,
      retryScheduled = true,
      failedAtMs = 300L,
      successfulCheckAtMs = 250L,
      targetVersionName = "v1.2.3",
    )

    assertEquals(UpdateStatusPhase.RETRY_SCHEDULED, failed.phase)
    assertEquals(250L, failed.lastSuccessfulCheckAtMs)
    assertEquals(300L, failed.lastFailedCheckAtMs)
    assertEquals(UpdateFailureKind.DOWNLOAD, failed.failureKind)
    assertEquals("v1.2.3", failed.targetVersionName)
  }

  @Test
  fun `runtime download observations do not rewrite check times`() {
    val checked = UpdateStatus(
      phase = UpdateStatusPhase.DOWNLOAD_QUEUED,
      lastSuccessfulCheckAtMs = 400L,
      lastFailedCheckAtMs = 200L,
      targetVersionName = "1.2.3",
    )

    val downloading = UpdateStatusReducer.runtimeState(
      checked,
      UpdateStatusPhase.DOWNLOADING,
      "1.2.3",
    )

    assertEquals(400L, downloading.lastSuccessfulCheckAtMs)
    assertEquals(200L, downloading.lastFailedCheckAtMs)
  }

  @Test
  fun `a completed install resolves a stale ready ledger to up to date`() {
    val ready = UpdateStatus(
      phase = UpdateStatusPhase.READY,
      lastSuccessfulCheckAtMs = 400L,
      lastFailedCheckAtMs = 200L,
      targetVersionName = "1.2.3",
    )

    val installed = UpdateStatusReducer.installedTarget(
      previous = ready,
      targetVersionName = "1.2.3",
      currentVersionName = "1.2.3",
    )

    assertEquals(UpdateStatusPhase.UP_TO_DATE, installed.phase)
    assertEquals(400L, installed.lastSuccessfulCheckAtMs)
    assertEquals(200L, installed.lastFailedCheckAtMs)
    assertNull(installed.failureKind)
    assertNull(installed.targetVersionName)
    assertEquals(
      UpdateStatusPhase.UP_TO_DATE,
      UpdateStatusReducer.installedTarget(
        previous = ready,
        targetVersionName = "1.2.3",
        currentVersionName = "1.2.4",
      ).phase,
    )
    assertThrows(IllegalArgumentException::class.java) {
      UpdateStatusReducer.installedTarget(
        previous = ready,
        targetVersionName = "1.2.4",
        currentVersionName = "1.2.3",
      )
    }
  }

  @Test
  fun `a missing update that is still newer than the app is a download failure`() {
    val ready = UpdateStatus(
      phase = UpdateStatusPhase.READY,
      lastSuccessfulCheckAtMs = 400L,
      targetVersionName = "1.2.3",
    )

    val missing = UpdateStatusReducer.failedCheck(
      previous = ready,
      failureKind = UpdateFailureKind.DOWNLOAD,
      retryScheduled = false,
      failedAtMs = 500L,
      targetVersionName = "1.2.3",
    )

    assertEquals(UpdateStatusPhase.FAILED, missing.phase)
    assertEquals(400L, missing.lastSuccessfulCheckAtMs)
    assertEquals(500L, missing.lastFailedCheckAtMs)
    assertEquals(UpdateFailureKind.DOWNLOAD, missing.failureKind)
    assertEquals("1.2.3", missing.targetVersionName)
  }

  @Test
  fun `remote version labels are trimmed and bounded before persistence`() {
    val checked = UpdateStatusReducer.successfulCheck(
      previous = UpdateStatus(),
      phase = UpdateStatusPhase.DOWNLOAD_QUEUED,
      checkedAtMs = 500L,
      targetVersionName = "  ${"x".repeat(100)}  ",
    )

    assertEquals("x".repeat(64), checked.targetVersionName)
  }
}
