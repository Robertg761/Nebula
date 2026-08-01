package com.stremioshell.host.update

import com.stremioshell.host.tv.data.MAX_JSON_RESPONSE_BYTES
import com.stremioshell.host.tv.data.SharedHttpClient
import com.stremioshell.host.tv.data.readUtf8Limited
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal data class GitHubAssetDto(
  val name: String,
  val browserDownloadUrl: String,
  val size: Long?
)

internal data class GitHubLatestReleaseDto(
  val tagName: String,
  val htmlUrl: String?,
  val body: String?,
  val publishedAt: String?,
  val assets: List<GitHubAssetDto>
)

/**
 * The GitHub releases endpoint, over the app's shared OkHttp client.
 *
 * Blocking on purpose: its one caller is a WorkManager worker. Going through the shared client
 * buys three things a bare HttpURLConnection did not have - the app's connection pool, a response
 * that is always closed, and the disk cache. The last one matters most: `releases/latest` carries
 * an ETag, so a repeat check revalidates and comes back 304 without spending one of the sixty
 * anonymous API calls an hour this app is allowed.
 */
class GitHubReleaseApi {
  internal fun fetchLatestRelease(owner: String, repo: String): GitHubLatestReleaseDto {
    val request = Request.Builder()
      .url("https://api.github.com/repos/$owner/$repo/releases/latest")
      // GitHub rejects anonymous API requests that do not identify themselves.
      .header("User-Agent", "StremioShell")
      .header("Accept", "application/vnd.github+json")
      .build()

    val body = SharedHttpClient.client.newCall(request).execute().use { response ->
      // Read before the status check so a failure can still quote what the service said; the
      // limit applies either way, since an error page is not a size we control.
      val text = response.body?.readUtf8Limited(MAX_JSON_RESPONSE_BYTES).orEmpty()
      if (!response.isSuccessful) {
        // BackgroundUpdateWorker.isRetryable reads the status back out of this message.
        throw IllegalStateException("GitHub API error ${response.code}: $text")
      }
      text
    }

    val json = JSONObject(body)
    val assets = parseAssets(json.optJSONArray("assets"))
    return GitHubLatestReleaseDto(
      tagName = json.optString("tag_name").orEmpty(),
      htmlUrl = json.optString("html_url").takeIf { it.isNotBlank() },
      body = json.optString("body").takeIf { it.isNotBlank() },
      publishedAt = json.optString("published_at").takeIf { it.isNotBlank() },
      assets = assets
    )
  }

  private fun parseAssets(rawAssets: JSONArray?): List<GitHubAssetDto> {
    if (rawAssets == null || rawAssets.length() == 0) {
      return emptyList()
    }

    val parsed = mutableListOf<GitHubAssetDto>()
    for (index in 0 until rawAssets.length()) {
      val assetJson = rawAssets.optJSONObject(index) ?: continue
      val name = assetJson.optString("name").orEmpty().trim()
      val downloadUrl = assetJson.optString("browser_download_url").orEmpty().trim()
      if (name.isBlank() || downloadUrl.isBlank()) {
        continue
      }
      val size = assetJson.optLong("size").takeIf { it > 0L }
      parsed += GitHubAssetDto(
        name = name,
        browserDownloadUrl = downloadUrl,
        size = size
      )
    }
    return parsed
  }
}
