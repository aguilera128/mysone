# 04 — Toolchain Android: versiones estables y compatibles (15-oct-2026)

App Android nueva en Kotlin + Jetpack Compose, sideload, uso personal. Todas las versiones de este
documento se han sacado **hoy** de `maven-metadata.xml` de Google Maven / Maven Central / services.gradle.org
(no de memoria), con fecha de publicación (cabecera `Last-Modified` del POM) cuando importa.

**Y se han comprobado compilando**: proyecto mínimo en
`C:/Users/aguil/AppData/Local/Temp/claude/C--Users-aguil-Dev-mysone/70657f54-8e94-4902-a40b-c79f75e4892e/scratchpad/tcheck`
(Compose + Navigation 3 + Room 3 con FTS4 y FTS5 + KSP + DataStore + WorkManager + OkHttp 5 + Sentry +
plugin Sentry + helper Keystore). Resultado:

| Build | Resultado |
|---|---|
| `assembleDebug testDebugUnitTest` (AGP 9.4.1, Gradle 9.8.1, JBR 21.0.10, configuration cache ON) | BUILD SUCCESSFUL (1 m 59 s en frío) |
| `assembleRelease` con R8 (`isMinifyEnabled`/`isShrinkResources`) | BUILD SUCCESSFUL, APK 7,4 MB (4 ABIs; `classes.dex` 2,2 MB) |
| Mismo proyecto con AGP **9.1.1** (fallback para el Android Studio instalado) | BUILD SUCCESSFUL (debug + release) |
| `./gradlew buildEnvironment` | KGP resuelto **2.4.21** (AGP pide 2.2.10 y Gradle lo sube), KSP 2.2.10-2.0.2 → **2.3.12** |
| Room 3 KSP | genera `AppDb_Impl.kt`, `SegmentDao_Impl.kt` y `schemas/.../1.json` con `USING FTS4(... content=Segment)` y `USING FTS5(body, tokenize=unicode61)` |

Única advertencia: `Configuration.setVisible(boolean) ... deprecated ... removed in Gradle 11`, emitida
por el propio AGP (`BasePlugin.createAndroidJdkImageConfiguration`) con Gradle 9.8.1. Inofensiva; no
se arregla desde el proyecto. Con AGP 9.1.1 sale otra más (`Project object as a dependency notation`).

APK debug: 41,9 MB (dex 34,6 MB sin minificar + 1,2 MB de nativos por ABI). Release con R8: 7,4 MB
con 4 ABIs; con `abiFilters += "arm64-v8a"` bajaría unos 3,6 MB (cifra estimada a partir del tamaño de
`lib/*`, no medida).

---

## 1. Decisiones (lo que hay que copiar)

| Pieza | Versión | Por qué |
|---|---|---|
| **Android Gradle Plugin** | **9.4.1** (18-sep-2026) | Última estable (9.5.0 está en alpha10). Fallback verificado: 9.1.1 |
| **Gradle** | **9.8.1** (7-oct-2026) | Última estable. AGP 9.4 exige ≥ 9.6.0 |
| **JDK** | JBR 21.0.10 de Android Studio | AGP 9.4 exige JDK ≥ 17. Bytecode a Java 17 |
| **Kotlin** | **2.4.21** (8-oct-2026, bugfix de 2.4.20) | Última estable (2.5.0-Beta1 no) |
| Compose compiler plugin | `org.jetbrains.kotlin.plugin.compose` **2.4.21** | Va siempre con la versión de Kotlin |
| **KSP** | **2.3.12** (9-sep-2026) | KSP2, versión desacoplada de Kotlin desde 2.3.0; funciona con 2.4.21 (verificado) |
| **Compose BOM** | **2026.09.00** (9-sep-2026) | → Compose UI/foundation/runtime **1.12.1**, **material3 1.4.0** |
| activity-compose | 1.13.0 | |
| core(-ktx) | 1.19.1 (23-sep-2026) | core-ktx ya es un artefacto vacío |
| lifecycle (runtime-compose, viewmodel-compose, service, viewmodel-navigation3) | 2.11.0 (17-jun-2026) | |
| **Navigation** | **navigation3 1.2.0** (23-sep-2026) | Estable desde 1.0.0 (19-nov-2025). Alternativa clásica: navigation-compose 2.10.2 |
| **Room** | **Room 3 — `androidx.room3` 3.0.3** (9-sep-2026) | Estable desde 1-jul-2026; KSP-only, Kotlin-only; FTS3/FTS4/FTS5 |
| sqlite-bundled | 2.7.1 | SQLite propio con FTS5 (la del sistema no lo garantiza) |
| WorkManager | 2.12.0 (23-sep-2026) | `work-runtime` ya incluye `CoroutineWorker` |
| DataStore | 1.2.1 (11-mar-2026) | 1.3.0-rc01 (12-oct-2026) trae `datastore-tink`, aún no estable |
| kotlinx-coroutines | 1.11.0 | |
| kotlinx-serialization-json | 1.11.0 | 1.12.0-RC no estable |
| **OkHttp** | **5.5.0** (16-ago-2026) | 4.x está congelado en 4.12.0 (oct-2023) |
| Sentry Android SDK | `io.sentry:sentry-android-core` 8.61.0 (14-oct-2026) | Sin Session Replay (ver §8) |
| Plugin Sentry | `io.sentry.android.gradle` 6.23.0 (28-sep-2026) | **Opcional**: solo aporta subida del mapping de R8 |
| API key cifrada | Android Keystore AES-256-GCM + DataStore Preferences | `security-crypto` está deprecado (§9) |
| **compileSdk** | **37** | Obligatorio: core 1.19.1, Compose 1.12.1 y navigation3 1.2.0 llevan `minCompileSdk=37` |
| **targetSdk** | **37** | Android 17 estable (16-jun-2026). Ver cambios de comportamiento en §5 |
| **minSdk** | **31** (Android 12) | Ver §5. Suelo de las librerías: 24 |

