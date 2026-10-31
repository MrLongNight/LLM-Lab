package com.example.ui.screens

import androidx.compose.foundation.background
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
import com.example.ui.LLMLabViewModel
import com.example.ui.ModelLifecycleState
import com.example.ui.theme.*

@Composable
fun BenchmarkScreen(viewModel: LLMLabViewModel) {
    val modelState by viewModel.modelState.collectAsState()
    val bench by viewModel.benchmarkSummary.collectAsState()
    val config by viewModel.config.collectAsState()

    val readyState = modelState as? ModelLifecycleState.Ready
    val isModelReady = readyState != null
    val contextReloadRequired = readyState != null && !readyState.loadedConfig.hasSameNativeContext(config)

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
                text = "BENCHMARK & STRESSTEST",
                color = NeonCyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            Text(
                text = "3x Stabilitätscheck",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Validiert Langzeitstabilität, Speicherlecks und Latenzverteilung (p50 / p95).",
                color = TextSecondary,
                fontSize = 12.sp
            )
        }

        // Aktionskarte
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (bench.isRunning) NeonCyan else DarkCardBorder)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (bench.isRunning) "Testlauf läuft: Iteration ${bench.currentIteration} / 3" else "Stresstest bereit",
                            color = if (bench.isRunning) NeonCyan else TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = readyState?.let { "Geladener Context: n_ubatch=${it.loadedConfig.nUbatch} (n_ctx=${it.loadedConfig.nCtx}, Batch=${it.loadedConfig.nBatch}, Threads=${it.loadedConfig.nThreads})" } ?: "Kein Context geladen",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }

                    if (contextReloadRequired && !bench.isRunning) {
                        Text("Context-Einstellungen geändert. Modell bitte neu laden; sonst würden falsche n_ubatch-Werte protokolliert.", color = ElectricAmber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    if (bench.isRunning) {
                        Button(
                            onClick = { viewModel.requestAbort() },
                            colors = ButtonDefaults.buttonColors(containerColor = LaserCrimson, contentColor = Color.White),
                            modifier = Modifier.testTag("abort_benchmark_button")
                        ) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Stoppen")
                        }
                    } else {
                        Button(
                            onClick = { viewModel.run3xBenchmark() },
                            enabled = isModelReady && !contextReloadRequired,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonCyan,
                                contentColor = Color.Black,
                                disabledContainerColor = DarkSurfaceVariant,
                                disabledContentColor = TextMuted
                            ),
                            modifier = Modifier.testTag("start_benchmark_button")
                        ) {
                            Icon(imageVector = Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("3x Test starten", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Fortschrittsbalken
                if (bench.isRunning) {
                    LinearProgressIndicator(
                        progress = { bench.currentIteration / 3f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = NeonCyan,
                        trackColor = DarkSurfaceVariant
                    )
                }

                bench.stopReason?.let { reason ->
                    Surface(
                        color = LaserCrimson.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, LaserCrimson),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Abbruch: $reason",
                            color = LaserCrimson,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }

        // Metrik-Kacheln
        Text(
            text = "Aggregierte Benchmark-Metriken",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricTile(
                title = "Erfolgsquote",
                value = if (bench.totalRuns > 0 && (bench.successfulRuns + bench.failedRuns) > 0) {
                    "${((bench.successfulRuns.toFloat() / (bench.successfulRuns + bench.failedRuns)) * 100).toInt()}%"
                } else "0%",
                subtitle = "${bench.successfulRuns} von ${bench.successfulRuns + bench.failedRuns} Läufen",
                accentColor = MatrixGreen,
                modifier = Modifier.weight(1f)
            )

            MetricTile(
                title = "Timeouts / Fehler",
                value = "${bench.timeoutRuns} / ${bench.failedRuns}",
                subtitle = "Automatische Fehlererfassung",
                accentColor = if (bench.failedRuns > 0) LaserCrimson else TextSecondary,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricTile(
                title = "p50 (Median)",
                value = "${bench.p50DurationMs} ms",
                subtitle = "Typische Inferenzlatenz",
                accentColor = NeonCyan,
                modifier = Modifier.weight(1f)
            )

            MetricTile(
                title = "p95 (Worst Case)",
                value = "${bench.p95DurationMs} ms",
                subtitle = "95%-Quantil-Latenz",
                accentColor = ElectricAmber,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricTile(
                title = "Ø Durchsatz",
                value = "${"%.2f".format(bench.avgTps)} Tok/s",
                subtitle = "Gemittelte Generierungsrate",
                accentColor = NeonCyan,
                modifier = Modifier.weight(1f)
            )

            MetricTile(
                title = "Ø Gesamtlaufzeit",
                value = "${bench.avgDurationMs} ms",
                subtitle = "Prompt-Eval + Generierung",
                accentColor = TextSecondary,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun MetricTile(
    title: String,
    value: String,
    subtitle: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = title, color = TextSecondary, fontSize = 11.sp)
            Text(
                text = value,
                color = accentColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(text = subtitle, color = TextMuted, fontSize = 10.sp)
        }
    }
}
