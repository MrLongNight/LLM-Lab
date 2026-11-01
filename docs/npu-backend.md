# NPU-Backend – aktueller Stand

LLM Lab enthält derzeit **kein NPU-Backend**. Die Android-Builds liefern CPU und Vulkan, aber keine herstellerspezifischen NPU-Runtimes.

## Nicht enthalten

- Qualcomm Hexagon / QNN
- MediaTek NeuroPilot
- Samsung Exynos / proprietäre NPU-Schnittstellen
- Andere Hersteller-Runtimes oder delegierte NNAPI-/LiteRT-Pfade

Die bloße Verfügbarkeit einer NPU im Smartphone bedeutet daher nicht, dass llama.cpp sie in dieser App verwendet. Aktuell gibt es dafür weder einen integrierten Adapter noch einen belastbaren Laufzeitnachweis.

## Voraussetzungen für eine spätere Integration

Eine Implementierung benötigt mindestens eine passende, redistributierbare Android-Runtime samt SDK/NDK-Schnittstelle, ein llama.cpp-kompatibles Backend oder einen klar abgegrenzten Adapter, ABI-/Geräteprüfungen, reproduzierbare Performance- und Speicher-Messungen sowie Abbruch-/Timeout-Tests. Lizenz- und Distributionsbedingungen des jeweiligen Herstellers müssen vor Einbindung geprüft werden.

Bis dahin ist CPU der stabile Referenzpfad. Vulkan ist ein experimenteller GPU-Pfad und kein Ersatz für ein NPU-Backend.
