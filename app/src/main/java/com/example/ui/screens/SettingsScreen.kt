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
import com.example.engine.ProfilePreset
import com.example.ui.LLMLabViewModel
import com.example.ui.theme.*

@Composable
fun SettingsScreen(viewModel: LLMLabViewModel) {
    val config by viewModel.config.collectAsState()

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

        // Micro-Batching (n_ubatch) Varianten-Vergleich (1, 4, 8, 16, 32)
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
                    listOf(1, 4, 8, 16, 32).forEach { ubatchVal ->
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
                        valueRange = 1f..Runtime.getRuntime().availableProcessors().toFloat().coerceAtLeast(8f),
                        steps = (Runtime.getRuntime().availableProcessors().coerceAtLeast(8) - 2),
                        colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan)
                    )
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
