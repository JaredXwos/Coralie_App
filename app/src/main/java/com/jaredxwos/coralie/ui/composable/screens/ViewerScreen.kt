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
import com.jaredxwos.coralie.capability.PageCapabilities
import com.jaredxwos.coralie.capability.PageCapability
import com.jaredxwos.coralie.session.CapabilityPermissionPrompt
import com.jaredxwos.coralie.session.DomainPermissionPrompt
import com.jaredxwos.coralie.session.PermissionDecision
import com.jaredxwos.coralie.session.SessionPermissionPrompt
import com.jaredxwos.coralie.session.ViewerSession
import com.jaredxwos.coralie.bridge.AppProxy
import com.jaredxwos.coralie.bridge.CoralieJavascriptInterface
import com.jaredxwos.coralie.storage.AppStorage
import com.jaredxwos.coralie.storage.AppStorage.internalPathFor
import com.jaredxwos.coralie.ui.composable.component.SquareIconButton
import com.jaredxwos.coralie.ui.composable.component.dialogs.AppDialog
import com.jaredxwos.coralie.ui.composable.component.dialogs.ButtonConfig
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow

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
    var viewerSession by remember {
        mutableStateOf<ViewerSession?>(null)
    }
    val viewerSessionRef = remember {
        AtomicReference<ViewerSession?>(null)
    }
    val emptyPermissionPromptFlow = remember {
        MutableStateFlow<SessionPermissionPrompt?>(null)
    }

    // Load the persisted page policy before starting any native subsystem.
    LaunchedEffect(assetId, spaceId) {
        Log.d(
            "nav",
            "PageRender launched for asset=$assetId space=$spaceId",
        )

        val html = AppStorage.retrieveHtml(assetId)
            .getOrElse {
                Log.e(
                    "nav",
                    "retrieveHtml failed: ${it.message}",
                    it,
                )
                status = LoadStatus.Failed(
                    "Couldn't load page settings: ${it.message}",
                )
                return@LaunchedEffect
            }

        if (html.spaceId != spaceId) {
            Log.w(
                "nav",
                "route.spaceMismatch assetId=$assetId " +
                    "routeSpaceId=$spaceId dbSpaceId=${html.spaceId}",
            )
        }

        val newSession =
            ViewerSession(
                assetId = assetId,
                spaceId = html.spaceId,
                initialCapabilities =
                    PageCapabilities(html.capabilityMask),
                parentScope = scope,
            )

        viewerSessionRef
            .getAndSet(newSession)
            ?.close()
        viewerSession = newSession

        try {
            newSession.prepare()
        } catch (error: Exception) {
            Log.e(
                "nav",
                "session.prepare failed: ${error.message}",
                error,
            )
            newSession.close()
            viewerSessionRef.compareAndSet(newSession, null)
            viewerSession = null
            status = LoadStatus.Failed(
                "Couldn't prepare page session: ${error.message}",
            )
            return@LaunchedEffect
        }

        Log.i(
            WEBVIEW_TAG,
            "session.loaded assetId=$assetId " +
                "session=${newSession.sessionId} " +
                "granted=${newSession.effectiveCapabilities().toJson()}",
        )

        AppStorage.cache(assetId)
            .onFailure {
                // Fall back to an existing cached copy if one exists.
                if (!internalPathFor(assetId).exists()) {
                    status = LoadStatus.Failed(
                        "Couldn't load html: ${it.message}",
                    )
                    return@LaunchedEffect
                }
            }

        status = LoadStatus.Ready
    }

    DisposableEffect(Unit) {
        onDispose {
            viewerSessionRef
                .getAndSet(null)
                ?.close()
            viewerSession = null
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

        if (
            viewerSession
                ?.hasCapability(PageCapability.HTTP) == true &&
            activeHttpRequests > 0
        ) {
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
                            // `window.Coralie` is the native object itself.
                            val session =
                                checkNotNull(viewerSessionRef.get()) {
                                    "ViewerSession was not prepared"
                                }

                            addJavascriptInterface(
                                CoralieJavascriptInterface(session),
                                "Coralie",
                            )
                            session.attachWebView(this)

                            Log.i(
                                WEBVIEW_TAG,
                                "session.activate " +
                                    "assetId=$assetId " +
                                    "session=${session.sessionId} " +
                                    "granted=${session.effectiveCapabilities().toJson()}",
                            )

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

    val permissionPromptFlow =
        viewerSession?.permissionPrompt
            ?: emptyPermissionPromptFlow
    val permissionPrompt by
        permissionPromptFlow.collectAsState()

    permissionPrompt?.let { prompt ->
        val title: String
        val message: String

        when (prompt) {
            is CapabilityPermissionPrompt -> {
                val capabilityName =
                    when (prompt.capability) {
                        PageCapability.MESH ->
                            stringResource(
                                R.string.capability_mesh_title,
                            )
                        PageCapability.STORAGE ->
                            stringResource(
                                R.string.capability_storage_title,
                            )
                        PageCapability.HTTP ->
                            stringResource(
                                R.string.capability_http_title,
                            )
                        PageCapability.TIMERS ->
                            stringResource(
                                R.string.capability_timers_title,
                            )
                    }

                title =
                    stringResource(
                        R.string.capability_prompt_title,
                    )
                message =
                    stringResource(
                        R.string.capability_prompt_message,
                        name,
                        capabilityName,
                    )
            }

            is DomainPermissionPrompt -> {
                title =
                    stringResource(
                        R.string.domain_prompt_title,
                    )
                message =
                    stringResource(
                        R.string.domain_prompt_message,
                        name,
                        prompt.domain,
                    )
            }
        }

        AppDialog(
            title = title,
            message = message,
            onDismiss = {
                viewerSession?.resolvePermissionPrompt(
                    PermissionDecision.REJECT,
                )
            },
            isWarning = true,
            buttons = listOf(
                ButtonConfig(
                    isWarning = false,
                    text =
                        stringResource(
                            R.string.permission_reject,
                        ),
                    effect = {
                        viewerSession
                            ?.resolvePermissionPrompt(
                                PermissionDecision.REJECT,
                            )
                    },
                ),
                ButtonConfig(
                    isWarning = false,
                    text =
                        stringResource(
                            R.string.permission_allow_once,
                        ),
                    effect = {
                        viewerSession
                            ?.resolvePermissionPrompt(
                                PermissionDecision.ALLOW_ONCE,
                            )
                    },
                ),
                ButtonConfig(
                    isWarning = true,
                    text =
                        stringResource(
                            R.string.permission_allow_always,
                        ),
                    effect = {
                        viewerSession
                            ?.resolvePermissionPrompt(
                                PermissionDecision.ALLOW_ALWAYS,
                            )
                    },
                ),
            ),
        )
    }

}

private sealed interface LoadStatus {
    object Preparing : LoadStatus
    object Ready : LoadStatus
    data class Failed(val message: String) : LoadStatus
}