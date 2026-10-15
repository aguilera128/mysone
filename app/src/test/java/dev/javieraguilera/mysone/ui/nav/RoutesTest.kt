package dev.javieraguilera.mysone.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutesTest {
    @Test fun `destinos del extra open`() {
        assertEquals(listOf(Today()), stackForOpen("today"))
        assertEquals(listOf(Today(), Ask), stackForOpen("ask"))
        assertEquals(listOf(Today(), Tasks(42)), stackForOpen("tasks:42"))
        assertEquals(listOf(Today(), Tasks(null)), stackForOpen("tasks:"))
        assertEquals(listOf(Today(), ConversationDetail(7)), stackForOpen("conversation:7"))
        assertEquals(listOf(Today(), Settings, SettingsModels), stackForOpen("models"))
        assertEquals(listOf(Today(), Settings), stackForOpen("settings"))
    }

    @Test fun `valores desconocidos o mal formados se ignoran`() {
        assertNull(stackForOpen("conversation:abc"))
        assertNull(stackForOpen("conversation"))
        assertNull(stackForOpen("nada"))
        assertNull(stackForOpen(""))
    }
}
