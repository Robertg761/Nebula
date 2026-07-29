package com.stremioshell.host.tv.diagnostics

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import androidx.core.content.FileProvider
import com.stremioshell.host.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Small, local-first support recorder.
 *
 * It records bounded, already-redacted operational events in no-backup storage. Nothing is sent
 * anywhere: the viewer must explicitly choose "Share diagnostics", which creates a fresh text
 * report in the app cache and opens Android's chooser.
 */
object NebulaDiagnostics {
  private const val MAX_EVENT_BYTES = 64 * 1024L
  private const val RETAINED_EVENT_LINES = 160
  private const val MAX_EXIT_RECORDS = 5
  private const val REPORT_DIRECTORY = "diagnostics"
  private const val EVENT_FILE = "events.log"

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val fileMutex = Mutex()
  @Volatile private var appContext: Context? = null

  fun initialize(application: Application) {
    if (appContext != null) return
    appContext = application.applicationContext
    application.registerActivityLifecycleCallbacks(ActivityEvents)
    record(
      area = "app",
      detail = "started version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
    )
  }

  /** Records one bounded event off the calling thread. */
  fun record(area: String, detail: String) {
    val context = appContext ?: return
    val safeArea = DiagnosticSanitizer.sanitize(area).take(40)
    val safeDetail = DiagnosticSanitizer.sanitize(detail)
    val line = "${Instant.now()}\t$safeArea\t$safeDetail"
    scope.launch {
      fileMutex.withLock {
        runCatching { appendBounded(eventFile(context), line) }
      }
    }
  }

