# LLM Lab – Mobile Inference Testbench

Entwicklung einer eigenständigen, produktionsnahen Android-Testbench zur lokalen Ausführung und Leistungsanalyse von GGUF-Modellen (speziell Qwen 2.5 1.5B Instruct Q4_K_M) via nativer llama.cpp C++/JNI-Inferenz mit striktem Abbruch- und Diagnosesystem.

## User Review & Critical Decisions

> [!IMPORTANT]
> Folgende Architekturentscheidungen und Benutzervorgaben wurden in Phase 1 bestätigt:

- **Confirmed Decision 1 (UI-Stil):** Technisches Dark-Mode Engineering- und Benchmark-Dashboard mit klaren Status-Pills, Monospace-Metrik-Karten und reduzierter visueller Ablenkung.
- **Confirmed Decision 2 (Modell-Laden):** Dualer Auswahl-Workflow über den Android System-Dateiauswahldialog (Storage Access Framework) sowie Schnellzugriff auf Standard-Verzeichnisse (z. B. Downloads / App External Files).
- **Confirmed Decision 3 (Inferenzprofile):** Bereitstellung von drei umschaltbaren Profilen (`Balanced`: n_ubatch=8, 4 Threads; `Power-Saver`: n_ubatch=4, 2 Threads; `Max-Throughput`: n_ubatch=16, alle Performance-Kerne) sowie freier manueller Parameterkonfiguration (n_ctx, n_batch, n_ubatch, n_threads).
- **Core Principle:** Keine simulierten Antworten, keine gefälschten Performance-Daten. Jede Kennzahl (TTFT, Decode-Rate, Speichernutzung) stammt aus echten nativen Messungen.

---

## 1. Overview & Core Concept

- **What It Does:** Eine hochgradig isolierte, native Android-Applikation zur Ausführung und Validierung quantisierter GGUF-Large-Language-Models direkt auf dem Zielgerät (arm64-v8a). Sie ermöglicht das Laden von GGUF-Dateien, interaktive Chats, automatisierte Labor-Prompts (Extraktion, JSON, Halluzinationstests), Last- und Stresstests (20 Iterationen) und die Erfassung technischer Diagnosemetriken.
- **Target Audience / Persona:** Android Systems Engineers, KI-Entwickler und Performance-Tester, die lokale On-Device-Inferenz auf spezifischer Smartphone-Hardware evaluieren und für den späteren Einsatz in `myDocAnizer-Mobile` absichern.
- **Key Value:** Eindeutiger, empirischer Nachweis, ob und unter welchen Parametern (insbesondere `n_ubatch` und Thread-Zahlen) lokale Inferenz auf dem Ziel-Smartphone stabil, ohne Deadlocks und mit zuverlässigem Abbruch funktioniert.

---

## 2. User Experience & Visual Design

### Key User Flows
1. **Modell-Setup:** App-Start -> Navigationsbereich "Modell" -> Dateiauswahl öffnen (oder Download-Shortcut) -> GGUF-Header-Validierung (Magic `GGUF`, Versions-Check, Tensor-Info) -> Profil wählen -> "Initialisieren" -> Progress-Ring mit Phasenanzeige -> Modell einsatzbereit.
2. **Interaktiver Inferenz-Test:** Wechsel zum "Chat"-Tab -> Texteingabe -> "Senden" -> Sofortige Anzeige von Token-Stream, Prompt-Eval-Dauer, Generation-Speed (Tokens/s) -> Optionaler "Abbrechen"-Button bricht Inferenz innerhalb von Millisekunden nativ ab.
3. **Prompt-Testlabor:** Tab "Prompt-Labor" -> Auswahl aus 7 standardisierten Testfällen (Kurztest OK, DMS-Erklärung, JSON-Extraktion, Dokumentenanalyse, Halluzinationstest, Long-Context, 10x-Wiederholung) -> Automatisierter Durchlauf mit Metrik-Tabelle.
4. **Stresstest & Diagnose:** Tab "Benchmark" -> 20x Lasttest mit automatischer Notabschaltung bei Fehlern -> Tab "Diagnose" für Hardware-Telemetrie (Heap, RAM, Thermal-Status) -> Export als TXT und JSON.

