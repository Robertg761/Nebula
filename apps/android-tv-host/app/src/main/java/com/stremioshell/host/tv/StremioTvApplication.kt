package com.stremioshell.host.tv

import android.app.Application
import android.os.Looper
import android.util.Log
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.stremioshell.host.tv.channel.WatchNextSync
import com.stremioshell.host.tv.data.SharedHttpClient
import com.stremioshell.host.tv.data.persistenceScope
import com.stremioshell.host.tv.diagnostics.NebulaDiagnostics
import com.stremioshell.host.tv.player.MpvTlsCertificates
import java.io.File
import kotlinx.coroutines.launch

/**
 * Tunes Coil for a low-RAM TV: RGB_565 halves poster memory (posters are
 * opaque JPEGs), a bounded memory cache keeps GC pressure down during scroll,
 * and crossfade is off so focus moves stay cheap.
 *
 * Also the WorkManager configuration holder. Everything here that is not needed to paint the
 * first frame is either lazy or deferred to the first main-thread idle.
 */
class StremioTvApplication : Application(), ImageLoaderFactory, Configuration.Provider {
  override fun onCreate() {
    super.onCreate()
    NebulaDiagnostics.initialize(this)
    // The shared OkHttp client's disk cache needs a Context, and this is the first point in the
    // process where one exists - well before any screen can ask for data. Only records the
    // directory; the client itself is built on first use.
    SharedHttpClient.init(this)
    // The rows outlive the process, so anything that changed the watch state
    // while the app was not running - a retention prune, a restore from backup,
    // an install over an older build that never published - is reconciled here.
    // Off the main thread and throttled inside; a cold start publishes once.
    //
    // Held back until the main thread first runs dry, which on a launch is after the first frame:
    // the publish reads the whole watch list out of DataStore and writes it across to the TV
    // provider, and that competes with the launch for the Streamer's eMMC. Nothing is lost by
    // waiting - the rows are only looked at once the viewer leaves, and TvAppActivity.onStop
    // force-publishes then.
    Looper.myQueue().addIdleHandler {
      WatchNextSync.publish(this)
      // The player hands this file to mpv on every open, and generating it is a one-time
      // ~200-root export that used to land on the player's onCreate - the worst possible
      // moment for a few hundred milliseconds of main-thread X.509 work. Warmed here, off
      // the main thread, the player's own call is a stat of an existing file.
      persistenceScope.launch { MpvTlsCertificates.ensureBundle(this@StremioTvApplication) }
      // One shot; the throttle inside publish() governs everything after it.
      false
    }
  }

  /**
   * WorkManager's manifest initializer is removed, so it starts on demand instead of opening its
   * work database while the launcher intent is still being handled. This is the configuration that
   * first `WorkManager.getInstance` call builds from; without it that call throws.
   */
  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder()
      // The default, INFO, narrates every scheduling decision into logcat for an app whose only
      // background job runs four times a day.
      .setMinimumLoggingLevel(Log.WARN)
      .build()

  override fun newImageLoader(): ImageLoader {
    return ImageLoader.Builder(this)
      .allowRgb565(true)
      .crossfade(false)
      // Posters go through the app's one connection pool, dispatcher and TLS session cache; a
      // separate client would stand up a second set of all three. The response cache is dropped
      // from the copy deliberately: Coil keeps its own disk cache below, and leaving OkHttp's in
      // place would write every poster twice and evict the catalog JSON that cache exists for.
      .okHttpClient { SharedHttpClient.client.newBuilder().cache(null).build() }
      .diskCache {
        // Coil's default size is a share of whatever is free, which on a Streamer that ships
        // nearly full is both unpredictable and far more than a poster cache ever needs. The
        // directory is Coil's own default name on purpose: a build that upgrades into this
        // adopts the cache it already has instead of stranding it beside a new one.
        DiskCache.Builder()
          .directory(File(cacheDir, "image_cache"))
          .maxSizeBytes(POSTER_DISK_CACHE_BYTES)
          .build()
      }
      .memoryCache {
        // This TV class runs tight on RAM; keep the poster cache modest to
        // avoid swap/GC pressure that stalls the UI thread.
        MemoryCache.Builder(this)
          .maxSizePercent(0.12)
          .build()
      }
      .build()
  }

  private companion object {
    const val POSTER_DISK_CACHE_BYTES = 64L * 1024 * 1024
  }
}
