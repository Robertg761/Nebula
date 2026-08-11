package com.stremioshell.host.tv.data

import com.stremioshell.host.tv.data.addon.AddonBehaviorHints
import com.stremioshell.host.tv.data.addon.AddonFetch
import com.stremioshell.host.tv.data.addon.AddonStream
import com.stremioshell.host.tv.data.addon.StreamMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamMergeTest {
  @Test
  fun `quality decides the order across addons, not which addon answered`() {
    // The whole point of a second addon is being offered a better release; burying
    // its 4K under the first addon's whole catalogue would defeat it.
    val merged = StreamMerge.merge(
      listOf(
        AddonFetch("Comet", listOf(stream("Comet 480p", "u1"), stream("Comet 1080p", "u2"))),
        AddonFetch("Torrentio", listOf(stream("Torrentio 2160p", "u3"))),
      ),
    )

    assertEquals(
      listOf("Torrentio 2160p", "Comet 1080p", "Comet 480p"),
      merged.streams.map { it.label },
    )
  }

  @Test
  fun `inside one quality tier the addon order is the tie-break`() {
    val merged = StreamMerge.merge(
      listOf(
        AddonFetch("Comet", listOf(stream("Comet A 1080p", "u1"), stream("Comet B 1080p", "u2"))),
        AddonFetch("Torrentio", listOf(stream("Torrentio 1080p", "u3"))),
      ),
    )

    assertEquals(
      listOf("Comet A 1080p", "Comet B 1080p", "Torrentio 1080p"),
      merged.streams.map { it.label },
    )
  }

  @Test
  fun `the same resolved url from two addons appears once`() {
    val merged = StreamMerge.merge(
      listOf(
        AddonFetch("Comet", listOf(stream("Comet 1080p", "https://rd.example/v.mkv"))),
        AddonFetch("Torrentio", listOf(stream("Torrentio 1080p", "https://rd.example/v.mkv"))),
      ),
    )

    assertEquals(1, merged.streams.size)
    // The earlier addon in the viewer's list is the one whose row survives.
    assertEquals("Comet", merged.streams.single().source)
  }

  @Test
  fun `one torrent resolved twice by two debrid addons appears once`() {
    // The duplicate a url-only check misses: same file, two signed links.
    val merged = StreamMerge.merge(
      listOf(
        AddonFetch("Comet", listOf(stream("Comet 1080p", "https://rd.example/a", hash = "ABC", idx = 2))),
        AddonFetch("MediaFusion", listOf(stream("MF 1080p", "https://ad.example/b", hash = "abc", idx = 2))),
      ),
    )

    assertEquals(listOf("Comet 1080p"), merged.streams.map { it.label })
  }

  @Test
  fun `two files inside one pack are two streams`() {
    val merged = StreamMerge.merge(
      listOf(
        AddonFetch(
          "Comet",
          listOf(
            stream("E01 1080p", "https://rd.example/a", hash = "abc", idx = 1),
            stream("E02 1080p", "https://rd.example/b", hash = "abc", idx = 2),
          ),
        ),
      ),
    )

    assertEquals(2, merged.streams.size)
  }

  @Test
  fun `a pack with no file index is not collapsed into one row`() {
    // `infoHash` alone is the torrent, not the file. Every episode of a season pack shares it, so
    // keying on "$hash/-1" turned a twenty-episode pack into one row and lost nineteen streams as
    // "duplicates" - which reads as a season with one episode in it.
    val merged = StreamMerge.merge(
      listOf(
        AddonFetch(
          "Comet",
          listOf(
            packEntry("Show S01E01 1080p", "Show.S01E01.1080p.mkv"),
            packEntry("Show S01E02 1080p", "Show.S01E02.1080p.mkv"),
            packEntry("Show S01E03 1080p", "Show.S01E03.1080p.mkv"),
          ),
        ),
      ),
    )

    assertEquals(3, merged.streams.size)
  }

  @Test
  fun `the same pack entry from two addons is still one row`() {
    // The file name is what two addons describing one file agree on, which is why it is preferred
    // over the row's own label - that carries seeders, size and the addon's branding.
    val merged = StreamMerge.merge(
      listOf(
        AddonFetch("Comet", listOf(packEntry("[RD+] Comet 1080p", "Show.S01E01.1080p.mkv"))),
        AddonFetch("MediaFusion", listOf(packEntry("MF 1080p 12 seeders", "show.s01e01.1080p.mkv"))),
      ),
    )

    assertEquals(listOf("[RD+] Comet 1080p"), merged.streams.map { it.label })
  }

  @Test
  fun `a hash-only row with nothing to tell it apart still dedupes`() {
    // No index and no name: the rows really are indistinguishable, and this is where the old
    // hash-only identity was right all along.
    val bare = AddonStream(infoHash = "abc")
    val merged = StreamMerge.merge(listOf(AddonFetch("Comet", listOf(bare, bare))))

    assertEquals(1, merged.streams.size)
  }

  @Test
  fun `a row with no url and no hash is kept rather than collapsed`() {
    val nameless = AddonStream(name = "Mystery 1080p")
    val merged = StreamMerge.merge(listOf(AddonFetch("Comet", listOf(nameless, nameless))))

    assertEquals(2, merged.streams.size)
  }

  @Test
  fun `rows are badged with their addon only when there is more than one`() {
    val single = StreamMerge.merge(listOf(AddonFetch("Comet", listOf(stream("Comet 1080p", "u1")))))
    assertNull(single.streams.single().source)

    val several = StreamMerge.merge(
      listOf(
        AddonFetch("Comet", listOf(stream("Comet 1080p", "u1"))),
        AddonFetch("Torrentio", listOf(stream("Torrentio 1080p", "u2"))),
      ),
    )
    assertEquals(listOf("Comet", "Torrentio"), several.streams.map { it.source })
  }

  @Test
  fun `a partial failure keeps the rows that landed and names the addon that did not`() {
    val merged = StreamMerge.merge(
      listOf(
        AddonFetch("Comet", listOf(stream("Comet 1080p", "u1"))),
        AddonFetch("Torrentio", null),
      ),
    )

    assertEquals(listOf("Comet 1080p"), merged.streams.map { it.label })
    assertEquals("Couldn't reach Torrentio.", merged.notice)
    assertFalse(merged.allFailed)
  }

  @Test
  fun `an addon that answered with nothing is not a failure`() {
    val merged = StreamMerge.merge(
      listOf(
        AddonFetch("Comet", emptyList()),
        AddonFetch("Torrentio", listOf(stream("Torrentio 1080p", "u1"))),
      ),
    )

    assertNull(merged.notice)
    assertFalse(merged.allFailed)
  }

  @Test
  fun `every addon failing is a failed load, not a partial one`() {
    val merged = StreamMerge.merge(
      listOf(AddonFetch("Comet", null), AddonFetch("Torrentio", null)),
    )

    assertTrue(merged.allFailed)
    assertTrue(merged.streams.isEmpty())
  }

  @Test
  fun `no addons at all is not a failure to report`() {
    // "Nothing configured" is the caller's message, not this one's.
    assertFalse(StreamMerge.merge(emptyList()).allFailed)
  }

  @Test
  fun `an oversized addon answer is capped after quality ordering`() {
    val rows = (1..600).map { index ->
      val quality = if (index == 600) "2160p" else "480p"
      stream("Release $index $quality", "https://rd.example/$index")
    }

    val merged = StreamMerge.merge(listOf(AddonFetch("Huge", rows)))

    assertEquals(StreamMerge.MAX_MERGED_STREAMS, merged.streams.size)
    assertEquals("Release 600 2160p", merged.streams.first().label)
  }

  @Test
  fun `the failure notice is one sentence whatever the count`() {
    assertNull(StreamMerge.failureNotice(emptyList()))
    assertEquals("Couldn't reach Comet.", StreamMerge.failureNotice(listOf("Comet")))
    assertEquals(
      "Couldn't reach Comet and Torrentio.",
      StreamMerge.failureNotice(listOf("Comet", "Torrentio")),
    )
    assertEquals(
      "Couldn't reach Comet, Torrentio and MediaFusion.",
      StreamMerge.failureNotice(listOf("Comet", "Torrentio", "MediaFusion")),
    )
  }

  private fun stream(
    name: String,
    url: String? = null,
    hash: String? = null,
    idx: Int? = null,
  ) = AddonStream(name = name, url = url, infoHash = hash, fileIdx = idx)

  /** One file inside a season pack, as an addon that does not supply `fileIdx` reports it. */
  private fun packEntry(name: String, filename: String) = AddonStream(
    name = name,
    infoHash = "abc",
    behaviorHints = AddonBehaviorHints(filename = filename),
  )
}