### Visual Identity & Theme
- **Aesthetic Direction:** Technisches, hochpräzises Engineering-Dashboard ("Dark Industrial / Sci-Fi Telemetry").
- **Color Palette:**
  - Background: Tiefes Obsidian-Schwarz (`#0A0E14`) und Graphit (`#121820`)
  - Primary Accent: Neon-Cyan (`#00E5FF`) für Inferenz-Status und aktive Aktionen
  - Secondary Accent: Signal-Bernstein (`#FFB300`) für Ladephasen und Warnungen
  - Status Success: Matrix-Grün (`#00E676`)
  - Status Abort / Error: Laser-Rot (`#FF1744`)
  - Surface & Borders: Dunkelgraue Konturen (`#222D3D`) mit subtilen Tonal-Elevationen
- **Typography & Hierarchy:**
  - Header: Fette, moderne Grotesk-Typografie für Sektionstitel
  - Metriken & Logs: Robuste Monospace-Schrift (`FontFamily.Monospace`) für Token-Zahlen, Latenzen (ms), Hex-Signaturen und Speicherwerte.
- **Component Styling & Layout:**
  - Metrik-Kacheln mit zweizeiligem Aufbau (Großer Messwert + kleine technische Einheit)
  - Echtzeit-Pills mit pulsierender Statusanzeige (Idle, Evaluating, Generating, Aborting, Ready)
  - Feste Action-Leiste am unteren Bildschirmrand mit großem, gut erreichbarem Abbrechen-Button (Mindestgröße 48dp).

---

## 3. Key Product Decisions & Trade-Offs

### Decision 1: Native Inferenz-Engine & JNI-Architektur
- **Chosen Approach:** Einbindung einer C++ JNI-Schicht mit `llama.cpp` Core Engine (spezifischer dokumentierter Build/Commit). Die JNI-Brücke kapselt Initialisierung, Tokenisierung, Context-Erstellung, Batch/Micro-Batch-Decodes und Sampling ab.
- **Why:** Reines C++ auf arm64-v8a mit NDK ist der performanteste und ressourceneffizienteste Weg für GGUF-Inferenz ohne Drittanbieter-Cloud.
- **Alternatives Considered:** ONNX Runtime / MediaPipe GenAI (unterstützen nicht das exakte GGUF Q4_K_M Format mit flexibler `n_ubatch`-Microbatch-Justierung).

### Decision 2: 4-stufiges Abbruch- und Timeout-System
- **Chosen Approach:**
  1. Kotlin Coroutine Cancellation (`Job.cancel()`).
  2. Thread-sicheres atomares Flag in JNI (`std::atomic<bool> abort_requested`).
  3. `llama.cpp` Abort-Callback (`llama_decode` Prüfpunkt bei jedem Token).
  4. Harter Watchdog-Timer (30s Kurztest, 90s Inferenz, 120s Init), der bei Timeout automatisch das Flag setzt.
- **Why:** Verhindert das Einfrieren der UI oder dauerhafte Mutex-Deadlocks auf Systemebene.

### Decision 3: Konfigurierbare Profile & Micro-Batching Matrix
- **Chosen Approach:** Direkter Vergleich von `n_ubatch` (1, 4, 8, 16, 32) und Thread-Anzahlen in wiederholbaren Benchmarks.
- **Why:** Dies adressiert direkt die Inferenzprobleme auf dem Zielgerät und macht Leistungsengpässe quantifizierbar.

### Decision 4: Lokale Diagnose-Persistenz & Export
- **Chosen Approach:** Room Database zur Protokollierung aller Testläufe, Fehlermeldungen und Systemtelemetrie; Export über Jetpack ShareSheet & FileProvider als strukturierte `.json` und `.txt` Dateien.
- **Why:** Gewährleistet, dass Diagnosedaten auch nach App-Restarts oder Crash-Analysen unversehrt zur Verfügung stehen.

---

