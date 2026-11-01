# LLM Engine – Integrationsprototyp für myDocAnizer

Dieses Repository entwickelt und verifiziert die lokale GGUF-Inferenz-Engine, die später in myDocAnizer-Mobile integriert wird. Es ist **kein eigenständiges Endnutzerprodukt**. Die Android-Oberfläche ist nur ein schlanker Debug- und Funktionscontainer, damit Engine, Modell-Download und Inferenz auf realer Hardware geprüft werden können.

## Produktziel und Prioritäten

Die Engine ist das Produkt. Die Debug-Oberfläche ist austauschbar und darf die spätere myDocAnizer-Bedienoberfläche nicht vorwegnehmen.

1. **Korrektheit:** zuverlässiges Modellladen, echte Token-Ausgabe, saubere Fehlerzustände, kontrollierter Abbruch und keine erfundenen Messwerte.
2. **Performance:** Prefill-Latenz, First-Token-Latenz, Generierungsdurchsatz, Speicherbedarf und thermisches Verhalten auf dem Zielgerät messen und verbessern.
3. **Stabilität:** keine ANRs, Deadlocks, Speicherüberläufe oder hängenbleibenden Inferenzprozesse; Timeouts müssen den nativen Aufruf zuverlässig beenden.
4. **Integration:** InferenceClient ist die API-Grenze zum isolierten Inferenzprozess. Die Engine darf nicht von Compose-Screens oder UI-Navigation abhängig sein.

## Schlanker Debug-Container

Die normale Oberfläche hat nur zwei Arbeitsbereiche: **Modell** (Modell auswählen/herunterladen/laden) und **Inferenz** (manuelle Funktionsprobe). Entwicklungstests, Benchmarks, Telemetrie, Fehlerhistorie und Exporte befinden sich zusammen in einer einzigen Entwicklungsansicht, die nur im Debug-Build über das Menü erreichbar ist. Erweiterte Laufzeitparameter sind ebenfalls nur dort zugänglich.

Prompt-Tests, automatisierte Suites und Benchmarks sind **Entwicklungs- und Abnahmewerkzeuge**, keine Produktfunktionen. Sie werden nicht auf mehrere App-Bereiche verteilt. Alle Mess- und Testergebnisse liegen in derselben lokalen Room-Historie und werden zentral angezeigt/exportiert. Chat-Prompts und -Antworten werden in dieser Historie weiterhin redigiert gespeichert; nur technische Messwerte und Fehlerdaten bleiben erhalten.

## Technische Architektur

- Android, Kotlin, Jetpack Compose, Room und C++/JNI.
- Native Engine: llama.cpp, fest gepinnt als Git-Submodul unter third_party/llama.cpp auf Commit 78feec062d50ec9e8940244cac918ac4480b60cb (API-Version 0.8.0-dev).
- Native Inferenz läuft im separaten Android-Prozess :inference. InferenceClient kapselt Verbindung, Binder-Aufrufe und harten Timeout-/Abort-Pfad. Neue Integrationen sollen nicht direkt LlamaNativeBridge oder JNI aufrufen.
- Modell-Dateien liegen dauerhaft im internen App-Speicher unter filesDir/models; sie werden nicht zusätzlich als active_model.gguf dupliziert. Modelle sind nicht Teil der APK.
- Debug-Package-ID: de.mrlongnight.llmlab; minSdk=29, targetSdk=36, ABIs arm64-v8a und x86_64. Die Package-ID macht den Debug-Container nicht zu einem eigenständigen Produkt.
- Keine Firebase-, Gemini-, Cloud-Inferenz-, Login- oder externen KI-API-Abhängigkeiten. Internet wird ausschließlich für vom Nutzer gestartete HTTPS-Modell-Downloads von Hugging Face verwendet; die Inferenz läuft lokal. Android-App-Backup ist deaktiviert.

Die Engine darf „Modell bereit“ erst melden, wenn Modell und llama.cpp-Kontext erfolgreich initialisiert wurden. Bei fehlender nativer Bibliothek gibt es keinen JVM-Ersatzpfad. AUTO verwendet derzeit CPU; Vulkan wird ausdrücklich über ACCELERATOR_REQUIRED angefordert.

## Backends und aktueller Stand

