package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** All development tests, performance data and diagnostics live on this single debug-only screen. */
@Composable
fun DeveloperToolsScreen(viewModel: LLMLabViewModel, onOpenSettings: () -> Unit) {
    val modelState by viewModel.modelState.collectAsState()
    val suite by viewModel.automatedSuite.collectAsState()
    val benchmark by viewModel.benchmarkSummary.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val history by viewModel.testRunHistory.collectAsState()
    val labOutput by viewModel.labTestOutput.collectAsState()
    val exportStatus by viewModel.exportStatus.collectAsState()
    var confirmClearHistory by remember { mutableStateOf(false) }

    val ready = modelState is ModelLifecycleState.Ready
    val running = suite.isRunning || benchmark.isRunning
    val recentRuns = history.take(25)

    Column(
        modifier = Modifier.fillMaxSize().background(DarkBackground).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Interne Qualitätssicherung", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "Diese Werkzeuge dienen ausschließlich der Entwicklung und Abnahme der lokalen LLM-Engine. Sie gehören nicht zur späteren myDocAnizer-Bedienoberfläche.",
            color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp
        )

        DevSectionCard("Funktion und Modellqualität") {
            Text(
                text = when {
                    suite.isRunning -> suite.message.ifBlank { "Testreihe läuft…" }
                    suite.error != null -> suite.error!!
                    modelState is ModelLifecycleState.Ready -> "Modell geladen: ${(modelState as ModelLifecycleState.Ready).fileName}"
                    else -> "Kein Modell geladen. Die automatisierte Suite kann Katalogmodelle bei Bedarf herunterladen."
                },
                color = if (suite.error != null) LaserCrimson else TextSecondary, fontSize = 12.sp
            )
            if (suite.isRunning) {
                LinearProgressIndicator(
                    progress = {
                        when {
                            suite.fullTestsTotal > 0 -> (suite.fullTestsCompleted.toFloat() / suite.fullTestsTotal).coerceIn(0f, 1f)
                            suite.modelCount > 0 -> (suite.currentModelIndex.toFloat() / suite.modelCount).coerceIn(0f, 1f)
                            else -> 0f
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), color = NeonCyan, trackColor = DarkSurfaceVariant
                )
                Text("Phase: ${suite.phase} · Modell ${suite.currentModelIndex}/${suite.modelCount}",
                    color = NeonCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.runQwenQuickCheck() }, enabled = !running,
                    modifier = Modifier.weight(1f).testTag("developer_quick_suite"),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
                ) { Text("Quick") }
                Button(
                    onClick = { viewModel.runQwenQuickThenFull() }, enabled = !running,
                    modifier = Modifier.weight(1f).testTag("developer_full_suite"),
                    colors = ButtonDefaults.buttonColors(containerColor = MatrixGreen, contentColor = Color.Black)
                ) { Text("Full") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.run3xBenchmark() }, enabled = ready && !running,
                    modifier = Modifier.weight(1f).testTag("developer_stability_test")
                ) { Text("3x Stabilität") }
                if (running) {
                    Button(
                        onClick = { viewModel.requestAbort() },
                        modifier = Modifier.weight(1f).testTag("developer_abort"),
                        colors = ButtonDefaults.buttonColors(containerColor = LaserCrimson, contentColor = Color.White)
                    ) { Text("Abbrechen") }
                }
            }
            if (suite.quickPassed + suite.quickFailed > 0) {
                Text("Quick-Ergebnisse: ${suite.quickPassed} bestanden · ${suite.quickFailed} fehlgeschlagen",
                    color = if (suite.quickFailed == 0) MatrixGreen else LaserCrimson, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            if (benchmark.isRunning) {
                Text("Stabilitätslauf ${benchmark.currentIteration}/${benchmark.totalRuns}", color = NeonCyan, fontSize = 12.sp)
            }
            if (suite.error != null) Text(suite.error!!, color = LaserCrimson, fontSize = 12.sp)
            if (labOutput.isNotBlank()) {
                Text(labOutput.takeLast(1800), color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
            }
        }

        DevSectionCard("Gerät und Engine") {
            DevMetricRow("CPU", "${telemetry.cpuArch} · ${telemetry.cpuCores} Kerne")
            DevMetricRow("Thermik", telemetry.thermalStatus)
            DevMetricRow("RAM verfügbar", "${telemetry.systemAvailableRamMb.toInt()} / ${telemetry.systemTotalRamMb.toInt()} MB")
            DevMetricRow("Java Heap", "${"%.1f".format(Locale.US, telemetry.javaHeapUsedMb)} / ${"%.1f".format(Locale.US, telemetry.javaHeapMaxMb)} MB")
            DevMetricRow("Native Heap", "${"%.1f".format(Locale.US, telemetry.nativeHeapAllocatedMb)} MB")
            DevMetricRow("App-CPU", telemetry.appCpuUsagePercent?.let { "${"%.1f".format(Locale.US, it)}%" } ?: "Noch kein Messintervall")
            DevMetricRow("Modellstatus", when (modelState) {
                is ModelLifecycleState.Ready -> "Bereit"
                is ModelLifecycleState.Initializing -> "Initialisierung"
                is ModelLifecycleState.InferenceRunning -> "Inferenz"
                is ModelLifecycleState.Error -> "Fehler"
                else -> "Nicht geladen"
            })
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().testTag("developer_engine_settings")) {
                Text("Erweiterte Engine-Parameter")
            }
        }

        DevSectionCard("Einheitliche Messwert- und Testhistorie") {
            Text("Alle Chat-, Funktions-, Suite- und Stabilitätsläufe stammen aus derselben lokalen Room-Historie.",
                color = TextSecondary, fontSize = 11.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.exportReport(asJson = false) },
                    modifier = Modifier.weight(1f).testTag("developer_export_txt")) { Text("Export TXT") }
                OutlinedButton(onClick = { viewModel.exportReport(asJson = true) },
                    modifier = Modifier.weight(1f).testTag("developer_export_json")) { Text("Export JSON") }
            }
            exportStatus?.let {
                Text(it, color = if (it.startsWith("Export fehlgeschlagen")) LaserCrimson else MatrixGreen, fontSize = 11.sp)
            }
            if (recentRuns.isEmpty()) {
                Text("Noch keine Inferenz- oder Testergebnisse vorhanden.", color = TextMuted, fontSize = 12.sp)
            } else {
                Column(modifier = Modifier.testTag("developer_history_list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    recentRuns.forEach { run -> TestRunRow(run) }
                }
            }
            TextButton(onClick = { confirmClearHistory = true }, enabled = history.isNotEmpty(),
                modifier = Modifier.align(Alignment.End)) { Text("Historie löschen", color = LaserCrimson) }
        }
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("Testhistorie löschen?") },
            text = { Text("Alle lokal gespeicherten Mess- und Testergebnisse werden gelöscht. Chat-Inhalte sind darin bereits redigiert.") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearHistory(); confirmClearHistory = false }) { Text("Löschen", color = LaserCrimson) }
            },
            dismissButton = { TextButton(onClick = { confirmClearHistory = false }) { Text("Abbrechen") } }
        )
    }
}

@Composable
private fun DevSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content) {
            Text(title, color = NeonCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DevMetricRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextSecondary, fontSize = 11.sp)
        Text(value, color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun TestRunRow(run: TestRunEntity) {
    val date = remember(run.timestamp) { SimpleDateFormat("dd.MM.yy HH:mm", Locale.GERMANY).format(Date(run.timestamp)) }
    Surface(color = DarkSurfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(run.testTitle, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (run.isSuccess) "OK" else run.finishReason, color = if (run.isSuccess) MatrixGreen else LaserCrimson,
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Text("$date · ${run.modelName}", color = TextMuted, fontSize = 10.sp)
            Text("TTFT ${run.firstTokenDurationMs} ms · Prefill ${run.promptEvalDurationMs} ms · Decode ${"%.2f".format(Locale.US, run.tokensPerSecond)} Tok/s · Gesamt ${run.totalDurationMs} ms",
                color = TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            run.errorMessage?.takeIf { it.isNotBlank() }?.let { Text(it.take(240), color = LaserCrimson, fontSize = 10.sp) }
        }
    }
}
