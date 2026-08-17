import groovy.json.JsonSlurper

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.serialization")
  // @Parcelize on the TV back-stack Screen types so navigation survives
  // activity recreation.
  id("org.jetbrains.kotlin.plugin.parcelize")
  id("androidx.baselineprofile")
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
  .ifBlank { "Nebula" }

/*
 * workflow_dispatch builds run from the branch commit, before the release tag exists, so Git does
 * not expose the prerelease suffix to Gradle. GitHub does expose the validated dispatch inputs in
 * its event file. Embedding that suffix in the shipped package version is what lets an installed
 * beta distinguish itself from the stable release built from the same numeric source version.
 */
val githubPrereleaseVersionSuffix = run {
  if (
    System.getenv("GITHUB_ACTIONS") != "true" ||
    System.getenv("GITHUB_EVENT_NAME") != "workflow_dispatch"
  ) {
    return@run ""
  }
  val eventPath = System.getenv("GITHUB_EVENT_PATH")?.takeIf { it.isNotBlank() }
    ?: return@run ""
  val inputs = runCatching {
    val event = JsonSlurper().parse(file(eventPath)) as? Map<*, *>
    event?.get("inputs") as? Map<*, *>
  }.getOrNull() ?: return@run ""
  if (inputs["prerelease"]?.toString() != "true") return@run ""
  val suffix = inputs["tag_suffix"]?.toString()?.trim().orEmpty()
  if (!suffix.matches(Regex("[0-9A-Za-z]+([.-][0-9A-Za-z]+)*")) || suffix.length > 64) {
    throw GradleException("GitHub prerelease tag_suffix is missing or invalid")
  }
  "-$suffix"
}

val repositoryRoot = rootProject.layout.projectDirectory.dir("../..")
val generatedLegalAssets = layout.buildDirectory.dir("generated/legal-assets")
val syncLegalAssets = tasks.register<Sync>("syncLegalAssets") {
  from(repositoryRoot.file("LICENSE")) {
    into("licenses")
    rename { "GPL-3.0-or-later.txt" }
  }
  from(repositoryRoot.file("THIRD_PARTY_NOTICES.md")) {
    into("licenses")
  }
  from(repositoryRoot.file("apps/android-tv-host/licenses/Outfit-OFL.txt")) {
    into("licenses")
  }
  into(generatedLegalAssets)
}

