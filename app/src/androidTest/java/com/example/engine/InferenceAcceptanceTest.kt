package com.example.engine

import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeoutException

/**
 * Device acceptance tests for the isolated llama.cpp inference process.
 *
 * Pass a local test model with:
 * adb shell am instrument -w -e modelPath /absolute/path/to/model.gguf \
 *   de.mrlongnight.llmlab.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Alternatively use modelId or modelFilename. Without a model each test is
 * skipped with the JUnit assumption reason "requires device model".
 */
@RunWith(AndroidJUnit4::class)
class InferenceAcceptanceTest {

    private var client: InferenceClient? = null
    private var modelFile: File? = null

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuredModel = TestFixture.resolveModel(context)

        // A runtime assumption is the conditional equivalent of @Ignore:
        // unlike a static @Ignore annotation, it still enables real-device runs.
        Assume.assumeTrue(
            "requires device model",
            configuredModel != null && configuredModel.isFile && configuredModel.length() > 0L
        )

        modelFile = checkNotNull(configuredModel)
        client = InferenceClient(context)
    }

    @After
    fun tearDown() {
        client?.close()
        client = null
    }

    @Test
    fun modelLoadsOnDevice() {
        loadModelOnDevice(requireVulkan = false)
        assertEquals(
            "Inference service must be connected after model loading",
            InferenceConnectionState.CONNECTED,
            checkNotNull(client).connectionState.value
        )
    }

    @Test
    fun shortGenerationProducesTokens() {
        loadModelOnDevice(requireVulkan = false)
        val output = generateOnDevice(prompt = "Hi", maxTokens = 16, timeoutMs = 30_000L)
        assertFalse("Generation output must not be empty", output.isBlank())
    }

    @Test
    fun abortKillsInferenceProcessAndAllowsReload() {
        loadModelOnDevice(requireVulkan = false)

        val inferenceClient = checkNotNull(client)
        val abortFailure = try {
            runBlocking(Dispatchers.IO) {
                inferenceClient.withGuaranteedAbort(500L) { service ->
                    generate(
                        service = service,
                        prompt = "loop",
                        maxTokens = 100_000,
                        timeoutMs = 100_000L
                    )
                }
            }
            null
        } catch (error: Throwable) {
            error
        }

        assertTrue(
            "Expected a hard-abort TimeoutException, got " +
                (abortFailure?.javaClass?.simpleName ?: "successful completion"),
            abortFailure is TimeoutException
        )

        // withGuaranteedAbort must have killed the old :inference process.
        // A fresh bind and model load prove the service can be recreated.
        runBlocking(Dispatchers.IO) { inferenceClient.connect() }
        loadModelOnDevice(requireVulkan = false)
        val output = generateOnDevice(prompt = "Hi", maxTokens = 16, timeoutMs = 30_000L)
        assertFalse("Generation after process restart must work", output.isBlank())
    }

    @Test
    fun repeatedRunsStable() {
        loadModelOnDevice(requireVulkan = false)

        repeat(10) { index ->
            val output = generateOnDevice(
                prompt = "Hi",
                maxTokens = 16,
                timeoutMs = 30_000L
            )
            assertFalse("Run " + (index + 1) + " returned empty output", output.isBlank())
        }
    }

    private fun loadModelOnDevice(requireVulkan: Boolean) {
        val inferenceClient = checkNotNull(client)
        val model = checkNotNull(modelFile)
        runBlocking(Dispatchers.IO) {
            inferenceClient.connect()
            val loadResult = inferenceClient.withGuaranteedAbort(MODEL_LOAD_TIMEOUT_MS) { service ->
                service.prepareForOperation()
                service.loadModel(
                    model.absolutePath,
                    TestFixture.inferenceConfig(requireVulkan).toWireBundle()
                )
            }
            assertTrue(
                "Model load failed: " + loadResult.errorMessage(),
                loadResult.success()
            )
            inferenceClient.connect()
        }
    }

    private fun generateOnDevice(
        prompt: String,
        maxTokens: Int,
        timeoutMs: Long
    ): String {
        val inferenceClient = checkNotNull(client)
        return runBlocking(Dispatchers.IO) {
            inferenceClient.withGuaranteedAbort(timeoutMs) { service ->
                generate(service, prompt, maxTokens, timeoutMs)
            }
        }
    }

    /**
     * Executes one synchronous Binder generation. The surrounding
     * InferenceClient.withGuaranteedAbort call provides the hard deadline.
     */
    private fun generate(
        service: IInferenceService,
        prompt: String,
        maxTokens: Int,
        timeoutMs: Long
    ): String {
        service.prepareForOperation()

        val output = StringBuffer()
        val callback = object : IInferenceCallback.Stub() {
            override fun onToken(tokenText: String, tokenIndex: Int) {
                output.append(tokenText)
            }

            override fun onProgress(progressPercent: Float, step: Int) = Unit
        }

        val timeoutSeconds = (timeoutMs / 1_000L)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val config = TestFixture.inferenceConfig(requireVulkan = false).copy(
            maxTokens = maxTokens,
            timeoutNormalInferenceSec = timeoutSeconds
        )
        val metrics = service.executeInference(prompt, config.toWireBundle(), callback).toNativeMetrics()

        assertNull("Inference returned an error: " + metrics.errorMessage, metrics.errorMessage)
        assertTrue(
            "Inference must report at least one generated token; finishReason=" + metrics.finishReason,
            metrics.generatedTokens > 0
        )
        val generatedText = output.toString()
        assertTrue("Token callback output must not be empty", generatedText.isNotBlank())
        return generatedText
    }

    private companion object {
        const val MODEL_LOAD_TIMEOUT_MS = 180_000L
    }
}

