import org.gradle.api.GradleException
import org.gradle.api.tasks.Sync

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

val debugWebAppUrl = (project.findProperty("webAppUrl") as String?)
  ?.trim()
  .orEmpty()
  .ifBlank { "" }
val githubReleaseOwner = (project.findProperty("githubReleaseOwner") as String?)
  ?.trim()
  .orEmpty()
  .ifBlank { "Robertg761" }
val githubReleaseRepo = (project.findProperty("githubReleaseRepo") as String?)
  ?.trim()
  .orEmpty()
  .ifBlank { "stremio-shell-tv" }

val webDistDir = rootDir.resolve("../web/dist")
val coreRuntimeDistDir = rootDir.resolve("core-runtime-dist")
val generatedMainAssetsDir = project.layout.buildDirectory.dir("generated/assets/main")
val webAssetsDir = generatedMainAssetsDir.map { it.dir("web") }
val coreRuntimeAssetsDir = generatedMainAssetsDir.map { it.dir("core-runtime") }

android {
  namespace = "com.stremioshell.host"
  compileSdk = 34

  defaultConfig {
    // Keep the historical .tv application id so self-updates keep installing
    // over builds produced when this was a flavor suffix.
    applicationId = "com.stremioshell.host.tv"
    minSdk = 26
    targetSdk = 34
    versionCode = 14
    versionName = "0.4.0"
    resValue("string", "app_name", "Stremio Shell TV")
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
      buildConfigField("String", "WEB_APP_URL", "\"$debugWebAppUrl\"")
      buildConfigField("String", "WEB_REMOTE_FALLBACK_URL", "\"https://web.stremio.com/\"")
      manifestPlaceholders["usesCleartextTraffic"] = "true"
    }
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
      if (ssHasSigning) {
        signingConfig = signingConfigs.getByName("release")
      }
      buildConfigField("String", "WEB_APP_URL", "\"\"")
      buildConfigField("String", "WEB_REMOTE_FALLBACK_URL", "\"https://web.stremio.com/\"")
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

  sourceSets {
    getByName("main") {
      assets.srcDir(generatedMainAssetsDir)
    }
  }
}

kotlin {
  jvmToolchain(17)
}

val syncWebAssets by tasks.registering(Sync::class) {
  group = "build setup"
  description = "Sync built web shell assets from apps/web/dist into Android assets."

  from(webDistDir)
  into(webAssetsDir)

  doFirst {
    if (!webDistDir.exists()) {
      throw GradleException(
        "Missing web bundle at ${webDistDir.absolutePath}. Run `pnpm --filter @stremio-shell/web build` first."
      )
    }
  }
}

val syncCoreRuntimeAssets by tasks.registering(Sync::class) {
  group = "build setup"
  description = "Sync bundled core runtime JS into Android assets."

  from(coreRuntimeDistDir)
  into(coreRuntimeAssetsDir)

  doFirst {
    if (!coreRuntimeDistDir.exists()) {
      throw GradleException(
        "Missing core runtime bundle at ${coreRuntimeDistDir.absolutePath}. Expected runtime.js in this directory."
      )
    }
  }
}

tasks.matching { task ->
  task.name.startsWith("merge") && task.name.endsWith("Assets")
}.configureEach {
  dependsOn(syncWebAssets)
  dependsOn(syncCoreRuntimeAssets)
}

tasks.matching { task ->
  task.name.contains("LintVital", ignoreCase = true)
}.configureEach {
  dependsOn(syncWebAssets)
  dependsOn(syncCoreRuntimeAssets)
}

dependencies {
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("com.google.android.material:material:1.12.0")
  implementation("androidx.webkit:webkit:1.11.0")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
  implementation("androidx.work:work-runtime-ktx:2.9.1")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

  implementation("androidx.javascriptengine:javascriptengine:1.0.0")

  implementation("androidx.media3:media3-exoplayer:1.4.1")
  implementation("androidx.media3:media3-ui:1.4.1")

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
