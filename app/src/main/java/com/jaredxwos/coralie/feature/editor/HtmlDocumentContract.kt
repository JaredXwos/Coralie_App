package com.jaredxwos.coralie.feature.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

/** Selects an HTML document whose read grant can survive process restarts. */
internal class HtmlDocumentContract :
    ActivityResultContract<Array<String>, Uri?>() {
    private val delegate = ActivityResultContracts.OpenDocument()

    override fun createIntent(
        context: Context,
        input: Array<String>,
    ): Intent =
        delegate.createIntent(context, input).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        delegate.parseResult(resultCode, intent)

    companion object {
        val MIME_TYPES = arrayOf("text/html")
    }
}
