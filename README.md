# LLM Lab – Mobile Inference Testbench

Eigenständige Android-Test-App zur Untersuchung echter lokaler GGUF-Inferenz auf dem späteren myDocAnizer-Zielgerät. Dieses Repository ist unabhängig von `myDocAnizer-Mobile`; Änderungen hier verändern das Hauptprojekt nicht.

## Architektur und harte Grenzen

- Android, Kotlin, Jetpack Compose, Room und C++/JNI.
- Native Engine: `llama.cpp`, fest gepinnt als Git-Submodul unter `third_party/llama.cpp` auf Commit `78feec062d50ec9e8940244cac918ac4480b60cb` (API-Version `0.8.0-dev`).
- **CPU (stabil):** als sicherer Fallback einkompiliert und der Referenzpfad für verlässliche Inferenz. **Vulkan (experimentell):** generisches GPU-Backend; die Verfügbarkeit wird zur Laufzeit über die llama.cpp-Registry geprüft. Der native Code läuft isoliert im Android-Prozess `:inference`. CUDA/Metal sind auf Android nicht aktiviert. **NPU:** nicht enthalten; siehe [docs/npu-backend.md](docs/npu-backend.md).
- GGUF-Modell wird über den Android-Dateiauswahldialog ausgewählt und vor dem nativen Laden in den internen App-Speicher kopiert. Das Modell ist nicht in der APK enthalten.
- Eigenständige Package-ID: `de.mrlongnight.llmlab`; `minSdk=29` (Android 10), `targetSdk=36`, ABIs `arm64-v8a` und `x86_64`.
- Keine Firebase-, Gemini-, Cloud-Inferenz-, Login- oder externen KI-API-Abhängigkeiten. Internetzugriff wird ausschließlich für vom Nutzer gestartete HTTPS-Downloads der Qwen-GGUF-Dateien von Hugging Face verwendet; die Inferenz läuft anschließend lokal. Android-App-Backup ist deaktiviert.

Die App darf „Modell bereit“ erst anzeigen, wenn Modell und llama.cpp-Kontext erfolgreich initialisiert wurden. Bei fehlender nativer Bibliothek gibt es keinen JVM-Ersatzpfad. Die Backend-Registry meldet verfügbare Backends und deren Eigenschaften. Der Standardpfad `AUTO` bleibt auf CPU; Vulkan wird nur mit `ACCELERATOR_REQUIRED` ausdrücklich angefordert.

## Build und Tests

Voraussetzungen: JDK 21, Android SDK, Android NDK `30.0.16248370`, CMake `3.31.6` und Git-Submodule-Unterstützung. Für den Vulkan-Host-Build unter Ubuntu/Debian werden außerdem `glslc`, `libvulkan-dev` und `spirv-headers` benötigt.

```bash
git clone --recurse-submodules https://github.com/MrLongNight/LLM-Lab.git
cd LLM-Lab
git submodule update --init --recursive
chmod +x gradlew
./gradlew assembleDebug --no-daemon
./gradlew testDebugUnitTest --no-daemon
./gradlew assembleDebugAndroidTest --no-daemon
# Auf einem verbundenen Android-Zielgerät:
./gradlew connectedDebugAndroidTest
```

Die GitHub-Actions-Pipeline baut die Debug-APK, führt JVM-/Robolectric-Tests aus und kompiliert zusätzlich das Android-Test-APK. Sie veröffentlicht das Artefakt `LLM-Lab-Standalone-Debug-APK`. CI grün ≠ geräteverifiziert: Ohne angeschlossenes Zielgerät werden die Instrumentationstests nicht ausgeführt.

## Installation und erster Modelltest