### Acciones en la máquina antes de compilar

1. **Instalar la plataforma 37**: ya hecho en esta investigación (`platforms;android-37.0`, 67 MB, misma
   licencia `android-sdk-license` que ya estaba aceptada, sin nuevos términos). Comando por si hace falta en
   otra máquina: `sdkmanager "platforms;android-37.0"`. Build-tools 36.0.0 (instalado) es el que usa AGP 9.4
   por defecto; no hace falta 37.0.0.
2. **cmdline-tools** instalado es 20.0 y avisa: *"This version only understands SDK XML versions up to 3
   but an SDK XML file of version 4 was encountered"*. Funciona, pero conviene actualizar a
   `cmdline-tools;latest` (= 23.0).
3. **Android Studio instalado = Panda 3 (2025.3.3, build `AI-253.31033.145.2533.15113396`)**, que según la
   tabla oficial solo soporta **AGP 7.0–9.1**. Para abrir el proyecto con AGP 9.4.1 en el IDE hay que
   actualizar a **Quail 4 (2026.1.4) o Rabbit 1 (2026.2.1)** (ambos soportan hasta 9.4). Desde línea de
   comandos (`gradlew`) no importa: compila igual. Si no se quiere actualizar el IDE: `agp = "9.1.1"` en el
   catálogo y nada más (verificado). Además, la API 37.0 requiere "Panda 3 Patch 1"; no he podido confirmar
   si el build instalado es el Patch 1.
4. **Gradle 9.8.1**: no está en caché (solo 8.14.3 y 8.2.1). El wrapper lo descarga (~140 MB) la primera vez.
   El wrapper ya generado está en `scratchpad/tcheck/gradle/wrapper/` (+ `gradlew`, `gradlew.bat`), con
   `distributionSha256Sum=dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f` y
   `gradle-wrapper.jar` con SHA-256 `3b8a2577…68e98`, que coincide con el publicado en
   https://services.gradle.org/versions/current.
5. **JAVA_HOME**: lanzar con `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`. No poner
   `org.gradle.java.home` en `gradle.properties` (ruta de máquina en el repo).

---

## 2. AGP 9: Kotlin integrado ("built-in Kotlin") — cómo se configura

Fuente: https://developer.android.com/build/migrate-to-built-in-kotlin y
https://developer.android.com/build/releases/agp-9-0-0-release-notes

- AGP 9.0+ compila Kotlin por sí mismo (`android.builtInKotlin=true` por defecto). **No se aplica
  `org.jetbrains.kotlin.android`**; si se aplica, falla con
  *"Cannot add extension with name 'kotlin', as there is an extension already registered"*.
- **`kotlin-kapt` es incompatible** → usar KSP (Room 3 es KSP-only de todas formas).
- AGP 9.4.1 sigue declarando dependencia runtime de **KGP 2.2.10** y de KSP `2.2.10-2.0.2` (comprobado
  en el POM `gradle-9.4.1.pom`). Para usar Kotlin 2.4.21 basta con que un plugin de Kotlin 2.4.21 esté en el
  classpath raíz: el `compose-compiler-gradle-plugin:2.4.21` tiene dependencia `compile` de
  `kotlin-gradle-plugin:2.4.21`, así que declarar `org.jetbrains.kotlin.plugin.compose` con `apply false` en
  el `build.gradle.kts` raíz sube KGP a 2.4.21 (verificado con `buildEnvironment`:
  `kotlin-gradle-plugin:2.2.10 -> 2.4.21`). La forma "oficial" que documenta Google es un
  `buildscript { dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:KGP_VERSION") } }`;
  no hace falta con el bloque `plugins` de abajo. Es el mismo patrón que usa Now in Android (AGP 9.3.2).
