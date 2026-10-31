package com.example.engine

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExportManagerIntentTest {
    @Test fun chooserCanBeStartedWithApplicationContext() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        val chooser = ExportManager.createChooserIntent(send, "Diagnosebericht exportieren")
        assertTrue((chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }
    @Test fun shareIntentIncludesUriGrantAndClipData() {
        val uri = Uri.parse("content://de.mrlongnight.llmlab.fileprovider/exports/test.txt")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("test.txt", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = ExportManager.createChooserIntent(send, "Diagnosebericht exportieren")
        assertTrue((send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertTrue((chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertEquals(uri, send.clipData?.getItemAt(0)?.uri)
    }
}