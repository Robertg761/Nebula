plugins {
  id("com.android.application") version "8.5.2" apply false
  id("com.android.test") version "8.5.2" apply false
  id("org.jetbrains.kotlin.android") version "2.4.10" apply false
  id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
  id("org.jetbrains.kotlin.plugin.parcelize") version "2.4.10" apply false
  id("androidx.baselineprofile") version "1.3.3" apply false
}

// Keep every resolved configuration on the reviewed graph recorded in each
// project's gradle.lockfile. Updating a dependency is therefore an explicit
// source change rather than an incidental result of repository metadata.
allprojects {
  dependencyLocking {
    lockAllConfigurations()
  }
}
