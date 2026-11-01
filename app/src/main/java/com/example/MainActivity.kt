package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.LLMLabViewModel
import com.example.ui.ModelLifecycleState
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.DeveloperToolsScreen
import com.example.ui.screens.ModelScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.*

private data class NavTabItem(val title: String, val selectedIcon: ImageVector, val unselectedIcon: ImageVector, val tag: String)

class MainActivity : ComponentActivity() {
    private val viewModel: LLMLabViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LLMLabTheme {
                val currentTab by viewModel.currentTab.collectAsState()
                val modelState by viewModel.modelState.collectAsState()
                var developerMenuExpanded by remember { mutableStateOf(false) }

                BackHandler(enabled = currentTab >= 2) {
                    viewModel.selectTab(if (currentTab == 3) 2 else 0)
                }

                val coreNavigation = listOf(
                    NavTabItem("Modell", Icons.Filled.Layers, Icons.Outlined.Layers, "nav_model"),
                    NavTabItem("Inferenz", Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat, "nav_inference")
                )

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            navigationIcon = {
                                if (currentTab >= 2) {
                                    IconButton(onClick = { viewModel.selectTab(if (currentTab == 3) 2 else 0) }) {
                                        Icon(Icons.Filled.ArrowBack, contentDescription = "Zurück", tint = NeonCyan)
                                    }
                                }
                            },
                            title = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = when (currentTab) {
                                                2 -> "Entwicklung & Qualität"
                                                3 -> "Engine-Einstellungen"
                                                else -> "LLM Engine"
                                            },
                                            color = NeonCyan,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp
                                        )
                                        Text(
                                            text = when (currentTab) {
                                                2 -> "Zentrale Tests und Messwerte"
                                                3 -> "Interne Laufzeitparameter"
                                                else -> "Lokale Modell-Inferenz"
                                            },
                                            color = TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                    Surface(
                                        color = DarkSurfaceVariant,
                                        shape = RoundedCornerShape(12.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                                        modifier = Modifier.padding(end = 8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(
                                                    when (modelState) {
                                                        is ModelLifecycleState.Ready -> MatrixGreen
                                                        is ModelLifecycleState.InferenceRunning -> NeonCyan
                                                        is ModelLifecycleState.Initializing -> ElectricAmber
                                                        is ModelLifecycleState.Error -> LaserCrimson
                                                        else -> TextMuted
                                                    }
                                                )
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = when (modelState) {
                                                    is ModelLifecycleState.Ready -> "Bereit"
                                                    is ModelLifecycleState.InferenceRunning -> "Inferenz"
                                                    is ModelLifecycleState.Initializing -> "Lädt…"
                                                    is ModelLifecycleState.Error -> "Fehler"
                                                    else -> "Offline"
                                                },
                                                color = TextPrimary,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            },
                            actions = {
                                if (BuildConfig.DEBUG && currentTab < 2) {
                                    Box {
                                        IconButton(onClick = { developerMenuExpanded = true }) {
                                            Icon(Icons.Filled.MoreVert, contentDescription = "Entwicklermenü", tint = TextSecondary)
                                        }
                                        DropdownMenu(
                                            expanded = developerMenuExpanded,
                                            onDismissRequest = { developerMenuExpanded = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Entwicklung & Diagnose") },
                                                onClick = {
                                                    developerMenuExpanded = false
                                                    viewModel.selectTab(2)
                                                }
                                            )
                                        }
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface, titleContentColor = TextPrimary)
                        )
                    },
                    bottomBar = {
                        if (currentTab in 0..1) {
                            NavigationBar(
                                containerColor = DarkSurface,
                                tonalElevation = 8.dp,
                                modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)
                            ) {
                                coreNavigation.forEachIndexed { index, item ->
                                    val selected = currentTab == index
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { viewModel.selectTab(index) },
                                        icon = {
                                            Icon(
                                                imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                                contentDescription = item.title,
                                                tint = if (selected) NeonCyan else TextSecondary
                                            )
                                        },
                                        label = {
                                            Text(item.title, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (selected) NeonCyan else TextSecondary)
                                        },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = NeonCyan,
                                            selectedTextColor = NeonCyan,
                                            indicatorColor = DarkSurfaceVariant
                                        ),
                                        modifier = Modifier.testTag(item.tag)
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(Modifier.fillMaxSize().padding(innerPadding)) {
                        when (currentTab) {
                            0 -> ModelScreen(viewModel)
                            1 -> ChatScreen(viewModel)
                            2 -> DeveloperToolsScreen(viewModel) { viewModel.selectTab(3) }
                            3 -> SettingsScreen(viewModel)
                            else -> ModelScreen(viewModel)
                        }
                    }
                }
            }
        }
    }
}
