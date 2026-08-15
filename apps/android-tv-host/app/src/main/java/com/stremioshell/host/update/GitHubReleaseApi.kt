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
  val sha256: String? = null,
  /** Immutable for one upload; deleting and re-uploading a corrected asset produces a new id. */
  val id: Long? = null,
)

internal data class GitHubLatestReleaseDto(
  val tagName: String,
  val htmlUrl: String?,
  val body: String?,
  val publishedAt: String?,
  val assets: List<GitHubAssetDto>,
  val prerelease: Boolean = false,
  val draft: Boolean = false,
)

internal class GitHubApiException(
  val statusCode: Int,
  val rateLimited: Boolean,
  val retryAfterSeconds: Long?,
  val rateLimitResetEpochSeconds: Long?,
  message: String,
) : IllegalStateException(message)

/**
 * The GitHub releases endpoint, over the app's shared OkHttp client.
 *
 * Blocking on purpose: its one caller is a WorkManager worker. Going through the shared client
 * buys three things a bare HttpURLConnection did not have - the app's connection pool, a response
 * that is always closed, and the disk cache. The last one matters most: the releases list carries
 * an ETag, so a repeat check revalidates and comes back 304 without spending one of the sixty
 * anonymous API calls an hour this app is allowed.
 */
class GitHubReleaseApi {
  internal fun fetchReleases(owner: String, repo: String): List<GitHubLatestReleaseDto> {
    val request = Request.Builder()
      // Unlike /releases/latest, this includes prereleases. Stable installs filter them in the
      // repository; an installed beta needs them to discover beta.2 before stable exists.
      .url("https://api.github.com/repos/$owner/$repo/releases?per_page=$MAX_RELEASES_PER_PAGE")
      // GitHub rejects anonymous API requests that do not identify themselves.
      .header("User-Agent", "StremioShell")
      .header("Accept", "application/vnd.github+json")
      .build()

    val body = SharedHttpClient.client.newCall(request).execute().use { response ->
      // Read before the status check so a failure can still quote what the service said; the
      // limit applies either way, since an error page is not a size we control.
      val text = response.body?.readUtf8Limited(MAX_JSON_RESPONSE_BYTES).orEmpty()
      if (!response.isSuccessful) {
        throw apiException(
          statusCode = response.code,
          retryAfterHeader = response.header("Retry-After"),
          rateLimitRemainingHeader = response.header("X-RateLimit-Remaining"),
          rateLimitResetHeader = response.header("X-RateLimit-Reset"),
          body = text,
        )
      }
      text
    }

    return parseReleases(JSONArray(body))
  }

  internal companion object {
    /** GitHub prefixes the asset digest with its algorithm, e.g. `sha256:9f86d0...`. */
    private const val SHA256_DIGEST_PREFIX = "sha256:"
    private const val MAX_RELEASES_PER_PAGE = 100

    internal fun parseReleases(json: JSONArray): List<GitHubLatestReleaseDto> = buildList {
      for (index in 0 until json.length()) {
        json.optJSONObject(index)?.let { add(parseRelease(it)) }
      }
    }

    internal fun parseRelease(json: JSONObject): GitHubLatestReleaseDto = GitHubLatestReleaseDto(
      tagName = json.optTrimmedOrNull("tag_name").orEmpty(),
      htmlUrl = json.optTrimmedOrNull("html_url"),
      body = json.optTrimmedOrNull("body"),
      publishedAt = json.optTrimmedOrNull("published_at"),
      assets = parseAssets(json.optJSONArray("assets")),
      prerelease = json.optBoolean("prerelease", false),
      draft = json.optBoolean("draft", false),
    )

    /** GitHub uses both 403 and 429 for rate limiting; headers and its error body disambiguate 403. */
    internal fun apiException(
      statusCode: Int,
      retryAfterHeader: String?,
      rateLimitRemainingHeader: String?,
      rateLimitResetHeader: String?,
      body: String,
    ): GitHubApiException {
      val retryAfterSeconds = retryAfterHeader?.trim()?.toLongOrNull()?.takeIf { it >= 0L }
      val resetEpochSeconds = rateLimitResetHeader?.trim()?.toLongOrNull()?.takeIf { it >= 0L }
      val rateLimited = statusCode == 429 || (
        statusCode == 403 && (
          rateLimitRemainingHeader?.trim() == "0" ||
            retryAfterSeconds != null ||
            body.contains("rate limit", ignoreCase = true)
          )
        )
      val summary = body.trim().replace(Regex("\\s+"), " ").take(512)
      return GitHubApiException(
        statusCode = statusCode,
        rateLimited = rateLimited,
        retryAfterSeconds = retryAfterSeconds,
        rateLimitResetEpochSeconds = resetEpochSeconds,
        message = "GitHub API error $statusCode${summary.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()}",
      )
    }

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
          sha256 = parseSha256Digest(assetJson.optTrimmedOrNull("digest")),
          id = assetJson.optLong("id").takeIf { it > 0L },
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
