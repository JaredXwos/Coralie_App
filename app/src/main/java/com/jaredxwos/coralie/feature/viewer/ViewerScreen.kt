package com.jaredxwos.coralie.feature.viewer

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.jaredxwos.coralie.feature.viewer.runtime.http.NativeHttpProxy
import com.jaredxwos.coralie.feature.viewer.bridge.CoralieJavascriptInterface
import com.jaredxwos.coralie.feature.viewer.webview.CORALIE_HOST_CACHE_PATH_PREFIX
import com.jaredxwos.coralie.feature.viewer.webview.CORALIE_HOST_ROOT_PATH_PREFIX
import com.jaredxwos.coralie.feature.viewer.webview.CoralieHostPathHandler
import com.jaredxwos.coralie.data.library.model.PageCapability
import com.jaredxwos.coralie.feature.viewer.runtime.permission.CapabilityPermissionPrompt
import com.jaredxwos.coralie.feature.viewer.runtime.permission.DomainPermissionPrompt
import com.jaredxwos.coralie.feature.viewer.runtime.permission.PermissionDecision
import com.jaredxwos.coralie.feature.viewer.runtime.permission.SessionPermissionPrompt
import com.jaredxwos.coralie.ui.component.SquareIconButton
import com.jaredxwos.coralie.ui.component.dialogs.AppDialog
import com.jaredxwos.coralie.ui.component.dialogs.ButtonConfig
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow

private fun WebSettings.applySecurityModifiers() {
    javaScriptEnabled = true
    domStorageEnabled = true
    allowFileAccess = false
    allowContentAccess = false
    javaScriptCanOpenWindowsAutomatically =
        false
    setGeolocationEnabled(false)
    setSupportMultipleWindows(false)

    // The source document is refreshed before every viewer session. Do not
    // let WebView substitute a response retained from an earlier session.
    cacheMode = WebSettings.LOAD_NO_CACHE
}

private const val SCHEME = "https"
private const val HOST =
    "appassets.androidplatform.net"
private const val ASSET_ORIGIN =
    "$SCHEME://$HOST"

private fun Uri.isSafe(): Boolean =
    scheme == SCHEME &&
        host == HOST

private fun Uri.safeForLog(): String {
    val schemePart =
        scheme ?: "(no-scheme)"
    val hostPart =
        host ?: "(no-host)"
    val portPart =
        if (port >= 0) {
            ":$port"
        } else {
            ""
        }
    val pathPart =
        encodedPath
            ?.takeIf { it.isNotBlank() }
            ?: "/"

    return "$schemePart://$hostPart" +
        "$portPart$pathPart"
}

private const val WEBVIEW_TAG =
    "CoralieWebView"
private const val WEB_CONSOLE_TAG =
    "CoralieWebConsole"

