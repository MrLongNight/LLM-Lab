package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
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
import com.example.engine.PromptTestCatalog
import com.example.engine.PromptTestCase
import com.example.ui.LLMLabViewModel
import com.example.ui.ModelLifecycleState
import com.example.ui.theme.*

@Composable
fun PromptLabScreen(viewModel: LLMLabViewModel) {
    val modelState by viewModel.modelState.collectAsState()
    val activeTest by viewModel.labActiveTest.collectAsState()
    val testOutput by viewModel.labTestOutput.collectAsState()
    val labMetrics by viewModel.labMetrics.collectAsState()
    val config by viewModel.config.collectAsState()
    val automatedSuite by viewModel.automatedSuite.collectAsState()
    val downloadState by viewModel.huggingFaceDownload.collectAsState()

    val readyState = modelState as? ModelLifecycleState.Ready
    val isModelReady = readyState != null && readyState.loadedConfig.hasSameNativeContext(config)
    val contextReloadRequired = readyState != null && !isModelReady
    val isRunning = activeTest != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        Text(
            text = "PROMPT-TESTLABOR",
            color = NeonCyan,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp
        )
        Text(
            text = "${PromptTestCatalog.ALL_TESTS.size} automatische Testabläufe",
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Nur der Durchsatzvergleich enthält eine CPU-Referenz. DMS, Extraktion und alle Tuning-/Prefill-Läufe verwenden Vulkan; damit werden langsame CPU-Wiederholungen vermieden.",
            color = TextSecondary,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            color = DarkSurfaceVariant,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("VERGLEICHSPROFILE", color = NeonCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                PromptTestCatalog.PARAMETER_VARIANTS.forEachIndexed { index, variant ->
                    Text(
                        text = "${index + 1}. ${variant.label}",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    text = "Vulkan-Tuning: ${PromptTestCatalog.TUNING_VARIANTS.size} Profile · Vulkan-Generierung mit 2/4/6/8 CPU-Threads · ubatch 64/128.",
                    color = TextSecondary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Prefill: 2 Prompt-Längen · je ${PromptTestCatalog.PREFILL_VARIANTS.size} Vulkan-ubatch-Profile (32/64/128).",
                    color = TextSecondary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Sampling-Tuning: ${PromptTestCatalog.SAMPLING_VARIANTS.size} Profile · Temperatur, Top-K und Top-P einzeln variieren.",
                    color = TextSecondary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Vulkan läuft jeweils ohne verlässliche Abbruchgarantie.",
                    color = ElectricAmber,
                    fontSize = 10.sp
                )
            }
        }

        AutomatedQwenSuiteCard(
            suiteState = automatedSuite,
            downloadInProgress = downloadState.isDownloading,
            singleTestRunning = isRunning,
            onQuickCheck = { viewModel.runQwenQuickCheck() },
            onQuickThenFull = { viewModel.runQwenQuickThenFull() },
            onAbort = { viewModel.requestAbort() }
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (contextReloadRequired) {
            Text(
                text = "Context-Parameter geändert. Bitte das Modell erneut laden, bevor ein Vergleich startet.",
                color = ElectricAmber,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (testOutput.isNotEmpty() || isRunning) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isRunning) NeonCyan else DarkCardBorder)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isRunning) "Vergleich läuft: ${activeTest?.title}" else "Letztes Vergleichsergebnis",
                            color = if (isRunning) NeonCyan else TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (isRunning) {
                            Button(
                                onClick = { viewModel.requestAbort() },
                                colors = ButtonDefaults.buttonColors(containerColor = LaserCrimson, contentColor = Color.White),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp).testTag("abort_lab_test_button")
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Abbrechen", fontSize = 11.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = CodeBackground,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = testOutput,
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .padding(10.dp)
                                .heightIn(max = 120.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }

                    labMetrics?.let { metrics ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Zuletzt: First Token ${metrics.firstTokenDurationMs} ms · Prefill ${metrics.promptEvalDurationMs} ms · " +
                                "${metrics.generatedTokens} Tokens · ${"%.2f".format(metrics.tokensPerSecond)} Tok/s · ${metrics.finishReason}",
                            color = NeonCyan,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("prompt_test_list"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(PromptTestCatalog.ALL_TESTS, key = { it.id }) { testCase ->
                PromptWorkflowCard(
                    testCase = testCase,
                    isModelReady = isModelReady,
                    isThisRunning = activeTest?.id == testCase.id,
                    isAnyRunning = isRunning,
                    onExecute = { viewModel.runLabTest(testCase) }
                )
            }
        }
    }
}

@Composable
private fun PromptWorkflowCard(
    testCase: PromptTestCase,
    isModelReady: Boolean,
    isThisRunning: Boolean,
    isAnyRunning: Boolean,
    onExecute: () -> Unit
) {
    val profileCount = when (testCase.id) {
        "TEST_PERFORMANCE" -> PromptTestCatalog.PARAMETER_VARIANTS.size
        "TEST_TUNING" -> PromptTestCatalog.TUNING_VARIANTS.size
        "TEST_SAMPLING" -> PromptTestCatalog.SAMPLING_VARIANTS.size
        "TEST_PREFILL_256", "TEST_PREFILL_512" -> PromptTestCatalog.PREFILL_VARIANTS.size
        else -> PromptTestCatalog.VULKAN_ONLY_VARIANTS.size
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isThisRunning) NeonCyan else DarkCardBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = testCase.title,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(text = testCase.description, color = TextSecondary, fontSize = 12.sp)
            Text(
                text = "$profileCount Konfigurationen · bis zu ${testCase.timeoutSec}s je Lauf · max. ${testCase.maxTokens} Tokens",
                color = TextMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onExecute,
                    enabled = isModelReady && !isAnyRunning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = Color.Black,
                        disabledContainerColor = DarkSurfaceVariant,
                        disabledContentColor = TextMuted
                    ),
                    modifier = Modifier.testTag("run_test_${testCase.id}")
                ) {
                    if (isThisRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Vergleich läuft...", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("$profileCount Profile testen", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}


@Composable
private fun AutomatedQwenSuiteCard(
    suiteState: com.example.ui.AutomatedModelSuiteUiState,
    downloadInProgress: Boolean,
    singleTestRunning: Boolean,
    onQuickCheck: () -> Unit,
    onQuickThenFull: () -> Unit,
    onAbort: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("AUTOMATISCHE QWEN-MODELLMATRIX", color = NeonCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(
                "Testet alle vier Qwen-Modelle nacheinander. Quick prüft Laden, Vulkan-GPU-Offload und echte Token-Ausgabe. Quick → Full startet die 80 Einzelprofile ausschließlich dann, wenn alle vier Quick-Checks bestanden wurden.",
                color = TextSecondary,
                fontSize = 11.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onQuickCheck,
                    enabled = !suiteState.isRunning && !downloadInProgress && !singleTestRunning,
                    modifier = Modifier.weight(1f).testTag("qwen_quick_check_button")
                ) {
                    Text("Quick-Pretest", fontSize = 11.sp)
                }
                Button(
                    onClick = onQuickThenFull,
                    enabled = !suiteState.isRunning && !downloadInProgress && !singleTestRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = MatrixGreen, contentColor = Color.Black),
                    modifier = Modifier.weight(1f).testTag("qwen_quick_full_button")
                ) {
                    Text("Quick → Full", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (suiteState.isRunning || suiteState.phase != "IDLE") {
                Text(
                    "${suiteState.phase} · Modell ${suiteState.currentModelIndex}/${suiteState.modelCount} · Quick: ${suiteState.quickPassed}/${suiteState.modelCount} · Full: ${suiteState.fullTestsCompleted}/${suiteState.fullTestsTotal}",
                    color = if (suiteState.error == null) NeonCyan else LaserCrimson,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (suiteState.isRunning) {
                    LinearProgressIndicator(
                        progress = {
                            when {
                                suiteState.fullTestsTotal > 0 ->
                                    suiteState.fullTestsCompleted.toFloat() / suiteState.fullTestsTotal.toFloat()
                                suiteState.modelCount > 0 ->
                                    suiteState.quickPassed.toFloat() / suiteState.modelCount.toFloat()
                                else -> 0f
                            }.coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = NeonCyan,
                        trackColor = DarkSurfaceVariant
                    )
                    Text(suiteState.message, color = TextSecondary, fontSize = 11.sp)
                    OutlinedButton(onClick = onAbort, modifier = Modifier.fillMaxWidth()) {
                        Text("Testreihe abbrechen", color = LaserCrimson)
                    }
                } else {
                    Text(
                        suiteState.message,
                        color = if (suiteState.error == null) MatrixGreen else LaserCrimson,
                        fontSize = 11.sp
                    )
                    suiteState.error?.let { Text(it, color = LaserCrimson, fontSize = 11.sp) }
                }
            }
        }
    }
}