- `kotlinOptions {}` desaparece → `kotlin { compilerOptions { … } }` a nivel de módulo.
  `jvmTarget` no hace falta: sale de `android.compileOptions.targetCompatibility`.
- Fuentes Kotlin extra solo vía `android.sourceSets.named("main") { kotlin.directories += … }`.
- Opt-out temporal (`android.builtInKotlin=false` + `android.newDsl=false`) desaparece en AGP 10.
- Valores por defecto que cambian en AGP 9 y afectan a este proyecto:
  `android.sdk.defaultTargetSdkToCompileSdkIfUnset=true` (se fija `targetSdk` explícito igualmente),
  `android.r8.strictFullModeForKeepRules=true` (`-keep class A` ya no conserva el constructor: escribir
  `-keep class A { <init>(); }`), `android.r8.proguardAndroidTxt.disallowed=true` (usar
  `proguard-android-optimize.txt`, no `proguard-android.txt`), `android.useAndroidx=true`,
  `android.uniquePackageNames=true`, `buildConfig`/`resValues` desactivados por defecto.
- La variante antigua del Variant API (`applicationVariants`) ya no existe: `androidComponents.onVariants`.

### Compatibilidad oficial cruzada (y dónde nos salimos)

| | Requisito publicado | Fuente |
|---|---|---|
| AGP 9.4 | Gradle ≥ 9.6.0, JDK ≥ 17, build-tools ≥ 36.0.0, API máx. 37 | https://developer.android.com/build/releases/agp-9-4-0-release-notes |
| AGP 9.3 | Gradle ≥ 9.5.0, API máx. 37 | https://developer.android.com/build/releases/agp-9-3-0-release-notes |
| AGP 9.1.1 | Gradle ≥ 9.3.1, KGP 2.2.10, "supports API level 37.0 and below" | https://developer.android.com/build/releases/agp-9-1-0-release-notes |
| API 37.0 | Studio ≥ Panda 3 Patch 1, AGP ≥ 9.1.1 | https://developer.android.com/build/releases/about-agp |
| KGP 2.4.20–2.4.21 | Gradle 7.6.3–**9.7.0**, AGP 8.5.2–**9.3.1** (rango "totalmente soportado") | https://kotlinlang.org/docs/gradle-configure-project.html |
| KSP 2.3.12 | AGP mínimo 8.12.0 | https://github.com/google/ksp/releases/tag/2.3.12 |

Con AGP 9.4.1 + Gradle 9.8.1 + Kotlin 2.4.21 nos salimos del rango "totalmente soportado" que publica
JetBrains para KGP 2.4.21 (AGP ≤ 9.3.1, Gradle ≤ 9.7.0). Esa tabla dice que se pueden usar versiones más
nuevas "but … you might encounter deprecation warnings or some new features might not work". La build
real compila sin errores (debug, release con R8 y configuration cache), así que lo doy por bueno. Si se
quiere estar **dentro de todas las tablas oficiales**, la combinación es AGP **9.3.3** + Gradle **9.7.1** +
Kotlin 2.4.21 (no la he compilado; 9.3.3 es un parche posterior al 9.3.1 de la tabla).

---

## 3. `gradle/libs.versions.toml` (verificado compilando, salvo lo marcado)

