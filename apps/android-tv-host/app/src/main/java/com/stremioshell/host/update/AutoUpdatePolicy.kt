package com.stremioshell.host.update

object AutoUpdatePolicy {
  enum class Decision {
    START_DOWNLOAD,
    ALREADY_DOWNLOADED,
    DOWNLOAD_IN_PROGRESS,

    /**
     * This exact release was already downloaded once and refused by the integrity or archive
     * check. Downloading it again would cost another ~117 MB to reach the identical verdict.
     */
    REJECTED_RELEASE,
    NO_UPDATE
  }

  /**
   * @param isRejectedRelease whether [updateInfo]'s version is the one this device has already
   *   downloaded and thrown away. Several of the archive verdicts are permanent for a given pair
   *   of builds - a release published without a versionCode bump is NOT_NEWER however many times
   *   it is fetched, a differently-signed local build is UNTRUSTED_SIGNER forever - and the
   *   six-hourly worker used to re-download the same rejected archive indefinitely.
   */
  fun decide(
    updateInfo: UpdateInfo?,
    hasDownloadedForVersion: Boolean,
    hasActiveDownload: Boolean,
    isRejectedRelease: Boolean = false
  ): Decision {
    if (updateInfo == null) {
      return Decision.NO_UPDATE
    }
    if (hasActiveDownload) {
      return Decision.DOWNLOAD_IN_PROGRESS
    }
    if (hasDownloadedForVersion) {
      return Decision.ALREADY_DOWNLOADED
    }
    if (isRejectedRelease) {
      return Decision.REJECTED_RELEASE
    }
    return Decision.START_DOWNLOAD
  }
}
