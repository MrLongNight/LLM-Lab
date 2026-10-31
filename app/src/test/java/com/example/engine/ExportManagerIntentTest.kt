package com.example.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExportManagerIntentTest {
    @Test fun exportFileNameKeepsOnlySafeBaseName() {
        assertEquals("diagnose.txt", ExportManager.sanitizeExportFileName("../diagnose.txt"))
        assertEquals("llmlab.json", ExportManager.sanitizeExportFileName("C:\\temp\\llmlab.json"))
    }
    @Test fun exportFileNameRejectsEmptyOrUnsafeNames() {
        assertThrows(IllegalArgumentException::class.java) { ExportManager.sanitizeExportFileName("") }
        assertThrows(IllegalArgumentException::class.java) { ExportManager.sanitizeExportFileName("bad name.txt") }
    }
}