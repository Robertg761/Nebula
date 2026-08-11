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
  val publishedAt: String? = null
)
