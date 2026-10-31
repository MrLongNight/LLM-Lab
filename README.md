# LLM Lab – Mobile Inference Testbench

Eigenständige Android-Test-App zur Untersuchung echter lokaler GGUF-Inferenz auf dem späteren myDocAnizer-Zielgerät. Dieses Repository ist unabhängig von `myDocAnizer-Mobile`; Änderungen hier verändern das Hauptprojekt nicht.

## Architektur und harte Grenzen

- Android, Kotlin, Jetpack Compose, Room und C++/JNI.
- Native Engine: `llama.cpp`, fest gepinnt als Git-Submodul unter `third_party/llama.cpp` auf Commit `78feec062d50ec9e8940244cac918ac4480b60cb` (API-Version `0.8.0-dev`).
- CPU ist immer als sicherer Fallback einkompiliert. Vulkan wird zusätzlich als generisches GPU-Backend gebaut; ob es auf einem Gerät initialisierbar ist, wird zur Laufzeit über die llama.cpp-Registry geprüft. CUDA/Metal sind auf Android nicht aktiviert. Hersteller-NPUs (z. B. Qualcomm QNN/Hexagon oder MediaTek NeuroPilot) benötigen jeweils eigene SDK-/Backend-Adapter und sind in diesem Build noch nicht enthalten.
- GGUF-Modell wird über den Android-Dateiauswahldialog ausgewählt und vor dem nativen Laden in den internen App-Speicher kopiert. Das Modell ist nicht in der APK enthalten.
- Eigenständige Package-ID: `de.mrlongnight.llmlab`; `minSdk=29` (Android 10), `targetSdk=36`, ABIs `arm64-v8a` und `x86_64`.
- Keine Firebase-, Gemini-, Cloud-, Login- oder externen KI-API-Abhängigkeiten. Das Manifest fordert keine Internet-Berechtigung an und Android-App-Backup ist deaktiviert.

Die App darf „Modell bereit“ erst anzeigen, wenn `llama_load_model_from_file` und `llama_new_context_with_model` erfolgreich zurückgekehrt sind. Bei fehlender nativer Bibliothek gibt es keinen JVM-Ersatzpfad. Die Backend-Registry meldet pro Backend Initialisierbarkeit und Abbruchfähigkeit. In der gepinnten llama.cpp-Version b11998 wird der sichere AUTO-Pfad auf CPU gehalten; Vulkan-Offload wird nur mit `ACCELERATOR_REQUIRED` erzwungen und bleibt wegen der fehlenden verlässlichen Abbruchgarantie ein experimenteller Testmodus.

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
6. Im Prompt-Labor einen der drei fachlichen Abläufe starten. Jeder vergleicht CPU-Referenz, CPU-Wide-Batch und Vulkan-Offload. Der vierte Ablauf **Hardware-Tuning** führt acht CPU-/Vulkan-Konfigurationen aus (Generation 2/4/6/8 Threads, 8 Prefill-Threads, ubatch 64/128). Der fünfte Ablauf **Sampling-Tuning** vergleicht fünf Varianten, bei denen Temperatur, Top-K und Top-P einzeln verändert werden. Danach optional den 3-fachen Stabilitätscheck ausführen.
7. Bei Fehlern TXT- und JSON-Diagnoseexport erzeugen. Chat-Prompt und Chat-Antwort werden in der lokalen Testhistorie redigiert gespeichert.

## Inferenzmesswerte

Die JNI-Schicht liefert tatsächliche Werte aus `llama.cpp`: Prompt-Tokenisierung, Prompt-Tokenanzahl, Prompt-Evaluierungsdauer, Prompt-Decode-Aufrufe, First-Token-Latenz, generierte Tokens, Generierungsdauer, Generierungs-Decode-Aufrufe, Gesamtdauer sowie getrennten Prefill- und Generierungsdurchsatz. Vor dem Abschluss der Prefill-Messung wird `llama_synchronize()` aufgerufen. Damit wird asynchron eingereichte Vulkan-Arbeit der Prompt-Evaluierung zugerechnet und nicht mehr fälschlich als Wartezeit nach dem Prefill ausgewiesen. Die CPU-Auslastung wird zusätzlich direkt um jeden Inferenzlauf gemessen, statt einen bis zu drei Sekunden alten Telemetrie-Snapshot zu verwenden. Der Diagnoseexport weist außerdem die Zeit zwischen abgeschlossenem Prefill und erstem Token, die Profilvorbereitung sowie den Prozess-PSS inklusive anteilig gemappter Modellseiten aus.

Der Prompt-Callback meldet Fortschritt während der Prompt-Auswertung. Während der eigentlichen Token-Generierung wird der echte Token-Stream angezeigt. Gerätemesswerte, die Android nicht zuverlässig bereitstellt, müssen als nicht verfügbar behandelt werden. Der native Allocator-Heap umfasst nicht zwingend alle per `mmap` eingeblendeten Modellgewichte.

## Testprofile

Für reproduzierbare Vergleiche hält jeder Workflow `n_ctx=2048`, `n_batch=512`, Temperatur `0.05`, Top-k `20` und Top-p `0.8` konstant. Die drei Testprofile variieren Backend, Threads und Micro-Batch: CPU-Referenz (AUTO, 4/8 Threads, `n_ubatch=32`), CPU-Wide-Batch (CPU_ONLY, 4/8 Threads, `n_ubatch=64`) und Vulkan-Offload (ACCELERATOR_REQUIRED, 4/8 Threads, `n_ubatch=64`). Die maximale Tokenzahl ist je Workflow begrenzt.

