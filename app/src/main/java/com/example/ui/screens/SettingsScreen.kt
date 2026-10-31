package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.BackendPreference
import com.example.engine.ProfilePreset
import com.example.ui.LLMLabViewModel
import com.example.ui.ModelLifecycleState
import com.example.ui.theme.*

@Composable
fun SettingsScreen(viewModel: LLMLabViewModel) {
    val config by viewModel.config.collectAsState()
    val modelState by viewModel.modelState.collectAsState()
    val availableBackends by viewModel.availableBackends.collectAsState()
    val maxCpuThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
    val contextReloadRequired = (modelState as? ModelLifecycleState.Ready)?.loadedConfig?.hasSameNativeContext(config) == false

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Column {
            Text(
                text = "EINSTELLUNGEN & PROFILE",
                color = NeonCyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            Text(
                text = "Inferenzparameter & Timeouts",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Wähle vorkonfigurierte Profile oder justiere n_ubatch und Zeitlimits manuell.",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }

        if (contextReloadRequired) {
            Text(
                "Native Context-/Backend-Parameter geändert: Modell bitte neu laden, damit die neuen Einstellungen tatsächlich gelten.",
                color = ElectricAmber,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        // Vordefinierte Profile (wie in Frage 3 bestätigt)
        Text(
            text = "Inferenzprofil-Schnellauswahl",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfilePreset.values().forEach { preset ->
                val isSelected = config.selectedProfile == preset
                Surface(
                    color = if (isSelected) DarkSurfaceVariant else DarkSurface,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isSelected) NeonCyan else DarkCardBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setProfilePreset(preset) }
                        .testTag("preset_${preset.name}")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { viewModel.setProfilePreset(preset) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = NeonCyan,
                                unselectedColor = TextMuted
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = preset.label,
                                color = if (isSelected) NeonCyan else TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = preset.description,
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }


        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Hardware-Backends (llama.cpp Runtime-Registry)", color = NeonCyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Die Liste zeigt einkompilierte und initialisierbare Backends. AUTO wählt nur sicher abbrechbare Pfade. GPU/NPU ist in llama.cpp b3600 ein expliziter Versuch, solange kein garantierter Abbruch verfügbar ist.",
                    color = TextSecondary, fontSize = 11.sp
                )
                if (availableBackends.isEmpty()) {
                    Text("Backend-Informationen nicht verfügbar.", color = TextMuted, fontSize = 12.sp)
                } else {
                    availableBackends.forEach { backend ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(backend.registryName, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    listOf(backend.kind, backend.bufferType, if (backend.supportsAbort) "Abbruch unterstützt" else "Abbruch nicht garantiert")
                                        .filter { it.isNotBlank() }.joinToString(" · "),
                                    color = TextMuted,
                                    fontSize = 10.sp
                                )
                            }
                            Text(if (backend.available) "Initialisierbar" else "Nicht verfügbar", color = if (backend.available) MatrixGreen else TextMuted, fontSize = 11.sp)
                        }
                    }
                }
                Text("Backend-Auswahl", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                BackendPreference.values().forEach { preference ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { viewModel.updateConfig(config.copy(backendPreference = preference)) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = config.backendPreference == preference,
                            onClick = { viewModel.updateConfig(config.copy(backendPreference = preference)) },
                            colors = RadioButtonDefaults.colors(selectedColor = NeonCyan, unselectedColor = TextMuted)
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(preference.label, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(preference.description, color = TextMuted, fontSize = 10.sp)
                        }
                    }
                }
            }
        }

        // Micro-Batching (n_ubatch) Varianten-Vergleich
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Micro-Batching (n_ubatch)",
                    color = NeonCyan,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Bestimmt, wie viele Tokens pro internem Compute-Step evaluiert werden. Vergleich der Varianten 1, 4, 8, 16, 32:",
                    color = TextSecondary,
                    fontSize = 12.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(1, 4, 8, 16, 32, 64, 128).forEach { ubatchVal ->
                        val isUbatchSelected = config.nUbatch == ubatchVal
                        FilterChip(
                            selected = isUbatchSelected,
                            onClick = {
                                viewModel.updateConfig(config.copy(nUbatch = ubatchVal))
                            },
                            label = {
                                Text(
                                    text = "$ubatchVal",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isUbatchSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NeonCyan,
                                selectedLabelColor = Color.Black,
                                containerColor = DarkSurfaceVariant,
                                labelColor = TextPrimary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isUbatchSelected,
                                borderColor = if (isUbatchSelected) NeonCyan else DarkCardBorder
                            ),
                            modifier = Modifier.testTag("ubatch_chip_$ubatchVal")
                        )
                    }
                }
            }
        }

        // Manuelle Inferenzparameter
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Manuelle Parameterfeinabstimmung",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )

                // Threads
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("CPU-Threads (n_threads):", color = TextSecondary, fontSize = 12.sp)
                        Text("${config.nThreads}", color = NeonCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = config.nThreads.toFloat(),
                        onValueChange = { viewModel.updateConfig(config.copy(nThreads = it.toInt())) },
                        valueRange = 1f..maxCpuThreads.toFloat(),
                        steps = (maxCpuThreads - 2).coerceAtLeast(0),
                        colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan)
                    )
                }


                // Separate prompt prefill threads
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("CPU-Threads Prompt-Prefill (n_threads_batch):", color = TextSecondary, fontSize = 12.sp)
                        Text("${config.nThreadsBatch}", color = NeonCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = config.nThreadsBatch.toFloat(),
                        onValueChange = { viewModel.updateConfig(config.copy(nThreadsBatch = it.toInt().coerceIn(1, maxCpuThreads))) },
                        valueRange = 1f..maxCpuThreads.toFloat(),
                        steps = (maxCpuThreads - 2).coerceAtLeast(0),
                        colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan)
                    )
                    Text("Separater Thread-Pool für den Prompt-Vorlauf. Die beste Kombination muss auf dem Gerät gemessen werden.", color = TextMuted, fontSize = 11.sp)
                }

                // Context Size
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Context Size (n_ctx):", color = TextSecondary, fontSize = 12.sp)
                        Text("${config.nCtx}", color = NeonCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = config.nCtx.toFloat(),
                        onValueChange = { viewModel.updateConfig(config.copy(nCtx = it.toInt())) },
                        valueRange = 512f..4096f,
                        steps = 6,
                        colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan)
                    )
                }
            }
        }


        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sampling / Antwortverhalten", color = NeonCyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("Diese Werte ändern das Sampling, nicht den geladenen Modell-Context.", color = TextMuted, fontSize = 11.sp)
                Text("Temperatur: ${"%.2f".format(config.temperature)}", color = TextSecondary, fontSize = 12.sp)
                Slider(value = config.temperature, onValueChange = { viewModel.updateConfig(config.copy(temperature = it)) }, valueRange = 0f..1f, steps = 19, colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan))
                Text("Top-K: ${config.topK}", color = TextSecondary, fontSize = 12.sp)
                Slider(value = config.topK.toFloat(), onValueChange = { viewModel.updateConfig(config.copy(topK = it.toInt().coerceIn(1, 100))) }, valueRange = 1f..100f, steps = 98, colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan))
                Text("Top-P: ${"%.2f".format(config.topP)}", color = TextSecondary, fontSize = 12.sp)
                Slider(value = config.topP, onValueChange = { viewModel.updateConfig(config.copy(topP = it.coerceIn(0.1f, 1f))) }, valueRange = 0.1f..1f, steps = 8, colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan))
            }
        }

        // Konfigurierbare Timeouts (Spezifikation E)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Konfigurierbare Zeitlimits (Timeouts)",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )

                // Init Timeout (max 120s)
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Modellinitialisierung Timeout:", color = TextSecondary, fontSize = 12.sp)
                        Text("${config.timeoutInitSec}s", color = NeonCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    Slider(
                        value = config.timeoutInitSec.toFloat(),
                        onValueChange = { viewModel.updateConfig(config.copy(timeoutInitSec = it.toInt())) },
                        valueRange = 30f..180f,
                        steps = 5,
                        colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan)
                    )
                }

                // Kurztest Timeout (max 30s)
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Kurztest Timeout:", color = TextSecondary, fontSize = 12.sp)
                        Text("${config.timeoutShortTestSec}s", color = NeonCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    Slider(
                        value = config.timeoutShortTestSec.toFloat(),
                        onValueChange = { viewModel.updateConfig(config.copy(timeoutShortTestSec = it.toInt())) },
                        valueRange = 10f..60f,
                        steps = 4,
                        colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan)
                    )
                }

                // Normale Inferenz Timeout (max 90s)
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Normale Inferenz Timeout:", color = TextSecondary, fontSize = 12.sp)
                        Text("${config.timeoutNormalInferenceSec}s", color = NeonCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    Slider(
                        value = config.timeoutNormalInferenceSec.toFloat(),
                        onValueChange = { viewModel.updateConfig(config.copy(timeoutNormalInferenceSec = it.toInt())) },
                        valueRange = 30f..180f,
                        steps = 5,
                        colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan)
                    )
                }

                // Stresstest Timeout (max 120s)
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Stresstest pro Testfall Timeout:", color = TextSecondary, fontSize = 12.sp)
                        Text("${config.timeoutStressTestSec}s", color = NeonCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    Slider(
                        value = config.timeoutStressTestSec.toFloat(),
                        onValueChange = { viewModel.updateConfig(config.copy(timeoutStressTestSec = it.toInt())) },
                        valueRange = 30f..240f,
                        steps = 6,
                        colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan)
                    )
                }
            }
        }
    }
}
