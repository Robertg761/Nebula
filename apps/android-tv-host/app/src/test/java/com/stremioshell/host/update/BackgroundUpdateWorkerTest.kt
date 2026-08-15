package com.stremioshell.host.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class BackgroundUpdateWorkerTest {

  @Test
  fun `returns true for UnknownHostException`() {
    assertTrue(BackgroundUpdateWorker.isRetryable(UnknownHostException()))
  }

  @Test
  fun `returns true for SocketTimeoutException`() {
    assertTrue(BackgroundUpdateWorker.isRetryable(SocketTimeoutException()))
  }

  @Test
  fun `returns true for IOException`() {
    assertTrue(BackgroundUpdateWorker.isRetryable(IOException()))
  }

  @Test
  fun `returns true for generic exception with GitHub API error 429`() {
    assertTrue(BackgroundUpdateWorker.isRetryable(RuntimeException("GitHub API error 429")))
  }

  @Test
  fun `returns true for a typed GitHub rate limit 403`() {
    val error = GitHubReleaseApi.apiException(
      statusCode = 403,
      retryAfterHeader = "60",
      rateLimitRemainingHeader = null,
      rateLimitResetHeader = null,
      body = "secondary rate limit",
    )

    assertTrue(BackgroundUpdateWorker.isRetryable(error))
    assertTrue(BackgroundUpdateWorker.isRetryable(RuntimeException("wrapped", error)))
  }

  @Test
  fun `returns false for an ordinary typed GitHub forbidden response`() {
    val error = GitHubReleaseApi.apiException(
      statusCode = 403,
      retryAfterHeader = null,
      rateLimitRemainingHeader = "59",
      rateLimitResetHeader = null,
      body = "Resource not accessible",
    )

    assertFalse(BackgroundUpdateWorker.isRetryable(error))
  }

  @Test
  fun `returns true for generic exception with GitHub API error 500`() {
    assertTrue(BackgroundUpdateWorker.isRetryable(RuntimeException("GitHub API error 500")))
  }

  @Test
  fun `returns true for generic exception with GitHub API error 502`() {
    assertTrue(BackgroundUpdateWorker.isRetryable(RuntimeException("GitHub API error 502")))
  }

  @Test
  fun `returns true for generic exception with GitHub API error 599`() {
    assertTrue(BackgroundUpdateWorker.isRetryable(RuntimeException("GitHub API error 599")))
  }

  @Test
  fun `returns false for generic exception with GitHub API error 400`() {
    assertFalse(BackgroundUpdateWorker.isRetryable(RuntimeException("GitHub API error 400")))
  }

  @Test
  fun `returns false for generic exception with GitHub API error 404`() {
    assertFalse(BackgroundUpdateWorker.isRetryable(RuntimeException("GitHub API error 404")))
  }

  @Test
  fun `returns false for generic exception with GitHub API error 499`() {
    assertFalse(BackgroundUpdateWorker.isRetryable(RuntimeException("GitHub API error 499")))
  }

  @Test
  fun `returns false for generic exception with GitHub API error 600`() {
    assertFalse(BackgroundUpdateWorker.isRetryable(RuntimeException("GitHub API error 600")))
  }

  @Test
  fun `returns false for generic exception with random message`() {
    assertFalse(BackgroundUpdateWorker.isRetryable(RuntimeException("Some random error")))
  }

  @Test
  fun `returns false for generic exception with no message`() {
    assertFalse(BackgroundUpdateWorker.isRetryable(RuntimeException()))
  }

  @Test
  fun `returns false for general Exception`() {
    assertFalse(BackgroundUpdateWorker.isRetryable(Exception("Unknown error")))
  }

  @Test
  fun `classifies check failures without retaining throwable messages`() {
    val rateLimit = GitHubReleaseApi.apiException(
      statusCode = 429,
      retryAfterHeader = "60",
      rateLimitRemainingHeader = "0",
      rateLimitResetHeader = null,
      body = "request body that must not be retained",
    )
    val server = GitHubReleaseApi.apiException(
      statusCode = 503,
      retryAfterHeader = null,
      rateLimitRemainingHeader = null,
      rateLimitResetHeader = null,
      body = "provider details",
    )

    assertEquals(
      UpdateFailureKind.RATE_LIMITED,
      BackgroundUpdateWorker.failureKind(rateLimit, duringDownload = false),
    )
    assertEquals(
      UpdateFailureKind.SERVER,
      BackgroundUpdateWorker.failureKind(server, duringDownload = false),
    )
    assertEquals(
      UpdateFailureKind.NETWORK,
      BackgroundUpdateWorker.failureKind(
        UnknownHostException("private host name"),
        duringDownload = false,
      ),
    )
    assertEquals(
      UpdateFailureKind.DOWNLOAD,
      BackgroundUpdateWorker.failureKind(
        IOException("private local path"),
        duringDownload = true,
      ),
    )
  }
}
