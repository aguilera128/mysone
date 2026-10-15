package dev.javieraguilera.mysone.core

import kotlinx.serialization.json.Json

/** Instancia compartida: tolera claves desconocidas y nulos donde hay valor por defecto (salidas de IA). */
val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}
