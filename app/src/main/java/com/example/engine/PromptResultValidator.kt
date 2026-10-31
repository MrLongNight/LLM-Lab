package com.example.engine

import org.json.JSONObject

data class PromptValidationResult(val passed: Boolean, val details: String)

object PromptResultValidator {
    fun validate(testCase: PromptTestCase, rawOutput: String): PromptValidationResult {
        val output = rawOutput.trim()
        if (output.isEmpty()) return PromptValidationResult(false, "Das Modell hat keinen Antworttext erzeugt.")
        return when (testCase.id) {
            "TEST_1_OK", "TEST_7_REPEAT_10X" -> result(output == "OK", "Erwartet wurde ausschließlich OK.")
            "TEST_2_DMS" -> {
                val count = Regex("[.!?](?:\\s|$)").findAll(output).count()
                val keywords = testCase.expectedKeywords.all { output.contains(it, ignoreCase = true) }
                result(keywords && count == 2, "Erwartet: zwei Sätze mit Dokument und System; erkannt: $count.")
            }
            "TEST_3_JSON" -> {
                val json = parseJson(output)
                val keys = listOf("documentType", "sender", "date")
                result(json != null && keys.all { json.has(it) && json.opt(it) === JSONObject.NULL }, "Erwartet: valides JSON mit drei expliziten null-Werten.")
            }
            "TEST_4_EXTRACTION" -> {
                val json = parseJson(output)
                if (json == null) result(false, "Die Extraktion muss valides JSON liefern.") else {
                    val amount = parseAmount(json.opt("totalAmount"))
                    val valid = json.optString("sender", "").contains("Müller", true) &&
                        json.optString("documentType", "").contains("Rechnung", true) &&
                        json.optString("date", "") == "2026-03-15" && amount != null && kotlin.math.abs(amount - 149.50) < 0.005
                    result(valid, "Erwartet: Müller, Rechnung, 2026-03-15 und 149.50.")
                }
            }
            "TEST_5_HALLUCINATION" -> {
                val json = parseJson(output)
                result(json != null && json.has("iban") && json.opt("iban") === JSONObject.NULL && json.has("poNumber") && json.opt("poNumber") === JSONObject.NULL, "IBAN und poNumber müssen explizit JSON-null sein.")
            }
            "TEST_8_SIMPLE_HALLOWEEN" -> {
                val hasDate = output.contains("31. Oktober", ignoreCase = true) || output.contains("31.10")
                val hasContext = listOf("Kostüm", "Verkleid", "Süßigkeit", "Grusel").any { output.contains(it, ignoreCase = true) }
                val sentenceCount = Regex("[.!?](?:\\s|$)").findAll(output).count()
                result(output.contains("Halloween", ignoreCase = true) && hasDate && hasContext && sentenceCount == 2,
                    "Erwartet werden genau zwei kurze Sätze, das Datum 31. Oktober und ein typischer Brauch.")
            }
            "TEST_6_LONG_CONTEXT" -> {
                val keywords = testCase.expectedKeywords.all { output.contains(it, true) }
                val bullets = Regex("(?m)^\\s*(?:[-*•]|\\d+[.)])\\s+\\S.*$").findAll(output).count()
                result(keywords && bullets >= 3, "Erwartet: mindestens drei Stichpunkte und alle drei Hauptthemen; erkannt: $bullets.")
            }
            else -> result(testCase.expectedKeywords.all { output.contains(it, true) }, "Erwartete Schlüsselbegriffe fehlen.")
        }
    }

    private fun parseJson(raw: String): JSONObject? {
        var text = raw.trim()
        if (text.startsWith("```")) {
            val newline = text.indexOf(10.toChar())
            if (newline >= 0) text = text.substring(newline + 1)
            if (text.trimEnd().endsWith("```")) text = text.trimEnd().dropLast(3)
        }
        return runCatching { JSONObject(text.trim()) }.getOrNull()
    }

    private fun parseAmount(value: Any?): Double? {
        if (value is Number) return value.toDouble()
        val raw = value?.toString()?.trim().orEmpty().replace("€", "").replace(" ", "")
        val normalized = if (raw.contains(",")) raw.replace(".", "").replace(",", ".") else raw
        return normalized.replace(Regex("[^0-9.]"), "").toDoubleOrNull()
    }

    private fun result(passed: Boolean, details: String) = PromptValidationResult(passed, details)
}