@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by
        viewModel.uiState.collectAsState()
    val ready =
        uiState as?
            ViewerUiState.Ready
    val session = ready?.session
    val pageName =
        ready?.page?.name.orEmpty()

    val activeHttpRequests by
        NativeHttpProxy.activeRequests
            .collectAsState()

    var showLeaveConfirmation by remember {
        mutableStateOf(false)
    }

    val webViewRef = remember {
        AtomicReference<WebView?>(null)
    }

    val emptyPermissionPromptFlow =
        remember {
            MutableStateFlow<
                SessionPermissionPrompt?
            >(null)
        }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef
                .getAndSet(null)
                ?.let { webView ->
                    webView.stopLoading()
                    webView.removeJavascriptInterface(
                        "Coralie",
                    )
                    webView.destroy()
                }
        }
    }

    BackHandler {
        if (ready == null) {
            onBack()
        } else {
            showLeaveConfirmation = true
        }
    }

    Column(
        modifier =
            modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme
                        .colorScheme
                        .background,
                ),
        ) {
            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween,
                verticalAlignment =
                    Alignment.CenterVertically,
            ) {
                SquareIconButton(
                    onClick = {
                        if (ready == null) {
                            onBack()
                        } else {
                            showLeaveConfirmation =
                                true
                        }
                    },
                    modifier =
                        Modifier.padding(
                            start = 6.dp,
                        ),
                ) {
                    Icon(
                        painter =
                            painterResource(
                                R.drawable
                                    .ic_arrow_back,
                            ),
                        contentDescription =
                            stringResource(
                                R.string.cd_back,
                            ),
                        tint =
                            MaterialTheme
                                .colorScheme
                                .primary,
                    )
                }

                Text(
                    text = pageName,
                    style =
                        MaterialTheme
                            .typography
                            .titleLarge,
                    fontWeight =
                        FontWeight.Bold,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onPrimaryContainer,
                )

                SquareIconButton(
                    onClick = onSettings,
                    modifier =
                        Modifier.padding(
                            end = 6.dp,
                        ),
                ) {
                    Icon(
                        painter =
                            painterResource(
                                R.drawable
                                    .ic_settings,
                            ),
                        contentDescription =
                            stringResource(
                                R.string.cd_settings,
                            ),
                        tint =
                            MaterialTheme
                                .colorScheme
                                .primary,
                    )
                }
            }
        }

        HorizontalDivider(
            color =
                MaterialTheme
                    .colorScheme
                    .onBackground,
            thickness = 2.dp,
        )

        if (
            session
                ?.hasCapability(
                    PageCapability.HTTP,
                ) == true &&
            activeHttpRequests > 0
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme
                            .colorScheme
                            .surfaceVariant,
                    ),
            ) {
                LinearProgressIndicator(
                    modifier =
                        Modifier.fillMaxWidth(),
                )

                Text(
                    text =
                        stringResource(
                            R.string
                                .loading_page_data,
                        ),
                    style =
                        MaterialTheme
                            .typography
                            .labelMedium,
                    color =
                        MaterialTheme
                            .colorScheme
                            .onSurfaceVariant,
                    modifier =
                        Modifier.padding(
                            horizontal = 12.dp,
                            vertical = 6.dp,
                        ),
                )
            }
        }

        Box(
            modifier =
                Modifier.fillMaxSize(),
        ) {
            when (val state = uiState) {
                ViewerUiState.Loading ->
                    Box(
                        modifier =
                            Modifier.fillMaxSize(),
                        contentAlignment =
                            Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                is ViewerUiState.Failed ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                    ) {
                        Text(
                            text =
                                state.cause.message
                                    ?: stringResource(
                                        R.string
                                            .error_load_html_failed,
                                        "",
                                    ),
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .error,
                        )

                        Button(
                            onClick = onBack,
                        ) {
                            Text(
                                stringResource(
                                    R.string
                                        .button_back,
                                ),
                            )
                        }
                    }

                is ViewerUiState.Ready ->
                    PageWebView(
                        state = state,
                        webViewRef =
                            webViewRef,
                    )
            }
        }
    }

    if (showLeaveConfirmation) {
        AppDialog(
            title =
                stringResource(
                    R.string
                        .leave_page_title,
                    pageName,
                ),
            message =
                stringResource(
                    R.string
                        .leave_page_message,
                ),
            onDismiss = {
                showLeaveConfirmation =
                    false
            },
            isWarning = true,
            buttons = listOf(
                ButtonConfig(
                    isWarning = false,
                    text =
                        stringResource(
                            R.string
                                .button_cancel,
                        ),
                    effect = {
                        showLeaveConfirmation =
                            false
                    },
                ),
                ButtonConfig(
                    isWarning = true,
                    text =
                        stringResource(
                            R.string
                                .button_leave,
                        ),
                    effect = {
                        showLeaveConfirmation =
                            false
                        onBack()
                    },
                ),
            ),
        )
    }

    val permissionPromptFlow =
        session?.permissionPrompt
            ?: emptyPermissionPromptFlow
    val permissionPrompt by
        permissionPromptFlow
            .collectAsState()

    permissionPrompt?.let { prompt ->
        val title: String
        val message: String

        when (prompt) {
            is CapabilityPermissionPrompt -> {
                val capabilityName =
                    capabilityDisplayName(
                        prompt.capability,
                    )

                title =
                    stringResource(
                        R.string
                            .capability_prompt_title,
                    )
                message =
                    stringResource(
                        R.string
                            .capability_prompt_message,
                        pageName,
                        capabilityName,
                    )
            }

            is DomainPermissionPrompt -> {
                title =
                    stringResource(
                        R.string
                            .domain_prompt_title,
                    )
                message =
                    stringResource(
                        R.string
                            .domain_prompt_message,
                        pageName,
                        prompt.domain,
                    )
            }
        }

        AppDialog(
            title = title,
            message = message,
            onDismiss = {
                session
                    ?.resolvePermissionPrompt(
                        PermissionDecision.REJECT,
                    )
            },
            isWarning = true,
            buttons = listOf(
                ButtonConfig(
                    isWarning = false,
                    text =
                        stringResource(
                            R.string
                                .permission_reject,
                        ),
                    effect = {
                        session
                            ?.resolvePermissionPrompt(
                                PermissionDecision
                                    .REJECT,
                            )
                    },
                ),
                ButtonConfig(
                    isWarning = false,
                    text =
                        stringResource(
                            R.string
                                .permission_allow_once,
                        ),
                    effect = {
                        session
                            ?.resolvePermissionPrompt(
                                PermissionDecision
                                    .ALLOW_ONCE,
                            )
                    },
                ),
                ButtonConfig(
                    isWarning = true,
                    text =
                        stringResource(
                            R.string
                                .permission_allow_always,
                        ),
                    effect = {
                        session
                            ?.resolvePermissionPrompt(
                                PermissionDecision
                                    .ALLOW_ALWAYS,
                            )
                    },
                ),
            ),
        )
    }
}

