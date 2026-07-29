package com.stremioshell.host.tv.ui.theme

import androidx.compose.ui.graphics.toArgb
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * res/values/colors.xml says of itself that its entries "mirror NebulaPalette in
 * tv/ui/theme/Color.kt and have to move with it, or the icon drifts away from the app it opens".
 *
 * Nothing enforced that, so it was a hope rather than a rule - and the launcher icon, the TV
 * banner and the window background the system paints before Compose exists are all drawn from
 * those four values. This is the enforcement: change a mirrored colour in one place and this test
 * says so.
 */
class PaletteMirrorTest {
  private val mirrored = mapOf(
    "nebula_void" to NebulaPalette.Void,
    "nebula_violet" to NebulaPalette.Violet,
    "nebula_violet_bright" to NebulaPalette.VioletBright,
    "nebula_cyan" to NebulaPalette.Cyan,
  )

  private val entryPattern = Regex("""<color name="([^"]+)">\s*(#[0-9A-Fa-f]{6,8})\s*</color>""")

  /** Unit tests run with the module directory as the working directory. */
  private fun colorsXml(): String {
    val file = File("src/main/res/values/colors.xml")
    assertTrue("colors.xml not found at ${file.absolutePath}", file.isFile)
    return file.readText()
  }

  private fun parsed(): Map<String, String> =
    entryPattern.findAll(colorsXml()).associate { it.groupValues[1] to it.groupValues[2].uppercase() }

  @Test
  fun `every mirrored entry matches the palette`() {
    val xml = parsed()
    mirrored.forEach { (name, color) ->
      val declared = xml[name]
      assertTrue("colors.xml is missing $name", declared != null)
      // Compose carries alpha; the XML entries are opaque, so compare the RGB the artwork uses.
      val expected = "#%06X".format(color.toArgb() and 0xFFFFFF)
      assertEquals("$name drifted from NebulaPalette", expected, declared)
    }
  }

  /**
   * A colour added to the XML side with no counterpart here would be exactly the drift the file's
   * own comment warns about, so a new `nebula_*` entry has to be claimed by this test.
   */
  @Test
  fun `no unmirrored nebula entries have crept in`() {
    val unclaimed = parsed().keys.filter { it.startsWith("nebula_") && it !in mirrored }
    assertEquals("Add these to PaletteMirrorTest.mirrored: $unclaimed", emptyList<String>(), unclaimed)
  }
}
