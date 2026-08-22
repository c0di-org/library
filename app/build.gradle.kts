plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val libraryVersionName = providers.gradleProperty("LIBRARY_VERSION").orElse("1.0.0")
val versionMatch = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(libraryVersionName.get())
    ?: error("LIBRARY_VERSION must be major.minor.patch")
val (versionMajor, versionMinor, versionPatch) = versionMatch.destructured
require(versionMinor.toInt() <= 999 && versionPatch.toInt() <= 999) {
    "LIBRARY_VERSION minor and patch components must be <= 999"
}
val libraryVersionCode = versionMajor.toInt() * 1_000_000 + versionMinor.toInt() * 1_000 + versionPatch.toInt()
val catalogUrl = providers.gradleProperty("LIBRARY_CATALOG_URL").orElse("")
val catalogRepository = providers.gradleProperty("LIBRARY_CATALOG_REPOSITORY").orElse("c0di-org/library")
val githubAppClientId = providers.environmentVariable("LIBRARY_CATALOG_APP_CLIENT_ID")
    .orElse(providers.gradleProperty("LIBRARY_CATALOG_APP_CLIENT_ID"))
    .orElse(providers.environmentVariable("LIBRARY_GITHUB_APP_CLIENT_ID"))
    .orElse(providers.gradleProperty("LIBRARY_GITHUB_APP_CLIENT_ID"))
    .orElse("")

val signingStoreFile = providers.environmentVariable("LIBRARY_SIGNING_STORE_FILE")
val signingStorePassword = providers.environmentVariable("LIBRARY_SIGNING_STORE_PASSWORD")
val signingKeyAlias = providers.environmentVariable("LIBRARY_SIGNING_KEY_ALIAS")
val signingKeyPassword = providers.environmentVariable("LIBRARY_SIGNING_KEY_PASSWORD")
val hasReleaseSigning = listOf(signingStoreFile, signingStorePassword, signingKeyAlias, signingKeyPassword).all { it.isPresent }

android {
    namespace = "com.garfbargle.library"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.garfbargle.library"
        minSdk = 28
        targetSdk = 36
        versionCode = libraryVersionCode
        versionName = libraryVersionName.get()
        buildConfigField("String", "CATALOG_URL", "\"${catalogUrl.get()}\"")
        buildConfigField("String", "CATALOG_REPOSITORY", "\"${catalogRepository.get()}\"")
        buildConfigField("String", "GITHUB_APP_CLIENT_ID", "\"${githubAppClientId.get()}\"")
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingStoreFile.get())
                storePassword = signingStorePassword.get()
                keyAlias = signingKeyAlias.get()
                keyPassword = signingKeyPassword.get()
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".dev"; versionNameSuffix = "-dev" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    lint { abortOnError = true; checkReleaseBuilds = true; warningsAsErrors = false }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)

    // API 36 is the newest stable SDK available on hosted Android runners today.
    // Keep the AndroidX base layer on the last generation compiled against API 36;
    // newer Core releases already require the unpublished API 37 platform.
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    // Material 3 versions advance independently of the main Compose runtime.
    // Pin the current stable release so navigation APIs stay on the stable surface.
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
