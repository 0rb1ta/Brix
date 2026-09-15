import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Git hash в versionName: видно, какая сборка стоит на устройстве
val gitHash: String =
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { "nogit" }

// Подпись релиза. Файл с паролями лежит ВНЕ репозитория и никогда не
// коммитится: путь задаётся переменной окружения BRIX_KEYSTORE_PROPERTIES,
// по умолчанию ищется keystore.properties рядом с проектом.
//
// Без него релизная сборка подписывается отладочным ключом — это сделано
// намеренно, чтобы сборка у постороннего человека и в CI проходила без
// секретов. Такой APK годен для проверки, но не для распространения.
val keystoreProperties =
    Properties().apply {
        val f =
            rootProject.file(
                System.getenv("BRIX_KEYSTORE_PROPERTIES") ?: "keystore.properties",
            )
        if (f.exists()) f.inputStream().use { load(it) }
    }

android {
    namespace = "app.brix"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.brix"
        minSdk = 29
        targetSdk = 36
        // Версия человеческая, без хэша: её видят люди, каталог приложений и
        // страница релиза. Хэш коммита добавляется только к отладочным
        // сборкам (см. buildTypes ниже) — там он и нужен, чтобы по HUD было
        // видно, что именно стоит на телефоне.
        //
        // Слово beta в самой версии, а не только в описании: оно видно и в
        // каталоге приложений, и в HUD на экране стримера. Пользователь должен
        // понимать, что поставил, не читая страницу проекта. Список того, что
        // не проверялось, — в README.
        //
        // versionCode обязан строго расти от релиза к релизу, иначе обновление
        // не доедет до тех, кто уже поставил.
        versionCode = 7
        versionName = "0.1.6-beta"
    }

    // Restricts which locale-qualified resources actually get packaged into
    // the APK — without this, context.assets.locales() (used by
    // LanguageScreen to build the language picker) returns every locale any
    // dependency ships (AndroidX alone carries ~80), not just the ones BRIX
    // itself has a values-<lang>/strings.xml for. Adding a language still
    // needs zero UI/logic code (LanguageScreen stays fully dynamic) — a
    // translator just adds their values-<lang>/ directory AND this one tag.
    androidResources {
        localeFilters += listOf("en", "ru")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["storeAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            // Хэш коммита только в отладочных сборках: он виден в HUD, и по
            // нему сразу понятно, что именно стоит на телефоне после полевого
            // выезда. В релизе такого хвоста быть не должно — там версия
            // человеческая.
            versionNameSuffix = "-$gitHash"
        }
        release {
            // Ссылку на коммит внутрь APK не кладём. AGP пишет в
            // META-INF/version-control-info.textproto хэш HEAD того дерева, из
            // которого собирали. У нас деревьев два — приватное и публичное, —
            // и хэши там разные по определению, поэтому воспроизводимая сборка
            // F-Droid на этом файле разошлась в первый же прогон (15.09).
            // Никакой пользы этот файл нам не даёт: версию и хэш отладочной
            // сборки мы и так показываем в HUD.
            vcsInfo { include = false }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ui"))
    implementation(project(":streaming"))
    implementation(project(":bonding"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
