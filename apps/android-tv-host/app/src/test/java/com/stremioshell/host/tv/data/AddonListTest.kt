package com.stremioshell.host.tv.data

import com.stremioshell.host.tv.data.addon.AddonList
import org.junit.Assert.assertEquals
import org.junit.Test

class AddonListTest {
  @Test
  fun `a bare host is completed into a manifest url`() {
    assertEquals("https://comet.example/manifest.json", AddonList.normalize("comet.example"))
  }

  @Test
  fun `a stremio install link becomes an https one`() {
    assertEquals(
      "https://comet.example/cfg/manifest.json",
      AddonList.normalize("stremio://comet.example/cfg/manifest.json"),
    )
  }

  @Test
  fun `a base url gains the manifest path the addon client requires`() {
    assertEquals(
      "https://comet.example/cfg/manifest.json",
      AddonList.normalize("  https://comet.example/cfg/  "),
    )
  }

  @Test
  fun `a url that already names the manifest is left alone`() {
    val url = "https://comet.example/cfg/manifest.json"
    assertEquals(url, AddonList.normalize(url))
    assertEquals(url, AddonList.normalize(AddonList.normalize(url)))
  }

  @Test
  fun `a url carrying a query keeps the path its author chose`() {
    // Appending to a configured route would produce one that matches nothing.
    assertEquals(
      "https://addon.example/manifest.json?key=abc",
      AddonList.normalize("https://addon.example/manifest.json?key=abc"),
    )
  }

  @Test
  fun `nothing usable normalizes to nothing`() {
    assertEquals("", AddonList.normalize(""))
    assertEquals("", AddonList.normalize("   "))
    assertEquals("", AddonList.normalize("https://"))
  }

  @Test
  fun `an install that only ever stored one url sees it as the first list entry`() {
    // The migration that has to be invisible: the list key has never been written.
    assertEquals(
      listOf("https://comet.example/cfg/manifest.json"),
      AddonList.migrated(stored = null, legacy = "https://comet.example/cfg/manifest.json"),
    )
  }

  @Test
  fun `a fresh install with neither key stored has no addons`() {
    assertEquals(emptyList<String>(), AddonList.migrated(stored = null, legacy = ""))
  }

  @Test
  fun `removing the last addon is not undone by the legacy url`() {
    // A stored empty list is a decision; falling back to the old single URL there
    // would make removal impossible.
    assertEquals(
      emptyList<String>(),
      AddonList.migrated(stored = emptyList(), legacy = "https://comet.example/cfg/manifest.json"),
    )
  }

  @Test
  fun `a stored list wins over the legacy url`() {
    assertEquals(
      listOf("https://a.example/manifest.json", "https://b.example/manifest.json"),
      AddonList.migrated(
        stored = listOf("https://a.example/manifest.json", "https://b.example/manifest.json"),
        legacy = "https://comet.example/cfg/manifest.json",
      ),
    )
  }

  @Test
  fun `adding appends, normalizes and refuses duplicates`() {
    var list = AddonList.added(emptyList(), "comet.example")
    list = AddonList.added(list, "https://torrentio.example/manifest.json")
    // Same addon, spelled the way a browser address bar would.
    list = AddonList.added(list, "https://comet.example/")
    list = AddonList.added(list, "   ")

    assertEquals(
      listOf("https://comet.example/manifest.json", "https://torrentio.example/manifest.json"),
      list,
    )
  }

  @Test
  fun `the list is capped`() {
    val full = (1..AddonList.MAX_ADDONS).fold(emptyList<String>()) { acc, n ->
      AddonList.added(acc, "https://a$n.example/manifest.json")
    }
    assertEquals(AddonList.MAX_ADDONS, full.size)
    assertEquals(full, AddonList.added(full, "https://one-too-many.example/manifest.json"))
  }

  @Test
  fun `removing takes out exactly the named url`() {
    val list = listOf("https://a.example/manifest.json", "https://b.example/manifest.json")
    assertEquals(
      listOf("https://b.example/manifest.json"),
      AddonList.removed(list, "https://a.example/manifest.json"),
    )
  }

  @Test
  fun `the single-url callers replace the first entry and leave the rest`() {
    val list = listOf("https://a.example/manifest.json", "https://b.example/manifest.json")
    assertEquals(
      listOf("https://c.example/manifest.json", "https://b.example/manifest.json"),
      AddonList.replacingFirst(list, "https://c.example/manifest.json"),
    )
  }

  @Test
  fun `a blank from a single-url caller means unchanged, not cleared`() {
    // The pairing form spells "leave this alone" as an empty field.
    val list = listOf("https://a.example/manifest.json")
    assertEquals(list, AddonList.replacingFirst(list, ""))
  }

  @Test
  fun `the first entry can be set on an empty list`() {
    assertEquals(
      listOf("https://a.example/manifest.json"),
      AddonList.replacingFirst(emptyList(), "a.example"),
    )
  }

  @Test
  fun `an addon is named after the first label of its host`() {
    assertEquals("Comet", AddonList.label("https://comet.elfhosted.com/cfg/manifest.json"))
    assertEquals("Torrentio", AddonList.label("https://www.torrentio.strem.fun/manifest.json"))
  }

  @Test
  fun `a self-hosted addon keeps its whole address`() {
    // "192" would say nothing at all on a stream row.
    assertEquals("192.168.1.5", AddonList.label("http://192.168.1.5:8080/manifest.json"))
  }

  @Test
  fun `two configurations of one addon are told apart`() {
    assertEquals(
      listOf("Comet 1", "Torrentio", "Comet 2"),
      AddonList.labels(
        listOf(
          "https://comet.example/cached/manifest.json",
          "https://torrentio.example/manifest.json",
          "https://comet.example/everything/manifest.json",
        ),
      ),
    )
  }

  @Test
  fun `sanitizing a stored list drops blanks and duplicates`() {
    assertEquals(
      listOf("https://a.example/manifest.json", "https://b.example/manifest.json"),
      AddonList.sanitized(listOf("a.example", "", "https://a.example/", "  b.example  ")),
    )
  }
}
