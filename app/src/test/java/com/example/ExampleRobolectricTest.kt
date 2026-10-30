package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.TestRunEntity
import com.example.engine.GgufMetadataParser
import com.example.engine.InferenceConfig
import com.example.engine.ProfilePreset
import com.example.engine.PromptTestCatalog
import com.example.ui.LLMLabViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("LLM Lab", appName)
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
        assertEquals(8, config.nUbatch)
        assertEquals(120, config.timeoutInitSec)
        assertEquals(30, config.timeoutShortTestSec)
        assertEquals(90, config.timeoutNormalInferenceSec)
        assertEquals(120, config.timeoutStressTestSec)

        // Preset-Wechsel testen
        viewModel.setProfilePreset(ProfilePreset.POWER_SAVER)
        assertEquals(4, viewModel.config.value.nUbatch)

        viewModel.setProfilePreset(ProfilePreset.MAX_THROUGHPUT)
        assertEquals(16, viewModel.config.value.nUbatch)
    }

    @Test
    fun `verify PromptTestCatalog contains all 7 required test cases`() {
        val tests = PromptTestCatalog.ALL_TESTS
        assertEquals(7, tests.size)

        val ids = tests.map { it.id }
        assertTrue(ids.contains("TEST_1_OK"))
        assertTrue(ids.contains("TEST_2_DMS"))
        assertTrue(ids.contains("TEST_3_JSON"))
        assertTrue(ids.contains("TEST_4_EXTRACTION"))
        assertTrue(ids.contains("TEST_5_HALLUCINATION"))
        assertTrue(ids.contains("TEST_6_LONG_CONTEXT"))
        assertTrue(ids.contains("TEST_7_REPEAT_10X"))

        val repeatTest = tests.first { it.id == "TEST_7_REPEAT_10X" }
        assertEquals(10, repeatTest.repetitionCount)
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
    fun `verify MainActivity onCreate launches cleanly`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            assertNotNull(activity)
            assertNotNull(activity.window)
        }
        scenario.close()
    }
}
