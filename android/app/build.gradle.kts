import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Optional release signing: create android/keystore.properties with
// storeFile, storePassword, keyAlias, keyPassword (never commit it).
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "io.github.nomskis.earshot"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.nomskis.earshot"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Pre-fill the server address, e.g. ./gradlew assembleDebug -Pearshot.serverUrl=https://calls.example.com
        val serverUrl = providers.gradleProperty("earshot.serverUrl").getOrElse("")
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$serverUrl\"")
    }

    signingConfigs {
        // A shared debug key so APKs built on CI and on any laptop can update
        // each other. It protects nothing; release builds use their own key.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    testOptions {
        // android.util.Log and friends return defaults in JVM unit tests.
        unitTests.isReturnDefaultValues = true
        // Robolectric UI smoke tests render the real screens on the JVM.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Shared protocol examples, also checked by the server tests.
            it.systemProperty("earshot.fixtures", rootProject.file("../protocol/fixtures").absolutePath)
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.webrtc)
    // Optional "Turbo": privileged Bluetooth/audio controls via Shizuku (MIT), no root.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