@Composable
private fun PageWebView(
    state: ViewerUiState.Ready,
    webViewRef:
        AtomicReference<WebView?>,
) {
    val page = state.page
    val session = state.session
    val cachedFile = state.cachedFile

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                webViewRef
                    .getAndSet(this)
                    ?.destroy()

                settings
                    .applySecurityModifiers()

                addJavascriptInterface(
                    CoralieJavascriptInterface(
                        session,
                    ),
                    "Coralie",
                )
                session.attachWebView(this)

                Log.i(
                    WEBVIEW_TAG,
                    "session.activate " +
                        "assetId=${page.assetId} " +
                        "session=" +
                        session.sessionId +
                        " granted=" +
                        session
                            .effectiveCapabilities()
                            .toJson(),
                )

                val cacheDirectory =
                    cachedFile.parentFile
                        ?: File(
                            context.filesDir,
                            "html",
                        )

                val coralieHostPathHandler =
                    CoralieHostPathHandler()

                val assetLoader =
                    WebViewAssetLoader
                        .Builder()
                        // Relative imports from a cached page resolve under
                        // /cache/. Register this before the general /cache/
                        // handler so the no-op bootstrap wins.
                        .addPathHandler(
                            CORALIE_HOST_CACHE_PATH_PREFIX,
                            coralieHostPathHandler,
                        )
                        // Retain support for older pages that used the
                        // root-relative /Coralie/v2/host.js URL.
                        .addPathHandler(
                            CORALIE_HOST_ROOT_PATH_PREFIX,
                            coralieHostPathHandler,
                        )
                        .addPathHandler(
                            "/assets/",
                            WebViewAssetLoader
                                .AssetsPathHandler(
                                    context,
                                ),
                        )
                        .addPathHandler(
                            "/cache/",
                            WebViewAssetLoader
                                .InternalStoragePathHandler(
                                    context,
                                    cacheDirectory,
                                ),
                        )
                        .build()

                webViewClient =
                    createWebViewClient(
                        assetId = page.assetId,
                        assetLoader =
                            assetLoader,
                    )

                webChromeClient =
                    createWebChromeClient(
                        assetId =
                            page.assetId,
                    )

                val pageRevision =
                    cachedFile.lastModified()

                Log.i(
                    WEBVIEW_TAG,
                    "page.loadFresh assetId=${page.assetId} " +
                        "bytes=${cachedFile.length()} " +
                        "revision=$pageRevision",
                )

                loadUrl(
                    "$ASSET_ORIGIN/cache/" +
                        cachedFile.name +
                        "?revision=$pageRevision",
                )

                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams
                            .MATCH_PARENT,
                        ViewGroup.LayoutParams
                            .MATCH_PARENT,
                    )
            }
        },
    )
}

