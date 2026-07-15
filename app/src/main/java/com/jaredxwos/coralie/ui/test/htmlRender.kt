package com.jaredxwos.coralie.ui.test

import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.jaredxwos.coralie.mesh.AppMesh
import com.jaredxwos.coralie.bridge.dispatch
import com.jaredxwos.coralie.storage.AppStorage
import com.jaredxwos.coralie.storage.AppStorage.internalPathFor
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

private fun WebSettings.applySecurityModifiers() {
    javaScriptEnabled = true
    allowFileAccess = false
    allowContentAccess = false
    javaScriptCanOpenWindowsAutomatically = false
    setGeolocationEnabled(false)
    setSupportMultipleWindows(false)
}
private const val SCHEME = "https"
private const val HOST = "appassets.androidplatform.net"
private const val ASSET_ORIGIN = "$SCHEME://$HOST"
private fun Uri.isSafe(): Boolean = scheme == SCHEME && host == HOST
private val allowedOriginRules = setOf(ASSET_ORIGIN)

@Composable
fun PageRender(
    assetId: Long,
    spaceId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<LoadStatus>(LoadStatus.Preparing) }

    // Open the space + refresh-on-open the cache, before pointing the WebView anywhere.
    LaunchedEffect(assetId, spaceId) {
        Log.d("nav", "PageRender launched for asset=$assetId space=$spaceId")
        AppStorage.openSpace(spaceId)
            .onSuccess { Log.d("nav", "space opened") }
            .onFailure { Log.e("nav", "openSpace failed: ${it.message}"); status = LoadStatus.Failed("Couldn't open space: ${it.message}"); return@LaunchedEffect }
        AppStorage.cache(assetId)
            .onFailure {
                // Fall back to an existing cached copy if one exists (your refresh-on-open model).
                if (!internalPathFor(assetId).exists()) {
                    status = LoadStatus.Failed("Couldn't load html: ${it.message}")
                    return@LaunchedEffect
                }
            }
        status = LoadStatus.Ready
    }

    DisposableEffect(Unit) {
        onDispose {
            AppStorage.closeSpaceSync()
            AppMesh.teardownForPageExit()
        }
    }

    when (val s = status) {
        LoadStatus.Preparing -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is LoadStatus.Failed  -> Column(modifier.padding(16.dp)) {
            Text(s.message, color = MaterialTheme.colorScheme.error)
            Button(onClick = onBack) { Text("Back") }
        }
        LoadStatus.Ready -> AndroidView(
            modifier = modifier,
            factory = { context ->
                WebView(context).apply {
                    settings.applySecurityModifiers()
                    AppMesh.attach(scope) { type, data ->
                        evaluateJavascript(
                            "window.NativeBridge.onEvent && window.NativeBridge.onEvent(${Json.encodeToString(type)}, $data)",
                            null
                        )
                    }
                    AppMesh.rebuild()

                    val assetLoader = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                        .addPathHandler(
                            "/cache/",
                            WebViewAssetLoader.InternalStoragePathHandler(
                                context,
                                File(context.filesDir, "html")   // must match internalPathFor's directory
                            )
                        )
                        .build()

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            !request.url.isSafe()
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                            assetLoader.shouldInterceptRequest(request.url)
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                            Log.d("WebConsole", "${m.message()} -- line ${m.lineNumber()} of ${m.sourceId()}")
                            return true
                        }
                    }

                    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
                        WebViewCompat.addWebMessageListener(this, "nativeBridge", allowedOriginRules) { _, message, _, _, replyProxy ->
                            scope.launch { replyProxy.postMessage(Json.encodeToString(dispatch(message))) }
                        }

                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        val bridgeJs = context.assets.open("bridge.js").bufferedReader().use { it.readText() }
                        WebViewCompat.addDocumentStartJavaScript(this, bridgeJs, allowedOriginRules)
                    }

                    loadUrl("$ASSET_ORIGIN/cache/$assetId.html")

                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            }
        )
    }
}

private sealed interface LoadStatus {
    object Preparing : LoadStatus
    object Ready : LoadStatus
    data class Failed(val message: String) : LoadStatus
}
