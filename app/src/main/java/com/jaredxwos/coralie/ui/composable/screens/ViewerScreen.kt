package com.jaredxwos.coralie.ui.composable.screens

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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.jaredxwos.coralie.R
import com.jaredxwos.coralie.mesh.AppMesh
import com.jaredxwos.coralie.bridge.dispatch
import com.jaredxwos.coralie.bridge.AppProxy
import com.jaredxwos.coralie.storage.AppStorage
import com.jaredxwos.coralie.storage.AppStorage.internalPathFor
import com.jaredxwos.coralie.timer.AppTimers
import com.jaredxwos.coralie.ui.composable.component.SquareIconButton
import com.jaredxwos.coralie.ui.composable.component.dialogs.AppDialog
import com.jaredxwos.coralie.ui.composable.component.dialogs.ButtonConfig
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
fun ViewerScreen(
    assetId: Long,
    spaceId: Long,
    name: String,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<LoadStatus>(LoadStatus.Preparing) }
    var showLeaveConfirmation by remember { mutableStateOf(false) }

    // Open the space + refresh-on-open the cache, before pointing the WebView anywhere.
    LaunchedEffect(assetId, spaceId) {
        Log.d("nav", "PageRender launched for asset=${assetId} space=${spaceId}")
        AppStorage.openSpace(spaceId)
            .onSuccess { Log.d("nav", "space opened") }
            .onFailure {
                Log.e("nav", "openSpace failed: ${it.message}")
                status = LoadStatus.Failed("Couldn't open space: ${it.message}")
                return@LaunchedEffect
            }
        AppStorage.cache(assetId)
            .onFailure {
                // Fall back to an existing cached copy if one exists (refresh-on-open model).
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
            AppTimers.teardownForPageExit()
            AppProxy.teardownForPageExit()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // --- Top bar: back / settings row, then title, then divider. Shown in every LoadStatus. ---
        Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SquareIconButton(
                    onClick = { showLeaveConfirmation = true },
                    modifier = Modifier.padding( start = 6.dp )
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.primary,

                        )
                }
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                SquareIconButton(onClick = onSettings,
                    modifier = Modifier.padding( end = 6.dp )
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground, thickness = 2.dp)

        // --- Body: existing per-state rendering, now living below the bar instead of replacing it. ---
        Box(modifier = Modifier.fillMaxSize()) {
            when (val s = status) {
                LoadStatus.Preparing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is LoadStatus.Failed -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onBack) { Text("Back") }
                }
                LoadStatus.Ready -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.applySecurityModifiers()
                            // Shared by AppMesh and AppTimers — both just need "push this
                            // (type, data) pair into the page's onEvent", the dispatch by
                            // event name (peers/message/terminalFailure/timerFired) happens
                            // entirely on the HTML side.
                            val sendEvent: (String, JsonElement) -> Unit = { type, data ->
                                evaluateJavascript(
                                    "window.NativeBridge.onEvent && window.NativeBridge.onEvent(${Json.encodeToString(type)}, $data)",
                                    null
                                )
                            }
                            AppMesh.attach(scope, sendEvent)
                            AppMesh.rebuild()
                            AppTimers.attach(scope, sendEvent)

                            val assetLoader = WebViewAssetLoader.Builder()
                                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                                .addPathHandler(
                                    "/cache/",
                                    WebViewAssetLoader.InternalStoragePathHandler(
                                        context,
                                        File(context.filesDir, "html")
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

                            loadUrl("$ASSET_ORIGIN/cache/${assetId}.html")

                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    }
                )
            }
        }
    }

    if (showLeaveConfirmation) {
        AppDialog(
            title = "Leave ${name}?",
            message = "You'll disconnect from this page and return to the home screen. You can come back anytime — nothing will be deleted.",
            onDismiss = { showLeaveConfirmation = false },
            isWarning = true,
            buttons = listOf(
                ButtonConfig(
                    isWarning = false,
                    text = "Cancel",
                    effect = { showLeaveConfirmation = false }),
                ButtonConfig(
                    isWarning = true,
                    text = "Leave",
                    effect = {
                        showLeaveConfirmation = false
                        onBack()
                    }
                ),
            ),
        )
    }

    // --- Native-HTTP consent prompt: shown whenever AppProxy needs a decision
    // for a domain not already on the allowlist for this page/session. ---
    val permissionDomain by AppProxy.prompt.collectAsState()
    permissionDomain?.let { domain ->
        AppDialog(
            title = "Allow network request?",
            message = "This page is trying to reach \"$domain\". Allow it to send the request?",
            onDismiss = { AppProxy.resolvePrompt(AppProxy.Decision.REJECT) },
            isWarning = true,
            buttons = listOf(
                ButtonConfig(
                    isWarning = false,
                    text = "Reject",
                    effect = { AppProxy.resolvePrompt(AppProxy.Decision.REJECT) }),
                ButtonConfig(
                    isWarning = false,
                    text = "Allow",
                    effect = { AppProxy.resolvePrompt(AppProxy.Decision.ALLOW_ONCE) }),
                ButtonConfig(
                    isWarning = true,
                    text = "Always allow",
                    effect = { AppProxy.resolvePrompt(AppProxy.Decision.ALLOW_ALWAYS) }),
            ),
        )
    }
}

private sealed interface LoadStatus {
    object Preparing : LoadStatus
    object Ready : LoadStatus
    data class Failed(val message: String) : LoadStatus
}