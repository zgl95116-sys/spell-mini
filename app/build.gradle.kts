import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Secrets live in the git-ignored local.properties and are only baked into DEBUG builds.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secret(name: String): String = (localProps.getProperty(name) ?: "").trim()

android {
    namespace = "com.logan.spellmini"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.logan.spellmini"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "0.6.1"
        // Comma-separated packages whose notifications start switched off (the per-app switch turns them back on).
        // Meant for other assistant apps on the same phone; which ones is local knowledge, so it lives in local.properties.
        buildConfigField("String", "QUIET_PACKAGES", "\"${secret("QUIET_PACKAGES")}\"")
    }

    buildTypes {
        debug {
            buildConfigField("String", "OPENROUTER_API_KEY", "\"${secret("OPENROUTER_API_KEY")}\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "OPENROUTER_API_KEY", "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.10" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3") { version { strictly("1.2.0") } }
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    // HTTP + SSE streaming to OpenRouter.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Remote cover images on feed cards.
    implementation("io.coil-kt:coil-compose:2.5.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
}