/**
 * Device-local model selection. Instrumentation arguments:
 * - modelPath: absolute or app-local path to a GGUF file
 * - modelFilename: file name or relative path below an app model directory
 * - modelId: id from HuggingFaceModelCatalog
 *
 * With no explicit selection, the first available catalog model is used.
 */
private object TestFixture {

    fun resolveModel(context: Context): File? {
        val arguments = InstrumentationRegistry.getArguments()
        val modelPath = arguments.getString("modelPath")?.trim()?.takeIf { it.isNotEmpty() }
        val modelId = arguments.getString("modelId")?.trim()?.takeIf { it.isNotEmpty() }
        val modelFilename = arguments.getString("modelFilename")?.trim()?.takeIf { it.isNotEmpty() }

        if (modelPath != null && File(modelPath).isAbsolute) {
            return File(modelPath).takeIf { it.isFile && it.length() > 0L }
        }

        val selectedFilename = when {
            modelPath != null -> modelPath
            modelFilename != null -> modelFilename
            modelId != null -> HuggingFaceModelCatalog.find(modelId)?.filename ?: return null
            else -> null
        }

        val filenames = if (selectedFilename != null) {
            listOf(selectedFilename)
        } else {
            HuggingFaceModelCatalog.models.map { it.filename }
        }

        val roots = buildList {
            add(File(context.filesDir, "models"))
            add(context.filesDir)
            context.getExternalFilesDir("models")?.let(::add)
            context.getExternalFilesDir(null)?.let { externalRoot ->
                add(externalRoot)
                add(File(externalRoot, "models"))
            }
            context.getExternalFilesDirs("models").filterNotNull().forEach(::add)
        }.distinctBy { it.absolutePath }

        return filenames.asSequence()
            .flatMap { filename -> roots.asSequence().map { root -> File(root, filename) } }
            .firstOrNull { it.isFile && it.length() > 0L }
    }

    fun inferenceConfig(requireVulkan: Boolean): InferenceConfig {
        val defaults = InferenceConfig.fromPreset(ProfilePreset.POWER_SAVER)
        return defaults.copy(
            nCtx = 2_048,
            nBatch = 256,
            nUbatch = 8,
            backendPreference = if (requireVulkan) {
                BackendPreference.ACCELERATOR_REQUIRED
            } else {
                BackendPreference.AUTO
            }
        )
    }
}
