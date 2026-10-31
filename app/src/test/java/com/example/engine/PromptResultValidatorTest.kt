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
    fun shortTestOnlyAcceptsExactOk() {
        assertTrue(PromptResultValidator.validate(test("TEST_1_OK"), "OK").passed)
        assertFalse(PromptResultValidator.validate(test("TEST_1_OK"), "OK, gern.").passed)
    }

    @Test
    fun jsonRequiresUnknownValuesToBeNull() {
        assertTrue(PromptResultValidator.validate(test("TEST_3_JSON"), """{"documentType":null,"sender":null,"date":null}""").passed)
        assertFalse(PromptResultValidator.validate(test("TEST_3_JSON"), """{"documentType":"Rechnung","sender":null,"date":null}""").passed)
    }

    @Test
    fun extractionChecksKnownSampleValues() {
        val output = """{"sender":"Praxis Dr. med. Martin Müller","documentType":"Rechnung","date":"2026-03-15","totalAmount":149.50}"""
        assertTrue(PromptResultValidator.validate(test("TEST_4_EXTRACTION"), output).passed)
    }

    @Test
    fun hallucinationRequiresExplicitNulls() {
        assertTrue(PromptResultValidator.validate(test("TEST_5_HALLUCINATION"), """{"iban":null,"poNumber":null}""").passed)
        assertFalse(PromptResultValidator.validate(test("TEST_5_HALLUCINATION"), """{"iban":"DE123","poNumber":null}""").passed)
    }

    @Test
    fun halloweenQuestionRequiresCorrectDateAndShortGermanAnswer() {
        val good = "Halloween wird am 31. Oktober gefeiert. Viele Menschen verkleiden sich und Kinder sammeln Süßigkeiten."
        val bad = "Halloween ist irgendwann im November. Es gibt Süßigkeiten."
        assertTrue(PromptResultValidator.validate(test("TEST_8_SIMPLE_HALLOWEEN"), good).passed)
        assertFalse(PromptResultValidator.validate(test("TEST_8_SIMPLE_HALLOWEEN"), bad).passed)
    }

    @Test
    fun longContextRequiresThreeBullets() {
        val output = """
            - Aufbewahrung: zehn Jahre.
            - Zugriff: Rollen steuern Rechte.
            - Extraktion: unbekannt wird null.
        """.trimIndent()
        assertTrue(PromptResultValidator.validate(test("TEST_6_LONG_CONTEXT"), output).passed)
    }
}