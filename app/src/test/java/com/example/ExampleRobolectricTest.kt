package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.TestRunEntity
import com.example.engine.GgufMetadataParser
import com.example.engine.GgufInfo
import com.example.engine.InferenceConfig
import com.example.engine.BackendPreference
import com.example.engine.InferenceCallback
import com.example.engine.LlamaNativeBridge
import com.example.engine.ProfilePreset
import com.example.engine.PromptTestCatalog
import com.example.ui.LLMLabViewModel
import com.example.ui.ModelLifecycleState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `JVM host never reports simulated model loading or inference`() {
        Assume.assumeFalse("Test only applies when JNI is unavailable on the Robolectric host.", LlamaNativeBridge.isNativeLibraryLoaded)
        val bridge = LlamaNativeBridge(ApplicationProvider.getApplicationContext<Application>().applicationInfo.nativeLibraryDir)
        val error = arrayOfNulls<String>(1)
        val loaded = bridge.loadModel(
            "/does/not/exist/model.gguf",
            InferenceConfig.fromPreset(ProfilePreset.BALANCED),
            error
        )
        assertFalse(loaded)
        assertTrue(error[0]?.contains("Native llama.cpp Engine nicht verfügbar") == true)

        val callback = object : InferenceCallback {
            override fun onToken(tokenText: String, tokenIndex: Int) = Unit
            override fun onProgress(progressPercent: Float, step: Int) = Unit
        }
        val metrics = bridge.executeInference(
            "Antworte ausschließlich mit OK.",
            InferenceConfig.fromPreset(ProfilePreset.BALANCED),
            callback
        )
        assertEquals("NATIVE_ERROR", metrics.finishReason)
        assertEquals(0, metrics.promptTokens)
        assertEquals(0, metrics.generatedTokens)
        assertEquals(0f, metrics.tokensPerSecond)
        bridge.close()
    }

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("LLM Lab (Standalone)", appName)
    }

    @Test
    fun `verify AppDatabase initialization and DAO operations`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val dao = db.testRunDao()

        dao.clearAll()
        val countBefore = dao.getCount()
        assertEquals(0, countBefore)

        val testEntity = TestRunEntity(
            testCategory = "LAB_SHORT_OK",
            testTitle = "1. Einfacher Kurztest (OK)",
            promptText = "Antworte ausschließlich mit OK.",
            responseText = "OK",
            promptTokens = 6,
            generatedTokens = 1,
            promptEvalDurationMs = 25,
            generationDurationMs = 15,
            tokensPerSecond = 66.6f,
            totalDurationMs = 40,
            nCtx = 2048,
            nBatch = 512,
            nUbatch = 8,
            nThreads = 4,
            finishReason = "STOP_TOKEN",
            isSuccess = true,
            modelName = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        )

        val insertedId = dao.insertRun(testEntity)
        assertTrue(insertedId > 0)

        val runs = dao.getAllRuns().first()
        assertEquals(1, runs.size)
        assertEquals("LAB_SHORT_OK", runs[0].testCategory)
        assertEquals("OK", runs[0].responseText)
    }

    @Test
    fun `verify LLMLabViewModel initial state and presets`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = LLMLabViewModel(app)

        assertNotNull(viewModel.modelState.value)
        assertEquals(0, viewModel.currentTab.value)

        val config = viewModel.config.value
        assertEquals(ProfilePreset.BALANCED, config.selectedProfile)
        assertEquals(32, config.nUbatch)
        assertEquals(120, config.timeoutInitSec)
        assertEquals(60, config.timeoutShortTestSec)
        assertEquals(120, config.timeoutNormalInferenceSec)
        assertEquals(120, config.timeoutStressTestSec)

        // Preset-Wechsel testen
        viewModel.setProfilePreset(ProfilePreset.POWER_SAVER)
        assertEquals(8, viewModel.config.value.nUbatch)

        viewModel.setProfilePreset(ProfilePreset.MAX_THROUGHPUT)
        assertEquals(32, viewModel.config.value.nUbatch)
    }

    @Test
    fun `verify PromptTestCatalog contains three workflows and three parameter profiles`() {
        val workflows = PromptTestCatalog.ALL_TESTS
        assertEquals(5, workflows.size)
        assertEquals(
            listOf("TEST_PERFORMANCE", "TEST_DMS", "TEST_EXTRACTION", "TEST_TUNING", "TEST_SAMPLING"),
            workflows.map { it.id }
        )

        val variants = PromptTestCatalog.PARAMETER_VARIANTS
        assertEquals(3, variants.size)
        assertEquals(
            listOf("CPU_REFERENCE", "CPU_WIDEBATCH", "VULKAN_OFFLOAD"),
            variants.map { it.id }
        )
        assertEquals(BackendPreference.ACCELERATOR_REQUIRED, variants.last().backendPreference)
        assertEquals(8, PromptTestCatalog.TUNING_VARIANTS.size)
        assertEquals(listOf(2, 4, 6, 8), PromptTestCatalog.TUNING_VARIANTS.take(4).map { it.nThreads })
        assertTrue(PromptTestCatalog.TUNING_VARIANTS.all { it.nThreadsBatch == 8 })
        assertEquals(128, PromptTestCatalog.TUNING_VARIANTS[4].nUbatch)
        assertEquals(BackendPreference.ACCELERATOR_REQUIRED, PromptTestCatalog.TUNING_VARIANTS.last().backendPreference)
        assertEquals(5, PromptTestCatalog.SAMPLING_VARIANTS.size)
        assertEquals(listOf(0.05f, 0.0f, 0.2f, 0.05f, 0.05f), PromptTestCatalog.SAMPLING_VARIANTS.map { it.temperature })
        assertEquals(listOf(20, 20, 20, 40, 20), PromptTestCatalog.SAMPLING_VARIANTS.map { it.topK })
        assertEquals(listOf(0.8f, 0.8f, 0.8f, 0.8f, 0.95f), PromptTestCatalog.SAMPLING_VARIANTS.map { it.topP })
        assertEquals(32, variants.first().nUbatch)
        assertEquals(64, variants[1].nUbatch)
        assertEquals(4, variants[1].nThreads)
        assertEquals(8, variants[1].nThreadsBatch)
        assertEquals(4, variants[2].nThreads)
        assertEquals(8, variants[2].nThreadsBatch)
        assertEquals(BackendPreference.CPU_ONLY, variants[1].backendPreference)
        assertEquals(BackendPreference.ACCELERATOR_REQUIRED, variants[2].backendPreference)
    }

    @Test
    fun `verify GgufMetadataParser validates magic header and rejects invalid files`() {
        // Ungültige Bytes testen
        val invalidBytes = "NOT_GGUF_HEADER_DATA_1234567890".toByteArray(Charsets.US_ASCII)
        val invalidResult = GgufMetadataParser.parse(ByteArrayInputStream(invalidBytes), invalidBytes.size.toLong())
        assertFalse(invalidResult.isValid)
        assertTrue(invalidResult.errorMessage?.contains("Ungültige GGUF-Magicsignatur") == true)

        // Gültigen minimalen GGUF-Header testen: 'G','G','U','F' + Version 3 + 0 Tensoren + 0 KV
        val validHeader = byteArrayOf(
            'G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(),
            3, 0, 0, 0, // Version 3
            0, 0, 0, 0, 0, 0, 0, 0, // tensor_count = 0
            0, 0, 0, 0, 0, 0, 0, 0  // kv_count = 0
        )
        val validResult = GgufMetadataParser.parse(ByteArrayInputStream(validHeader), validHeader.size.toLong())
        assertTrue(validResult.isValid)
        assertEquals("GGUF", validResult.magic)
        assertEquals(3, validResult.version)
    }

    @Test
    fun `verify PromptLab and Diagnose provide bounded scroll lists`() {
        val promptScreen = java.io.File("src/main/java/com/example/ui/screens/PromptLabScreen.kt").readText()
        val diagnoseScreen = java.io.File("src/main/java/com/example/ui/screens/DiagnoseScreen.kt").readText()

        assertTrue(promptScreen.contains(".weight(1f)"))
        assertTrue(promptScreen.contains("testTag(\"prompt_test_list\")"))
        assertTrue(promptScreen.contains(".heightIn(max = 120.dp)"))
        assertTrue(diagnoseScreen.contains(".weight(1f)"))
        assertTrue(diagnoseScreen.contains("testTag(\"diagnose_history_list\")"))
    }

    @Test
    fun `verify MainActivity onCreate launches cleanly`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            assertNotNull(activity)
            assertNotNull(activity.window)
        }
        scenario.close()
    }
    @Test
    fun `chat context mismatch does not leave a streaming placeholder`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = LLMLabViewModel(app)
        val currentConfig = viewModel.config.value

        val modelStateField = LLMLabViewModel::class.java.getDeclaredField("_modelState")
        modelStateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val modelState = modelStateField.get(viewModel) as MutableStateFlow<ModelLifecycleState>
        modelState.value = ModelLifecycleState.Ready(
            fileName = "test.gguf",
            fileSizeBytes = 1L,
            ggufInfo = GgufInfo(
                isValid = true,
                magic = "GGUF",
                version = 3,
                tensorCount = 0,
                kvCount = 0
            ),
            initDurationMs = 1L,
            engineVersion = "test",
            loadedConfig = currentConfig.copy(nCtx = currentConfig.nCtx + 512),
            activeBackendName = "CPU"
        )

        viewModel.sendChatMessage("Teste mit geänderten Context-Parametern")

        val messages = viewModel.chatMessages.value
        assertEquals(3, messages.size)
        assertTrue(messages[1].isUser)
        assertFalse(messages.any { it.isStreaming })
        assertTrue(messages.last().text.contains("Context-Parameter geändert"))
    }
}