```toml
[versions]
agp = "9.4.1"              # fallback para Android Studio Panda 3: "9.1.1" (verificado)
kotlin = "2.4.21"
ksp = "2.3.12"
composeBom = "2026.09.00"  # Compose 1.12.1, material3 1.4.0
activityCompose = "1.13.0"
coreKtx = "1.19.1"         # core-ktx 1.19 es un artefacto vacío; androidx.core:core vale igual
lifecycle = "2.11.0"
navigation3 = "1.2.0"
room3 = "3.0.3"
sqlite = "2.7.1"
work = "2.12.0"
datastore = "1.2.1"
coroutines = "1.11.0"
serialization = "1.11.0"
okhttp = "5.5.0"
sentry = "8.61.0"
sentryPlugin = "6.23.0"
junit = "4.13.2"
androidxJunit = "1.3.0"
espresso = "3.7.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }
androidx-compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-navigation3 = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-navigation3", version.ref = "lifecycle" }
androidx-lifecycle-service = { group = "androidx.lifecycle", name = "lifecycle-service", version.ref = "lifecycle" }
androidx-navigation3-runtime = { group = "androidx.navigation3", name = "navigation3-runtime", version.ref = "navigation3" }
androidx-navigation3-ui = { group = "androidx.navigation3", name = "navigation3-ui", version.ref = "navigation3" }
androidx-room3-runtime = { group = "androidx.room3", name = "room3-runtime", version.ref = "room3" }
androidx-room3-compiler = { group = "androidx.room3", name = "room3-compiler", version.ref = "room3" }
androidx-sqlite-bundled = { group = "androidx.sqlite", name = "sqlite-bundled", version.ref = "sqlite" }
androidx-work-runtime = { group = "androidx.work", name = "work-runtime", version.ref = "work" }
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }
okhttp-bom = { group = "com.squareup.okhttp3", name = "okhttp-bom", version.ref = "okhttp" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp" }
okhttp-sse = { group = "com.squareup.okhttp3", name = "okhttp-sse" }
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver3" }   # existe 5.5.0; no usado en la build de prueba
sentry-android-core = { group = "io.sentry", name = "sentry-android-core", version.ref = "sentry" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-junit = { group = "androidx.test.ext", name = "junit", version.ref = "androidxJunit" }
androidx-espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espresso" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
room3 = { id = "androidx.room3", version.ref = "room3" }
sentry = { id = "io.sentry.android.gradle", version.ref = "sentryPlugin" }
```

No hay entrada `kotlin-android` ni `kotlin-kapt` (AGP 9). Los tests instrumentados (`androidTest`) se
resolvieron pero no se ejecutaron.

Lo que **no** he metido a propósito (lo resuelve el SDK o lo que ya hay):
- `kotlinx-datetime`: `java.time` está disponible desde API 26 (minSdk 31).
- `material-icons-extended` (1.7.8, sin versión nueva desde 12-feb-2025, ~varios MB): mejor iconos sueltos como
  vector drawables.
- Hilt/Koin: para una app de un módulo basta con un `AppContainer` manual.
- Retrofit: OkHttp + kotlinx-serialization directamente.
- `work-runtime-ktx`: `CoroutineWorker` y las extensiones ya están en `work-runtime` 2.12.0 (comprobado en
  el `classes.jar`).

## 4. Bloques de Gradle (verificados)

`settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "<nombre>"
include(":app")
```

`build.gradle.kts` (raíz) — todos los plugins en el classpath raíz para que KGP se resuelva una sola vez a 2.4.21:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room3) apply false
    alias(libs.plugins.sentry) apply false   // quitar si no se usa el plugin (ver §8)
}
```

`app/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
    alias(libs.plugins.sentry)
}

