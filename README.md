# LLM Lab – Mobile Inference Testbench

Eigenständige Android-Test-App zur Untersuchung echter lokaler GGUF-Inferenz auf dem späteren myDocAnizer-Zielgerät. Dieses Repository ist unabhängig von `myDocAnizer-Mobile`; Änderungen hier verändern das Hauptprojekt nicht.

## Architektur und harte Grenzen

- Android, Kotlin, Jetpack Compose, Room und C++/JNI.
- Native Engine: `llama.cpp`, fest gepinnt als Git-Submodul unter `third_party/llama.cpp` auf Commit `78feec062d50ec9e8940244cac918ac4480b60cb` (API-Version `0.8.0-dev`).
- CPU ist immer als sicherer Fallback einkompiliert. Vulkan wird zusätzlich als generisches GPU-Backend gebaut; ob es auf einem Gerät initialisierbar ist, wird zur Laufzeit über die llama.cpp-Registry geprüft. CUDA/Metal sind auf Android nicht aktiviert. Hersteller-NPUs (z. B. Qualcomm QNN/Hexagon oder MediaTek NeuroPilot) benötigen jeweils eigene SDK-/Backend-Adapter und sind in diesem Build noch nicht enthalten.
- GGUF-Modell wird über den Android-Dateiauswahldialog ausgewählt und vor dem nativen Laden in den internen App-Speicher kopiert. Das Modell ist nicht in der APK enthalten.
- Eigenständige Package-ID: `de.mrlongnight.llmlab`; `minSdk=29` (Android 10), `targetSdk=36`, ABIs `arm64-v8a` und `x86_64`.
- Keine Firebase-, Gemini-, Cloud-, Login- oder externen KI-API-Abhängigkeiten. Das Manifest fordert keine Internet-Berechtigung an und Android-App-Backup ist deaktiviert.

Die App darf „Modell bereit“ erst anzeigen, wenn `llama_load_model_from_file` und `llama_new_context_with_model` erfolgreich zurückgekehrt sind. Bei fehlender nativer Bibliothek gibt es keinen JVM-Ersatzpfad. Die Backend-Registry meldet pro Backend Initialisierbarkeit und Abbruchfähigkeit. In llama.cpp b3600 ist `abort_callback` laut Header nur bei CPU-Ausführung unterstützt: AUTO bevorzugt daher den sicher abbrechbaren CPU-Pfad; GPU-Offload ist ein ausdrücklich experimenteller Testmodus.

## Build und Tests

Voraussetzungen: JDK 21, Android SDK, Android NDK `30.0.16248370`, CMake `3.31.6` und Git-Submodule-Unterstützung. Für den Vulkan-Host-Build unter Ubuntu/Debian werden außerdem `glslc`, `libvulkan-dev` und `spirv-headers` benötigt.

```bash
git clone --recurse-submodules https://github.com/MrLongNight/LLM-Lab.git
cd LLM-Lab
git submodule update --init --recursive
chmod +x gradlew
./gradlew assembleDebug --no-daemon
./gradlew testDebugUnitTest --no-daemon
```

Die GitHub-Actions-Pipeline führt dieselben beiden Gradle-Aufgaben aus und veröffentlicht anschließend das Artefakt `LLM-Lab-Standalone-Debug-APK`. Die Tests umfassen JVM-/Robolectric-Tests; sie ersetzen keine Tests auf dem tatsächlichen Android-Zielgerät.

## Installation und erster Modelltest

1. Die APK `LLM-Lab-Standalone-debug.apk` aus dem Actions-Artefakt herunterladen und auf dem Android-Zielgerät installieren.
2. Die Datei `qwen2.5-1.5b-instruct-q4_k_m.gguf` (ca. 1,12 GB) lokal auf dem Gerät verfügbar machen.
3. In LLM Lab den Bereich **Modell** öffnen und die GGUF-Datei über den System-Dateiauswahldialog auswählen.
4. Die angezeigte Datei-/GGUF-Information prüfen und **Initialisieren** starten.
5. Erst bei Status **Modell bereit** den Kurztest im Prompt-Labor ausführen.
6. Danach Chat, JSON-/Extraktionstests, Abbruchtests und zuletzt den 20-fachen Benchmark durchführen.
7. Bei Fehlern TXT- und JSON-Diagnoseexport erzeugen. Chat-Prompt und Chat-Antwort werden in der lokalen Testhistorie redigiert gespeichert.

## Inferenzmesswerte

Die JNI-Schicht liefert tatsächliche Werte aus `llama.cpp`: Prompt-Tokenisierung, Prompt-Tokenanzahl, Prompt-Evaluierungsdauer, Prompt-Decode-Aufrufe, First-Token-Latenz, generierte Tokens, Generierungsdauer, Generierungs-Decode-Aufrufe, Gesamtdauer und Tokens/s. Es werden keine aus Prompt-Länge geschätzten Tokenzahlen oder simulierten Antworttexte als Inferenz ausgegeben.

