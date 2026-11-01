package com.example.engine

import org.json.JSONObject

data class PromptValidationResult(val passed: Boolean, val details: String)

object PromptResultValidator {
    fun failureReason(nativeError: String?, inferenceCompleted: Boolean, validation: PromptValidationResult): String? {
        nativeError?.takeIf { it.isNotBlank() }?.let { return it }
        return if (inferenceCompleted && !validation.passed) "Inhaltliche Prüfung fehlgeschlagen: ${validation.details}" else null
    }

    fun validate(testCase: PromptTestCase, rawOutput: String): PromptValidationResult {
        val output = rawOutput.trim()
        if (output.isEmpty()) return PromptValidationResult(false, "Das Modell hat keinen Antworttext erzeugt.")
        return when (testCase.id) {
            "chat-basic" -> {
                val words = Regex("[\\p{L}\\p{N}]+(?:[-'][\\p{L}\\p{N}]+)*").findAll(output).count()
                val sentences = Regex("[.!?](?:\\s|$)").findAll(output).count()
                val keywords = testCase.expectedKeywords.all { output.contains(it, ignoreCase = true) }
                val passed = keywords && words >= testCase.minimumOutputWords && sentences == 1
                PromptValidationResult(
                    passed,
                    "Erwartet: mindestens \${testCase.minimumOutputWords} Wörter, genau ein Satz und das Thema Dokument; erkannt: $words Wörter, $sentences Sätze."
                )
            }
            "dms-smoke" -> {
                val sentences = Regex("[.!?](?:\\s|$)").findAll(output).count()
                val keywords = testCase.expectedKeywords.all { output.contains(it, ignoreCase = true) }
                val words = Regex("[\\p{L}\\p{N}]+(?:[-'][\\p{L}\\p{N}]+)*").findAll(output).count()
                val passed = keywords && words >= testCase.minimumOutputWords && sentences == 2
                PromptValidationResult(
                    passed,
                    "Erwartet: mindestens \${testCase.minimumOutputWords} Wörter, genau zwei Sätze und Dokument/Suche; erkannt: $words Wörter, $sentences Sätze."
                )
            }
            "json-strict" -> {
                val json = if (output.startsWith("{") && output.endsWith("}")) {
                    runCatching { JSONObject(output) }.getOrNull()
                } else {
                    null
                }
                if (json == null) {
                    PromptValidationResult(false, "Erwartet wird valides JSON ohne Markdown oder zusätzlichen Fließtext.")
                } else {
                    val amount = parseAmount(json.opt("totalAmount"))
                    val passed = json.length() == 3 &&
                        json.optString("sender", "").contains("Müller", ignoreCase = true) &&
                        amount != null && kotlin.math.abs(amount - 149.50) < 0.005 &&
                        json.has("iban") && json.opt("iban") === JSONObject.NULL
                    PromptValidationResult(
                        passed,
                        "Erwartet: exakt sender/totalAmount/iban, Absender Müller, Betrag 149.50 und iban=null."
                    )
                }
            }
            "TEST_PERFORMANCE" -> {
                val words = Regex("[\\p{L}\\p{N}]+(?:[-'][\\p{L}\\p{N}]+)*").findAll(output).count()
                val sentences = Regex("[.!?](?:\\s|$)").findAll(output).count()
                val keywords = testCase.expectedKeywords.all { output.contains(it, ignoreCase = true) }
                val passed = keywords && words >= testCase.minimumOutputWords && sentences == 5
                PromptValidationResult(
                    passed,
                    "Erwartet: mindestens ${testCase.minimumOutputWords} Wörter, genau fünf Sätze und Dokument/Suche/Archiv; erkannt: $words Wörter, $sentences Sätze."
                )
            }
            "TEST_DMS" -> {
                val count = Regex("[.!?](?:\\s|$)").findAll(output).count()
                val keywords = testCase.expectedKeywords.all { output.contains(it, ignoreCase = true) }
                PromptValidationResult(
                    keywords && count == 2,
                    "Erwartet: genau zwei Sätze mit Dokument und System; erkannt: $count."
                )
            }
            "TEST_PREFILL_256", "TEST_PREFILL_512" -> {
                val passed = Regex("^OCR[.!]?$", RegexOption.IGNORE_CASE).matches(output)
                PromptValidationResult(
                    passed,
                    if (passed) "Das erwartete OCR-Signal wurde nach dem langen Prefill ausgegeben."
                    else "Erwartet wurde ausschließlich OCR als kurze Antwort auf den Prefill-Test."
                )
            }
            "TEST_TUNING", "TEST_SAMPLING" -> {
                val relevant = output.contains("ocr", ignoreCase = true) ||
                    output.contains("text", ignoreCase = true) ||
                    output.contains("such", ignoreCase = true) ||
                    output.contains("erkennung", ignoreCase = true)
                PromptValidationResult(
                    output.length >= 12 && relevant,
                    if (output.length >= 12 && relevant) {
                        "Thematisch passende Antwort vorhanden; Geschwindigkeit und Sampling werden separat bewertet."
                    } else {
                        "Erwartet wurde eine kurze thematisch passende OCR-/Text-Erklärung."
                    }
                )
            }
            "TEST_EXTRACTION" -> {
                val json = parseJson(output)
                if (json == null) {
                    PromptValidationResult(false, "Die Extraktion muss valides JSON ohne zusätzlichen Fließtext liefern.")
                } else {
                    val amount = parseAmount(json.opt("totalAmount"))
                    val passed = json.length() == 7 &&
                        json.optString("sender", "").contains("Müller", true) &&
                        json.optString("documentType", "").contains("Rechnung", true) &&
                        json.optString("date", "") == "2026-03-15" &&
                        json.optString("invoiceNumber", "") == "2026-9812" &&
                        amount != null && kotlin.math.abs(amount - 149.50) < 0.005 &&
                        json.has("iban") && json.opt("iban") === JSONObject.NULL &&
                        json.has("poNumber") && json.opt("poNumber") === JSONObject.NULL
                    PromptValidationResult(
                        passed,
                        "Erwartet: Müller, Rechnung, 2026-03-15, 2026-9812, 149.50, iban=null und poNumber=null."
                    )
                }
            }
            else -> PromptValidationResult(false, "Unbekannter automatischer Testablauf: ${testCase.id}")
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
}
