plugins {
  id("com.android.test")
  id("org.jetbrains.kotlin.android")
  id("androidx.baselineprofile")
}

android {
  namespace = "com.stremioshell.host.baselineprofile"
  compileSdk = 34

  defaultConfig {
    minSdk = 28
    targetSdk = 34
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  targetProjectPath = ":app"
}

kotlin {
  jvmToolchain(17)
}

baselineProfile {
  // Use an explicitly selected API 33+ TV device. The generation wrapper
  // records that device's build and refuses to overwrite the committed profile.
  useConnectedDevices = true
}

dependencies {
  implementation("androidx.test.ext:junit:1.3.0")
  implementation("androidx.test:runner:1.7.0")
  implementation("androidx.test.uiautomator:uiautomator:2.4.0")
  implementation("androidx.benchmark:benchmark-macro-junit4:1.3.3")
}
