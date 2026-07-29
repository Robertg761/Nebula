package com.stremioshell.host.tv.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportFilePolicyTest {
  @Test
  fun `each token creates a distinct managed report name at the same instant`() {
    val first = DiagnosticReportFilePolicy.reportName(
      1_722_250_000_000L,
      "0123456789abcdef0123456789abcdef",
    )
    val second = DiagnosticReportFilePolicy.reportName(
      1_722_250_000_000L,
      "fedcba9876543210fedcba9876543210",
    )

    assertNotEquals(first, second)
    assertTrue(DiagnosticReportFilePolicy.isManagedReportName(first))
    assertTrue(DiagnosticReportFilePolicy.isManagedReportName(second))
    assertFalse(DiagnosticReportFilePolicy.isManagedReportName("../$first"))
  }

  @Test
  fun `pruning keeps the protected export and newest bounded history only`() {
    val protected = report(1, token = "00000000000000000000000000000001")
    val entries = listOf(
      DiagnosticReportEntry(protected, 1),
      DiagnosticReportEntry(report(2), 2),
      DiagnosticReportEntry(report(3), 3),
      DiagnosticReportEntry(report(4), 4),
      DiagnosticReportEntry(report(5), 5),
      DiagnosticReportEntry(report(6), 6),
      DiagnosticReportEntry("nebula-diagnostics.txt", 0),
      DiagnosticReportEntry("unrelated-cache.txt", -1),
      DiagnosticReportEntry(
        "${DiagnosticReportFilePolicy.TEMP_PREFIX}leftover${DiagnosticReportFilePolicy.TEMP_SUFFIX}",
        100,
      ),
    )

    val pruned = DiagnosticReportFilePolicy.reportsToPrune(
      entries = entries,
      protectedName = protected,
    )

    assertEquals(
      setOf(report(2), "nebula-diagnostics.txt"),
      pruned,
    )
    assertFalse("unrelated-cache.txt" in pruned)
    assertFalse(protected in pruned)
  }

  @Test
  fun `temporary cleanup matcher cannot widen to reports or unrelated cache files`() {
    val temporary = DiagnosticReportFilePolicy.temporaryName(
      "0123456789abcdef0123456789abcdef",
    )
    assertTrue(
      DiagnosticReportFilePolicy.isTemporaryName(temporary),
    )
    assertFalse(DiagnosticReportFilePolicy.isTemporaryName(report(1)))
    assertFalse(DiagnosticReportFilePolicy.isTemporaryName("other.tmp"))
    assertFalse(
      DiagnosticReportFilePolicy.isTemporaryName(
        "${DiagnosticReportFilePolicy.TEMP_PREFIX}unrelated${DiagnosticReportFilePolicy.TEMP_SUFFIX}",
      ),
    )
  }

  private fun report(
    timestamp: Long,
    token: String = timestamp.toString(16).padStart(32, '0'),
  ): String = DiagnosticReportFilePolicy.reportName(timestamp, token)
}
