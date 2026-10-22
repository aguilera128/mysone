package dev.javieraguilera.mysone.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLogicTest {
    @Test fun `enlace de dontkillmyapp con la marca en minusculas`() {
        assertEquals("https://dontkillmyapp.com/xiaomi", dontKillMyAppUrl("Xiaomi"))
        assertEquals("https://dontkillmyapp.com/samsung", dontKillMyAppUrl(" samsung "))
        assertEquals("https://dontkillmyapp.com/oneplus", dontKillMyAppUrl("OnePlus"))
    }

    @Test fun `acortar la retención se confirma, alargarla no`() {
        assertTrue(shortensRetention(90, 7))
        assertTrue(shortensRetention(0, 365)) // de «No borrar nunca» a un plazo
        assertFalse(shortensRetention(7, 90))
        assertFalse(shortensRetention(90, 0))
        assertFalse(shortensRetention(90, 90))
    }

    @Test fun `url de la api solo https o http local`() {
        assertTrue(isValidBaseUrl("https://api.anthropic.com"))
        assertTrue(isValidBaseUrl("http://10.0.2.2:8787", allowLocalHttp = true))
        assertFalse(isValidBaseUrl("http://10.0.2.2:8787", allowLocalHttp = false)) // release: sin texto plano
        assertFalse(isValidBaseUrl("http://api.example.com"))
        assertFalse(isValidBaseUrl("api.anthropic.com"))
        assertFalse(isValidBaseUrl(""))
    }
}
