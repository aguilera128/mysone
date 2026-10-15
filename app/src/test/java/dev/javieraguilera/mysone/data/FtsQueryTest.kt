package dev.javieraguilera.mysone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtsQueryTest {
    @Test fun `búsqueda del usuario une los términos con AND implícito y prefijo`() {
        assertEquals("\"reunión\"* \"martes\"*", FtsQuery.all("  reunión   martes "))
    }

    @Test fun `quita la sintaxis de FTS5 y las comillas`() {
        // Operadores y paréntesis quedan como palabras entrecomilladas: ya no son sintaxis.
        assertEquals("\"NEAR\"* \"a\"* \"b\"* \"OR\"* \"c\"* \"x\"*", FtsQuery.all("NEAR(a b) OR \"c\"* -x:"))
        assertEquals("\"precio\"*", FtsQuery.all("precio^ - : ()"))
    }

    @Test fun `sin términos devuelve null`() {
        assertNull(FtsQuery.all("  ¿? ¡! ... \"\" * "))
        assertNull(FtsQuery.any(listOf("", "--")))
    }

    @Test fun `conserva letras con tilde, eñes y números`() {
        assertEquals("\"año\"* \"2026\"* \"canción\"*", FtsQuery.all("año 2026, ¿canción?"))
    }

    @Test fun `normaliza a NFC para no partir letras con tilde descompuestas`() {
        val decomposed = "canción" // "canción" con el acento como carácter combinante
        assertEquals("\"canción\"*", FtsQuery.all(decomposed))
    }

    @Test fun `elimina duplicados sin distinguir mayúsculas`() {
        assertEquals("\"Ana\"* \"dijo\"*", FtsQuery.all("Ana dijo ana ANA"))
    }

    @Test fun `recuperación de la IA usa OR entre todas las palabras`() {
        assertEquals(
            "\"presupuesto\"* OR \"coste\"* OR \"obra\"*",
            FtsQuery.any(listOf("presupuesto", "coste obra", "Presupuesto")),
        )
    }
}