## 4. Technical Architecture & Data Strategy

### Architecture & Component Diagram

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Jetpack Compose UI                              │
│ ┌──────────────┬──────────────┬──────────────┬──────────────┬────────┐ │
│ │ ModelScreen  │  ChatScreen  │  LabScreen   │ BenchScreen  │DiagTab │ │
│ └──────┬───────┴──────┬───────┴──────┬───────┴──────┬───────┴────┬───┘ │
└────────┼──────────────┼──────────────┼──────────────┼────────────┼─────┘
         │              │              │              │            │
┌────────▼──────────────▼──────────────▼──────────────▼────────────▼─────┐
│                 LLMTestbenchViewModel (MVVM StateFlow)                 │
│   - UI State, Model Status, Progress, Streaming Tokens, Metriken       │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
┌──────────────────────────────────▼─────────────────────────────────────┐
│                       InferenceManager Service                         │
│   - Watchdog Timer (120s / 90s / 30s)                                  │
│   - Asynchrone Coroutine Dispatcher (Dispatchers.Default / IO)         │
│   - AbortController (Atomic Cancellation)                              │
└──────────────────┬───────────────────────────────┬─────────────────────┘
                   │                               │
┌──────────────────▼───────────┐   ┌───────────────▼─────────────────────┐
│   Diagnostic & Room Repo     │   │     NativeBridge (JNI / C++)        │
│   - TestRunEntity / Dao      │   │  com.example.llmlab.NativeLib       │
│   - System Telemetry Collector│   │  - initModel(path, params)         │
│   - TXT / JSON Export Engine │   │  - tokenize / evalPrompt            │
└──────────────────────────────┘   │  - generateTokenStream()            │
                                   │  - requestAbort()                   │
                                   │  - releaseModel()                   │
                                   └───────────────┬─────────────────────┘
                                                   │
                                   ┌───────────────▼─────────────────────┐
                                   │       llama.cpp Core Engine         │
                                   │   - GGUF Parser (Tensor & Header)   │
                                   │   - KV-Cache & llama_context        │
                                   │   - llama_batch (n_batch, n_ubatch) │
                                   │   - Sampler Chain & Abort Callback  │
                                   └─────────────────────────────────────┘
```

### Data Model & State
- **ModelConfig:** `n_ctx: Int`, `n_batch: Int`, `n_ubatch: Int`, `n_threads: Int`, `temperature: Float`
- **ModelStatus:** `Unloaded`, `SelectingFile`, `Initializing(progress, phase)`, `Ready(info)`, `InferenceRunning(stage)`, `Aborting`, `Error(message)`
- **InferenceMetrics:** `promptTokens`, `generatedTokens`, `promptEvalDurationMs`, `generationDurationMs`, `tokensPerSecond`, `totalDurationMs`, `peakNativeHeapMb`, `finishReason`
- **TestRunEntity:** Speichert jeden Testlauf aus dem Prompt-Labor oder Stresstest in Room mit Zeitstempel, Prompt, Output, Fehler und Metriken.

### Phasenweiser Umsetzungsplan
- **Phase 1: Projektbasis & UI-Skelett:** Navigation, Themes (Obsidian/Neon-Cyan), 6 Hauptbereiche, ViewModels, Room-Datenbank, System-Telemetrie.
- **Phase 2: JNI-Bridge & llama.cpp Integration:** C++ Wrapper mit GGUF-Validierung, Threading-Konfiguration, sauberem JNI-Aufruf und Modell-Lade-Pipeline.
- **Phase 3: Abort- & Diagnose-Engine:** Token-Level Cancellation Callback, Watchdogs, getrennte Messung von TTFT und Decode-Rate.
- **Phase 4: Prompt-Testlabor:** Implementierung der 7 vordefinierten Testfälle mit automatisierter Evaluierung.
- **Phase 5: Stresstest & Export:** 20-facher Testlauf mit p50/p95 Latenzberechnung, TXT/JSON Export.
- **Phase 6: Verifizierung & Test-Gate:** Robolectric-Tests, Clean Build und Dokumentation.
