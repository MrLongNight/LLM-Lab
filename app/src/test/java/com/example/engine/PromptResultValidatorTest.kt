package com.example.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PromptResultValidatorTest {
    private fun test(id: String) = PromptTestCatalog.ALL_TESTS.first { it.id == id }
    @Test fun shortTestOnlyAcceptsExactOk() {
        assertTrue(PromptResultValidator.validate(test("TEST_1_OK"), "OK").passed)
        assertFalse(PromptResultValidator.validate(test("TEST_1_OK"), "OK, gern.").passed)
    }
    @Test fun jsonRequiresUnknownValuesToBeNull() {
        assertTrue(PromptResultValidator.validate(test("TEST_3_JSON"), """{"documentType":null,"sender":null,"date":null}""").passed)
        assertFalse(PromptResultValidator.validate(test("TEST_3_JSON"), """{"documentType":"Rechnung","sender":null,"date":null}""").passed)
    }
    @Test fun extractionChecksKnownSampleValues() {
        val output = """{"sender":"Praxis Dr. med. Martin Müller","documentType":"Rechnung","date":"2026-03-15","totalAmount":149.50}"""
        assertTrue(PromptResultValidator.validate(test("TEST_4_EXTRACTION"), output).passed)
    }
    @Test fun hallucinationRequiresExplicitNulls() {
        assertTrue(PromptResultValidator.validate(test("TEST_5_HALLUCINATION"), """{"iban":null,"poNumber":null}""").passed)
        assertFalse(PromptResultValidator.validate(test("TEST_5_HALLUCINATION"), """{"iban":"DE123","poNumber":null}""").passed)
    }
    @Test fun longContextRequiresThreeBullets() {
        val output = """- Aufbewahrung: zehn Jahre.\n- Zugriff: Rollen steuern Rechte.\n- Extraktion: unbekannt wird null."""
        assertTrue(PromptResultValidator.validate(test("TEST_6_LONG_CONTEXT"), output).passed)
    }
}