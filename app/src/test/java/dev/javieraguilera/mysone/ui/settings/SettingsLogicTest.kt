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

    @Test fun `url de la api solo https o http local`() {
        assertTrue(isValidBaseUrl("https://api.anthropic.com"))
        assertTrue(isValidBaseUrl("http://10.0.2.2:8787"))
        assertFalse(isValidBaseUrl("http://api.example.com"))
        assertFalse(isValidBaseUrl("api.anthropic.com"))
        assertFalse(isValidBaseUrl(""))
    }
}
