package com.stremioshell.host.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataCacheTest {
  private val hour = 60L * 60 * 1000

  private fun cache(maxEntries: Int = 3, maxAge: Long = 4 * hour) =
    MetadataCache<String, String>(maxEntries, StalenessPolicy(maxAge))

  @Test
  fun `a title that was never opened is a miss`() {
    assertNull(cache().get("tt1", nowMillis = 1_000))
  }

  @Test
  fun `a recent title is served without a refresh`() {
    val cache = cache()
    cache.put("tt1", "Heat", loadedAtMillis = 1_000)

    val hit = cache.get("tt1", nowMillis = 1_000 + hour)

    assertEquals("Heat", hit?.value)
    assertFalse(hit!!.stale)
  }

  @Test
  fun `an aged-out title is still served, marked for refresh`() {
    // The point of the cache: BACK into a title the viewer just left never shows a spinner, even
    // when what is cached is old enough to be worth reloading underneath them.
    val cache = cache()
    cache.put("tt1", "Heat", loadedAtMillis = 1_000)

    val hit = cache.get("tt1", nowMillis = 1_000 + 5 * hour)

    assertEquals("Heat", hit?.value)
    assertTrue(hit!!.stale)
  }

  @Test
  fun `a completed refresh makes the entry fresh again`() {
    val cache = cache()
    cache.put("tt1", "old", loadedAtMillis = 1_000)
    cache.put("tt1", "new", loadedAtMillis = 1_000 + 5 * hour)

    val hit = cache.get("tt1", nowMillis = 1_000 + 5 * hour)

    assertEquals("new", hit?.value)
    assertFalse(hit!!.stale)
    assertEquals(1, cache.size)
  }

  @Test
  fun `a clock that jumped backwards counts as stale`() {
    val cache = cache()
    cache.put("tt1", "Heat", loadedAtMillis = 5_000_000)

    assertTrue(cache.get("tt1", nowMillis = 1_000)!!.stale)
  }

  @Test
  fun `the cache stops growing at its cap`() {
    val cache = cache(maxEntries = 3)
    repeat(10) { cache.put("tt$it", "title $it", loadedAtMillis = 1_000) }

    assertEquals(3, cache.size)
    assertNull(cache.get("tt0", nowMillis = 1_000))
    assertEquals("title 9", cache.get("tt9", nowMillis = 1_000)?.value)
  }

  @Test
  fun `eviction drops the least recently read, not the least recently loaded`() {
    // Bouncing between two titles is the pattern the cache exists for: the first one must survive
    // however many others get opened around it.
    val cache = cache(maxEntries = 3)
    cache.put("a", "A", loadedAtMillis = 1_000)
    cache.put("b", "B", loadedAtMillis = 2_000)
    cache.put("c", "C", loadedAtMillis = 3_000)

    cache.get("a", nowMillis = 4_000)
    cache.put("d", "D", loadedAtMillis = 5_000)

    assertEquals("A", cache.get("a", nowMillis = 6_000)?.value)
    assertNull(cache.get("b", nowMillis = 6_000))
    assertEquals("C", cache.get("c", nowMillis = 6_000)?.value)
    assertEquals("D", cache.get("d", nowMillis = 6_000)?.value)
  }

  @Test
  fun `a stale read still counts as use`() {
    // A hit that is served and refreshed is exactly as "in use" as a fresh one; evicting it for
    // being old would throw away the entry the viewer is looking at.
    val cache = cache(maxEntries = 2, maxAge = hour)
    cache.put("a", "A", loadedAtMillis = 1_000)
    cache.put("b", "B", loadedAtMillis = 1_000)

    assertTrue(cache.get("a", nowMillis = 1_000 + 5 * hour)!!.stale)
    cache.put("c", "C", loadedAtMillis = 1_000 + 5 * hour)

    assertEquals("A", cache.get("a", nowMillis = 1_000 + 5 * hour)?.value)
    assertNull(cache.get("b", nowMillis = 1_000 + 5 * hour))
  }

  @Test
  fun `clear empties the cache, which is what a changed TMDB key does`() {
    val cache = cache()
    cache.put("tt1", "Heat", loadedAtMillis = 1_000)

    cache.clear()

    assertEquals(0, cache.size)
    assertNull(cache.get("tt1", nowMillis = 1_000))
  }

  @Test
  fun `a cache of one entry is still a cache`() {
    val cache = cache(maxEntries = 1)
    cache.put("a", "A", loadedAtMillis = 1_000)
    cache.put("b", "B", loadedAtMillis = 1_000)

    assertNull(cache.get("a", nowMillis = 1_000))
    assertEquals("B", cache.get("b", nowMillis = 1_000)?.value)
  }

  @Test
  fun `a non-positive cap is rejected`() {
    assertThrows(IllegalArgumentException::class.java) { MetadataCache<String, String>(0) }
  }

  @Test
  fun `the default window is the rails' window`() {
    val cache = MetadataCache<String, String>(maxEntries = 2)
    cache.put("a", "A", loadedAtMillis = 1_000)

    assertFalse(cache.get("a", 1_000 + StalenessPolicy.DEFAULT_MAX_AGE_MILLIS - 1)!!.stale)
    assertTrue(cache.get("a", 1_000 + StalenessPolicy.DEFAULT_MAX_AGE_MILLIS)!!.stale)
  }

  @Test
  fun `keys of different shapes do not collide`() {
    // Details is keyed by type+id and seasons by id+season; a movie 42 and a show 42 are different
    // titles, and season 1 of a show is not season 2.
    val details = MetadataCache<Pair<String, Int>, String>(maxEntries = 4)
    details.put("movie" to 42, "Movie 42", loadedAtMillis = 1_000)
    details.put("show" to 42, "Show 42", loadedAtMillis = 1_000)

    assertEquals("Movie 42", details.get("movie" to 42, 1_000)?.value)
    assertEquals("Show 42", details.get("show" to 42, 1_000)?.value)
  }
}
