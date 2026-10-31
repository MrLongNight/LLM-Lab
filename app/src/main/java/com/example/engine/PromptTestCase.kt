package com.example.engine

data class PromptTestCase(
    val id: String,
    val title: String,
    val description: String,
    val prompt: String,
    val expectedKeywords: List<String>,
    val repetitionCount: Int = 1,
    val timeoutSec: Int = 90
)

object PromptTestCatalog {
    val sampleLetterText = """
Praxis Dr. med. Martin Müller
Facharzt für Innere Medizin
Goethestraße 42, 80336 München

Rechnung Nr. 2026-9812
Datum: 15.03.2026

Sehr geehrte(r) Patient(in),
für die am 10.03.2026 durchgeführte Kontrolluntersuchung sowie labormedizinische Diagnostik berechnen wir gemäß GOÄ:

1. Ziffer 1: Symptombezogene Untersuchung - 18,50 €
2. Ziffer 250: Blutentnahme - 8,20 €
3. Ziffer 3550: Großes Blutbild / Entzündungsparameter - 122,80 €

Gesamtbetrag fällig ohne Abzug bis 31.03.2026: 149,50 €

Mit freundlichen Grüßen,
Dr. med. Martin Müller
    """.trimIndent()

    val longContextText = """
Allgemeine Geschäftsbedingungen und Betriebsvereinbarung für das Dokumentenmanagementsystem (DMS)
Stand: Oktober 2026 – Version 4.1

Abschnitt 1: Revisionssicherheit und Aufbewahrungsfristen
Gemäß den Grundsätzen zur ordnungsmäßigen Führung und Aufbewahrung von Büchern, Aufzeichnungen und Unterlagen in elektronischer Form sowie zum Datenzugriff (GoBD) sind steuerrelevante Belege, Buchungsbelege und Handelsbriefe für eine Dauer von mindestens 10 Jahren unveränderbar und manipulationssicher im Archiv vorzuhalten. Jedes Dokument muss bei der Einbringung mit einem kryptografischen Hashwert und einem qualifizierten Zeitstempel versehen werden.

Abschnitt 2: Zugriffskontrolle und Mandantenfähigkeit
Der Zugriff auf vertrauliche Verträge, Personalakten und medizinische Nachweise ist ausschließlich autorisierten Benutzern über rollenbasierte Zugriffsberechtigungen (RBAC) gestattet. Jeder Lese-, Schreib- und Exportvorgang wird im unveränderlichen Audit-Trail protokolliert.

Abschnitt 3: Automatische Extraktion und Metadaten-Indizierung
Eingehende Rechnungen und Lieferscheine werden durch ein lokales On-Device-LLM auf Schlüsselattribute wie Absender, Rechnungsnummer, Bruttobetrag, Umsatzsteuersatz und Fälligkeit analysiert. Unbekannte oder mehrdeutige Felder sind ausdrücklich mit 'null' zu kennzeichnen und in die manuelle Nachprüfung zu übergeben.
    """.trimIndent()

    val ALL_TESTS: List<PromptTestCase> = listOf(
        PromptTestCase(
            id = "TEST_1_OK",
            title = "1. Einfacher Kurztest (OK)",
            description = "Überprüft, ob das Modell strikt und ohne Halluzinationen nur 'OK' erzeugt.",
            prompt = "Antworte ausschließlich mit OK.",
            expectedKeywords = listOf("OK"),
            timeoutSec = 30
        ),
        PromptTestCase(
            id = "TEST_2_DMS",
            title = "2. Deutscher Sprachtest (DMS)",
            description = "Überprüft die korrekte deutsche Formulierung in zwei prägnanten Sätzen.",
            prompt = "Erkläre in zwei kurzen Sätzen, was ein Dokumentenmanagementsystem ist.",
            expectedKeywords = listOf("Dokument", "System"),
            timeoutSec = 60
        ),
        PromptTestCase(
            id = "TEST_3_JSON",
            title = "3. Strukturierter JSON-Test",
            description = "Prüft, ob die Ausgabe valides JSON mit den Pflichtfeldern erzeugt.",
            prompt = "Erzeuge ein JSON-Objekt mit den Feldern documentType, sender und date. Verwende null, wenn ein Wert unbekannt ist. Antworte nur mit dem JSON.",
            expectedKeywords = listOf("documentType", "sender", "date"),
            timeoutSec = 60
        ),
        PromptTestCase(
            id = "TEST_4_EXTRACTION",
            title = "4. Extraktion aus Beispielbrief",
            description = "Extrahiert Absender, Dokumenttyp, Datum und Rechnungsbetrag aus dem Text.",
            prompt = "Lies folgenden Text sorgfältig:\n\n$sampleLetterText\n\nExtrahiere als einzelnes valides JSON-Objekt mit exakt den Feldern sender, documentType, date und totalAmount. Verwende für date das ISO-Format yyyy-MM-dd und für totalAmount eine Zahl ohne Währungszeichen.",
            expectedKeywords = listOf("Müller", "149,50", "2026-03-15"),
            timeoutSec = 90
        ),
        PromptTestCase(
            id = "TEST_5_HALLUCINATION",
            title = "5. Halluzinationstest (Null-Regel)",
            description = "Verlangt 'null' für nicht im Text vorhandene Daten wie 'iban' und 'poNumber'.",
            prompt = "Lies folgenden Text:\n$sampleLetterText\n\nErzeuge ausschließlich ein valides JSON-Objekt mit exakt den Feldern iban und poNumber. Wenn eine Information im Text nicht vorhanden ist, MUSS der Wert zwingend JSON-null sein.",
            expectedKeywords = listOf("null"),
            timeoutSec = 60
        ),
        PromptTestCase(
            id = "TEST_6_LONG_CONTEXT",
            title = "6. Längerer Prompt (Kontextverarbeitung)",
            description = "Prüft die Batch-Verarbeitung und KV-Cache-Leistung bei einem mehrabsätzigen Text.",
            prompt = "Analysiere folgenden Text:\n\n$longContextText\n\nFasse die 3 Hauptpunkte stichpunktartig in jeweils einem Satz auf Deutsch zusammen.",
            expectedKeywords = listOf("Aufbewahrung", "Zugriff", "Extraktion"),
            timeoutSec = 120
        ),
        PromptTestCase(
            id = "TEST_7_REPEAT_10X",
            title = "7. Wiederholungstest (10x Durchlauf)",
            description = "Führt denselben Prompt zehnmal hintereinander aus, um Stabilität und Latenz-Varianz zu erfassen.",
            prompt = "Antworte ausschließlich mit OK.",
            expectedKeywords = listOf("OK"),
            repetitionCount = 10,
            timeoutSec = 120
        ),
        PromptTestCase(
            id = "TEST_8_SIMPLE_HALLOWEEN",
            title = "8. Einfache Wissensfrage (Halloween)",
            description = "Prüft eine kurze, konkrete deutsche Alltagsantwort ohne erfundene Selbstbeschreibung.",
            prompt = "Was ist Halloween? Antworte auf Deutsch in genau zwei kurzen Sätzen und nenne das Datum.",
            expectedKeywords = listOf("Halloween"),
            timeoutSec = 45
        )
    )
}
