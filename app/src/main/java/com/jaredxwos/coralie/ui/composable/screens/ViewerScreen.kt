package com.jaredxwos.coralie.ui.composable.screens

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.jaredxwos.coralie.R
import com.jaredxwos.coralie.mesh.AppMesh
import com.jaredxwos.coralie.bridge.AppProxy
import com.jaredxwos.coralie.bridge.CoralieEventEmitter
import com.jaredxwos.coralie.bridge.CoralieJavascriptInterface
import com.jaredxwos.coralie.storage.AppStorage
import com.jaredxwos.coralie.storage.AppStorage.internalPathFor
import com.jaredxwos.coralie.timer.AppTimers
import com.jaredxwos.coralie.ui.composable.component.SquareIconButton
import com.jaredxwos.coralie.ui.composable.component.dialogs.AppDialog
import com.jaredxwos.coralie.ui.composable.component.dialogs.ButtonConfig
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.util.concurrent.atomic.AtomicReference

private fun WebSettings.applySecurityModifiers() {
    javaScriptEnabled = true
    domStorageEnabled = true
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

private fun Uri.safeForLog(): String {
    val schemePart = scheme ?: "(no-scheme)"
    val hostPart = host ?: "(no-host)"
    val portPart = if (port >= 0) ":$port" else ""
    val pathPart = encodedPath?.takeIf { it.isNotBlank() } ?: "/"
    return "$schemePart://$hostPart$portPart$pathPart"
}

private const val WEBVIEW_TAG = "CoralieWebView"
private const val WEB_CONSOLE_TAG = "CoralieWebConsole"

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
    val activeHttpRequests by AppProxy.activeRequests.collectAsState()
    val eventEmitterRef = remember {
        AtomicReference<CoralieEventEmitter?>(null)
    }

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
            eventEmitterRef
                .getAndSet(null)
                ?.close()
            AppStorage.closeSpaceSync()
            AppMesh.teardownForPageExit()
            AppTimers.teardownForPageExit()
            AppProxy.teardownForPageExit()
        }
    }
    BackHandler(onBack = { showLeaveConfirmation = true })

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

        if (activeHttpRequests > 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.loading_page_data),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

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
                            // `window.Coralie` is the native object itself. There is no
                            // page-visible transport object and no bridge.js.
                            addJavascriptInterface(
                                CoralieJavascriptInterface(),
                                "Coralie",
                            )

                            val eventEmitter = CoralieEventEmitter(this)
                            eventEmitterRef
                                .getAndSet(eventEmitter)
                                ?.close()
                            val sendEvent: (String, JsonElement) -> Unit =
                                eventEmitter::emit
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
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean {
                                    val blocked = !request.url.isSafe()
                                    if (blocked) {
                                        Log.w(
                                            WEBVIEW_TAG,
                                            "navigation.blocked " +
                                                "mainFrame=${request.isForMainFrame} " +
                                                "method=${request.method} " +
                                                "url=${request.url.safeForLog()}",
                                        )
                                    }
                                    return blocked
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): WebResourceResponse? =
                                    assetLoader.shouldInterceptRequest(
                                        request.url,
                                    )

                                override fun onPageStarted(
                                    view: WebView,
                                    url: String?,
                                    favicon: Bitmap?,
                                ) {
                                    Log.i(
                                        WEBVIEW_TAG,
                                        "page.start assetId=$assetId " +
                                            "url=${url?.let(Uri::parse)?.safeForLog() ?: "(null)"}",
                                    )
                                }

                                override fun onPageFinished(
                                    view: WebView,
                                    url: String?,
                                ) {
                                    Log.i(
                                        WEBVIEW_TAG,
                                        "page.finish assetId=$assetId " +
                                            "url=${url?.let(Uri::parse)?.safeForLog() ?: "(null)"}",
                                    )
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError,
                                ) {
                                    val message =
                                        "resource.error " +
                                            "mainFrame=${request.isForMainFrame} " +
                                            "method=${request.method} " +
                                            "url=${request.url.safeForLog()} " +
                                            "code=${error.errorCode} " +
                                            "description=${error.description}"

                                    if (request.isForMainFrame) {
                                        Log.e(WEBVIEW_TAG, message)
                                    } else {
                                        Log.w(WEBVIEW_TAG, message)
                                    }
                                }

                                override fun onReceivedHttpError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    errorResponse: WebResourceResponse,
                                ) {
                                    val message =
                                        "resource.httpError " +
                                            "mainFrame=${request.isForMainFrame} " +
                                            "method=${request.method} " +
                                            "url=${request.url.safeForLog()} " +
                                            "status=${errorResponse.statusCode} " +
                                            "reason=${errorResponse.reasonPhrase.orEmpty()}"

                                    if (request.isForMainFrame) {
                                        Log.e(WEBVIEW_TAG, message)
                                    } else {
                                        Log.w(WEBVIEW_TAG, message)
                                    }
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(
                                    message: ConsoleMessage,
                                ): Boolean {
                                    val source =
                                        message.sourceId()
                                            ?.let(Uri::parse)
                                            ?.lastPathSegment
                                            ?.takeIf { it.isNotBlank() }
                                            ?: message.sourceId()
                                            ?: "(unknown source)"

                                    val rendered =
                                        "js.${message.messageLevel().name.lowercase()} " +
                                            "assetId=$assetId " +
                                            "source=$source:${message.lineNumber()} " +
                                            "message=${message.message()}"

                                    when (message.messageLevel()) {
                                        ConsoleMessage.MessageLevel.ERROR ->
                                            Log.e(WEB_CONSOLE_TAG, rendered)
                                        ConsoleMessage.MessageLevel.WARNING ->
                                            Log.w(WEB_CONSOLE_TAG, rendered)
                                        ConsoleMessage.MessageLevel.TIP ->
                                            Log.i(WEB_CONSOLE_TAG, rendered)
                                        ConsoleMessage.MessageLevel.LOG ->
                                            Log.i(WEB_CONSOLE_TAG, rendered)
                                        ConsoleMessage.MessageLevel.DEBUG ->
                                            Log.d(WEB_CONSOLE_TAG, rendered)
                                    }
                                    return true
                                }
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