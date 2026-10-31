package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.data.TestRunEntity
import com.example.ui.LLMLabViewModel
import com.example.ui.ModelLifecycleState
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnoseScreen(viewModel: LLMLabViewModel) {
    val modelState by viewModel.modelState.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val config by viewModel.config.collectAsState()
    val history by viewModel.testRunHistory.collectAsState()
    val exportStatus by viewModel.exportStatus.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "TECHNISCHES DIAGNOSEZENTRUM",
                    color = NeonCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "Hardware & Inferenz-Telemetrie",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Export Buttons
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { viewModel.exportReport(asJson = true) },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant, contentColor = NeonCyan),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("export_json_button")
                ) {
                    Text("JSON", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { viewModel.exportReport(asJson = false) },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant, contentColor = MatrixGreen),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("export_txt_button")
                ) {
                    Text("TXT", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        exportStatus?.let { status ->
            Text(text = status, color = if (status.startsWith("Export fehlgeschlagen")) LaserCrimson else MatrixGreen, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Hardware-Ressourcen Kacheln
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Geräteressourcen & Sensoren",
                            color = NeonCyan,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("CPU-Architektur:", color = TextSecondary, fontSize = 12.sp)
                            Text("${telemetry.cpuArch} (${telemetry.cpuCores} Kerne)", color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Thermal-Status:", color = TextSecondary, fontSize = 12.sp)
                            Text(telemetry.thermalStatus, color = if (telemetry.thermalStatus.contains("Normal")) MatrixGreen else ElectricAmber, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Java Heap (Belegt / Max):", color = TextSecondary, fontSize = 12.sp)
                            Text("${"%.1f".format(telemetry.javaHeapUsedMb)} MB / ${"%.1f".format(telemetry.javaHeapMaxMb)} MB", color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Native Heap:", color = TextSecondary, fontSize = 12.sp)
                            Text("${"%.1f".format(telemetry.nativeHeapAllocatedMb)} MB", color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("System-RAM Verfügbar:", color = TextSecondary, fontSize = 12.sp)
                            Text("${"%.0f".format(telemetry.systemAvailableRamMb)} MB von ${"%.0f".format(telemetry.systemTotalRamMb)} MB", color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("CPU-Auslastung:", color = TextSecondary, fontSize = 12.sp)
                            Text(
                                telemetry.appCpuUsagePercent?.let { "${"%.1f".format(it)}% (LLM-Lab-Prozess)" } ?: "Nicht verfügbar (noch kein Messintervall)",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // Aktive Inferenzparameter & Engine Status
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Modell- & Inferenzparameter",
                            color = NeonCyan,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )

                        val ready = modelState as? ModelLifecycleState.Ready
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Modellstatus:", color = TextSecondary, fontSize = 12.sp)
                            Text(if (ready != null) "Aktiv geladen" else "Nicht geladen", color = if (ready != null) MatrixGreen else TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }

                        if (ready != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Init-Dauer:", color = TextSecondary, fontSize = 12.sp)
                                Text("${ready.initDurationMs} ms", color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Engine Version:", color = TextSecondary, fontSize = 12.sp)
                                Text(ready.engineVersion, color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Context Size (n_ctx):", color = TextSecondary, fontSize = 12.sp)
                            Text("${config.nCtx}", color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Batch (n_batch):", color = TextSecondary, fontSize = 12.sp)
                            Text("${config.nBatch}", color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Micro-Batch (n_ubatch):", color = TextSecondary, fontSize = 12.sp)
                            Text("${config.nUbatch}", color = NeonCyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Threads:", color = TextSecondary, fontSize = 12.sp)
                            Text("${config.nThreads}", color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            // Protokollierte Testläufe Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Protokollierte Testläufe (${history.size})",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (history.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearHistory() }) {
                            Text("Verlauf leeren", color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Historie der Testläufe
            if (history.isEmpty()) {
                item {
                    Text(
                        text = "Noch keine Testläufe durchgeführt. Starte Tests im Chat oder Prompt-Labor.",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            } else {
                items(history, key = { it.id }) { run ->
                    HistoryRunCard(run = run)
                }
            }
        }
    }
}

@Composable
fun HistoryRunCard(run: TestRunEntity) {
    val dateStr = SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date(run.timestamp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (run.isSuccess) DarkCardBorder else LaserCrimson.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$dateStr • ${run.testTitle}",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )

                Surface(
                    color = if (run.isSuccess) MatrixGreen.copy(alpha = 0.15f) else LaserCrimson.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = run.finishReason,
                        color = if (run.isSuccess) MatrixGreen else LaserCrimson,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = "Prompt (${run.promptTokens}T): \"${run.promptText.take(60)}\"",
                color = TextSecondary,
                fontSize = 11.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Eval: ${run.promptEvalDurationMs}ms | Gen: ${run.generationDurationMs}ms (${run.generatedTokens}T)",
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "${"%.1f".format(run.tokensPerSecond)} Tok/s",
                    color = NeonCyan,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            if (run.errorMessage != null) {
                Text(
                    text = "Fehler: ${run.errorMessage}",
                    color = LaserCrimson,
                    fontSize = 11.sp
                )
            }
        }
    }
}
