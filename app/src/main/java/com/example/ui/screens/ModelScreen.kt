package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.example.engine.HuggingFaceModelCatalog
import com.example.ui.LLMLabViewModel
import com.example.ui.ModelLifecycleState
import com.example.ui.theme.*

@Composable
fun ModelScreen(viewModel: LLMLabViewModel) {
    val modelState by viewModel.modelState.collectAsState()
    val config by viewModel.config.collectAsState()
    val downloadedModelIds by viewModel.downloadedModelIds.collectAsState()
    val huggingFaceDownload by viewModel.huggingFaceDownload.collectAsState()
    val automatedSuite by viewModel.automatedSuite.collectAsState()

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onModelFileSelected(uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "MODELLVERWALTUNG",
                    color = NeonCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "GGUF Inferenz-Setup",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Profil-Pill
            Surface(
                color = DarkSurfaceVariant,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Profil",
                        tint = NeonCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = config.selectedProfile.label,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Status-Karte
        StatusDisplayCard(modelState = modelState)

        HuggingFaceModelsCard(
            viewModel = viewModel,
            downloadedModelIds = downloadedModelIds,
            downloadState = huggingFaceDownload,
            suiteState = automatedSuite,
            onQuickCheck = { viewModel.runQwenQuickCheck() },
            onQuickThenFull = { viewModel.runQwenQuickThenFull() },
            onCancelSuite = { viewModel.requestAbort() }
        )

        // Dateiauswahl & Aktionen
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
                    text = "GGUF-Datei wählen",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "Zielmodell: qwen2.5-1.5b-instruct-q4_k_m.gguf (~1,12 GB).\nWähle die Datei von deinem Gerätespeicher oder Downloads-Ordner.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                        enabled = !automatedSuite.isRunning,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("select_model_file_button")
                    ) {
                        Icon(imageVector = Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Datei auswählen", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Steuerungstasten abhängig vom Zustand
        when (val state = modelState) {
            is ModelLifecycleState.ModelSelected -> {
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
                            text = "Erkannte Modellparameter",
                            color = NeonCyan,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text("Dateiname: ${state.fileName}", color = TextPrimary, fontSize = 13.sp)
                        Text(
                            if (state.fileSizeBytes > 0L) {
                                "Größe: ${"%.2f".format(state.fileSizeBytes / (1024f * 1024f * 1024f))} GB (${state.fileSizeBytes} Bytes)"
                            } else {
                                "Größe: nicht verfügbar"
                            },
                            color = TextSecondary,
                            fontSize = 12.sp
                        )

                        val info = state.ggufInfo
                        if (info.isValid) {
                            Text("Signatur: ${info.magic} (Version ${info.version}) - GÜLTIG", color = MatrixGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text("Architektur: ${info.architecture ?: "Nicht verfügbar"}", color = TextSecondary, fontSize = 12.sp)
                            Text("Typ: ${info.fileTypeDescription ?: "Nicht verfügbar"}", color = TextSecondary, fontSize = 12.sp)
                            Text("Tensoren: ${info.tensorCount} | KV-Einträge: ${info.kvCount}", color = TextSecondary, fontSize = 12.sp)
                        } else {
                            Text("Warnung: ${info.errorMessage ?: "Kein gültiger GGUF-Header"}", color = LaserCrimson, fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Button(
                            onClick = { viewModel.initializeModel() },
                            colors = ButtonDefaults.buttonColors(containerColor = MatrixGreen, contentColor = Color.Black),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("initialize_model_button")
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Modell jetzt initialisieren", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            is ModelLifecycleState.Ready -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MatrixGreen.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = MatrixGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Modell einsatzbereit", color = MatrixGreen, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }

                        Text("Engine: ${state.engineVersion}", color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text("Initialisierungsdauer: ${state.initDurationMs} ms", color = TextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        Text("Geladene Context-Parameter: n_ctx=${state.loadedConfig.nCtx}, n_batch=${state.loadedConfig.nBatch}, n_ubatch=${state.loadedConfig.nUbatch}, threads=${state.loadedConfig.nThreads}", color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        if (viewModel.requiresModelReload()) {
                            Text("Einstellungen geändert: Diese Context-Werte sind noch nicht aktiv. Bitte das Modell erneut laden, bevor Chat, Prompt-Labor oder Benchmark verwendet werden.", color = ElectricAmber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.unloadModel() },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = LaserCrimson),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("unload_model_button")
                            ) {
                                Text("Entladen")
                            }

                            Button(
                                onClick = { viewModel.initializeModel() },
                                colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant, contentColor = NeonCyan),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("reload_model_button")
                            ) {
                                Text("Erneut laden")
                            }
                        }
                    }
                }
            }

            is ModelLifecycleState.Initializing -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ElectricAmber.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = ElectricAmber,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Initialisierung läuft...", color = ElectricAmber, fontWeight = FontWeight.Bold)
                        }

                        if (state.progressPercent != null) {
                            LinearProgressIndicator(
                                progress = { state.progressPercent / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = ElectricAmber,
                                trackColor = DarkSurfaceVariant
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                                color = ElectricAmber,
                                trackColor = DarkSurfaceVariant
                            )
                        }

                        Text("Phase: ${state.phase}", color = TextPrimary, fontSize = 13.sp)
                        Text(
                            "Fortschritt: ${state.progressPercent?.let { "${it.toInt()}%" } ?: "nicht messbar"} | Zeit: ${state.elapsedMs} ms",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )

                        OutlinedButton(
                            onClick = { viewModel.requestAbort() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = LaserCrimson),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Initialisierung abbrechen")
                        }
                    }
                }
            }

            else -> {}
        }
    }
}