Das Prompt-Labor bietet fünf automatische Workflows: (1) längere deutsche Antwort für Prefill-/Durchsatzmessung, (2) zweisätzige deutsche DMS-Erklärung, (3) synthetische Rechnungs-Extraktion mit sieben JSON-Feldern einschließlich `iban=null` und `poNumber=null`, (4) Hardware-Tuning mit acht CPU-/Vulkan-Profilen, (5) Sampling-Tuning mit fünf Varianten. Beim Hardware-Tuning werden CPU-Generierung 2/4/6/8 Threads (Prefill konstant 8), CPU-ubatch 64/128 sowie Vulkan 4/8 Threads und ubatch 64/128 verglichen. Beim Sampling-Tuning bleibt das Vulkan-8/8-Profil konstant; Temperatur, Top-K und Top-P werden einzeln gegenüber der Baseline verändert. Die ursprüngliche Modellkonfiguration wird anschließend wiederhergestellt. Bei CPU-zu-CPU-Profilwechseln wird nur der llama_context neu aufgebaut; die mmap-gemappten GGUF-Gewichte bleiben geladen. Nur ein Wechsel der CPU-/Vulkan-Gewichtplatzierung lädt das GGUF erneut.

Sampling-Parameter bleiben innerhalb eines Dreiervergleichs identisch, damit Unterschiede primär auf Backend, Threads und Micro-Batch zurückgeführt werden können. Native llama.cpp-Kernel werden auch im Debug-APK mit `-O3` kompiliert.

Der separate Stabilitätscheck führt drei echte Kurz-Inferenzen aus. Timeout, nativer Fehler oder eine falsche Antwort beenden den Lauf automatisch.

## Abbruch- und Timeout-Verhalten

Die Kotlin-Schicht sendet bei manuellem Abbruch oder Timeout ein natives Abbruchsignal. Der sichere AUTO-/CPU-Pfad nutzt den llama.cpp-Abort-Callback; der Vulkan-Offload-Modus hat in der gepinnten Version keine verlässliche Abbruchgarantie. Timeouts: Modellinitialisierung 120 s, normale Inferenz 120 s, Performance- und DMS-Workflow je Profil 180 s, Extraktion je Profil 240 s, Stabilitätscheck je Lauf 120 s.

Wichtige technische Grenze: Ein nativer C/C++-Aufruf kann von Kotlin nicht sicher zwangsweise beendet werden. Reagiert die native Operation 10 Sekunden nach dem Abbruchsignal weiterhin nicht, markiert die App die Engine als nicht mehr antwortfähig, blockiert weitere Engine-Operationen und fordert einen Neustart der App. Das ist ein diagnostizierter Fehlerzustand, kein erfolgreicher Abbruch. Ob die reale Zielgeräte-/llama.cpp-Kombination innerhalb des Grenzwerts reagiert, muss auf dem Smartphone geprüft werden.

## Abnahmestatus

Ein grüner GitHub-Actions-Lauf beweist nur, dass die Android-App und die native Bibliothek kompiliert werden und die vorhandenen JVM-/Robolectric-Tests bestehen. Er beweist ausdrücklich **nicht**, dass das Qwen-GGUF-Modell auf dem Zielgerät lädt oder dort echte Tokens generiert.

Vor Übergabe an `myDocAnizer-Mobile` sind auf dem tatsächlichen Smartphone mindestens folgende Punkte zu protokollieren:

- [ ] Modellinitialisierung mit dem angegebenen Qwen-GGUF erfolgreich, Status erst danach `Modell bereit`.
- [ ] Alle fünf automatischen Workflows laufen auf dem Smartphone; acht Hardware-Profile und fünf Sampling-Profile werden mit den tatsächlich geladenen Parametern und Messwerten dokumentiert.
- [ ] CPU-Referenz, CPU-Wide-Batch und Vulkan-Offload sind anhand tatsächlicher Ladeprotokolle und nicht nur der Backend-Auswahl verglichen.
- [ ] Deutsche DMS-Antwort und Extraktion erfüllen ihre inhaltlichen Validatoren.
- [ ] Tokenisierung, Prompt-Eval und Generierung zeigen plausible native Messwerte.
- [ ] Manueller Abbruch und jede Timeout-Klasse kehren innerhalb der festgelegten Grenzen zurück.
- [ ] Nach Abbruch kann unmittelbar eine neue Inferenz gestartet werden.
- [ ] Der 3-fache Stabilitätscheck läuft ohne Hänger; Erfolg, Fehler, Timeout, Mittelwert, p50 und p95 werden dokumentiert.
- [ ] TXT- und JSON-Export sind auf dem Smartphone erzeugt und enthalten Modell-/Engine-Version, Parameter und Testresultate.

Bis diese Zielgerätetests durchgeführt und dokumentiert sind, ist der Prototyp **buildfähig mit integrierter llama.cpp-Engine**, aber noch nicht als auf dem Smartphone funktionsfähig abgenommen.