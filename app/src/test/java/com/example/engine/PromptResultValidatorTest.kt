package com.example.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PromptResultValidatorTest {
    private fun test(id: String) = PromptTestCatalog.ALL_TESTS.first { it.id == id }

    @Test
    fun blankNativeErrorDoesNotHideContentValidationFailure() {
        val failed = PromptValidationResult(false, "Erwartet wurden zwei Sätze.")
        assertTrue(PromptResultValidator.failureReason("", true, failed)!!.contains("zwei Sätze"))
        assertTrue(PromptResultValidator.failureReason(null, true, failed)!!.contains("zwei Sätze"))
        assertTrue(PromptResultValidator.failureReason("Native decode failed", true, failed) == "Native decode failed")
        assertTrue(PromptResultValidator.failureReason("", true, PromptValidationResult(true, "OK")) == null)
    }

    @Test
    fun throughputWorkflowRequiresSubstantialGermanOutput() {
        val good = "Ein DMS erfasst digitale Dokumente und ordnet sie anhand zusätzlicher Metadaten. Die integrierte Suche findet Inhalte zuverlässig über zuvor durchgeführte OCR Verarbeitung. Das Archiv speichert Unterlagen sicher und hält wichtige Informationen dauerhaft nachvollziehbar bereit. Berechtigte Nutzer können Dokumente später schnell auffinden und verwalten. Dadurch sinkt der manuelle Aufwand im täglichen Arbeitsablauf deutlich."
        val bad = "Dokumente sind da."
        assertTrue(PromptResultValidator.validate(test("TEST_PERFORMANCE"), good).passed)
        assertFalse(PromptResultValidator.validate(test("TEST_PERFORMANCE"), bad).passed)
    }

    @Test
    fun dmsWorkflowRequiresExactlyTwoSentences() {
        val good = "Ein Dokumentenmanagementsystem verwaltet digitale Dokumente zentral. Es erleichtert Suche, Ablage und Zugriff auf jedes System."
        val bad = "Ein System verwaltet Dokumente. Es hilft. Eine Suche ist möglich."
        assertTrue(PromptResultValidator.validate(test("TEST_DMS"), good).passed)
        assertFalse(PromptResultValidator.validate(test("TEST_DMS"), bad).passed)
    }

    @Test
    fun extractionWorkflowChecksGroundedValuesAndNullsTogether() {
        val good = """{"sender":"Praxis Dr. med. Martin Müller","documentType":"Rechnung","date":"2026-03-15","invoiceNumber":"2026-9812","totalAmount":149.50,"iban":null,"poNumber":null}"""
        val wrongPo = """{"sender":"Praxis Dr. med. Martin Müller","documentType":"Rechnung","date":"2026-03-15","invoiceNumber":"2026-9812","totalAmount":149.50,"iban":null,"poNumber":"2026-9812"}"""
        val wrongAmount = """{"sender":"Praxis Dr. med. Martin Müller","documentType":"Rechnung","date":"2026-03-15","invoiceNumber":"2026-9812","totalAmount":149.00,"iban":null,"poNumber":null}"""
        assertTrue(PromptResultValidator.validate(test("TEST_EXTRACTION"), good).passed)
        assertFalse(PromptResultValidator.validate(test("TEST_EXTRACTION"), wrongPo).passed)
        assertFalse(PromptResultValidator.validate(test("TEST_EXTRACTION"), wrongAmount).passed)
    }
}
