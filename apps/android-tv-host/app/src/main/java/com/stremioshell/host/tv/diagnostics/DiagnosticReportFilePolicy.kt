package com.stremioshell.host.tv.diagnostics

/**
 * Pure naming and retention policy for exported support reports.
 *
 * Only names created here (plus the one legacy fixed name) are ever eligible for deletion. Keeping
 * that decision separate from filesystem work makes pruning incapable of widening to unrelated
 * cache files because of a loose prefix check.
 */
internal object DiagnosticReportFilePolicy {
  const val MAX_RETAINED_REPORTS = 5
  const val TEMP_PREFIX = ".nebula-diagnostics-write-"
  const val TEMP_SUFFIX = ".tmp"

  private const val LEGACY_REPORT_NAME = "nebula-diagnostics.txt"
  private val UNIQUE_REPORT_NAME =
    Regex("""nebula-diagnostics-[0-9]{1,19}-[0-9a-f]{32}\.txt""")
  private val TEMPORARY_NAME =
    Regex("""\.nebula-diagnostics-write-[0-9a-f]{32}\.tmp""")
  private val UNIQUE_TOKEN = Regex("""[0-9a-f]{32}""")

  fun reportName(createdAtMs: Long, uniqueToken: String): String {
    require(createdAtMs >= 0L)
    require(UNIQUE_TOKEN.matches(uniqueToken))
    return "nebula-diagnostics-$createdAtMs-$uniqueToken.txt"
  }

  fun temporaryName(uniqueToken: String): String {
    require(UNIQUE_TOKEN.matches(uniqueToken))
    return "$TEMP_PREFIX$uniqueToken$TEMP_SUFFIX"
  }

  fun isManagedReportName(name: String): Boolean =
    name == LEGACY_REPORT_NAME || UNIQUE_REPORT_NAME.matches(name)

  fun isTemporaryName(name: String): Boolean = TEMPORARY_NAME.matches(name)

  /**
   * Selects old managed reports while always preserving [protectedName], even if a device clock
   * moved backwards and made the newly-created report look older than an earlier export.
   */
  fun reportsToPrune(
    entries: List<DiagnosticReportEntry>,
    protectedName: String,
    retainedReports: Int = MAX_RETAINED_REPORTS,
  ): Set<String> {
    require(retainedReports >= 1)
    val managed = entries.filter { isManagedReportName(it.name) }
    val keep = buildSet {
      managed.firstOrNull { it.name == protectedName }?.let { add(it.name) }
      managed
        .asSequence()
        .filterNot { it.name == protectedName }
        .sortedWith(
          compareByDescending<DiagnosticReportEntry> { it.lastModifiedMs }
            .thenByDescending { it.name },
        )
        .take(retainedReports - size)
        .forEach { add(it.name) }
    }
    return managed.mapTo(mutableSetOf()) { it.name } - keep
  }
}

internal data class DiagnosticReportEntry(
  val name: String,
  val lastModifiedMs: Long,
)