@Composable
private fun HuggingFaceModelsCard(
    viewModel: LLMLabViewModel,
    downloadedModelIds: Set<String>,
    downloadState: com.example.ui.HuggingFaceDownloadUiState,
    suiteState: com.example.ui.AutomatedModelSuiteUiState,
    onQuickCheck: () -> Unit,
    onQuickThenFull: () -> Unit,
    onCancelSuite: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("QWEN-MODELLE VON HUGGING FACE", color = NeonCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("Direkt herunterladen und lokal verwenden", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Vier offizielle Qwen2.5-Instruct-GGUF-Dateien für den kontrollierten Vergleich von 0.5B/1.5B und Q4_0/Q4_K_M. Alle vier benötigen zusammen rund 3,1 GB Speicher. Download nur auf ausdrücklichen Tastendruck; danach läuft die Inferenz lokal. Lizenz: Apache-2.0.",
                color = TextSecondary,
                fontSize = 12.sp
            )

            HuggingFaceModelCatalog.models.forEach { model ->
                val downloaded = model.id in downloadedModelIds
                val isActiveDownload = downloadState.modelId == model.id && downloadState.isDownloading
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${model.parameters} · ${model.quantization}", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("${model.title} · ${model.sizeLabel}", color = TextSecondary, fontSize = 11.sp)
                        }
                        Button(
                            onClick = {
                                when {
                                    isActiveDownload -> viewModel.cancelHuggingFaceDownload()
                                    downloaded -> viewModel.selectDownloadedHuggingFaceModel(model.id)
                                    else -> viewModel.downloadHuggingFaceModel(model.id)
                                }
                            },
                            enabled = !suiteState.isRunning && (isActiveDownload || downloaded || !downloadState.isDownloading),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isActiveDownload) LaserCrimson else if (downloaded) MatrixGreen else NeonCyan,
                                contentColor = Color.Black,
                                disabledContainerColor = DarkSurfaceVariant,
                                disabledContentColor = TextMuted
                            ),
                            modifier = Modifier.testTag("hf_model_${model.id}")
                        ) {
                            Text(
                                when {
                                    isActiveDownload -> "Abbrechen"
                                    downloaded -> "Auswählen"
                                    else -> "Download"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }

                    if (isActiveDownload) {
                        val progress = if (downloadState.totalBytes > 0L) {
                            (downloadState.downloadedBytes.toFloat() / downloadState.totalBytes.toFloat()).coerceIn(0f, 1f)
                        } else 0f
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = NeonCyan,
                            trackColor = DarkSurfaceVariant
                        )
                        Text(
                            "${downloadState.downloadedBytes / (1024L * 1024L)} MB / ${if (downloadState.totalBytes > 0L) "${downloadState.totalBytes / (1024L * 1024L)} MB" else "Größe wird ermittelt"}",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (downloadState.modelId == model.id) {
                        downloadState.message?.let { Text(it, color = if (downloadState.error == null) TextSecondary else LaserCrimson, fontSize = 11.sp) }
                        downloadState.error?.let { Text(it, color = LaserCrimson, fontSize = 12.sp) }
                    }
                }
                if (model != HuggingFaceModelCatalog.models.last()) {
                    HorizontalDivider(color = DarkCardBorder)
                }
            }

            HorizontalDivider(color = DarkCardBorder)
            Text("AUTOMATISCHER MODELLVERGLEICH", color = NeonCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(
                "Die App schaltet nacheinander alle vier Modelle durch. Der Quick-Preflight prüft Modellladen, echte Vulkan-Layer-Zuordnung und eine kurze Inferenz. Nur wenn alle vier Modelle bestehen, startet die Full-Routine automatisch.",
                color = TextSecondary,
                fontSize = 12.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onQuickCheck,
                    enabled = !suiteState.isRunning,
                    modifier = Modifier.weight(1f).testTag("qwen_quick_check_button")
                ) {
                    Text("Quick-Check", fontSize = 11.sp)
                }
                Button(
                    onClick = onQuickThenFull,
                    enabled = !suiteState.isRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = MatrixGreen, contentColor = Color.Black),
                    modifier = Modifier.weight(1f).testTag("qwen_quick_full_button")
                ) {
                    Text("Quick → Full", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (suiteState.isRunning || suiteState.phase != "IDLE") {
                Text(
                    "${suiteState.phase} · Modell ${suiteState.currentModelIndex}/${suiteState.modelCount} · Quick bestanden: ${suiteState.quickPassed}/${suiteState.modelCount} · Full: ${suiteState.fullTestsCompleted}/${suiteState.fullTestsTotal}",
                    color = if (suiteState.error == null) NeonCyan else LaserCrimson,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (suiteState.isRunning) {
                    LinearProgressIndicator(
                        progress = {
                            when {
                                suiteState.fullTestsTotal > 0 -> suiteState.fullTestsCompleted.toFloat() / suiteState.fullTestsTotal.toFloat()
                                suiteState.modelCount > 0 -> suiteState.quickPassed.toFloat() / suiteState.modelCount.toFloat()
                                else -> 0f
                            }.coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = NeonCyan,
                        trackColor = DarkSurfaceVariant
                    )
                    Text(suiteState.message, color = TextSecondary, fontSize = 11.sp)
                    OutlinedButton(onClick = onCancelSuite, modifier = Modifier.fillMaxWidth()) {
                        Text("Testreihe abbrechen", color = LaserCrimson)
                    }
                } else {
                    Text(suiteState.message, color = if (suiteState.error == null) MatrixGreen else LaserCrimson, fontSize = 11.sp)
                    suiteState.error?.let { Text(it, color = LaserCrimson, fontSize = 11.sp) }
                }
            }
        }
    }
}

@Composable
fun StatusDisplayCard(modelState: ModelLifecycleState) {
    val (statusText, statusColor, desc) = when (modelState) {
        is ModelLifecycleState.Unloaded -> Triple("Kein Modell ausgewählt", TextSecondary, "Bitte lade ein GGUF-Modell, um Inferenzfunktionen zu testen.")
        is ModelLifecycleState.ModelSelected -> Triple("Modell ausgewählt", ElectricAmber, "Datei verifiziert. Bereit zur nativen Initialisierung.")
        is ModelLifecycleState.Initializing -> Triple("Initialisierung läuft", ElectricAmber, "Modell wird in den Arbeitsspeicher geladen und der KV-Cache aufgebaut.")
        is ModelLifecycleState.Ready -> Triple("Modell erfolgreich geladen", MatrixGreen, "Engine ist aktiv. Volle lokale Inferenz ist betriebsbereit.")
        is ModelLifecycleState.InferenceRunning -> Triple("Inferenz läuft", NeonCyan, "Modell generiert Tokens...")
        is ModelLifecycleState.Aborting -> Triple("Modell wird entladen / Abort aktiv", LaserCrimson, "Natives Signal gesendet.")
        is ModelLifecycleState.Error -> Triple("Modellinitialisierung fehlgeschlagen", LaserCrimson, modelState.errorMessage)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(statusColor)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = desc,
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}
