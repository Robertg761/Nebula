package com.stremioshell.host.update

import com.stremioshell.host.tv.data.MAX_JSON_RESPONSE_BYTES
import com.stremioshell.host.tv.data.SharedHttpClient
import com.stremioshell.host.tv.data.readUtf8Limited
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal data class GitHubAssetDto(
  val name: String,
  val browserDownloadUrl: String,
  val size: Long?,
  /** Lower-case hex SHA-256 from the asset's `digest` field, null when the release omits it. */
  val sha256: String? = null
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

    return parseRelease(JSONObject(body))
  }

  internal companion object {
    /** GitHub prefixes the asset digest with its algorithm, e.g. `sha256:9f86d0...`. */
    private const val SHA256_DIGEST_PREFIX = "sha256:"

    internal fun parseRelease(json: JSONObject): GitHubLatestReleaseDto = GitHubLatestReleaseDto(
      tagName = json.optTrimmedOrNull("tag_name").orEmpty(),
      htmlUrl = json.optTrimmedOrNull("html_url"),
      body = json.optTrimmedOrNull("body"),
      publishedAt = json.optTrimmedOrNull("published_at"),
      assets = parseAssets(json.optJSONArray("assets"))
    )

    internal fun parseAssets(rawAssets: JSONArray?): List<GitHubAssetDto> {
      if (rawAssets == null || rawAssets.length() == 0) {
        return emptyList()
      }

      val parsed = mutableListOf<GitHubAssetDto>()
      for (index in 0 until rawAssets.length()) {
        val assetJson = rawAssets.optJSONObject(index) ?: continue
        val name = assetJson.optTrimmedOrNull("name") ?: continue
        val downloadUrl = assetJson.optTrimmedOrNull("browser_download_url") ?: continue
        val size = assetJson.optLong("size").takeIf { it > 0L }
        parsed += GitHubAssetDto(
          name = name,
          browserDownloadUrl = downloadUrl,
          size = size,
          // Already in the JSON this call fetched. Ignoring it left the updater checking a
          // ~117 MB download by byte count alone; see DownloadIntegrityPolicy.
          sha256 = parseSha256Digest(assetJson.optTrimmedOrNull("digest"))
        )
      }
      return parsed
    }

    /**
     * The hex body of a `sha256:` digest, or null for any other algorithm, a truncated value, or
     * a field GitHub did not send. Nothing downstream guesses at a malformed digest: an absent
     * one falls back to the release asset's byte size.
     */
    internal fun parseSha256Digest(raw: String?): String? {
      val value = raw?.trim().orEmpty()
      if (!value.startsWith(SHA256_DIGEST_PREFIX, ignoreCase = true)) {
        return null
      }
      val hex = value.substring(SHA256_DIGEST_PREFIX.length).trim().lowercase(Locale.ROOT)
      return hex.takeIf { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }
    }

    /**
     * org.json has no nullable string accessor: on Android `optString` renders a JSON null as the
     * four-character string "null", which is how a release published with no body once became an
     * update prompt whose release notes read `null`. [JSONObject.isNull] is the only way to tell
     * an absent or null field from a present one.
     */
    private fun JSONObject.optTrimmedOrNull(key: String): String? {
      if (isNull(key)) {
        return null
      }
      return optString(key).trim().takeIf { it.isNotEmpty() }
    }
  }
}