  /**
   * Creates a current report and returns its shareable URI.
   *
   * The report remains private unless the caller grants read access through an explicit intent.
   */
  suspend fun export(context: Context): Result<Uri> = withContext(Dispatchers.IO) {
    runCatching {
      val report = fileMutex.withLock {
        val outputDirectory = reportDirectory(context)
        removeAbandonedTemporaryFiles(outputDirectory)
        val createdAtMs = Instant.now().toEpochMilli()
        val uniqueToken = UUID.randomUUID().toString().replace("-", "")
        val output = writeReportAtomically(
          directory = outputDirectory,
          fileName = DiagnosticReportFilePolicy.reportName(
            createdAtMs,
            uniqueToken,
          ),
          temporaryFileName = DiagnosticReportFilePolicy.temporaryName(uniqueToken),
          contents = renderReport(context.applicationContext),
        )
        pruneOlderReports(outputDirectory, protected = output)
        output
      }
      FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        report,
      )
    }
  }

  fun shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_SUBJECT, "Nebula diagnostics")
    putExtra(Intent.EXTRA_STREAM, uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
  }

  private fun eventFile(context: Context): File {
    val directory = File(context.noBackupFilesDir, REPORT_DIRECTORY).apply { mkdirs() }
    return File(directory, EVENT_FILE)
  }

  private fun reportDirectory(context: Context): File {
    val directory = File(context.cacheDir, REPORT_DIRECTORY)
    check((directory.isDirectory || directory.mkdirs()) && directory.isDirectory) {
      "Could not create the diagnostics report directory"
    }
    return directory
  }

  /**
   * Publishes a report only after its complete contents are on disk.
   *
   * The temporary file and destination share a directory, so ATOMIC_MOVE maps to one filesystem
   * rename. A recipient can therefore observe either no report or the complete report, never a
   * partially-written file at a previously shared URI. The destination is new for every export and
   * made read-only before its URI leaves this object.
   */
  private fun writeReportAtomically(
    directory: File,
    fileName: String,
    temporaryFileName: String,
    contents: String,
  ): File {
    val destination = File(directory, fileName)
    check(!destination.exists()) { "Diagnostics report name collision" }
    val temporary = File(directory, temporaryFileName)
    check(temporary.createNewFile()) { "Diagnostics temporary file name collision" }
    try {
      FileOutputStream(temporary).use { stream ->
        stream.write(contents.toByteArray(StandardCharsets.UTF_8))
        stream.fd.sync()
      }
      Files.move(
        temporary.toPath(),
        destination.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
      )
      check(destination.setReadOnly() || !destination.canWrite()) {
        destination.delete()
        "Could not make the diagnostics report read-only"
      }
      return destination
    } finally {
      // No-op after a successful move; cleans up a failed write/move without touching any report.
      temporary.delete()
    }
  }

  private fun pruneOlderReports(directory: File, protected: File) {
    val files = directory.listFiles().orEmpty()
    val namesToDelete = DiagnosticReportFilePolicy.reportsToPrune(
      entries = files.map { DiagnosticReportEntry(it.name, it.lastModified()) },
      protectedName = protected.name,
    )
    files
      .filter { it.isFile && it.name in namesToDelete }
      .forEach { it.delete() }
  }

  /**
   * A process death can leave only the private pre-publication temp file behind. Exports are
   * serialized by [fileMutex], so none of these tightly-named files can still be in use here.
   */
  private fun removeAbandonedTemporaryFiles(directory: File) {
    directory.listFiles()
      .orEmpty()
      .filter { it.isFile && DiagnosticReportFilePolicy.isTemporaryName(it.name) }
      .forEach { it.delete() }
  }

  private fun appendBounded(file: File, line: String) {
    val lines = if (file.exists()) file.readLines().takeLast(RETAINED_EVENT_LINES) else emptyList()
    val retained = (lines + line).toMutableList()
    var encoded = retained.joinToString(separator = "\n", postfix = "\n").encodeToByteArray()
    while (encoded.size > MAX_EVENT_BYTES && retained.size > 1) {
      retained.removeAt(0)
      encoded = retained.joinToString(separator = "\n", postfix = "\n").encodeToByteArray()
    }
    // A single sanitized line is far below the limit. Writing the complete bounded ring also
    // recovers an oversized legacy file without cutting through a multibyte UTF-8 character.
    file.writeBytes(encoded)
  }

  private fun renderReport(context: Context): String = buildString {
    appendLine("Nebula diagnostics")
    appendLine("Generated: ${Instant.now()}")
    appendLine()
    appendLine("[App]")
    appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    appendLine("Package: ${context.packageName}")
    appendLine("Build type: ${BuildConfig.BUILD_TYPE}")
    appendLine()
    appendLine("[Device]")
    appendLine("Manufacturer: ${DiagnosticSanitizer.sanitize(Build.MANUFACTURER)}")
    appendLine("Model: ${DiagnosticSanitizer.sanitize(Build.MODEL)}")
    appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
    appendLine("Low RAM device: ${activityManager(context)?.isLowRamDevice ?: "unknown"}")
    appendLine("Storage free bytes: ${freeStorageBytes(context)}")
    appendLine("Network: ${networkSummary(context)}")
    appendLine()
    appendLine("[Recent process exits]")
    val exits = historicalExits(context)
    if (exits.isEmpty()) {
      appendLine("Unavailable or none recorded")
    } else {
      exits.forEach(::appendLine)
    }
    appendLine()
    appendLine("[Recent redacted events]")
    val events = eventFile(context).takeIf(File::exists)?.readLines().orEmpty()
    if (events.isEmpty()) appendLine("None recorded") else events.forEach(::appendLine)
    appendLine()
    appendLine("This report is created locally and shared only through the Android app you choose.")
    appendLine("Credentials, URL query values, and private addon path segments are redacted.")
  }

  private fun activityManager(context: Context): ActivityManager? =
    context.getSystemService(ActivityManager::class.java)

  private fun freeStorageBytes(context: Context): Long = runCatching {
    StatFs(context.filesDir.absolutePath).availableBytes
  }.getOrDefault(-1L)

  private fun networkSummary(context: Context): String {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
    val network = manager.activeNetwork ?: return "offline"
    val capabilities = manager.getNetworkCapabilities(network) ?: return "unknown"
    val transports = buildList {
      if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
      if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
      if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
      if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
    }.ifEmpty { listOf("other") }
    val validation = if (
      capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    ) {
      "validated"
    } else {
      "not-validated"
    }
    return "${transports.joinToString("+")}, $validation"
  }

  private fun historicalExits(context: Context): List<String> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
    return runCatching {
      activityManager(context)
        ?.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_RECORDS)
        .orEmpty()
        .map { exit ->
          val timestamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            Instant.ofEpochMilli(exit.timestamp).atOffset(ZoneOffset.UTC),
          )
          "$timestamp: ${exitReason(exit.reason)}, importance=${exit.importance}, " +
            "pss=${exit.pss}, rss=${exit.rss}"
        }
    }.getOrDefault(emptyList())
  }

  private fun exitReason(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_ANR -> "ANR"
    ApplicationExitInfo.REASON_CRASH -> "crash"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "native-crash"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "resource-usage"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization-failure"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission-change"
    ApplicationExitInfo.REASON_SIGNALED -> "signal"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "user-requested"
    ApplicationExitInfo.REASON_USER_STOPPED -> "user-stopped"
    else -> "reason-$reason"
  }

  private object ActivityEvents : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
      record("lifecycle", "${activity.javaClass.simpleName} created")
    }

    override fun onActivityResumed(activity: Activity) {
      record("lifecycle", "${activity.javaClass.simpleName} resumed")
    }

    override fun onActivityDestroyed(activity: Activity) {
      record("lifecycle", "${activity.javaClass.simpleName} destroyed")
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
  }
}
