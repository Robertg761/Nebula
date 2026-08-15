package com.stremioshell.host.tv.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.Closeable

enum class SavedContentReason { Offline, RefreshUnavailable }

/** Why usable content remains on screen after its live refresh could not replace it. */
data class SavedContentProvenance(
  /** The last confirmed live load, or null when only the HTTP disk cache knows the age. */
  val savedAtMillis: Long?,
  val reason: SavedContentReason = SavedContentReason.RefreshUnavailable,
) {
  /** Null for an unknown age or a TV clock that moved backwards after the content was saved. */
  fun ageMillis(nowMillis: Long): Long? = savedAtMillis?.let { savedAt ->
    (nowMillis - savedAt).takeIf { it >= 0L }
  }

  companion object {
    /**
     * One badge can describe Details metadata and its selected episode list. If either age is
     * unknown, the combined age is unknown rather than claiming the known half describes both.
     */
    fun oldest(vararg values: SavedContentProvenance?): SavedContentProvenance? {
      val present = values.filterNotNull()
      if (present.isEmpty()) return null
      val timestamps = present.mapNotNull(SavedContentProvenance::savedAtMillis)
      return SavedContentProvenance(
        savedAtMillis = timestamps.minOrNull().takeIf { timestamps.size == present.size },
        reason = if (present.any { it.reason == SavedContentReason.Offline }) {
          SavedContentReason.Offline
        } else {
          SavedContentReason.RefreshUnavailable
        },
      )
    }
  }
}

/** Pure provenance decisions shared by the asynchronous loaders and their focused tests. */
internal object SavedContentRefreshPolicy {
  fun homeUsesSavedContent(
    visibleTitles: Set<String>,
    previousTitles: Set<String>,
    failedTitles: Set<String>,
    staleFallbackTitles: Set<String>,
  ): Boolean = staleFallbackTitles.any(visibleTitles::contains) ||
    failedTitles.any { it in previousTitles && it in visibleTitles }
}

/**
 * Turns noisy capability callbacks into one event per actual offline-to-validated transition.
 * Kept separate from Android's callback so the bounded behavior is deterministic in JVM tests.
 */
internal class ValidatedConnectivityTransition(initiallyValidated: Boolean) {
  private var validated = initiallyValidated

  @get:Synchronized
  val currentlyValidated: Boolean get() = validated

  @Synchronized
  fun update(isValidated: Boolean): Boolean {
    val returned = !validated && isValidated
    validated = isValidated
    return returned
  }
}

/**
 * Watches only the default network and reports when Android has validated Internet access again.
 *
 * `onAvailable` alone is not sufficient: captive portals and local-only Wi-Fi both reach that
 * callback. NET_CAPABILITY_VALIDATED is Android's bounded proof that the default route can reach
 * the Internet. Registration is process-local and explicitly closed with its owning ViewModel.
 */
internal class ValidatedConnectivityMonitor(
  context: Context,
  private val onValidatedReturn: () -> Unit,
) : Closeable {
  private val manager = context.applicationContext
    .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
  private val lifecycleLock = Any()
  private val networkLock = Any()

  @Volatile
  private var running = false
  private var registered = false
  private var currentNetwork: Network? = runCatching { manager?.activeNetwork }.getOrNull()
  private val transition = ValidatedConnectivityTransition(
    isValidated(runCatching { manager?.getNetworkCapabilities(currentNetwork) }.getOrNull()),
  )

  private val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
      updateNetwork(
        network,
        runCatching { manager?.getNetworkCapabilities(network) }.getOrNull(),
      )
    }

    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
      updateNetwork(network, capabilities)
    }

    override fun onLost(network: Network) {
      synchronized(networkLock) {
        if (currentNetwork == network) {
          currentNetwork = null
          transition.update(false)
        }
      }
    }
  }

  val currentlyValidated: Boolean?
    get() = manager?.let { transition.currentlyValidated }

  fun start() {
    val connectivity = manager ?: return
    synchronized(lifecycleLock) {
      if (running) return
      running = true
      try {
        connectivity.registerDefaultNetworkCallback(callback)
        registered = true
      } catch (_: RuntimeException) {
        // Monitoring is polish, not authority for whether requests may run. Devices with a broken
        // connectivity service retain manual Retry and normal lifecycle refresh behavior.
        running = false
      }
    }
  }

  override fun close() {
    synchronized(lifecycleLock) {
      if (!running && !registered) return
      running = false
      if (registered) {
        runCatching { manager?.unregisterNetworkCallback(callback) }
        registered = false
      }
    }
  }

  private fun updateNetwork(network: Network, capabilities: NetworkCapabilities?) {
    val shouldNotify = synchronized(networkLock) {
      currentNetwork = network
      transition.update(isValidated(capabilities))
    }
    if (shouldNotify && running) onValidatedReturn()
  }

  private companion object {
    fun isValidated(capabilities: NetworkCapabilities?): Boolean =
      capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
  }
}
