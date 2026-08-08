package com.devnav.rec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenValueDetectorTest {
    @Test
    fun `normalizes Persian and Arabic digits with separators`() {
        assertEquals("12345.67", NumberParser.canonicalize("مقدار: ۱۲٬۳۴۵٫۶۷"))
        assertEquals("987", NumberParser.canonicalize("القيمة ٩٨٧"))
        assertEquals("-42", NumberParser.canonicalize("تغییر −۴۲"))
    }

    @Test
    fun `finds the nearest number to the right of a text anchor`() {
        val tokens = listOf(
            OcrToken("HP", Bounds(40, 100, 75, 120)),
            OcrToken("۹۵", Bounds(88, 100, 112, 120)),
            OcrToken("2026", Bounds(700, 100, 750, 120))
        )

        val detection = ScreenValueDetector.findByText(tokens, "HP", RelativePosition.RIGHT)

        assertNotNull(detection)
        assertEquals("95", detection?.value)
    }

    @Test
    fun `finds a number beneath an image anchor bounds`() {
        val tokens = listOf(
            OcrToken("۱۲", Bounds(210, 360, 230, 382)),
            OcrToken("۹۹", Bounds(600, 50, 630, 74))
        )

        val detection = ScreenValueDetector.findNearAnchor(
            tokens,
            Bounds(200, 300, 240, 340),
            RelativePosition.BELOW
        )

        assertEquals("12", detection?.value)
    }

    @Test
    fun `detects multiple text markers independently`() {
        val tokens = listOf(
            OcrToken("HP", Bounds(40, 100, 75, 120)),
            OcrToken("۹۵", Bounds(88, 100, 112, 120)),
            OcrToken("MP", Bounds(40, 160, 75, 180)),
            OcrToken("۴۲", Bounds(88, 160, 112, 180))
        )
        val markers = listOf(
            MarkerConfig("HP", MarkerMode.TEXT, null, RelativePosition.RIGHT),
            MarkerConfig("MP", MarkerMode.TEXT, null, RelativePosition.RIGHT)
        )

        val results = ScreenValueDetector.findMultipleMarkers(tokens, markers, imageAnchor = null)

        assertEquals(listOf("95", "42"), results.map { it.value })
        assertTrue(results.all { it.found })
    }

    @Test
    fun `reports a missing marker without inventing a value`() {
        val marker = MarkerConfig("missing", MarkerMode.TEXT, null, RelativePosition.RIGHT)

        val result = ScreenValueDetector.findMultipleMarkers(emptyList(), listOf(marker), null).single()

        assertFalse(result.found)
        assertEquals("", result.value)
    }

    @Test
    fun `requires two matching readings before reporting a change`() {
        val gate = ValueChangeGate(requiredConsecutiveReads = 2)

        assertNull(gate.offer("10"))
        val initial = gate.offer("10")
        assertEquals("10", initial?.current)
        assertTrue(initial?.isInitial == true)

        assertNull(gate.offer("11"))
        val changed = gate.offer("11")
        assertEquals("10", changed?.previous)
        assertEquals("11", changed?.current)
        assertFalse(changed?.isInitial ?: true)
    }
}
