plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.brix.moblink"
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
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    // api: MoblinkServer extends WebSocketServer, a public supertype consumers
    // (e.g. :streaming) touch directly (server.start()/server.stop()).
    api(libs.java.websocket)
    testImplementation(libs.junit)
}
