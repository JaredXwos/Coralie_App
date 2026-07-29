package com.jaredxwos.coralie.feature.viewer.webview

import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream

/**
 * Android counterpart of the browser build output:
 *
 *     Coralie/v2/host.js
 *
 * The raw `CoralieNative` Java bridge cannot return JavaScript Promises.
 * This virtual script exposes the public `window.Coralie` facade and converts
 * native HTTP start/result messages into a genuine Promise-based operation.
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
            ANDROID_CORALIE_HOST_SCRIPT
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

        val ANDROID_CORALIE_HOST_SCRIPT =
            """
(() => {
  "use strict";

  if (window.Coralie !== undefined) {
    return;
  }

  const nativeHost = window.CoralieNative;
  if (nativeHost === undefined || nativeHost === null) {
    throw new Error(
      "Android Coralie native host is unavailable",
    );
  }

  const pendingHttp = new Map();
  let requestSequence = 0;

  function nextRequestId() {
    requestSequence += 1;

    if (
      globalThis.crypto &&
      typeof globalThis.crypto.randomUUID === "function"
    ) {
      return globalThis.crypto.randomUUID();
    }

    return (
      "http-" +
      Date.now().toString(36) +
      "-" +
      requestSequence.toString(36) +
      "-" +
      Math.random().toString(36).slice(2)
    );
  }

  function rejectionFromDetail(detail) {
    const error = new Error(
      detail.message || "Native HTTP request rejected",
    );

    error.name =
      detail.errorName || "Error";

    if (detail.scope !== undefined) {
      error.scope = detail.scope;
    }
    if (detail.target !== undefined) {
      error.target = detail.target;
    }
    if (detail.operation !== undefined) {
      error.operation = detail.operation;
    }

    return error;
  }

  window.addEventListener(
    "coralie:httpResult",
    (event) => {
      const detail = event.detail || {};
      const requestId = String(
        detail.requestId || "",
      );
      const pending =
        pendingHttp.get(requestId);

      if (!pending) {
        return;
      }

      pendingHttp.delete(requestId);

      if (detail.ok === true) {
        pending.resolve(
          String(detail.responseJson || ""),
        );
        return;
      }

      pending.reject(
        rejectionFromDetail(detail),
      );
    },
  );

  function httpRequestJson(requestJson) {
    const requestId = nextRequestId();

    return new Promise((resolve, reject) => {
      pendingHttp.set(
        requestId,
        { resolve, reject },
      );

      try {
        nativeHost.httpRequestStart(
          requestId,
          String(requestJson),
        );
      } catch (error) {
        pendingHttp.delete(requestId);
        reject(error);
      }
    });
  }

  function cancelPendingHttp() {
    for (
      const [requestId, pending]
      of pendingHttp
    ) {
      try {
        nativeHost.httpRequestCancel(
          requestId,
        );
      } catch {
        // Session shutdown also cancels native work.
      }

      const error = new Error(
        "Viewer page was unloaded",
      );
      error.name = "AbortError";
      pending.reject(error);
    }

    pendingHttp.clear();
  }

  window.addEventListener(
    "pagehide",
    cancelPendingHttp,
    { once: true },
  );

  const host = Object.freeze({
    apiVersion() {
      return nativeHost.apiVersion();
    },

    hostKind() {
      return nativeHost.hostKind();
    },

    getPubkey() {
      return nativeHost.getPubkey();
    },

    addPeer(pubkeyHex) {
      return nativeHost.addPeer(
        String(pubkeyHex),
      );
    },

    sendMessage(
      toPubkeyHex,
      payload,
    ) {
      const target = String(toPubkeyHex);
      const response = JSON.parse(
        nativeHost.sendMessage(
          target,
          Array.from(payload || []),
        ),
      );

      if (!response.ok) {
        const error = new Error(
          String(
            response.message ||
              "Unable to send message",
          ),
        );
        error.name = String(
          response.errorName ||
            "CoralieHostError",
        );
        error.operation = String(
          response.operation ||
            "sendMessage",
        );
        error.target = String(
          response.target ||
            target,
        );
        throw error;
      }

      return undefined;
    },

    getPeersJson() {
      return nativeHost.getPeersJson();
    },

    reset() {
      return nativeHost.reset();
    },

    close() {
      return nativeHost.close();
    },

    storageGetItem(key) {
      return nativeHost.storageGetItem(
        String(key),
      );
    },

    storageSetItem(key, value) {
      return nativeHost.storageSetItem(
        String(key),
        String(value),
      );
    },

    storageRemoveItem(key) {
      return nativeHost.storageRemoveItem(
        String(key),
      );
    },

    httpRequestJson,

    timerQueue(
      id,
      delaySeconds,
      payload,
    ) {
      return nativeHost.timerQueue(
        id == null ? null : String(id),
        Number(delaySeconds),
        payload == null
          ? null
          : String(payload),
      );
    },

    timerCancel(id) {
      return nativeHost.timerCancel(
        String(id),
      );
    },

    timerListJson() {
      return nativeHost.timerListJson();
    },
  });

  Object.defineProperty(
    window,
    "Coralie",
    {
      value: host,
      writable: false,
      configurable: false,
      enumerable: true,
    },
  );
})();
            """.trimIndent()
    }
}

/**
 * Used by pages containing:
 *
 *     <script src="./Coralie/v2/host.js"></script>
 */
internal const val CORALIE_HOST_CACHE_PATH_PREFIX =
    "/cache/Coralie/v2/"

/**
 * Compatibility route for pages that use:
 *
 *     <script src="/Coralie/v2/host.js"></script>
 */
internal const val CORALIE_HOST_ROOT_PATH_PREFIX =
    "/Coralie/v2/"

private const val CORALIE_HOST_SCRIPT_NAME =
    "host.js"