android {
    namespace = "<paquete>"
    compileSdk = 37

    defaultConfig {
        applicationId = "<paquete>"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // ndk { abiFilters += "arm64-v8a" }   // APK más pequeño si solo es para el móvil del dueño
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug") // sideload personal; cambiar si se distribuye
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.RequiresOptIn")
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")   // obligatorio con el plugin; commitear schemas/
}

sentry {
    autoInstallation { enabled = false }       // las dependencias las fijamos nosotros
    tracingInstrumentation { enabled = false } // no instrumentar Room/OkHttp/IO
    autoUploadProguardMapping = false          // true solo con SENTRY_AUTH_TOKEN en el entorno
    includeSourceContext = false
    telemetry = false                          // el plugin manda telemetría de build por defecto
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
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
    implementation(libs.okhttp.sse)
    implementation(libs.sentry.android.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
```

`gradle.properties`

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.configuration-cache=true
org.gradle.caching=true
kotlin.code.style=official
```

(No hace falta `android.useAndroidX=true` ni `android.nonTransitiveRClass`: ya son el valor por defecto.)
`local.properties` con `sdk.dir=C\:\\Users\\aguil\\AppData\\Local\\Android\\Sdk` (fuera del repo).
Un `sdk.dir` mal escapado da un confuso `java.io.IOException: Invalid file path` en `SdkLocator`: me pasó
en la primera build.

---

## 5. SDK: compileSdk / targetSdk / minSdk

**¿Existe API 37 estable?** Sí. El repositorio oficial del SDK (`https://dl.google.com/android/repository/repository2-4.xml`)
publica en el canal **stable** `platforms;android-37.0` (rev 2), `android-37.1` y `android-37.2` (las minor
SDK, ahora con versión mayor.menor), y `build-tools;37.0.0`. Android 17 salió estable el **16-jun-2026**
según la prensa (Android Authority, SlashGear, Gizmochina); no he encontrado el post oficial de Google con la
fecha. `compileSdk = 37` resuelve a `platforms/android-37.0` (verificado).

**compileSdk = 37 es obligatorio** con las versiones actuales. `aar-metadata.properties` de los AAR:

| Librería | minCompileSdk | minAGP | minSdk |
|---|---|---|---|
| core / core-ktx 1.19.1 | **37** | 9.1.0 | 23 |
| compose ui-android 1.12.1 (BOM 2026.09.00) | **37** | 9.1.0 | 23 |
| navigation3-ui 1.2.0 | **37** | 9.1.0 | 24 |
| activity 1.13.0 | 36 | 8.9.1 | 23 |
| material3 1.4.0 | 35 | 8.6.0 | 21 |
| work-runtime 2.12.0 | 35 | 8.6.0 | 24 |
| lifecycle 2.11.0, room3 3.0.3, room 2.8.5, datastore 1.2.1, sqlite-bundled 2.7.1, navigation 2.10.2 | 34 | 8.1.1 | 23–24 |

Si por algo hubiera que quedarse en compileSdk 36, habría que bajar a core 1.18.0, un BOM con Compose 1.11
y navigation3 1.1.x (no lo he comprobado; no lo recomiendo).

**targetSdk = 37.** Para sideload no hay obligación de Play, pero es lo recomendable y lo que AGP 9 pone por
defecto. Cambios de Android 17 que afectan a esta app
(https://developer.android.com/about/versions/17/behavior-changes-17,
https://developer.android.com/about/versions/17/behavior-changes-all,
https://developer.android.com/about/versions/17/changes/bg-audio):
- **Endurecimiento del audio en segundo plano**: reproducción, *audio focus* y volumen fallan en silencio sin
  actividad visible o FGS; con target 37 el FGS además debe tener capacidades **while-in-use (WIU)**, que se
  obtienen arrancándolo desde una acción del usuario o con la app visible. La página no dice explícitamente que
  afecte a `AudioRecord`, pero el acceso al micrófono desde FGS ya exigía WIU desde Android 11/14. Implicación:
  el servicio de grabación se arranca desde la UI; test: `adb shell cmd audio set-enable-hardening throw`.
- **`ACCESS_LOCAL_NETWORK`** (permiso runtime, grupo NEARBY_DEVICES) obligatorio con target 37 para hablar con
  la red local: afecta si se usa un LLM/Whisper en el NAS o en un PC de casa.
- **Certificate Transparency activado por defecto** y **ECH** en TLS si la librería lo soporta: sin impacto
  con APIs públicas de IA.
- **Límites de memoria por app** según la RAM (todas las apps): relevante si se carga un modelo de STT/LLM
  en memoria; se detecta con `ApplicationExitInfo` (`"MemoryLimiter:AnonSwap"`).
- Límite de 50.000 claves en Keystore por app (target 37): irrelevante (usamos 1).

**minSdk = 31 (Android 12)**, recomendado por la app, no por las librerías (su suelo es 24):
`SpeechRecognizer.createOnDeviceSpeechRecognizer` (API 31), modelo de alarmas exactas con
`SCHEDULE_EXACT_ALARM` (31+), `PendingIntent` con mutabilidad obligatoria y restricciones de arranque de FGS en
segundo plano (31+) — con 31 se eliminan todas las ramas de compatibilidad anteriores. Bajar a 29 solo si el
móvil del dueño es más viejo. (Las API concretas de STT/alarmas las valida su propio informe.)

---

## 6. Room 3 (y por qué no Room 2.8.5)

Fuente: https://developer.android.com/jetpack/androidx/releases/room3

- **Estable**: 3.0.0 el 1-jul-2026; 3.0.3 el 9-sep-2026 (arregla regresión `IllegalMonitorStateException`
  en `@Transaction`). 3.1.0-alpha01 no.
- Paquete y coordenadas nuevos: `androidx.room3:room3-runtime` / `room3-compiler`, plugin `androidx.room3`,
  bloque `room3 { schemaDirectory(...) }` (**obligatorio** con el plugin). Convive con Room 2 sin
  conflictos: WorkManager 2.12.0 sigue trayendo `androidx.room:room-runtime:2.7.0` dentro y no choca.
- **Solo KSP, solo genera Kotlin**, DAOs `suspend` o `Flow`, `SQLiteDriver` obligatorio, sin
  `SupportSQLiteDatabase`/`Cursor`, `InvalidationTracker.createFlow()` en vez de observers,
  `withWriteTransaction`/`useReaderConnection`.
- **FTS**: `room3-common-jvm-3.0.3.jar` contiene `androidx/room3/Fts3`, `Fts4`, **`Fts5`** y `FtsOptions`
  (con `Detail`, `MatchInfo`, `Order`). Verificado con KSP: `@Fts4(contentEntity = Segment::class)` genera
  `USING FTS4(text TEXT NOT NULL, content=Segment)` y `@Fts5` genera `USING FTS5(body, tokenize=unicode61)`.
- **FTS5 en el dispositivo**: `androidx.sqlite:sqlite-bundled` se compila con `-DSQLITE_ENABLE_FTS3`,
  `-DSQLITE_ENABLE_FTS4`, `-DSQLITE_ENABLE_FTS5`, `JSON1`, `RTREE`, `MATH_FUNCTIONS`
  (`sqlite/sqlite-bundled/build.gradle` en androidx-main). La SQLite del sistema no garantiza FTS5, así que:
  `Room.databaseBuilder<AppDb>(ctx, path).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO)`.
  Coste: ~1,2 MB de `libsqliteJni.so` por ABI.
- Para español: tokenizador `unicode61` con `remove_diacritics 2` ("canción" ≈ "cancion"); en Room 3 se
  pasa con `tokenizerArgs` de `@Fts4`/`@Fts5` (no lo he compilado con esos argumentos).
- Room 2.8.5 (9-sep-2026) sigue mantenido; solo tendría sentido si hiciera falta Java/kapt.
- Para búsqueda semántica (embeddings) no hay soporte vectorial en Room: se guardan como BLOB y el coseno se
  hace en Kotlin, o con una extensión SQLite (fuera del alcance de este informe).

## 7. Navigation 3 vs navigation-compose; OkHttp 5 vs 4

**Navigation 3 1.2.0** (estable; 1.0.0 el 19-nov-2025, 1.1.0 el 8-abr-2026, 1.2.0 el 23-sep-2026; exige
compileSdk 37 y minSdk 24). La pila de navegación es una lista que es tuya (`rememberNavBackStack`, claves
`@Serializable … : NavKey`, `NavDisplay` + `entryProvider { entry<T> { } }`): el ejemplo compila tal cual.
`lifecycle-viewmodel-navigation3` 2.11.0 da ViewModels por entrada. Sentry 8.60.0 añadió
`sentry-android-navigation3`. La página de release no dice que se recomiende sobre Navigation 2; navigation-compose
2.10.2 (23-sep-2026) también es válido. Elijo Nav3 porque es más simple para 4–5 pantallas.
Alternativa más barata: con una barra inferior de 4 pestañas basta un `var tab by rememberSaveable`, sin
librería.

**OkHttp 5.5.0.** OkHttp 4 se quedó en 4.12.0 (17-oct-2023). En OkHttp 5 la coordenada
`com.squareup.okhttp3:okhttp` es multiplataforma y en Android resuelve a la variante **`okhttp-android`**
(Gradle module metadata), con Okio 3.18.1. `okhttp-sse` 5.5.0 sirve para el streaming SSE de las APIs de
LLM. Importar con `platform(libs.okhttp.bom)`. Con R8 en AGP 9 no hubo avisos.

## 8. Sentry

- SDK **8.61.0** (14-oct-2026). `io.sentry:sentry-android` = `sentry-android-core` + `sentry-android-ndk` +
  `sentry-android-replay` (POM). Recomiendo **solo `sentry-android-core`**: la app transcribe todo lo que
  oye, y Session Replay capturaría pantallas con transcripciones (RGPD). Añadir `sentry-android-ndk` solo si
  hay código nativo propio (whisper.cpp / sherpa-onnx). Init: `sendDefaultPii = false`, `beforeSend` que
  quite mensajes/breadcrumbs con texto transcrito, DSN por `BuildConfig`/manifest desde variable de entorno
  `SENTRY_DSN` (AGP 9 desactiva `buildConfig` por defecto: `buildFeatures { buildConfig = true }` si se usa).
- **Plugin `io.sentry.android.gradle` 6.23.0** (28-sep-2026): menciona compatibilidad expresa con AGP 9.4.1;
  verificado con configuration cache. **Es opcional**: solo aporta la subida del mapping de R8 (stack traces
  legibles en release) y la instrumentación de bytecode. Sin `SENTRY_AUTH_TOKEN` en el entorno, dejar
  `autoUploadProguardMapping = false`. **`telemetry = false`** siempre (el plugin envía telemetría de build
  por defecto; desde 6.23.0 incluye la versión de KGP). Nunca poner el token en `build.gradle.kts`.
- Alternativa más simple: sin plugin, solo la dependencia; los stack traces de release salen ofuscados y se
  desofuscan subiendo `mapping.txt` a mano con `sentry-cli`.

## 9. API key cifrada: qué recomienda Google hoy

- `androidx.security:security-crypto` 1.1.0 (30-jul-2025) está **deprecado entero** desde 1.1.0-alpha07
  (9-abr-2025): *"Deprecated all APIs in favour of existing platform APIs and direct use of Android Keystore."*
  (https://developer.android.com/jetpack/androidx/releases/security). En el código fuente, `EncryptedSharedPreferences`
  dice `@deprecated Use android.content.SharedPreferences instead` y `EncryptedFile` `Use java.io.File instead`.
- La vía de Google con Tink es **`androidx.datastore:datastore-tink`** (`AeadSerializer`, envuelve un
  `Serializer<T>` con un `Aead` de Tink cuyo keyset protege una master key del Keystore). Pero solo existe en
  **DataStore 1.3.0-alpha07…rc01** (rc01 el 12-oct-2026): **no es estable**. Tink: `com.google.crypto.tink:tink-android` 1.23.0.
- **Recomendación (estable, sin dependencias nuevas):** clave **AES-256-GCM no exportable en Android Keystore**
  + blob `Base64(IV‖ciphertext)` en **DataStore Preferences 1.2.1**. Verificado compilando
  (`scratchpad/tcheck/app/src/main/java/com/example/tc/data/Secrets.kt`, ~60 líneas). Detalles que importan:
  - **NO** usar `setUserAuthenticationRequired` ni `setUnlockedDeviceRequired(true)`: el FGS/Worker tiene que
    leer la clave con la pantalla bloqueada (resúmenes nocturnos, recordatorios).
  - IV de 12 bytes generado por el Keystore; tag de 128 bits.
  - Si `decrypt` lanza `GeneralSecurityException` (clave perdida tras restaurar o borrar credenciales), borrar el
    valor y pedir la key de nuevo.
  - `android:allowBackup="false"` (o excluir el fichero en `dataExtractionRules`): la clave del Keystore no viaja en
    los backups y la restauración dejaría basura indescifrable.
  - StrongBox no hace falta (Google: "For most apps, StrongBox is not necessary").
  - Cuando `datastore-tink` sea estable, migrar a `AeadSerializer` es opcional.

```kotlin
// núcleo (versión completa en Secrets.kt)
KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
    .setKeySize(256)
    .build()
// encrypt: Cipher.getInstance("AES/GCM/NoPadding").init(ENCRYPT_MODE, key) → iv + doFinal(...)
// decrypt: init(DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12)) → doFinal(blob, 12, size-12)
```

---

## 10. Incompatibilidades y trampas encontradas

1. **Android Studio Panda 3 instalado ≠ AGP 9.4.1** (soporta hasta 9.1). CLI funciona; IDE no sincroniza.
   Solución: actualizar Studio (Quail 4 / Rabbit 1) o `agp = "9.1.1"`.
2. **compileSdk 37 obligatorio** (core 1.19.1, Compose 1.12.1, navigation3 1.2.0). Plataforma 37.0 ya instalada.
3. **Gradle 8.14.3 cacheado no sirve**: AGP 9.4 pide ≥ 9.6.0 (9.3 → 9.5.0; 9.1.1 → 9.3.1).
4. **No aplicar `org.jetbrains.kotlin.android` ni `kotlin-kapt`** con AGP 9.
5. **Kotlin real = el del plugin de Compose en el classpath raíz.** Si se quita `kotlin-compose` del
   `build.gradle.kts` raíz, el proyecto compilaría con el KGP 2.2.10 que arrastra AGP, desincronizado con el
   plugin de Compose 2.4.21. Mantener todos los plugins de Kotlin con `version.ref = "kotlin"` y declarados en la raíz.
6. KGP 2.4.21 oficialmente "totalmente soportado" solo hasta AGP 9.3.1 / Gradle 9.7.0. Funciona con
   9.4.1 / 9.8.1 (verificado), con un aviso de deprecación de Gradle 11 que emite AGP.
7. **R8 estricto en AGP 9**: `-keep class X` no conserva constructores; usar `proguard-android-optimize.txt`.
   La build de prueba con R8 pasó sin reglas extra (kotlinx-serialization, OkHttp 5, Room 3 y Sentry traen
   las suyas de consumidor), pero hay que probar la release en el dispositivo.
8. **Room 3 exige `schemaDirectory`** y KSP; FTS5 exige `BundledSQLiteDriver`.
9. `material3` del BOM es **1.4.0**; las APIs "Expressive" están en 1.5.0 (beta01): no usarlas.
10. `security-crypto` deprecado; `datastore-tink` aún RC.
11. cmdline-tools 20.0 avisa del XML v4 del repositorio: actualizar a 23.0.
12. El plugin de Sentry manda telemetría de build si no se pone `telemetry = false`.
13. `sdk.dir` mal escapado en `local.properties` → `IOException: Invalid file path`.

---

## Fuentes

- Metadatos Maven (consultados el 15-oct-2026):
  - https://dl.google.com/android/maven2/com/android/tools/build/gradle/maven-metadata.xml (AGP 9.4.1; 9.5.0-alpha10)
  - https://dl.google.com/android/maven2/com/android/tools/build/gradle/9.4.1/gradle-9.4.1.pom (KGP 2.2.10 runtime, KSP 2.2.10-2.0.2)
  - https://dl.google.com/android/maven2/androidx/compose/compose-bom/2026.09.00/compose-bom-2026.09.00.pom
  - https://dl.google.com/android/maven2/androidx/{core,activity,lifecycle,navigation,navigation3,room,room3,sqlite,work,datastore,security}/…/maven-metadata.xml
  - https://dl.google.com/android/maven2/androidx/room3/group-index.xml, https://dl.google.com/android/maven2/androidx/datastore/group-index.xml
  - https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml (2.4.21)
  - https://repo1.maven.org/maven2/org/jetbrains/kotlin/compose-compiler-gradle-plugin/2.4.21/compose-compiler-gradle-plugin-2.4.21.pom
  - https://repo1.maven.org/maven2/com/google/devtools/ksp/symbol-processing-gradle-plugin/maven-metadata.xml (2.3.12)
  - https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/5.5.0/okhttp-5.5.0.module
  - https://repo1.maven.org/maven2/io/sentry/sentry-android/maven-metadata.xml (8.61.0), …/sentry-android-gradle-plugin/maven-metadata.xml (6.23.0)
  - https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-android/maven-metadata.xml (1.11.0), …/kotlinx-serialization-json/maven-metadata.xml (1.11.0)
  - https://repo1.maven.org/maven2/com/google/crypto/tink/tink-android/maven-metadata.xml (1.23.0)
  - https://services.gradle.org/versions/current (9.8.1 + checksums)
  - https://dl.google.com/android/repository/repository2-4.xml (platforms;android-37.0/37.1/37.2, build-tools;37.0.0, canal stable)
- AGP: https://developer.android.com/build/releases/gradle-plugin · https://developer.android.com/build/releases/agp-9-4-0-release-notes · https://developer.android.com/build/releases/agp-9-3-0-release-notes · https://developer.android.com/build/releases/agp-9-1-0-release-notes · https://developer.android.com/build/releases/agp-9-0-0-release-notes · https://developer.android.com/build/releases/about-agp · https://developer.android.com/build/migrate-to-built-in-kotlin
- Kotlin: https://kotlinlang.org/docs/releases.html · https://kotlinlang.org/docs/gradle-configure-project.html · https://github.com/JetBrains/kotlin/releases/tag/v2.4.21
- KSP: https://github.com/google/ksp/releases
- AndroidX: https://developer.android.com/jetpack/androidx/releases/room3 · https://developer.android.com/jetpack/androidx/releases/navigation3 · https://developer.android.com/jetpack/androidx/releases/core · https://developer.android.com/jetpack/androidx/releases/datastore · https://developer.android.com/jetpack/androidx/releases/security
- SQLite bundled flags: https://raw.githubusercontent.com/androidx/androidx/androidx-main/sqlite/sqlite-bundled/build.gradle
- Android 17: https://developer.android.com/about/versions/17/behavior-changes-17 · https://developer.android.com/about/versions/17/behavior-changes-all · https://developer.android.com/about/versions/17/changes/bg-audio · fecha de estable (prensa): https://www.androidauthority.com/android-17-release-date-3639790 · https://www.slashgear.com/2211384/android-17-coming-to-these-google-pixel-phones/ · https://www.gizmochina.com/2026/06/17/stable-android-17-rolling-out-to-google-pixels-check-eligible-devices-new-features-and-more/
- Keystore: https://developer.android.com/privacy-and-security/keystore · fuentes de `security-crypto-1.1.0-sources.jar` (Google Maven)
- Sentry: https://github.com/getsentry/sentry-android-gradle-plugin/releases · https://github.com/getsentry/sentry-java/releases · https://docs.sentry.io/platforms/android/configuration/gradle/
- Patrón AGP 9 en proyecto de Google: https://github.com/android/nowinandroid (AGP 9.3.2, plugins en la raíz)
- Android Studio Panda 3 Patch 1: https://developer.android.com/studio/releases/past-releases/as-panda-3-release-notes
