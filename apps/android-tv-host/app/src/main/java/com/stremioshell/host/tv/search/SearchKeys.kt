package com.stremioshell.host.tv.search

/**
 * The remote keys that mean "I want to search".
 *
 * Literal codes, mirrored from android.view.KeyEvent, so the mapping is a JVM test
 * rather than something only an emulator can answer; SearchKeysTest pins them against
 * the framework constants.
 */
object SearchKeys {
  /** KeyEvent.KEYCODE_SEARCH - the magnifier key, and what most TV mic keys deliver. */
  const val KEYCODE_SEARCH = 84

  /**
   * KeyEvent.KEYCODE_VOICE_ASSIST. Normally swallowed by the system assistant; it only
   * reaches an app on a device with no assistant to route it to, where landing on our
   * own search is better than the key doing nothing.
   */
  const val KEYCODE_VOICE_ASSIST = 231

  /**
   * KEYCODE_ASSIST (219) is deliberately absent: the framework delivers it as an assist
   * gesture to whichever assistant the viewer chose, and claiming it here would shadow
   * that on the remotes where it does arrive.
   */
  fun opensSearch(keyCode: Int): Boolean =
    keyCode == KEYCODE_SEARCH || keyCode == KEYCODE_VOICE_ASSIST
}
