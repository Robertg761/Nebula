plugins {
  id("com.android.application") version "8.5.2" apply false
  id("com.android.test") version "8.5.2" apply false
  id("org.jetbrains.kotlin.android") version "1.9.24" apply false
  id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
  id("org.jetbrains.kotlin.plugin.parcelize") version "1.9.24" apply false
  id("androidx.baselineprofile") version "1.4.1" apply false
}

// Keep every resolved configuration on the reviewed graph recorded in each
// project's gradle.lockfile. Updating a dependency is therefore an explicit
// source change rather than an incidental result of repository metadata.
allprojects {
  dependencyLocking {
    lockAllConfigurations()
  }
}
