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
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
import com.example.ui.screens.*
import com.example.ui.theme.*

data class NavTabItem(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val tag: String
)

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

                BackHandler(enabled = currentTab != 0) {
                    viewModel.selectTab(0)
                }

                val navItems = listOf(
                    NavTabItem("Modell", Icons.Filled.Layers, Icons.Outlined.Layers, "nav_model"),
                    NavTabItem("Chat", Icons.Filled.Chat, Icons.Outlined.Chat, "nav_chat"),
                    NavTabItem("Labor", Icons.Filled.Science, Icons.Outlined.Science, "nav_lab"),
                    NavTabItem("Bench", Icons.Filled.Speed, Icons.Outlined.Speed, "nav_bench"),
                    NavTabItem("Diagnose", Icons.Filled.Analytics, Icons.Outlined.Analytics, "nav_diagnose"),
                    NavTabItem("Config", Icons.Filled.Settings, Icons.Outlined.Settings, "nav_settings")
                )

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "LLM Lab",
                                            color = NeonCyan,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp
                                        )
                                        Text(
                                            text = "Mobile Inference Testbench",
                                            color = TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }

                                    // Status Badge oben rechts
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
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(
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
                                                    is ModelLifecycleState.Ready -> "Qwen 2.5 Ready"
                                                    is ModelLifecycleState.InferenceRunning -> "Inferenz"
                                                    is ModelLifecycleState.Initializing -> "Lade..."
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
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = DarkSurface,
                                titleContentColor = TextPrimary
                            )
                        )
                    },
                    bottomBar = {
                        NavigationBar(
                            containerColor = DarkSurface,
                            tonalElevation = 8.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.navigationBars)
                        ) {
                            navItems.forEachIndexed { index, item ->
                                val isSelected = currentTab == index
                                NavigationBarItem(
                                    selected = isSelected,
                                    onClick = { viewModel.selectTab(index) },
                                    icon = {
                                        Icon(
                                            imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                                            contentDescription = item.title,
                                            tint = if (isSelected) NeonCyan else TextSecondary
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = item.title,
                                            fontSize = 10.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) NeonCyan else TextSecondary
                                        )
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
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (currentTab) {
                            0 -> ModelScreen(viewModel = viewModel)
                            1 -> ChatScreen(viewModel = viewModel)
                            2 -> PromptLabScreen(viewModel = viewModel)
                            3 -> BenchmarkScreen(viewModel = viewModel)
                            4 -> DiagnoseScreen(viewModel = viewModel)
                            5 -> SettingsScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }
}
