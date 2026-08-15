# The separately minified AndroidJUnitRunner APK calls this class from the tested app's dependency
# graph. Its Kotlin-based storage and this tracing hook rely on names across the APK boundary.
# Keep those library ABIs only in the emulator target; app classes and the shipping APK remain
# fully optimized.
-keep class androidx.tracing.Trace { *; }
-keep class androidx.core.view.ViewCompat { *; }
-keep class androidx.core.view.WindowInsetsCompat { *; }
-keep class androidx.core.view.WindowInsetsCompat$* { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

# Instrumentation lives in a separate APK and therefore cannot call members renamed only in the
# tested APK. Preserve the small API surface the credential-free tests cross directly; the rest of
# Nebula is still shrunk and obfuscated exactly like release.
-keep class com.stremioshell.host.tv.data.SettingsStore { *; }
-keep class com.stremioshell.host.tv.data.SettingsStore$* { *; }
-keep class com.stremioshell.host.tv.channel.WatchNextPublisher { *; }
-keep class com.stremioshell.host.tv.channel.WatchNextProgramData { *; }
-keep class com.stremioshell.host.tv.channel.WatchNextProgramType { *; }
-keep class com.stremioshell.host.tv.channel.WatchNextKind { *; }
-keep class com.stremioshell.host.tv.player.PlayerRestorableState { *; }
-keep class com.stremioshell.host.tv.player.PlayerRestorableState$* { *; }
