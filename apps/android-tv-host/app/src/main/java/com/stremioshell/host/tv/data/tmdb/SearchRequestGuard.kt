package com.stremioshell.host.tv.data.tmdb

/**
 * Opaque ownership for one search result set.
 *
 * The credential itself stays private to the guard: callbacks carry only a generation and query,
 * so an exception, debugger rendering, or future diagnostic cannot accidentally print an API key.
 */
internal data class SearchRequestToken(
  val generation: Long,
  val query: String,
)

internal class SearchRequestGuard {
  private var credential: String? = null
  private var nextGeneration = 0L
  private var current: SearchRequestToken? = null

  /** Invalidates the current result owner when the stored credential changes or is cleared. */
  @Synchronized
  fun updateCredential(value: String?): Boolean {
    val normalized = value?.takeIf { it.isNotBlank() }
    if (credential == normalized) return false
    credential = normalized
    current = null
    return true
  }

  /** Starts a replacement request, including a retry for the same query and credential. */
  @Synchronized
  fun begin(query: String, credential: String?): SearchRequestToken {
    updateCredential(credential)
    return SearchRequestToken(++nextGeneration, query).also { current = it }
  }

  /** True only when this exact query already belongs to the current credential. */
  @Synchronized
  fun canReuse(query: String, credential: String?): Boolean {
    updateCredential(credential)
    return current?.query == query
  }

  /** The owner a next-page request must inherit, or null when the key/query has moved on. */
  @Synchronized
  fun current(query: String, credential: String?): SearchRequestToken? {
    updateCredential(credential)
    return current?.takeIf { it.query == query }
  }

  /** Rejects late first-page and paging callbacks after either query or credential changes. */
  @Synchronized
  fun isCurrent(
    token: SearchRequestToken,
    query: String,
    credential: String?,
  ): Boolean {
    updateCredential(credential)
    return current == token && token.query == query
  }
}
