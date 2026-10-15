// Todos los plugins en el classpath raíz: así KGP se resuelve una sola vez a la versión de Kotlin
// del catálogo (AGP 9 arrastra un KGP más viejo). Ver docs/investigacion/04-toolchain.md §2.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room3) apply false
}
