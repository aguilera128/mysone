plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

// Vacío = Sentry apagado. Nunca escribir el DSN en el repositorio.
val sentryDsn: String = providers.gradleProperty("mysone.sentryDsn")
    .orElse(providers.environmentVariable("SENTRY_DSN"))
    .getOrElse("")

android {
    namespace = "dev.javieraguilera.mysone"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.javieraguilera.mysone"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")
        // http:// al propio equipo (tools/mock-llm.mjs desde el emulador). Lo permite el networkSecurityConfig de debug y qa.
        buildConfigField("boolean", "ALLOW_LOCAL_HTTP", "false")
    }
    buildTypes {
        // Paquete aparte: con la misma firma, la debug se instalaría encima de la release del móvil y
        // connectedDebugAndroidTest la desinstalaría al acabar, con todos sus datos.
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "ALLOW_LOCAL_HTTP", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug") // uso personal por sideload
        }
        // La release (R8 y shrink) contra el servidor falso en el emulador; se instala junto a las otras.
        create("qa") {
            initWith(getByName("release"))
            applicationIdSuffix = ".qa"
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "ALLOW_LOCAL_HTTP", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Idioma por app (Ajustes de Android › Apps › Mysone › Idioma): genera la lista desde values-*.
    androidResources {
        generateLocaleConfig = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // MainActivity fuerza es-ES (selectores de Material 3): con un AAB partido por idioma, en un móvil en
    // otro idioma no llegarían las cadenas en español de las librerías.
    bundle {
        language { enableSplit = false }
    }
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.RequiresOptIn")
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.room3.runtime)
    ksp(libs.androidx.room3.compiler)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.sentry.android.core)
    implementation(libs.sherpa.onnx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.okhttp.bom))
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room3.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
