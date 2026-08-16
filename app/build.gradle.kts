plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val libraryVersionName = providers.gradleProperty("VERSION_NAME").orElse("1.0.0")
val libraryVersionCode = providers.gradleProperty("VERSION_CODE").orElse("1000000").map(String::toInt)
val catalogUrl = providers.gradleProperty("LIBRARY_CATALOG_URL").orElse("")
val catalogRepository = providers.gradleProperty("LIBRARY_CATALOG_REPOSITORY").orElse("garfbargle/library")

val signingStoreFile = providers.environmentVariable("LIBRARY_SIGNING_STORE_FILE")
val signingStorePassword = providers.environmentVariable("LIBRARY_SIGNING_STORE_PASSWORD")
val signingKeyAlias = providers.environmentVariable("LIBRARY_SIGNING_KEY_ALIAS")
val signingKeyPassword = providers.environmentVariable("LIBRARY_SIGNING_KEY_PASSWORD")
val hasReleaseSigning = listOf(signingStoreFile, signingStorePassword, signingKeyAlias, signingKeyPassword).all { it.isPresent }

android {
    namespace = "com.garfbargle.library"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.garfbargle.library"
        minSdk = 28
        targetSdk = 37
        versionCode = libraryVersionCode.get()
        versionName = libraryVersionName.get()
        buildConfigField("String", "CATALOG_URL", "\"${catalogUrl.get()}\"")
        buildConfigField("String", "CATALOG_REPOSITORY", "\"${catalogRepository.get()}\"")
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
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