1. Die APK `LLM-Lab-Standalone-debug.apk` aus dem Actions-Artefakt herunterladen und auf dem Android-Zielgerät installieren.
2. Im Bereich **Modell** kann ein Qwen-GGUF-Modell per **Download** über den Hintergrund-Download von Hugging Face bezogen werden. Alternativ lässt sich unter **Eigenes GGUF-Modell importieren** eine lokale Datei auswählen. Der Download-Code setzt `.part`-Dateien fort und prüft GGUF-Header sowie SHA-256 vor der Freigabe. **Wichtig:** Für die aktuelle Version wurde ein Fehler gemeldet, bei dem Hugging-Face-Dateien nicht geladen werden können. Dieser Downloadpfad ist deshalb ausdrücklich noch nicht als auf Zielhardware funktionsfähig abgenommen; die Dokumentation behauptet keine Fehlerbehebung.
3. Nach erfolgreicher Prüfung wird das ausgewählte Modell zur Initialisierung angeboten. **Modell jetzt initialisieren** startet das native Laden; erst danach erscheint der Status **Modell einsatzbereit**.
4. Im **Prompt-Testlabor** die automatische Qwen-Matrix starten: **Quick-Pretest** prüft alle vier Modelle einzeln. **Quick → Full** startet die vollständigen 80 Einzelprofile nur dann, wenn alle vier Quick-Checks bestanden wurden.
5. Im Prompt-Testlabor können außerdem die sieben Einzelworkflows für Durchsatz, DMS, JSON-Extraktion, Vulkan-Hardware-Tuning, Sampling und zwei reproduzierbare Prompt-Prefill-Längen separat ausgeführt werden.
6. Bei Fehlern TXT- und JSON-Diagnoseexport erzeugen. Chat-Prompt und Chat-Antwort werden in der lokalen Testhistorie redigiert gespeichert.

## Inferenzmesswerte

Die JNI-Schicht liefert tatsächliche Werte aus `llama.cpp`: Prompt-Tokenisierung, Prompt-Tokenanzahl, Prompt-Evaluierungsdauer, Prompt-Decode-Aufrufe, First-Token-Latenz, generierte Tokens, Generierungsdauer, Generierungs-Decode-Aufrufe, Gesamtdauer sowie getrennten Prefill- und Generierungsdurchsatz. Vor dem Abschluss der Prefill-Messung wird `llama_synchronize()` aufgerufen. Damit wird asynchron eingereichte Vulkan-Arbeit der Prompt-Evaluierung zugerechnet und nicht mehr fälschlich als Wartezeit nach dem Prefill ausgewiesen. Die CPU-Auslastung wird zusätzlich direkt um jeden Inferenzlauf gemessen, statt einen bis zu drei Sekunden alten Telemetrie-Snapshot zu verwenden. Der Diagnoseexport weist außerdem die Zeit zwischen abgeschlossenem Prefill und erstem Token, die Profilvorbereitung sowie den Prozess-PSS inklusive anteilig gemappter Modellseiten aus.

Der Prompt-Callback meldet Fortschritt während der Prompt-Auswertung. Während der eigentlichen Token-Generierung wird der echte Token-Stream angezeigt. Gerätemesswerte, die Android nicht zuverlässig bereitstellt, müssen als nicht verfügbar behandelt werden. Der native Allocator-Heap umfasst nicht zwingend alle per `mmap` eingeblendeten Modellgewichte.

## Testprofile

Für reproduzierbare Vergleiche hält die Matrix `n_ctx=2048`, `n_batch=512` und die Sampling-Baseline konstant. Nur der Durchsatzworkflow enthält zwei Profile: eine CPU-only-Referenz (4/8 Threads, `n_ubatch=64`) und Vulkan-Offload (4/8 Threads, `n_ubatch=64`). DMS, JSON-Extraktion, Hardware-, Sampling- und beide Prefill-Workflows verlangen Vulkan über `ACCELERATOR_REQUIRED`; die maximale Tokenzahl und das Zeitlimit sind je Workflow begrenzt.

