package com.stremioshell.host.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.stremioshell.host.tv.data.SharedHttpClient

/**
 * Tunes Coil for a low-RAM TV: RGB_565 halves poster memory (posters are
 * opaque JPEGs), a bounded memory cache keeps GC pressure down during scroll,
 * and crossfade is off so focus moves stay cheap.
 */
class StremioTvApplication : Application(), ImageLoaderFactory {
  override fun onCreate() {
    super.onCreate()
    // The shared OkHttp client's disk cache needs a Context, and this is the first point in the
    // process where one exists - well before any screen can ask for data.
    SharedHttpClient.init(this)
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
