package com.stremioshell.host.tv.data

import com.stremioshell.host.tv.data.tmdb.LogoPick
import com.stremioshell.host.tv.data.tmdb.LogoRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogoPickTest {
  private fun logo(
    path: String,
    language: String? = "en",
    vote: Double = 5.0,
    width: Int = 500,
  ) = LogoRef(filePath = path, language = language, voteAverage = vote, width = width)

  @Test
  fun `no logos at all yields null`() {
    assertNull(LogoPick.best(emptyList()))
  }

  /**
   * The rule the whole pick exists for: Coil ships no SVG decoder here, so choosing one renders an
   * empty box where the title should be - strictly worse than falling back to typeset text.
   */
  @Test
  fun `svg is never chosen even when it is the only candidate`() {
    assertNull(LogoPick.best(listOf(logo("/only.svg"))))
  }

  @Test
  fun `svg is skipped in favour of a raster file`() {
    val best = LogoPick.best(listOf(logo("/best.svg", vote = 9.0), logo("/ok.png", vote = 1.0)))
    assertEquals("/ok.png", best)
  }

  @Test
  fun `english wins over a higher-voted other language`() {
    val best = LogoPick.best(
      listOf(
        logo("/french.png", language = "fr", vote = 9.9),
        logo("/english.png", language = "en", vote = 1.0),
      ),
    )
    assertEquals("/english.png", best)
  }

  @Test
  fun `the device language wins while english remains the fallback`() {
    val logos = listOf(
      logo("/french.png", language = "fr", vote = 1.0),
      logo("/english.png", language = "en", vote = 9.0),
      logo("/textless.png", language = null, vote = 10.0),
    )

    assertEquals("/french.png", LogoPick.best(logos, preferredLanguage = "fr"))
    assertEquals("/english.png", LogoPick.best(logos, preferredLanguage = "de"))
  }

  @Test
  fun `among english logos the higher vote wins`() {
    val best = LogoPick.best(listOf(logo("/low.png", vote = 2.0), logo("/high.png", vote = 8.0)))
    assertEquals("/high.png", best)
  }

  @Test
  fun `width breaks a tie, because a wide lockup is the shape a billboard wants`() {
    val best = LogoPick.best(
      listOf(logo("/narrow.png", vote = 5.0, width = 300), logo("/wide.png", vote = 5.0, width = 1400)),
    )
    assertEquals("/wide.png", best)
  }

  /**
   * A null language is a textless emblem, which usually does not say what the title is - kept as a
   * fallback rather than preferred over an English wordmark.
   */
  @Test
  fun `textless entries lose to an english wordmark but still beat nothing`() {
    assertEquals(
      "/english.png",
      LogoPick.best(listOf(logo("/textless.png", language = null, vote = 9.0), logo("/english.png"))),
    )
    assertEquals(
      "/textless.png",
      LogoPick.best(listOf(logo("/textless.png", language = null))),
    )
  }

  @Test
  fun `blank paths are ignored`() {
    assertEquals("/real.png", LogoPick.best(listOf(logo(""), logo("/real.png"))))
  }

  @Test
  fun `uppercase SVG extension is still excluded`() {
    assertEquals("/real.png", LogoPick.best(listOf(logo("/shout.SVG", vote = 9.0), logo("/real.png"))))
  }
}
