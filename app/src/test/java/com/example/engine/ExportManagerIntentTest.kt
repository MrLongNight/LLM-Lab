package com.example.engine

import android.content.Intent
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExportManagerIntentTest {
    @Test
    fun chooserCanBeStartedWithApplicationContext() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        val chooser = ExportManager.createChooserIntent(send, "Diagnosebericht exportieren")
        assertTrue((chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }
}