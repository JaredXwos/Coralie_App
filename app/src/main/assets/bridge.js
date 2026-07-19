// Injected via WebViewCompat.addDocumentStartJavaScript, scoped to
// https://appassets.androidplatform.net. Runs before any page <script>.

(function () {
  "use strict";
  // Everything is nested in this IIFE to prevent the html from affecting it


  // Declares the ID currently active
  // Synchronous JS functions are atomic, so thread safety is not required
  const pending = new Map();



  // [Requests from HTML to Kotlin]
  // Gets the next request ID
  let nextId = 0;
  function nextRequestId() {
    const id = nextId;
    // 0x7FFFFFFF is int max (according to kotlin side, id have to be int)
    nextId = (nextId + 1) & 0x7FFFFFFF;
    return id;
  }
  // When HTML wants to execute a Kotlin callback
  function call(callback, params = null) {
    const id = nextRequestId();
    return new Promise((resolve, reject) => {
      // Preserves the resolve/reject function so that this bridge.js can resolve/reject later on
      pending.set(id, { resolve, reject });
      // Actually send the callback request to kotlin
      window.Coralie.postMessage(JSON.stringify({ id, callback, params }));
    });
  }



  // Responses from Kotlin to HTML
  // Function to check response schema
  function parseResponse(data) {
    let response;
    try {
      response = JSON.parse(data);
    } catch (e) {
      return null;
    }
    if (typeof response !== "object" || response === null)            return null;
    if (response.status !== "SUCCESS" && response.status !== "ERROR") return null;
    if (response.id !== null && typeof response.id !== "number")      return null;
    if (!("data" in response))                                        return null;
    
    return response;
  }


  // When Kotlin responds (Kotlin only messages in response to a callback request in current design)
  window.Coralie.onmessage = function (event) {
    const response = parseResponse(event.data);

    // Check if response fits JSON schema
    if (response === null) {
      console.error("[bridge.js] Invalid response format: ", event.data);
      return;
    }

    // Check if response has ID (required to resolve/reject promise)
    if (response.id === null) {
      console.error("[bridge.js] Kotlin unable to resolve ID: ", response.data);
      return;
    }

    // Check if response ID is active (cannot respond to promises that don't exist)
    const entry = pending.get(response.id);
    if (!entry) {
      console.error("[bridge.js] Response ID not in current records: ", response.id);
      return;
    }

    // Conversation has been resolved
    pending.delete(response.id);
    if (response.status === "ERROR") {
      console.error("[bridge.js] Call", response.id, "failed: ", response.data);
      entry.reject(new Error(response.data));
    } else {
      console.log("[bridge.js] Call", response.id, "succeeded: ", response.data);
      entry.resolve(response.data);
    }
  };

  window.Coralie = { call, onEvent: null };
})();