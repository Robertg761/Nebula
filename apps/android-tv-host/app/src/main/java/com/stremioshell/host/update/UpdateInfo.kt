package com.stremioshell.host.update

data class UpdateInfo(
  val latestVersionName: String,
  val apkName: String,
  val apkUrl: String,
  val releaseNotes: String = "",
  val releaseUrl: String = "",
  val apkSizeBytes: Long? = null,
  /**
   * Lower-case hex SHA-256 the release published for [apkName], when it published one. The
   * updater verifies the downloaded file against this; [apkSizeBytes] is the fallback.
   */
  val apkSha256: String? = null,
  val publishedAt: String? = null,
  /** Immutable GitHub asset id. Re-uploading corrected bytes creates a new id even under one tag. */
  val apkAssetId: Long? = null,
) {
  /**
   * Identity used to decide whether a permanent archive rejection still describes this asset.
   *
   * GitHub's numeric asset id is preferred because deleting and re-uploading a corrected file under
   * the same release tag, name and URL creates a new id. The published digest is an equally useful
   * fallback for responses or test repositories that omit the id. With neither, the updater declines
   * to blacklist the release permanently rather than risk hiding corrected bytes forever.
   */
  internal fun apkAssetIdentity(): String? {
    apkAssetId?.takeIf { it > 0L }?.let { return "github-asset:$it" }
    val digest = apkSha256?.trim()?.lowercase()
      ?.takeIf { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }
    return digest?.let { "sha256:$it" }
  }
}
