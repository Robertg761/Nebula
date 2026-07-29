package com.stremioshell.host.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.stremioshell.host.tv.channel.WatchNextSync
import com.stremioshell.host.tv.data.SharedHttpClient
import com.stremioshell.host.tv.diagnostics.NebulaDiagnostics

/**
 * Tunes Coil for a low-RAM TV: RGB_565 halves poster memory (posters are
 * opaque JPEGs), a bounded memory cache keeps GC pressure down during scroll,
 * and crossfade is off so focus moves stay cheap.
 */
class StremioTvApplication : Application(), ImageLoaderFactory {
  override fun onCreate() {
    super.onCreate()
    NebulaDiagnostics.initialize(this)
    // The shared OkHttp client's disk cache needs a Context, and this is the first point in the
    // process where one exists - well before any screen can ask for data.
    SharedHttpClient.init(this)
    // The rows outlive the process, so anything that changed the watch state
    // while the app was not running - a retention prune, a restore from backup,
    // an install over an older build that never published - is reconciled here.
    // Off the main thread and throttled inside; a cold start publishes once.
    WatchNextSync.publish(this)
  }

  override fun newImageLoader(): ImageLoader {
    return ImageLoader.Builder(this)
      .allowRgb565(true)
      .crossfade(false)
      .memoryCache {
        // This TV class runs tight on RAM; keep the poster cache modest to
        // avoid swap/GC pressure that stalls the UI thread.
        MemoryCache.Builder(this)
          .maxSizePercent(0.12)
          .build()
      }
      .build()
  }
}