Das Prompt-Labor bietet sieben Workflows: (1) längere deutsche Antwort für Durchsatz, (2) zweisätzige DMS-Erklärung, (3) synthetische Rechnungs-Extraktion mit sieben JSON-Feldern einschließlich `iban=null` und `poNumber=null`, (4) Vulkan-Hardware-Tuning mit fünf Profilen (2/4/6/8 Threads sowie zusätzlich ubatch 128), (5) Sampling-Tuning mit fünf Varianten, (6) mittleres und (7) langes Prompt-Prefill mit jeweils drei Vulkan-ubatch-Profilen (32/64/128). Beim Sampling-Tuning bleibt Vulkan aktiv; Temperatur, Top-K und Top-P werden einzeln gegenüber der Baseline verändert. Die ursprüngliche Modellkonfiguration wird anschließend wiederhergestellt.

Sampling-Parameter bleiben innerhalb eines Dreiervergleichs identisch, damit Unterschiede primär auf Backend, Threads und Micro-Batch zurückgeführt werden können. Native llama.cpp-Kernel werden auch im Debug-APK mit `-O3` kompiliert.

Der separate Stabilitätscheck führt drei echte Kurz-Inferenzen aus. Timeout, nativer Fehler oder eine falsche Antwort beenden den Lauf automatisch.

## Limitations

- Native Aufrufe laufen im separaten Android-Prozess `:inference` und sind über das Beenden dieses Prozesses abbrechbar. Ein kooperativer Abbruch innerhalb eines laufenden nativen Vulkan-Aufrufs ist damit nicht zugesichert.
- **Vulkan bleibt experimentell.** Die Prozessisolation ermöglicht den harten Timeout-/Abort-Pfad durch Prozessbeendigung; sie ist kein Beleg dafür, dass ein Vulkan-Kernel selbst kooperativ abbricht oder ein Vulkan-Fehler behoben wäre.
- Der Speicherschutz prüft vor Modellinitialisierungen den verfügbaren Speicher gegen 1,5× der im Modellkatalog geschätzten Modellgröße. Androids kritische Memory-Pressure-Meldungen brechen die laufende Suite ab. Das ist eine Schutzmaßnahme, aber keine Garantie gegen jede mögliche systemweite Speichersituation.
- Hersteller-NPUs sind nicht enthalten; Details und Voraussetzungen stehen unter [docs/npu-backend.md](docs/npu-backend.md).
- Der aktuelle Hugging-Face-Downloadpfad hat einen gemeldeten Fehler auf der installierten Version und ist bis zur Diagnose und einem echten erfolgreichen Gerätetest nicht als abgenommen zu betrachten.

## Device Acceptance (Gate für myDocAnizer-Integration)

Die Integration in `myDocAnizer-Mobile` ist erst freigegeben, wenn alle fünf Kriterien auf dem tatsächlichen Zielgerät erfolgreich geprüft und hier mit echtem Datum sowie Gerätebezeichnung eingetragen wurden.

| Abnahmekriterium | Ergebnis | Datum | Gerät |
|---|:---:|---|---|
| Modellinitialisierung mit einem lokalen GGUF-Modell erfolgreich |  |  |  |
| SuiteMode.FULL: alle sieben Workflows erfolgreich durchgelaufen |  |  |  |
| Abort-/Timeout-Pfad: Inference-Prozess beendet, erneute Verbindung und Modell-Reload erfolgreich |  |  |  |
| Zehn kurze Inferenzläufe hintereinander ohne Exception oder Prozessabsturz |  |  |  |
| Speicherdruck: Modell-Load wird bei verfügbarem RAM unter 1,5× Modellbedarf sauber abgewiesen; kritischer Memory-Pressure-Abbruch beendet die Suite |  |  |  |

Ein leeres Ergebnisfeld bedeutet **nicht getestet**. Häkchen, Datum und Gerät dürfen erst nach einem realen Lauf auf dem Zielgerät ergänzt werden. Die Tabelle wird nicht durch CI-Ergebnisse ersetzt.

## Integration Notes

