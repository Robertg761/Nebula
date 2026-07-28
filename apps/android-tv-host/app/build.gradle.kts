plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.serialization")
  // @Parcelize on the TV back-stack Screen types so navigation survives
  // activity recreation.
  id("org.jetbrains.kotlin.plugin.parcelize")
}

// Optional CI signing. If these env vars are present, `assemble...Release` will produce signed APKs.
val ssSigningStoreFile = System.getenv("SS_SIGNING_STORE_FILE")
val ssSigningStorePassword = System.getenv("SS_SIGNING_STORE_PASSWORD")
val ssSigningKeyAlias = System.getenv("SS_SIGNING_KEY_ALIAS")
val ssSigningKeyPassword = System.getenv("SS_SIGNING_KEY_PASSWORD")
val ssSigningStoreType = System.getenv("SS_SIGNING_STORE_TYPE") // e.g. "PKCS12" or "JKS"
val ssHasSigning = !ssSigningStoreFile.isNullOrBlank() &&
  !ssSigningStorePassword.isNullOrBlank() &&
  !ssSigningKeyAlias.isNullOrBlank() &&
  !ssSigningKeyPassword.isNullOrBlank()

val githubReleaseOwner = (project.findProperty("githubReleaseOwner") as String?)
  ?.trim()
  .orEmpty()
  .ifBlank { "Robertg761" }
val githubReleaseRepo = (project.findProperty("githubReleaseRepo") as String?)
  ?.trim()
  .orEmpty()
  .ifBlank { "stremio-shell-tv" }

android {
  namespace = "com.stremioshell.host"
  compileSdk = 34

  defaultConfig {
    // Keep the historical .tv application id so self-updates keep installing
    // over builds produced when this was a flavor suffix.
    applicationId = "com.stremioshell.host.tv"
    minSdk = 26
    targetSdk = 34
    versionCode = 16
    versionName = "0.6.0"
    // app_name lives in res/values/strings.xml alone; a generated resValue of the same name used
    // to shadow it, so the launcher label and the in-app copy could disagree.
    buildConfigField("String", "GITHUB_RELEASE_OWNER", "\"$githubReleaseOwner\"")
    buildConfigField("String", "GITHUB_RELEASE_REPO", "\"$githubReleaseRepo\"")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

  }

  if (ssHasSigning) {
    signingConfigs {
      create("release") {
        storeFile = file(ssSigningStoreFile!!)
        if (!ssSigningStoreType.isNullOrBlank()) {
          storeType = ssSigningStoreType
        }
        storePassword = ssSigningStorePassword
        keyAlias = ssSigningKeyAlias
        keyPassword = ssSigningKeyPassword
      }
    }
  }

  buildTypes {
    debug {
      // The dev/CI emulators are x86_64 (and libmpv ships no 32-bit x86 that a
      // TV emulator image would ever load), so debug keeps x86_64 on top of the
      // shipping ABIs.
      ndk {
        abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
      }
      manifestPlaceholders["usesCleartextTraffic"] = "true"
    }
    release {
      // No Android TV device is x86; shipping those ABIs doubled the APK for
      // nothing. Single universal APK on purpose - the release workflow picks
      // one file out of outputs/apk/release and the in-app updater matches a
      // single "-tv-" named asset, both of which ABI splits would break.
      ndk {
        abiFilters += listOf("arm64-v8a", "armeabi-v7a")
      }
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
      if (ssHasSigning) {
        signingConfig = signingConfigs.getByName("release")
      }
      manifestPlaceholders["usesCleartextTraffic"] = "false"
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions {
    jvmTarget = "17"
  }

  buildFeatures {
    buildConfig = true
    compose = true
  }

  composeOptions {
    kotlinCompilerExtensionVersion = "1.5.14"
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
  implementation("androidx.work:work-runtime-ktx:2.9.1")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

  // Native Compose TV app (Comet + Real-Debrid path)
  implementation(platform("androidx.compose:compose-bom:2024.09.00"))
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.tv:tv-material:1.0.0")
  implementation("androidx.compose.material:material-icons-core")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.activity:activity-compose:1.9.2")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
  implementation("androidx.datastore:datastore-preferences:1.1.1")
  // Watch Next rows on the Android TV home screen (TvContractCompat). Stable 1.0.0
  // rather than the 1.1.0 alpha: the Watch Next API has not moved since 1.0.0.
  implementation("androidx.tvprovider:tvprovider:1.0.0")
  implementation("io.coil-kt:coil-compose:2.6.0")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
  implementation("dev.jdtech.mpv:libmpv:0.4.1")

  // Phone-pairing config entry (QR + tiny on-device web form)
  implementation("com.google.zxing:core:3.5.3")
  implementation("org.nanohttpd:nanohttpd:2.3.1")

  // Installs the committed baseline profile (src/main/baseline-prof.txt) at
  // first run so the Compose UI paths are AOT-compiled. See docs for regen.
  implementation("androidx.profileinstaller:profileinstaller:1.3.1")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.json:json:20240303")

  androidTestImplementation("androidx.test:core-ktx:1.6.1")
  androidTestImplementation("androidx.test.ext:junit:1.2.1")
  androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
  androidTestImplementation("androidx.test:runner:1.6.2")
  androidTestImplementation("androidx.test:rules:1.6.1")
}
