plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.brix.streaming"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":bonding"))
    implementation(project(":moblink"))
    api(libs.rootencoder.library)
    api(libs.rootencoder.encoder)
    api(libs.kotlinx.coroutines.android)
    implementation(libs.glide)
    implementation(libs.okhttp)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

android {
    lint {
        // Third-party RootEncoder dependency ships a bouncycastle jar with an
        // empty-checkServerTrusted TrustManager — not our code, SRT traffic
        // is unencrypted by design anyway.
        disable += "TrustAllX509TrustManager"
    }
}
