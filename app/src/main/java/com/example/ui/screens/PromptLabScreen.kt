package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

    val isModelReady = modelState is ModelLifecycleState.Ready
    val isRunning = activeTest != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "PROMPT-TESTLABOR",
            color = NeonCyan,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp
        )
        Text(
            text = "7 Standardisierte Testfälle",
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Vergleiche native Evaluierungs- und Generierungsleistung für Dokumenten-Workflows.",
            color = TextSecondary,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Live Ausführungsfenster (wenn Test aktiv oder Ausgabe vorhanden)
        if (testOutput.isNotEmpty() || isRunning) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
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
                            text = if (isRunning) "Test läuft: ${activeTest?.title}" else "Letztes Testergebnis",
                            color = if (isRunning) NeonCyan else MatrixGreen,
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
                                Text("Abbrechen", fontSize = 11.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        color = CodeBackground,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = testOutput,
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp)
                        )
                    }

                    // Metrikzeile
                    labMetrics?.let { m ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Tokenisierung: ${m.promptTokenizeDurationMs} ms | Prompt-Eval: ${m.promptEvalDurationMs} ms (${m.promptTokens} Tokens)", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text("First Token: ${m.firstTokenDurationMs} ms | Generierung: ${m.generationDurationMs} ms (${m.generatedTokens} Tokens)", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text("Decode Calls: Prompt ${m.promptDecodeCalls} | Generation ${m.generationDecodeCalls} | ${"%.1f".format(m.tokensPerSecond)} Tok/s", color = NeonCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Text("Status: ${m.finishReason}", color = if (m.finishReason == "STOP_TOKEN" || m.finishReason == "MAX_TOKENS") MatrixGreen else LaserCrimson, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        // Testfall-Liste
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(PromptTestCatalog.ALL_TESTS, key = { it.id }) { testCase ->
                PromptTestCaseCard(
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
fun PromptTestCaseCard(
    testCase: PromptTestCase,
    isModelReady: Boolean,
    isThisRunning: Boolean,
    isAnyRunning: Boolean,
    onExecute: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isThisRunning) NeonCyan else DarkCardBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = testCase.title,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                if (testCase.repetitionCount > 1) {
                    Surface(
                        color = DarkSurfaceVariant,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.padding(start = 6.dp)
                    ) {
                        Text(
                            text = "${testCase.repetitionCount}x Loop",
                            color = ElectricAmber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Text(
                text = testCase.description,
                color = TextSecondary,
                fontSize = 12.sp
            )

            Surface(
                color = CodeBackground,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Prompt: ${testCase.prompt.take(100)}${if (testCase.prompt.length > 100) "..." else ""}",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(8.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Timeout: ${testCase.timeoutSec}s",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

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
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Läuft...", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Test starten", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