- **CPU – stabiler Referenzpfad:** AUTO verwendet derzeit CPU.
- **Vulkan – experimentell:** generisches GPU-Backend. Prozessisolation ermöglicht den harten Timeout-/Abort-Pfad, beweist aber weder kooperativen Abbruch innerhalb eines Vulkan-Kernels noch die Fehlerfreiheit des Backends.
- **NPU – nicht enthalten:** Qualcomm-QNN/Hexagon, MediaTek-NeuroPilot und andere Hersteller-NPU-Backends sind derzeit nicht integriert. Details: docs/npu-backend.md.
- **Hugging Face Download:** neue Downloads werden direkt als vom Nutzer gestarteter dataSync-Foreground-Service ausgeführt, nicht mehr über den JobScheduler-Netzwerk-Constraint. Der Pfad ist erst nach erfolgreichem Download auf dem Zielgerät abgenommen.

## Build und Entwicklungsprüfungen

Voraussetzungen: JDK 21, Android SDK, Android NDK 30.0.16248370, CMake 3.31.6, Git-Submodule-Unterstützung und für den Vulkan-Host-Build glslc, libvulkan-dev sowie spirv-headers.

```bash
git clone --recurse-submodules https://github.com/MrLongNight/LLM-Lab.git
cd LLM-Lab
git submodule update --init --recursive
chmod +x gradlew
./gradlew assembleDebug --no-daemon
./gradlew testDebugUnitTest --no-daemon
./gradlew assembleDebugAndroidTest --no-daemon
# Nur auf einem verbundenen Android-Zielgerät:
./gradlew connectedDebugAndroidTest
```

GitHub Actions baut den Debug-Container, führt JVM-/Robolectric-Tests aus, kompiliert Instrumentationstests und prüft die nativen Bibliotheken im APK. **CI grün ist kein Geräteabnahmetest.** Die Tests auf dem tatsächlichen Zielgerät bleiben ein separates Gate.

## Relevante Inferenzmesswerte

Die native Schicht liefert reale Werte für Tokenisierung, Prompt-Tokenanzahl, Prefill-Dauer, First-Token-Latenz, generierte Tokens, Generierungsdauer, Decode-Aufrufe und Gesamtdauer. Vor Abschluss der Prefill-Messung wird llama_synchronize() aufgerufen, damit asynchron eingereichte Vulkan-Arbeit nicht fälschlich als Wartezeit nach dem Prefill erscheint. Gerätemesswerte, die Android nicht zuverlässig bereitstellt, müssen als nicht verfügbar behandelt werden. Der native Allocator-Heap umfasst nicht zwingend alle per mmap eingeblendeten Modellgewichte.

## Geräteabnahme vor der Integration

Die Integration in myDocAnizer-Mobile wird erst freigegeben, wenn diese Punkte auf dem tatsächlichen Zielgerät erfolgreich geprüft und mit Datum/Gerät dokumentiert sind:

| Kriterium | Ergebnis | Datum | Gerät |
|---|:---:|---|---|
| GGUF-Modell laden und native Inferenz starten |  |  |  |
| Download von Hugging Face einschließlich SHA-256-Prüfung |  |  |  |
| Funktions-/Extraktionstests erfolgreich |  |  |  |
| Abort/Timeout beendet Inferenzprozess; Reconnect und Reload funktionieren |  |  |  |
| Zehn kurze Inferenzläufe ohne Exception oder Prozessabsturz |  |  |  |
| Speicherdruck wird sauber abgefangen; kein ANR/OOM |  |  |  |

Leere Felder bedeuten **nicht getestet**. CI-Ergebnisse ersetzen keine echten Geräteläufe.

## Qwen-Modellkatalog

| Parameter | Quantisierung | Datei |
|---|---|---|
| 0.5B | Q4_0 | qwen2.5-0.5b-instruct-q4_0.gguf |
| 0.5B | Q4_K_M | qwen2.5-0.5b-instruct-q4_k_m.gguf |
| 1.5B | Q4_0 | qwen2.5-1.5b-instruct-q4_0.gguf |
| 1.5B | Q4_K_M | qwen2.5-1.5b-instruct-q4_k_m.gguf |

Downloads laufen auf ausdrücklichen Tastendruck in filesDir/models. Unterbrochene Downloads werden über .part fortgesetzt. Vor der Freigabe werden GGUF-Header, Tensoranzahl und SHA-256 gegen den im Modellkatalog hinterlegten Wert geprüft.
