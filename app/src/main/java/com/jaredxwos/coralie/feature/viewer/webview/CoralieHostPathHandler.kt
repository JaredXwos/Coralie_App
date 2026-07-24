package com.jaredxwos.coralie.feature.viewer.webview

import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream

/**
 * Virtual Android counterpart of the browser-host build output:
 *
 *     /Coralie/v2/host.js
 *
 * Android already provides the complete `window.Coralie` implementation
 * through `addJavascriptInterface`. The shared page import must nevertheless
 * resolve successfully, so this handler returns an intentionally empty host
 * bootstrap script rather than bundling the browser implementation in the APK.
 */
internal class CoralieHostPathHandler :
    WebViewAssetLoader.PathHandler {

    override fun handle(
        path: String,
    ): WebResourceResponse? {
        if (path != CORALIE_HOST_SCRIPT_NAME) {
            return null
        }

        val script =
            """
            // Coralie API v2 is supplied natively by Android.
            // Browser host installation is intentionally skipped.
            """.trimIndent()
                .toByteArray(Charsets.UTF_8)

        return WebResourceResponse(
            JAVASCRIPT_MIME_TYPE,
            Charsets.UTF_8.name(),
            HTTP_OK,
            HTTP_OK_REASON,
            mapOf(
                "Cache-Control" to "no-store",
                "X-Content-Type-Options" to
                    "nosniff",
            ),
            ByteArrayInputStream(script),
        )
    }

    private companion object {
        const val JAVASCRIPT_MIME_TYPE =
            "application/javascript"
        const val HTTP_OK = 200
        const val HTTP_OK_REASON = "OK"
    }
}

/**
 * Used by pages containing:
 *
 *     <script src="./Coralie/v2/host.js"></script>
 *
 * A page loaded from `/cache/<page>.html` resolves that relative URL beneath
 * `/cache/`.
 */
internal const val CORALIE_HOST_CACHE_PATH_PREFIX =
    "/cache/Coralie/v2/"

/**
 * Compatibility route for pages that still use:
 *
 *     <script src="/Coralie/v2/host.js"></script>
 */
internal const val CORALIE_HOST_ROOT_PATH_PREFIX =
    "/Coralie/v2/"

private const val CORALIE_HOST_SCRIPT_NAME =
    "host.js"
