package com.stremioshell.host.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {
  private fun semVer(raw: String): SemVer = requireNotNull(SemVer.parseOrNull(raw)) {
    "expected $raw to parse"
  }

  @Test
  fun `parseOrNull accepts flavor suffixed version strings`() {
    val parsed = SemVer.parseOrNull("0.1.0-tv")
    assertNotNull(parsed)
    assertEquals(0, parsed?.major)
    assertEquals(1, parsed?.minor)
    assertEquals(0, parsed?.patch)
  }

  @Test
  fun `compareTo handles semantic ordering`() {
    val current = SemVer.parseOrNull("0.1.0-tv")
    val latest = SemVer.parseOrNull("v0.1.1")
    assertNotNull(current)
    assertNotNull(latest)
    assertTrue(latest!! > current!!)
  }

  @Test
  fun `the flavor suffix is not a pre-release`() {
    // `-tv` names the build, not a stage of the release, so 0.1.0-tv and 0.1.0 are the same
    // version and neither is an update over the other.
    assertEquals(emptyList<String>(), semVer("0.1.0-tv").preRelease)
    assertEquals(0, semVer("0.1.0-tv").compareTo(semVer("0.1.0")))
  }

  @Test
  fun `the stable release outranks its own beta`() {
    // The bug this replaced: the pre-release was parsed off, 0.6.2-beta.1 and 0.6.2 compared
    // equal, and a viewer on the beta was never offered the stable.
    assertTrue(semVer("v0.6.2") > semVer("v0.6.2-beta.1"))
  }

  @Test
  fun `the beta does not outrank the stable it precedes`() {
    assertFalse(semVer("v0.6.2-beta.1") > semVer("v0.6.2"))
  }

  @Test
  fun `a later beta outranks an earlier one`() {
    assertTrue(semVer("v0.6.2-beta.2") > semVer("v0.6.2-beta.1"))
  }

  @Test
  fun `numeric pre-release identifiers compare as numbers, not as text`() {
    assertTrue(semVer("1.0.0-beta.10") > semVer("1.0.0-beta.9"))
    assertTrue(semVer("1.0.0-rc.2") > semVer("1.0.0-beta.11"))
  }

  @Test
  fun `a numeric identifier sorts below an alphanumeric one`() {
    // semver 2.0 §11.4.3.
    assertTrue(semVer("1.0.0-alpha") > semVer("1.0.0-1"))
  }

  @Test
  fun `a longer pre-release outranks the prefix it extends`() {
    // §11.4.4.
    assertTrue(semVer("1.0.0-alpha.1") > semVer("1.0.0-alpha"))
  }

  @Test
  fun `a newer core version wins whatever the pre-release says`() {
    assertTrue(semVer("0.6.3-beta.1") > semVer("0.6.2"))
    assertTrue(semVer("0.6.3") > semVer("0.6.2-beta.1"))
  }

  @Test
  fun `build metadata is ignored`() {
    assertEquals(0, semVer("1.2.3+abc123").compareTo(semVer("1.2.3+def456")))
    assertEquals(listOf("rc", "1"), semVer("1.2.3-rc.1+abc123").preRelease)
  }

  @Test
  fun `trailing noise after the version is still tolerated`() {
    // The old parser accepted anything after the numbers; stored labels from older builds still
    // have to read back.
    assertEquals(semVer("1.2.3"), semVer("1.2.3 (build 4)"))
  }

  @Test
  fun `normalizeLabel keeps the pre-release and drops everything decorative`() {
    assertEquals("0.6.2-beta.1", SemVer.normalizeLabel("v0.6.2-BETA.1"))
    assertEquals("0.6.2", SemVer.normalizeLabel("V0.6.2-tv"))
    assertEquals("0.6.2", SemVer.normalizeLabel("  0.6.2+abc  "))
    // The shape is preserved rather than re-rendered: 0.4 does not become 0.4.0.
    assertEquals("0.4", SemVer.normalizeLabel("v0.4"))
  }

  @Test
  fun `coreLabel drops the pre-release, because the release asset name has none`() {
    assertEquals("0.6.2", SemVer.coreLabel("v0.6.2-beta.1"))
    assertEquals("0.6.2", SemVer.coreLabel("v0.6.2"))
  }

  @Test
  fun `overflowing numeric components are rejected rather than reset to zero`() {
    assertNull(SemVer.parseOrNull("1.999999999999999999999.2"))
    assertNull(SemVer.parseOrNull("1.2.999999999999999999999"))
  }
}
