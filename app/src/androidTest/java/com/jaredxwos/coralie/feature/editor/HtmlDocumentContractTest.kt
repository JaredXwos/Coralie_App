package com.jaredxwos.coralie.feature.editor

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HtmlDocumentContractTest {
    @Test
    fun usesOpenDocumentWithPersistableReadAccess() {
        val intent = HtmlDocumentContract().createIntent(
            ApplicationProvider.getApplicationContext(),
            HtmlDocumentContract.MIME_TYPES,
        )

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("*/*", intent.type)
        assertTrue(
            intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
                ?.contentEquals(arrayOf("text/html")) == true,
        )
        assertTrue(
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
        assertTrue(
            intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0,
        )
    }
}
