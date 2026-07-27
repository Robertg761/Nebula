package com.stremioshell.host.tv.search

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchKeysTest {
  @Test
  fun `the mirrored codes are the framework ones`() {
    // Compile-time constants, so this asserts the literals without loading KeyEvent.
    assertEquals(KeyEvent.KEYCODE_SEARCH, SearchKeys.KEYCODE_SEARCH)
    assertEquals(KeyEvent.KEYCODE_VOICE_ASSIST, SearchKeys.KEYCODE_VOICE_ASSIST)
  }

  @Test
  fun `the search and voice keys open search`() {
    assertTrue(SearchKeys.opensSearch(SearchKeys.KEYCODE_SEARCH))
    assertTrue(SearchKeys.opensSearch(SearchKeys.KEYCODE_VOICE_ASSIST))
  }

  @Test
  fun `navigation and playback keys are left alone`() {
    for (keyCode in listOf(
      KeyEvent.KEYCODE_DPAD_CENTER,
      KeyEvent.KEYCODE_DPAD_UP,
      KeyEvent.KEYCODE_BACK,
      KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
      KeyEvent.KEYCODE_HOME,
      // The assist gesture belongs to the system assistant, not to us.
      KeyEvent.KEYCODE_ASSIST,
    )) {
      assertFalse(SearchKeys.opensSearch(keyCode))
    }
  }
}
