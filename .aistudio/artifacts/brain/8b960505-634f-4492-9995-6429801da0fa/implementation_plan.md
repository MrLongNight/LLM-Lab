# LLM Engine – Projekt- und Integrationsplan

## Zweck

Dieses Repository entwickelt die lokale LLM-Engine für die spätere Integration in myDocAnizer-Mobile. Es ist ausdrücklich **kein eigenständiges Endnutzerprodukt**. Die Android-App ist ein minimaler Debug-Container, um die Engine auf realer Hardware auszuführen und zu verifizieren.

## Nicht verhandelbare Produktentscheidungen

- Die Engine steht im Mittelpunkt: Korrektheit, Performance, Speicherverhalten, Stabilität und reproduzierbare Ergebnisse haben Vorrang vor UI-Funktionen.
- Die normale Debug-Oberfläche bleibt minimal: Modellverwaltung und eine einfache Inferenzprobe.
- Entwicklungstests, Benchmarks, Telemetrie, Fehlerhistorie und Exporte liegen in einer einzigen Debug-only-Entwicklungsansicht.
- Es gibt keine getrennten Prompt-Labor-, Benchmark- und Diagnosebereiche und keine mehrfachen Statistikauswertungen.
- Eine technische Messung wird einmal gespeichert. Alle Ansichten und Exporte lesen dieselbe Room-Historie.
- Chat-Inhalte bleiben in der Historie redigiert; technische Messwerte und Fehlerdetails dürfen gespeichert werden.
- Compose darf keine Engine-Logik besitzen. InferenceClient bleibt die Integrationsgrenze zur isolierten Inferenz-Service-Instanz.
- Vulkan ist experimentell, CPU bleibt der stabile Referenzpfad. Keine Backend-Funktion wird als stabil bezeichnet, bevor sie auf dem Zielgerät verifiziert wurde.

## Laufzeitarchitektur

1. **Modellquelle:** lokaler GGUF-Import oder explizit gestarteter Hugging-Face-Download.
2. **Validierung:** GGUF-Signatur/Metadaten, Dateigröße und SHA-256 vor Freigabe.
3. **Inference API:** InferenceClient kapselt Lebenszyklus, Verbindung, Request, Stream, Abbruch und Fehlerzustände.
4. **Prozessisolation:** native llama.cpp-Aufrufe laufen im Prozess :inference; Timeout/Abort muss die Engine zuverlässig freigeben und erneutes Laden erlauben.
5. **Native Engine:** llama.cpp mit klaren Konfigurationsgrenzen, CPU-Referenz und separat verifiziertem Vulkan-Pfad.
6. **Messdaten:** reale native Messwerte plus klar gekennzeichnete Android-Telemetrie; keine Schätzwerte als Messwerte ausgeben.

## Priorisierte Arbeitsreihenfolge

### P0 – Funktion und Stabilität
- Hugging-Face-Download vollständig funktionsfähig machen (Foreground-Service, Resume, SHA-256).
- Modellladen und Entladen ohne ANR/OOM; Speicherbedarf vor Laden plausibilisieren.
- Native Inferenz, Streaming, Fehlerzustände, Cancel und Timeout zuverlässig machen.
- Nach Abort/Timeout muss Reconnect und Model-Reload möglich sein.
- Kein stiller CPU-Fallback, wenn Vulkan explizit verlangt wurde.

### P1 – Performance
- Prefill, First Token und Decode separat messen.
- Threading, n_batch, n_ubatch, Context-Größe und Backend auf dem Zielgerät systematisch optimieren.
- Modell-/KV-Cache-Speicher, Heap, Prozess-PSS und thermisches Verhalten beobachten.
- Keine Profil- oder Durchsatzoptimierung ohne reproduzierbaren Gerätevergleich.

### P2 – Entwicklungsqualitätssicherung
- Eine zentrale Test-Suite mit schnellen und vollständigen Modi.
- Funktions-, Extraktions-, JSON-, Stabilitäts-, Abort-/Timeout- und Speicherdrucktests.
- Eine zentrale Testhistorie und ein Diagnoseexport.
- Geräteabnahme klar von CI-Kompilierung unterscheiden.

### P3 – Integration
- Engine von Debug-UI entkoppeln und stabile API dokumentieren.
- Native Build-Konfiguration, Assets, Lifecycle und Fehlersemantik für die Einbindung in myDocAnizer-Mobile vorbereiten.
- Keine Standalone-Produktfeatures, Mehrfachnavigation oder Endnutzer-Dashboards hinzufügen.

## Debug-Container

- Sichtbar: Modellverwaltung und manuelle Inferenz.
- Nur Debug-Build: ein zentraler Zugang zu Qualitätstests, Benchmark, Telemetrie, Fehlerhistorie, Export und erweiterten Engine-Parametern.
- Keine Test- oder Statistikoberflächen im normalen Produktfluss.
- Keine separaten Tabs für Labor, Benchmark, Diagnose oder Konfiguration.

## Abnahme

- CI muss APK bauen, JVM-/Robolectric-Tests bestehen, Android-Test-APK kompilieren und native Backend-Bibliotheken prüfen.
- Geräteabnahme muss auf dem tatsächlichen Android-Zielgerät stattfinden; ein grüner CI-Lauf ist kein Gerätebeleg.
- Integration in myDocAnizer erst nach erfolgreicher Modellinitialisierung, Download-/Hash-Prüfung, Funktions-/Extraktionstests, Abbruch-/Reload-Prüfung, Wiederholungsläufen und Speicherdruckprüfung.