- **`InferenceClient` ist die stabile API-Grenze** für Verbindung, Binder-Aufrufe und den harten Timeout-/Abort-Pfad zur isolierten `:inference`-Service-Instanz. Neue Integrationen sollen nicht direkt `LlamaNativeBridge` oder JNI aufrufen.
- **`TimeoutConfig.MOBILE` ist der Standardwert** für `SuiteRequest` und mobile Testläufe. `SuiteMode.FULL` nutzt in der aktuellen Suite-Implementierung bewusst das Legacy-Zeitprofil für längere Reproduktionsläufe.
- **`SuiteMode.QUICK`** ist für den Alltags-Smoke-Test gedacht: drei kurze Workflows mit Backend-Präferenz `AUTO`.
- **`SuiteMode.FULL`** ist für reproduzierbare Vergleichsläufe gedacht und enthält die sieben definierten Workflows mit der zugehörigen Profilmatrix sowie dem vorgeschalteten Quick-Check.
- CI prüft Build, JVM-/Robolectric-Tests und die Kompilierung der Instrumentationstests. **CI grün ≠ geräteverifiziert** und ersetzt weder Modell-Download noch Inferenz, Abort, Wiederholung oder Speicherdruck auf dem Zielgerät.

## Hugging Face: Qwen-Modellmatrix

Die Modellverwaltung bietet vier direkt auswählbare GGUF-Dateien aus den offiziellen Hugging-Face-Repositories von Qwen:

| Parameter | Quantisierung | Datei |
|---|---|---|
| 0.5B | Q4_0 | `qwen2.5-0.5b-instruct-q4_0.gguf` |
| 0.5B | Q4_K_M | `qwen2.5-0.5b-instruct-q4_k_m.gguf` |
| 1.5B | Q4_0 | `qwen2.5-1.5b-instruct-q4_0.gguf` |
| 1.5B | Q4_K_M | `qwen2.5-1.5b-instruct-q4_k_m.gguf` |

Downloads laufen auf ausdrücklichen Tastendruck in den dauerhaften App-internen Ordner `filesDir/models`. Es gibt keine zweite vollständige Kopie als `active_model.gguf`. Unterbrochene Downloads werden über eine `.part`-Datei fortgesetzt. Vor der Freigabe werden GGUF-Header, Tensoranzahl und SHA-256 gegen den im Modellkatalog hinterlegten Hugging-Face-Wert geprüft. Die Inferenz danach erfolgt vollständig lokal.

### Automatische Testreihe

- `SuiteMode.QUICK` führt für die ausgewählten Modelle genau drei kurze Workflows (`chat-basic`, `dms-smoke`, `json-strict`) mit Backend-Präferenz `AUTO` aus. Dieser Modus ist der schnelle Alltags-Smoke-Test und kein Vulkan-Preflight.
- `SuiteMode.FULL` führt die vollständige siebenstufige Workflow-Matrix aus. Vorher werden die Modelle geladen und der bestehende Quick-Check ausgeführt; die Full-Matrix wird nur nach bestandenem Preflight freigegeben.
- Die Full-Routine umfasst sieben Workflows und 20 Einzelprofile je Modell (80 Gesamtprofile): Durchsatz (eine CPU-Referenz plus Vulkan), deutsche Instruktion, JSON-Extraktion, fünf Vulkan-Hardwareprofile, zwei Prefill-Längen mit jeweils drei Vulkan-ubatch-Profilen und fünf Vulkan-Samplingprofile.
- CPU-only wird damit nur einmal pro Modell als Referenz gemessen. Die restlichen 76 Full-Profile sowie alle Quick-Checks laufen mit zwingend angefordertem Vulkan-Backend.
- Nach der Testreihe wird das zuvor ausgewählte Modell und dessen Konfiguration wiederhergestellt.

Die Prefill-Längen sind reproduzierbare Näherungswerte, keine vom Modelltokenizer exakt garantierten Tokenzahlen. Ein Vulkan-Backendname allein gilt nicht als Quick-Erfolg: Die Suite kontrolliert zusätzlich das native Modell-Ladelog auf echte GPU-Layer-Zuordnungen.
