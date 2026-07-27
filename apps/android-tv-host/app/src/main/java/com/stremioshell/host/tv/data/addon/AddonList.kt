package com.stremioshell.host.tv.data.addon

/**
 * The viewer's ordered list of stream addons.
 *
 * Order is the viewer's own preference and is load-bearing twice over: it is the
 * order duplicate releases are resolved in (the first addon offering a release is
 * the one whose row survives) and the tie-break inside a resolution tier, so an
 * addon moved to the top genuinely gets looked at first.
 *
 * Everything here is pure so the rules that decide what a stored list contains -
 * migration above all - can be tested without DataStore.
 */
object AddonList {
  /**
   * Every addon is queried on every stream request, so the list is capped: past a
   * point the slowest addon is all the viewer is waiting for, and a TV's five
   * connections per host are not free.
   */
  const val MAX_ADDONS = 8

  /** Fallback for a URL with nothing host-shaped in it, so a row is never nameless. */
  private const val UNNAMED = "Addon"

  private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
  private const val MANIFEST_SUFFIX = "/manifest.json"

  /**
   * The stored form of a URL a viewer typed or pasted.
   *
   * Three things get fixed here because all three are what a TV keyboard and a
   * copied browser link actually produce:
   *  - `stremio://` install links, which no HTTP client can fetch;
   *  - a bare host, because typing `https://` on a remote is a minute of work;
   *  - a missing `/manifest.json`, which [AddonClient.streamUrl] rejects outright.
   *
   * Idempotent: a URL that is already in this form comes back unchanged, which is
   * what lets migration run it over a value that has been working for months.
   */
  fun normalize(raw: String): String {
    var value = raw.trim()
    if (value.isEmpty()) return ""
    for (scheme in STREMIO_SCHEMES) {
      if (value.startsWith(scheme, ignoreCase = true)) {
        value = "https://" + value.substring(scheme.length)
        break
      }
    }
    if (!value.contains("://")) value = "https://$value"
    // A scheme and nothing else is not a URL that can be completed into one.
    if (value.substringAfter("://").isBlank()) return ""
    value = value.trimEnd('/')
    // A query or fragment means the path is already whatever the addon author
    // intended; appending to it would produce a route that matches nothing.
    val completable = !value.contains('?') && !value.contains('#')
    if (completable && !value.endsWith(MANIFEST_SUFFIX, ignoreCase = true)) {
      value += MANIFEST_SUFFIX
    }
    return value
  }

  /**
   * What the stored preferences mean, given that installs predating the list only
   * ever wrote a single URL.
   *
   * [stored] is null when the list key has never been written - the only case the
   * legacy value is consulted. An empty stored list is a viewer who removed their
   * last addon, and resurrecting the old one there would make removal impossible.
   */
  fun migrated(stored: List<String>?, legacy: String): List<String> {
    if (stored != null) return sanitized(stored)
    return sanitized(listOf(legacy))
  }

  /** Appends [raw] unless it is blank or already present. */
  fun added(list: List<String>, raw: String): List<String> {
    val url = normalize(raw)
    if (url.isEmpty() || url in list) return list
    if (list.size >= MAX_ADDONS) return list
    return list + url
  }

  fun removed(list: List<String>, url: String): List<String> = list.filterNot { it == url }

  /**
   * Swaps the first entry for [raw], which is what the single-URL callers that
   * predate the list (the phone pairing form, the debug launch intent) mean when
   * they set "the" addon URL. A blank value leaves the list alone rather than
   * clearing it: those callers spell "unchanged" as blank.
   */
  fun replacingFirst(list: List<String>, raw: String): List<String> {
    val url = normalize(raw)
    if (url.isEmpty()) return list
    return sanitized(listOf(url) + list.drop(1))
  }

  /**
   * A short name for an addon, taken from the first label of its host: addons are
   * near-universally deployed as `comet.example.com` or `torrentio.strem.fun`, and
   * the brand is the part a viewer recognises on a stream row.
   */
  fun label(url: String): String {
    val host = hostOf(url)
    if (host.isEmpty()) return UNNAMED
    // A self-hosted addon on the LAN has no brand in its address, and "192" is
    // worse than useless as a row badge.
    if (IPV4.matches(host)) return host
    val first = host.removePrefix("www.").substringBefore('.')
    if (first.isEmpty()) return host
    return first.replaceFirstChar { it.uppercase() }
  }

  /**
   * [label] for every URL, with collisions numbered. Two configurations of the
   * same addon - a cached-only Comet and an everything Comet - are a real setup,
   * and two rows both badged "Comet" would say nothing.
   */
  fun labels(urls: List<String>): List<String> {
    val base = urls.map { label(it) }
    val totals = base.groupingBy { it }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return base.map { name ->
      if (totals[name] == 1) return@map name
      val nth = (seen[name] ?: 0) + 1
      seen[name] = nth
      "$name $nth"
    }
  }

  /** Normalized, blank-free, duplicate-free and capped - the invariants of a stored list. */
  fun sanitized(list: List<String>): List<String> = list
    .map { normalize(it) }
    .filter { it.isNotEmpty() }
    .distinct()
    .take(MAX_ADDONS)

  private fun hostOf(url: String): String = url
    .substringAfter("://", url)
    .substringBefore('/')
    .substringBefore('?')
    .substringBefore('#')
    // Userinfo and port are addressing, not identity.
    .substringAfterLast('@')
    .substringBefore(':')
    .lowercase()

  private val STREMIO_SCHEMES = listOf("stremio://", "stremio:")
}
