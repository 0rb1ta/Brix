plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {

    // android.util.Log зовётся из SettingsStore.load() на пути восстановления
    // из .bak — без этого юнит-тест именно этого пути падал бы на «not mocked».
    // Тот же флаг стоит в :streaming.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    namespace = "app.brix.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