Der Prompt-Callback meldet Fortschritt während der Prompt-Auswertung. Während der eigentlichen Token-Generierung wird der echte Token-Stream angezeigt. Gerätemesswerte, die Android nicht zuverlässig bereitstellt, müssen als nicht verfügbar behandelt werden. Der native Allocator-Heap umfasst nicht zwingend alle per `mmap` eingeblendeten Modellgewichte.

## Testprofile

`n_ubatch` ist auf `1`, `4`, `8`, `16` und `32` einstellbar. Zusätzlich sind `n_ctx`, `n_batch`, `n_threads`, Temperatur, maximale Tokens und die Zeitlimits konfigurierbar. Die Standardprofile sind Balanced, Power Saver und Max Throughput.

Das Prompt-Labor prüft nicht nur, ob die native Engine beendet wurde, sondern wertet die tatsächliche Antwort aus: exaktes `OK`, zweisätzige deutsche Erklärung, JSON-Felder mit expliziten `null`-Werten, Briefextraktion inklusive Datum/Betrag, Halluzinationstest und drei Themenpunkte im Langtext. Der 10x-Test dokumentiert die einzelnen Antworten und deren Varianz.

Der Standard-Samplingpfad verwendet Top-k=40, Top-p=0,90, Temperatur 0,25 und einen Wiederholungs-Guard; die Vokabularpuffer werden pro Inferenz wiederverwendet. Native llama.cpp-Kernel werden auch im Debug-APK mit `-O3` kompiliert.

Der Stresstest führt bis zu 20 echte Inferenzläufe aus und wertet nur exakt passende `OK`-Antworten als inhaltlich erfolgreich. Timeout, nativer Fehler oder eine falsche Antwort beenden den Lauf automatisch.

## Abbruch- und Timeout-Verhalten

Die Kotlin-Schicht sendet bei manuellem Abbruch oder Timeout ein natives Abbruchsignal; `llama.cpp` erhält einen Abort-Callback. Vorgesehene Timeouts: Modellinitialisierung 120 s, Kurztest 30 s, normale Inferenz 90 s, Stresstest je Lauf 120 s.

Wichtige technische Grenze: Ein nativer C/C++-Aufruf kann von Kotlin nicht sicher zwangsweise beendet werden. Reagiert die native Operation 10 Sekunden nach dem Abbruchsignal weiterhin nicht, markiert die App die Engine als nicht mehr antwortfähig, blockiert weitere Engine-Operationen und fordert einen Neustart der App. Das ist ein diagnostizierter Fehlerzustand, kein erfolgreicher Abbruch. Ob die reale Zielgeräte-/llama.cpp-Kombination innerhalb des Grenzwerts reagiert, muss auf dem Smartphone geprüft werden.

## Abnahmestatus

Ein grüner GitHub-Actions-Lauf beweist nur, dass die Android-App und die native Bibliothek kompiliert werden und die vorhandenen JVM-/Robolectric-Tests bestehen. Er beweist ausdrücklich **nicht**, dass das Qwen-GGUF-Modell auf dem Zielgerät lädt oder dort echte Tokens generiert.

Vor Übergabe an `myDocAnizer-Mobile` sind auf dem tatsächlichen Smartphone mindestens folgende Punkte zu protokollieren:

- [ ] Modellinitialisierung mit dem angegebenen Qwen-GGUF erfolgreich, Status erst danach `Modell bereit`.
- [ ] Kurztest liefert tatsächlich exakt `OK`.
- [ ] Der einfache Halloween-Wissenstest nennt korrekt den 31. Oktober und antwortet in zwei knappen Sätzen.
- [ ] Deutscher Test, JSON-Test und Extraktion bestehen die inhaltliche Validierung.
- [ ] Tokenisierung, Prompt-Eval und Generierung zeigen plausible native Messwerte.
- [ ] Manueller Abbruch und jede Timeout-Klasse kehren innerhalb der festgelegten Grenzen zurück.
- [ ] Nach Abbruch kann unmittelbar eine neue Inferenz gestartet werden.
- [ ] 20 aufeinanderfolgende Tests laufen ohne Hänger; Erfolgs-/Fehler-/Timeout-Zahlen, Mittelwert, p50 und p95 werden dokumentiert.
- [ ] TXT- und JSON-Export sind auf dem Smartphone erzeugt und enthalten Modell-/Engine-Version, Parameter und Testresultate.

Bis diese Zielgerätetests durchgeführt und dokumentiert sind, ist der Prototyp **buildfähig mit integrierter llama.cpp-Engine**, aber noch nicht als auf dem Smartphone funktionsfähig abgenommen.