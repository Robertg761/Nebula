package com.stremioshell.host.tv.data

import com.stremioshell.host.tv.data.addon.AddonClient
import com.stremioshell.host.tv.data.addon.StreamCatalog
import com.stremioshell.host.tv.data.addon.StreamMerge
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCatalogTest {
  private val comet = "https://comet.example/SECRETDEBRIDKEY/manifest.json"
  private val torrentio = "https://torrentio.example/manifest.json"
  private val mediafusion = "https://mediafusion.example/manifest.json"

  /** One addon's canned answer, keyed by the host the request went to. */
  private fun catalog(
    timeoutMillis: Long = 20_000L,
    requests: MutableList<String> = mutableListOf(),
    body: suspend (String) -> String,
  ) = StreamCatalog(
    client = AddonClient(fetcher = { url ->
      requests += url
      body(url)
    }),
    perAddonTimeoutMillis = timeoutMillis,
  )

  private fun streamsJson(vararg names: String): String =
    names.joinToString(",", "{\"streams\":[", "]}") { name ->
      """{"name":"$name","url":"https://cdn.example/${name.replace(' ', '-')}.mkv"}"""
    }

  @Test
  fun `every configured addon is asked, and quality decides the order across them`() = runBlocking {
    // The whole reason the player was rewired: addon #2's 2160p is the release the viewer wants,
    // and the old code never even sent it a request.
    val requests = mutableListOf<String>()
    val catalog = catalog(requests = requests) { url ->
      when {
        url.contains("comet") -> streamsJson("Comet 480p", "Comet 1080p")
        else -> streamsJson("Torrentio 2160p")
      }
    }

    val fetch = catalog.fetch(listOf(comet, torrentio), "tt0111161")

    assertEquals(2, requests.size)
    assertEquals(
      listOf("Torrentio 2160p", "Comet 1080p", "Comet 480p"),
      fetch.streams.map { it.label },
    )
    assertEquals(listOf("Torrentio", "Comet", "Comet"), fetch.streams.map { it.source })
    assertTrue(fetch.failures.isEmpty())
    assertNull(fetch.merged.notice)
    assertFalse(fetch.merged.allFailed)
  }

  @Test
  fun `addons are asked at once, not one after another`() = runBlocking {
    // Sequential fan-out makes the list as slow as the sum of every addon; with four configured
    // that is the difference between a usable picker and a viewer pressing BACK.
    val inFlight = AtomicInteger()
    val peak = AtomicInteger()
    val catalog = catalog { _ ->
      val concurrent = inFlight.incrementAndGet()
      peak.getAndUpdate { maxOf(it, concurrent) }
      delay(30)
      inFlight.decrementAndGet()
      streamsJson("1080p")
    }

    catalog.fetch(listOf(comet, torrentio, mediafusion), "tt0111161")

    assertEquals(3, peak.get())
  }

  @Test
  fun `a throttled fetch keeps its fan-out under the limit and still asks everyone`() = runBlocking {
    // The player's next-episode resolver runs while the current episode is streaming over the same
    // Wi-Fi, so it caps how many addons it interrogates at once. Every addon is still asked.
    val inFlight = AtomicInteger()
    val peak = AtomicInteger()
    val requests = mutableListOf<String>()
    val catalog = catalog(requests = requests) { _ ->
      val concurrent = inFlight.incrementAndGet()
      peak.getAndUpdate { maxOf(it, concurrent) }
      delay(30)
      inFlight.decrementAndGet()
      streamsJson("1080p")
    }

    val fetch = catalog.fetch(
      listOf(comet, torrentio, mediafusion, "https://orion.example/manifest.json"),
      "tt0111161",
      maxConcurrent = 2,
    )

    assertEquals(2, peak.get())
    assertEquals(4, requests.size)
    assertTrue(fetch.failures.isEmpty())
  }

  @Test
  fun `an addon queued behind the limit is not blamed for the time it spent waiting`() = runBlocking {
    // Its budget starts when it is actually asked. Charging it for our own queue would put
    // "Couldn't reach X" on screen for an addon that answered the moment it got a turn.
    val catalog = catalog(timeoutMillis = 200) { url ->
      if (url.contains("comet")) delay(150)
      streamsJson(url.substringAfter("https://").substringBefore('.'))
    }

    val fetch = catalog.fetch(
      listOf(comet, torrentio, mediafusion),
      "tt0111161",
      maxConcurrent = 1,
    )

    assertTrue(fetch.failures.isEmpty())
    assertEquals(3, fetch.streams.size)
  }

  @Test
  fun `an episode asks for the series id, a movie for the bare one`() = runBlocking {
    val requests = mutableListOf<String>()
    val catalog = catalog(requests = requests) { streamsJson("1080p") }

    catalog.fetch(listOf(comet), "tt14688458", season = 2, episode = 5)
    catalog.fetch(listOf(comet), "tt14688458")

    assertTrue(requests[0].endsWith("/stream/series/tt14688458:2:5.json"))
    assertTrue(requests[1].endsWith("/stream/movie/tt14688458.json"))
  }

  @Test
  fun `a season without an episode is a movie request, not a malformed series one`() = runBlocking {
    val requests = mutableListOf<String>()
    val catalog = catalog(requests = requests) { streamsJson("1080p") }

    catalog.fetch(listOf(comet), "tt14688458", season = 2, episode = null)

    assertTrue(requests.single().endsWith("/stream/movie/tt14688458.json"))
  }

  @Test
  fun `one addon failing leaves the others plus a notice naming it`() = runBlocking {
    val catalog = catalog { url ->
      if (url.contains("torrentio")) throw IOException("connection reset")
      streamsJson("Comet 1080p")
    }

    val fetch = catalog.fetch(listOf(comet, torrentio), "tt0111161")

    assertEquals(listOf("Comet 1080p"), fetch.streams.map { it.label })
    assertEquals("Couldn't reach Torrentio.", fetch.merged.notice)
    assertFalse(fetch.merged.allFailed)
    assertEquals(1, fetch.failures.size)
  }

  @Test
  fun `every addon failing is a failed load, with something to report`() = runBlocking {
    val catalog = catalog { throw IOException("connection reset") }

    val fetch = catalog.fetch(listOf(comet, torrentio), "tt0111161")

    assertTrue(fetch.merged.allFailed)
    assertTrue(fetch.streams.isEmpty())
    assertEquals(2, fetch.failures.size)
  }

  @Test
  fun `an addon over its budget fails by name, never by URL`() = runBlocking {
    // The configured path can carry a debrid key, and this message ends up in logs.
    val catalog = catalog(timeoutMillis = 40) { url ->
      if (url.contains("comet")) delay(10_000)
      streamsJson("Torrentio 1080p")
    }

    val fetch = catalog.fetch(listOf(comet, torrentio), "tt0111161")

    assertEquals(listOf("Torrentio 1080p"), fetch.streams.map { it.label })
    val message = fetch.failures.single().message.orEmpty()
    assertEquals("Comet timed out", message)
    assertFalse(message.contains("SECRETDEBRIDKEY"))
    assertEquals("Couldn't reach Comet.", fetch.merged.notice)
  }

  @Test
  fun `a slow addon does not hold up the fast ones' rows`() = runBlocking {
    val catalog = catalog(timeoutMillis = 40) { url ->
      if (url.contains("comet")) delay(10_000)
      streamsJson("Torrentio 1080p")
    }

    val elapsed = System.nanoTime().let { start ->
      catalog.fetch(listOf(comet, torrentio), "tt0111161")
      (System.nanoTime() - start) / 1_000_000
    }

    assertTrue("took ${elapsed}ms", elapsed < 5_000)
  }

  @Test
  fun `the same release from two addons is offered once`() = runBlocking {
    val catalog = catalog { _ ->
      """{"streams":[{"name":"1080p WEB-DL","url":"https://cdn.example/same.mkv"}]}"""
    }

    val fetch = catalog.fetch(listOf(comet, torrentio), "tt0111161")

    assertEquals(1, fetch.streams.size)
    // The earlier addon in the viewer's list is the one whose row survives.
    assertEquals("Comet", fetch.streams.single().source)
  }

  @Test
  fun `invalid high-ranked rows cannot crowd a healthy addons playable row out of the cap`() =
    runBlocking {
      val invalidRows = (1..StreamMerge.MAX_MERGED_STREAMS + 20).joinToString(",") { index ->
        """{"name":"Broken 2160p $index","url":"file:///private/$index.mkv"}"""
      }
      val catalog = catalog { url ->
        if (url.contains("comet")) {
          """{"streams":[$invalidRows]}"""
        } else {
          streamsJson("Healthy 1080p")
        }
      }

      val fetch = catalog.fetch(listOf(comet, torrentio), "tt0111161")

      assertEquals(listOf("Healthy 1080p"), fetch.streams.map { it.label })
      assertEquals(listOf("Torrentio"), fetch.streams.map { it.source })
    }

  @Test
  fun `a single addon leaves its rows unbadged`() = runBlocking {
    val catalog = catalog { streamsJson("Comet 1080p") }

    val fetch = catalog.fetch(listOf(comet), "tt0111161")

    assertNull(fetch.streams.single().source)
  }

  @Test
  fun `two configurations of one addon are told apart`() = runBlocking {
    val catalog = catalog { url ->
      if (url.contains("cached")) streamsJson("Cached 1080p") else streamsJson("Everything 720p")
    }

    val fetch = catalog.fetch(
      listOf("https://comet.example/cached/manifest.json", "https://comet.example/all/manifest.json"),
      "tt0111161",
    )

    assertEquals(listOf("Comet 1", "Comet 2"), fetch.streams.map { it.source })
  }

  @Test
  fun `no addons configured asks nothing and fails nothing`() = runBlocking {
    // "Nothing configured" is the caller's message to write, not a failed load.
    val requests = mutableListOf<String>()
    val catalog = catalog(requests = requests) { streamsJson("1080p") }

    val fetch = catalog.fetch(emptyList(), "tt0111161")

    assertTrue(requests.isEmpty())
    assertTrue(fetch.streams.isEmpty())
    assertFalse(fetch.merged.allFailed)
    assertNull(fetch.merged.notice)
  }
}