private fun createWebViewClient(
    assetId: Long,
    assetLoader: WebViewAssetLoader,
): WebViewClient =
    object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val blocked =
                !request.url.isSafe()

            if (blocked) {
                Log.w(
                    WEBVIEW_TAG,
                    "navigation.blocked " +
                        "mainFrame=" +
                        request.isForMainFrame +
                        " method=" +
                        request.method +
                        " url=" +
                        request.url.safeForLog(),
                )
            }

            return blocked
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? =
            assetLoader
                .shouldInterceptRequest(
                    request.url,
                )

        override fun onPageStarted(
            view: WebView,
            url: String?,
            favicon: Bitmap?,
        ) {
            Log.i(
                WEBVIEW_TAG,
                "page.start " +
                    "assetId=$assetId " +
                    "url=" +
                    (
                        url
                            ?.let(Uri::parse)
                            ?.safeForLog()
                            ?: "(null)"
                        ),
            )
        }

        override fun onPageFinished(
            view: WebView,
            url: String?,
        ) {
            Log.i(
                WEBVIEW_TAG,
                "page.finish " +
                    "assetId=$assetId " +
                    "url=" +
                    (
                        url
                            ?.let(Uri::parse)
                            ?.safeForLog()
                            ?: "(null)"
                        ),
            )
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            val message =
                "resource.error " +
                    "mainFrame=" +
                    request.isForMainFrame +
                    " method=" +
                    request.method +
                    " url=" +
                    request.url.safeForLog() +
                    " code=" +
                    error.errorCode +
                    " description=" +
                    error.description

            if (request.isForMainFrame) {
                Log.e(
                    WEBVIEW_TAG,
                    message,
                )
            } else {
                Log.w(
                    WEBVIEW_TAG,
                    message,
                )
            }
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse:
                WebResourceResponse,
        ) {
            val message =
                "resource.httpError " +
                    "mainFrame=" +
                    request.isForMainFrame +
                    " method=" +
                    request.method +
                    " url=" +
                    request.url.safeForLog() +
                    " status=" +
                    errorResponse.statusCode +
                    " reason=" +
                    errorResponse
                        .reasonPhrase
                        .orEmpty()

            if (request.isForMainFrame) {
                Log.e(
                    WEBVIEW_TAG,
                    message,
                )
            } else {
                Log.w(
                    WEBVIEW_TAG,
                    message,
                )
            }
        }
    }

private fun createWebChromeClient(
    assetId: Long,
): WebChromeClient =
    object : WebChromeClient() {
        override fun onConsoleMessage(
            message: ConsoleMessage,
        ): Boolean {
            val source =
                message.sourceId()
                    ?.let(Uri::parse)
                    ?.lastPathSegment
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: message.sourceId()
                    ?: "(unknown source)"

            val rendered =
                "js." +
                    message
                        .messageLevel()
                        .name
                        .lowercase() +
                    " assetId=$assetId " +
                    "source=$source:" +
                    message.lineNumber() +
                    " message=" +
                    message.message()

            when (message.messageLevel()) {
                ConsoleMessage
                    .MessageLevel.ERROR ->
                    Log.e(
                        WEB_CONSOLE_TAG,
                        rendered,
                    )

                ConsoleMessage
                    .MessageLevel.WARNING ->
                    Log.w(
                        WEB_CONSOLE_TAG,
                        rendered,
                    )

                ConsoleMessage
                    .MessageLevel.TIP,
                ConsoleMessage
                    .MessageLevel.LOG,
                ->
                    Log.i(
                        WEB_CONSOLE_TAG,
                        rendered,
                    )

                ConsoleMessage
                    .MessageLevel.DEBUG ->
                    Log.d(
                        WEB_CONSOLE_TAG,
                        rendered,
                    )
            }

            return true
        }
    }

@Composable
private fun capabilityDisplayName(
    capability: PageCapability,
): String =
    when (capability) {
        PageCapability.MESH ->
            stringResource(
                R.string
                    .capability_mesh_title,
            )

        PageCapability.STORAGE ->
            stringResource(
                R.string
                    .capability_storage_title,
            )

        PageCapability.HTTP ->
            stringResource(
                R.string
                    .capability_http_title,
            )

        PageCapability.TIMERS ->
            stringResource(
                R.string
                    .capability_timers_title,
            )
    }
