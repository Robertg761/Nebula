package com.stremioshell.host.tv.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The platform-neutral part of handing a TMDB trailer to another app.
 *
 * TMDB supplies a YouTube video id rather than a URL. Keeping the id on a strict allow-list both
 * prevents a malformed remote value from changing the destination and makes the request testable
 * without Android's local-unit-test stubs.
 */
data class TrailerLaunchRequest(
  val action: String,
  val uri: String,
  val categories: Set<String>,
)

enum class TrailerLaunchResult {
  Opened,
  InvalidVideo,
  NoHandler,
}

object TrailerLaunchPolicy {
  const val ACTION_VIEW = "android.intent.action.VIEW"
  const val CATEGORY_BROWSABLE = "android.intent.category.BROWSABLE"
  private val YOUTUBE_VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")

  fun request(rawVideoId: String?): TrailerLaunchRequest? {
    val videoId = rawVideoId?.trim()?.takeIf(YOUTUBE_VIDEO_ID::matches) ?: return null
    return TrailerLaunchRequest(
      action = ACTION_VIEW,
      uri = "https://www.youtube.com/watch?v=$videoId",
      categories = setOf(CATEGORY_BROWSABLE),
    )
  }

  /**
   * Attempts one external launch. [open] returns false when the platform has no app that can
   * handle the request, which lets Details keep the viewer in place and explain what happened.
   */
  fun launch(
    rawVideoId: String?,
    open: (TrailerLaunchRequest) -> Boolean,
  ): TrailerLaunchResult {
    val request = request(rawVideoId) ?: return TrailerLaunchResult.InvalidVideo
    return if (open(request)) TrailerLaunchResult.Opened else TrailerLaunchResult.NoHandler
  }
}

/** Android adapter for [TrailerLaunchPolicy]. The HTTPS intent can be handled by YouTube or a browser. */
object TrailerExternalLauncher {
  fun launch(context: Context, rawVideoId: String?): TrailerLaunchResult =
    TrailerLaunchPolicy.launch(rawVideoId) { request ->
      val intent = Intent(request.action, Uri.parse(request.uri)).apply {
        request.categories.forEach(::addCategory)
      }
      try {
        context.startActivity(intent)
        true
      } catch (_: ActivityNotFoundException) {
        false
      } catch (_: SecurityException) {
        false
      }
    }
}
