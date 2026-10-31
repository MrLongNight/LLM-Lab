package com.example

import com.example.engine.InferenceConfig
import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun appUsesStandaloneApplicationId() {
    assertEquals("de.mrlongnight.llmlab", BuildConfig.APPLICATION_ID)
  }

  @Test
  fun runtimeSettingsDoNotRequireContextReloadButNativeContextSettingsDo() {
    val loaded = InferenceConfig()
    assertTrue(loaded.hasSameNativeContext(loaded.copy(temperature = 0.5f, maxTokens = 128)))
    assertFalse(loaded.hasSameNativeContext(loaded.copy(nUbatch = 16)))
    assertFalse(loaded.hasSameNativeContext(loaded.copy(nThreads = 2)))
    assertFalse(loaded.hasSameNativeContext(loaded.copy(nCtx = loaded.nCtx * 2)))
  }

  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }
}
