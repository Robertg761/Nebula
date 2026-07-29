package com.stremioshell.host.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs CPU-heavy response decoding away from the caller.
 *
 * ViewModels normally call clients from Main. Keeping this seam shared makes it difficult for a
 * newly added endpoint to accidentally put kotlinx.serialization's full parse on the D-pad thread.
 */
internal suspend fun <T> decodeJsonOffMain(decode: () -> T): T =
  withContext(Dispatchers.Default) { decode() }
