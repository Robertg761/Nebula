package com.stremioshell.host.tv.data.addon

import java.util.Locale
/** What one addon had to say when the whole list was asked for a title's streams. */
data class AddonFetch(
  /** The addon's short name, as [AddonList.labels] spelled it. */
  val label: String,
  /** Its rows, or null when the request failed or ran out of time. */
  val streams: List<AddonStream>?,
)

/**
 * One list of streams assembled from every configured addon, plus what to say
 * about the ones that did not answer.
 */
data class MergedStreams(
  val streams: List<AddonStream>,
  /** Names the addons that failed, or null when none did. */
  val notice: String?,
  /** True only when every addon failed, which is a failed load rather than a partial one. */
  val allFailed: Boolean,
)

/**
 * Folds several addons' answers into the one list the picker shows.
 *
 * Ordering: the merged list is sorted by [StreamOrder], *not* grouped by addon.
 * Addon-major order would put the first addon's 480p rows above the second's 4K
 * remux, which is the exact complaint [StreamOrder] exists to answer - and a
 * viewer who added a second addon did it to be offered better releases, not to
 * scroll past the first addon's entire catalogue to reach them. The addon list's
 * order still decides everything a quality tier cannot: [StreamOrder] sorts
 * stably, so inside a tier the rows stay in addon order, and a duplicate release
 * is kept in the copy the earlier addon returned.
 */
object StreamMerge {
  /**
   * A remote-friendly ceiling after quality sorting. Keeping thousands of releases composed
   * consumes memory and makes a D-pad list effectively unnavigable; the highest-quality unique
   * rows survive regardless of which addon returned them.
   */
  const val MAX_MERGED_STREAMS = 500

  fun merge(
    fetches: List<AddonFetch>,
    /**
     * Off for a single addon: a badge naming the only possible source is noise on
     * every row of a list that is already dense.
     */
    labelSources: Boolean = fetches.size > 1,
  ): MergedStreams {
    val answered = fetches.filter { it.streams != null }
    val tagged = answered.flatMap { fetch ->
      fetch.streams.orEmpty().map { if (labelSources) it.copy(source = fetch.label) else it }
    }
    return MergedStreams(
      streams = StreamOrder.byQuality(dedupe(tagged)).take(MAX_MERGED_STREAMS),
      notice = failureNotice(fetches.filter { it.streams == null }.map { it.label }),
      // An empty list of addons is "nothing configured", which the caller reports
      // its own way; only an addon that was actually asked can have failed.
      allFailed = fetches.isNotEmpty() && answered.isEmpty(),
    )
  }

  /**
   * Drops rows that are the same release twice, keeping the first - so the addon
   * higher up the viewer's list is the one whose row (and whose resolved link)
   * survives.
   *
   * Two identities, because addons hand back two different kinds of duplicate.
   * An identical URL is the same link. An identical `infoHash` (with the same
   * `fileIdx`) is the same file inside the same torrent, which two debrid
   * resolvers pointed at one account will both return under URLs signed
   * differently - the duplicate that a url-only check misses entirely.
   */
  fun dedupe(streams: List<AddonStream>): List<AddonStream> {
    val seenUrls = mutableSetOf<String>()
    val seenFiles = mutableSetOf<String>()
    return streams.filter { stream ->
      val url = stream.url?.trim()?.takeIf { it.isNotEmpty() }
      val file = fileIdentity(stream)
      // A row with neither identity cannot be matched against anything, so it is
      // kept: dropping it would lose a stream rather than a duplicate.
      if (url == null && file == null) return@filter true
      val duplicate = (url != null && url in seenUrls) || (file != null && file in seenFiles)
      if (duplicate) return@filter false
      url?.let { seenUrls += it }
      file?.let { seenFiles += it }
      true
    }
  }

  /**
   * Which file inside which torrent a row points at, or null when it does not say.
   *
   * `fileIdx` answers this exactly when the addon supplies it. When it does not - and plenty do
   * not for season packs - `infoHash` alone is not an identity: every episode of a twenty-episode
   * pack shares it, so folding them onto one key collapsed a whole season into a single row and
   * lost nineteen streams as "duplicates". A name is the discriminator that is left.
   *
   * The file name is preferred over the row's own label because it is the part two addons
   * describing the same file agree on; a label carries seeders, size and the addon's own branding.
   * The cost is one-sided and is the right way round: two addons that spell a pack entry
   * differently now produce two rows where one would do, which is a longer list, rather than one
   * row where there should be twenty, which is a missing episode.
   */
  private fun fileIdentity(stream: AddonStream): String? {
    val hash = stream.infoHash?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
      ?: return null
    val index = stream.fileIdx
    if (index != null) return "$hash/$index"
    return "$hash/${fileDiscriminator(stream)}"
  }

  /** The most file-like text a row offers, flattened so spacing alone cannot split an identity. */
  private fun fileDiscriminator(stream: AddonStream): String =
    listOfNotNull(stream.behaviorHints?.filename, stream.title, stream.name)
      .firstOrNull { it.isNotBlank() }
      ?.lowercase(Locale.ROOT)
      ?.replace(WHITESPACE, " ")
      ?.trim()
      // Nothing to tell them apart by: back to hash-only, which is where this started, and the
      // rows really are indistinguishable from here.
      .orEmpty()

  private val WHITESPACE = Regex("\\s+")

  /** "Couldn't reach Comet and Torrentio." - one sentence, whatever the count. */
  fun failureNotice(labels: List<String>): String? {
    val named = labels.filter { it.isNotBlank() }
    return when (named.size) {
      0 -> null
      1 -> "Couldn't reach ${named[0]}."
      else -> "Couldn't reach ${named.dropLast(1).joinToString(", ")} and ${named.last()}."
    }
  }
}