android {
  namespace = "com.stremioshell.host"
  compileSdk = 34
  // AGP creates one instrumented-test target per configuration. Debug remains the local/PR
  // default; the release workflow opts into the minified emulator target with a Gradle property.
  testBuildType = providers.gradleProperty("nebulaInstrumentationBuildType")
    .getOrElse("debug")

  defaultConfig {
    // Keep the historical .tv application id so self-updates keep installing
    // over builds produced when this was a flavor suffix.
    applicationId = "com.stremioshell.host.tv"
    minSdk = 26
    targetSdk = 34
    versionCode = 18
    versionName = "0.6.2"
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
      // Keep Android's accented and RTL pseudolocales available on development builds so clipped
      // copy and direction mistakes can be reproduced without maintaining test translations.
      isPseudoLocalesEnabled = true
      // Android's API 26/34 TV system images are x86, while local generic
      // emulators are commonly x86_64. libmpv 0.4.1 ships both, so debug keeps
      // both emulator ABIs on top of the ABIs a physical device can sideload.
      // Debug is never shipped, so its size does not matter the way release's does.
      ndk {
        abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
      }
      manifestPlaceholders["usesCleartextTraffic"] = "true"
    }
    release {
      versionNameSuffix = githubPrereleaseVersionSuffix
      // Both ARM ABIs, neither x86. Dropping armeabi-v7a was tried and reverted:
      // the Google TV Streamer itself (kirkwood, this app's primary target)
      // reports ro.product.cpu.abilist=armeabi-v7a,armeabi - a 32-bit userspace -
      // and an arm64-only APK fails there with INSTALL_FAILED_NO_MATCHING_ABIS.
      // The arm64 slice stays for the boxes that do run 64-bit. Single universal
      // APK on purpose - the release workflow picks one file out of
      // outputs/apk/release and the in-app updater matches a single "-tv-" named
      // asset, both of which ABI splits would break.
      ndk {
        abiFilters += listOf("arm64-v8a", "armeabi-v7a")
      }
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
      if (ssHasSigning) {
        signingConfig = signingConfigs.getByName("release")
      }
      manifestPlaceholders["usesCleartextTraffic"] = "false"
    }
    create("emulatorRelease") {
      initWith(getByName("release"))
      // GitHub's Android TV AVDs in this workflow are x86. Keep every release behavior that can be
      // exercised there, including R8, resource shrinking, non-debuggable code, and the production
      // cleartext policy. ABI, certificate, and the narrow cross-APK test keep rules below differ
      // from the shipping ARM APK.
      ndk {
        abiFilters.clear()
        abiFilters += listOf("x86", "x86_64")
      }
      signingConfig = signingConfigs.getByName("debug")
      matchingFallbacks += listOf("release")
      proguardFiles("proguard-emulator-release-rules.pro")
      testProguardFiles("proguard-emulator-test-rules.pro")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions {
    jvmTarget = "17"
    // AGP applies the Compose compiler through composeOptions, which exposes no
    // stability-config property; the plugin only reads the file off its own -P
    // argument. The path has to be absolute because the compiler resolves it
    // against the daemon's working directory, not the project directory.
    freeCompilerArgs += listOf(
      "-P",
      "plugin:androidx.compose.compiler.plugins.kotlin:stabilityConfigurationPath=" +
        project.layout.projectDirectory.file("stability_config.conf").asFile.absolutePath
    )

    // Opt-in: the reports are rewritten by every compilation and are only of use while someone
    // is reading them, so they stay off the ordinary build.
    //
    //   ./gradlew :app:assembleRelease -PcomposeMetrics
    //
    // leaves app-module.json (skippable/restartable composable counts) and *-composables.txt
    // (the unstable parameter that made each one recompose) under app/build/compose-metrics.
    // Any variant produces them - they come out of the Kotlin compilation, not out of R8 - but
    // release is the variant the shipped app is built from. Toggling the property changes the
    // compile task's inputs, so the first build after adding or dropping it is a full one.
    //
    // Absolute paths for the same reason as the stability config above - the compiler resolves
    // them against the daemon's working directory.
    if (project.hasProperty("composeMetrics")) {
      val composeMetricsDir =
        project.layout.buildDirectory.dir("compose-metrics").get().asFile.absolutePath
      freeCompilerArgs += listOf(
        "-P",
        "plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=$composeMetricsDir",
        "-P",
        "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=$composeMetricsDir",
      )
    }
  }

  buildFeatures {
    buildConfig = true
    compose = true
  }

  composeOptions {
    kotlinCompilerExtensionVersion = "1.5.14"
  }

  lint {
    // Existing reviewed warnings live in source control. New warning classes
    // fail CI instead of silently growing the baseline.
    baseline = file("lint-baseline.xml")
    warningsAsErrors = true
    checkReleaseBuilds = true
    // Dependency freshness is enforced by Dependabot and lockfiles. Lint's
    // online "latest" result changes without a source change and is therefore
    // unsuitable as a deterministic build failure.
    disable += "GradleDependency"
  }

  testOptions {
    animationsDisabled = true
  }

  sourceSets.getByName("main").assets.srcDir(generatedLegalAssets)
}

tasks.named("preBuild").configure { dependsOn(syncLegalAssets) }

kotlin {
  jvmToolchain(17)
}

baselineProfile {
  // Profile generation is an explicit, device-evidenced release procedure.
  // A normal assemble must never silently replace the committed artifact.
  automaticGenerationDuringBuild = false
  // The plugin requires either source output or generation during every release build. Source
  // output keeps ordinary/CI builds device-independent; the regeneration wrapper immediately
  // moves its generated candidate out of src and refuses to run over an existing candidate.
  saveInSrc = true
}

dependencies {
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
  implementation("androidx.work:work-runtime-ktx:2.9.1")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

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
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
  implementation("dev.jdtech.mpv:libmpv:0.4.1")

  // Phone-pairing config entry (QR + tiny on-device web form)
  implementation("com.google.zxing:core:3.5.3")
  implementation("org.nanohttpd:nanohttpd:2.3.1")

  // Installs the committed baseline profile (src/main/baseline-prof.txt) at
  // first run so the Compose UI paths are AOT-compiled. See docs for regen.
  implementation("androidx.profileinstaller:profileinstaller:1.3.1")
  baselineProfile(project(":baselineprofile"))

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.json:json:20240303")

  androidTestImplementation("androidx.test:core-ktx:1.6.1")
  androidTestImplementation("androidx.test.ext:junit:1.2.1")
  androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
  androidTestImplementation("androidx.test:runner:1.6.2")
  androidTestImplementation("androidx.test:rules:1.6.1")
  androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
