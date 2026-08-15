package com.stremioshell.host.tv.data.addon

import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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

  /** A manifest URL may contain a provider token, but never needs an unbounded path or query. */
  const val MAX_URL_CHARS = 4 * 1024

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
    if (raw.length > MAX_URL_CHARS) return ""
    var value = raw.trim()
    if (value.isEmpty()) return ""
    for (scheme in STREMIO_SCHEMES) {
      if (value.startsWith(scheme, ignoreCase = true)) {
        val remainder = value.substring(scheme.length)
        // Both spellings of an install link are in the wild, and the bare-`stremio:` one wraps a
        // whole URL rather than a host: "stremio:https://host/manifest.json" is what an addon's
        // own Install button hands over. Prefixing that produced "https://https://host/...",
        // which parses, passes every check below, and is stored as an addon that can never answer.
        value = if (startsWithHttpScheme(remainder)) remainder else "https://$remainder"
        break
      }
    }
    // A mistyped explicit scheme must not be "fixed" into a host called
    // `http`/`https`, and addon credentials must never travel over cleartext.
    if (
      value.startsWith("http:", ignoreCase = true) &&
      !value.startsWith("http://", ignoreCase = true)
    ) {
      return ""
    }
    if (
      value.startsWith("https:", ignoreCase = true) &&
      !value.startsWith("https://", ignoreCase = true)
    ) {
      return ""
    }
    if (!value.contains("://")) value = "https://$value"
    val parsed = value.toHttpUrlOrNull() ?: return ""
    if (!parsed.isHttps || parsed.host.isBlank()) return ""

    val path = parsed.encodedPath.trimEnd('/')
    val manifestPath = if (path.endsWith(MANIFEST_SUFFIX, ignoreCase = true)) {
      path.dropLast(MANIFEST_SUFFIX.length) + MANIFEST_SUFFIX
    } else {
      path + MANIFEST_SUFFIX
    }
    val normalized = parsed.newBuilder()
      // A fragment is local browser state and is never part of an addon route.
      .fragment(null)
      .encodedPath(manifestPath)
      .build()
      .toString()
    return normalized.takeIf { it.length <= MAX_URL_CHARS }.orEmpty()
  }

  /**
   * Builds one Stremio resource endpoint from a manifest URL.
   *
   * [HttpUrl] does the path work so a configured query stays a query (rather
   * than becoming part of `manifest.json`) and each resource segment is encoded
   * independently. The normalized form also makes manifest matching
   * case-insensitive and rejects cleartext URLs before a request is attempted.
   */
  fun resourceUrl(
    manifestUrl: String,
    resource: String,
    type: String,
    id: String,
    encodedExtraPathSegment: String? = null,
  ): String {
    val normalized = normalize(manifestUrl)
    require(normalized.isNotEmpty()) { "Addon URL must be a valid HTTPS manifest URL" }
    val manifest = checkNotNull(normalized.toHttpUrlOrNull())
    val rootPath = manifest.encodedPath.dropLast(MANIFEST_SUFFIX.length)
    return manifest.newBuilder()
      .encodedPath(rootPath.ifEmpty { "/" })
      .apply {
        addPathSegment(resource)
        addPathSegment(type)
        addPathSegment(id)
        // Subtitle search arguments are already percent-encoded because a
        // literal plus and a space have different meanings in that route.
        if (encodedExtraPathSegment != null) addEncodedPathSegment(encodedExtraPathSegment)
      }
      .build()
      .toString()
  }

  /**
   * Canonical root form used by settings that store a resource base rather than
   * the manifest itself. Query parameters are retained and fragments are not.
   */
  fun baseUrl(raw: String): String {
    val normalized = normalize(raw)
    if (normalized.isEmpty()) return ""
    val manifest = checkNotNull(normalized.toHttpUrlOrNull())
    val rootPath = manifest.encodedPath.dropLast(MANIFEST_SUFFIX.length)
    val base = manifest.newBuilder()
      .encodedPath(rootPath.ifEmpty { "/" })
      .build()
      .toString()
    return if (manifest.query == null && base.endsWith('/')) base.dropLast(1) else base
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
   * Reorders a stored addon priority list. An invalid source is a no-op and the
   * destination is clamped so Up/Down controls cannot manufacture an
   * out-of-bounds state at either edge.
   */
  fun moved(list: List<String>, fromIndex: Int, toIndex: Int): List<String> {
    val clean = sanitized(list)
    if (fromIndex !in clean.indices || clean.size < 2) return clean
    val destination = toIndex.coerceIn(clean.indices)
    if (fromIndex == destination) return clean
    return clean.toMutableList().apply {
      val item = removeAt(fromIndex)
      add(destination, item)
    }
  }

  /**
   * Moves a stable addon identity by one slot.
   *
   * TV remotes can enqueue several presses before Compose renders the first result. Looking up the
   * URL in the latest stored list keeps every queued press attached to the row the viewer pressed,
   * rather than to whichever row later occupies a captured index.
   */
  fun moved(list: List<String>, url: String, direction: Int): List<String> {
    val clean = sanitized(list)
    if (direction != -1 && direction != 1) return clean
    val fromIndex = clean.indexOf(normalize(url))
    if (fromIndex < 0) return clean
    return moved(clean, fromIndex, fromIndex + direction)
  }

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
    return first.replaceFirstChar { it.uppercase(Locale.ROOT) }
  }

  /**
   * A Settings-safe identifier for a configured addon.
   *
   * Addon paths, userinfo and query parameters can all carry debrid credentials,
   * so none of them are displayed. The suffix still tells the viewer that this
   * is a configured endpoint rather than the addon's public root.
   */
  fun safeDisplay(url: String): String {
    val parsed = normalize(url).toHttpUrlOrNull() ?: return UNNAMED
    val rootManifest = parsed.encodedPath == MANIFEST_SUFFIX
    val configured = !rootManifest ||
      parsed.query != null ||
      parsed.username.isNotEmpty() ||
      parsed.password.isNotEmpty()
    val port = if (parsed.port == HttpUrl.defaultPort(parsed.scheme)) "" else ":${parsed.port}"
    return "${parsed.host}$port" + if (configured) " (configured)" else ""
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

  /**
   * True when [list] offered something and [sanitized] threw all of it away.
   *
   * Empty and unusable produce the same empty list, and until this existed they produced the same
   * message too - a viewer who mistyped their one addon URL was told "the list was empty", which
   * describes what the guard did rather than what they did. Anything that survives sanitising is
   * not reported: a list where one of three URLs was rejected still saved something.
   */
  fun allRejected(list: List<String>): Boolean =
    list.any { it.isNotBlank() } && sanitized(list).isEmpty()

  private fun hostOf(url: String): String =
    url.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT).orEmpty().ifEmpty {
      url
        .substringAfter("://", url)
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        // Userinfo and port are addressing, not identity.
        .substringAfterLast('@')
        .substringBefore(':')
        .lowercase(Locale.ROOT)
    }

  private fun startsWithHttpScheme(value: String): Boolean =
    value.startsWith("http://", ignoreCase = true) ||
      value.startsWith("https://", ignoreCase = true)

  private val STREMIO_SCHEMES = listOf("stremio://", "stremio:")
}
