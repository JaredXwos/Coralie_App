// Injected via WebViewCompat.addDocumentStartJavaScript, scoped to
// https://appassets.androidplatform.net. Runs before any page <script>.

(function () {
  "use strict";

  const transport = window.nativeBridge;

  if (!transport || typeof transport.postMessage !== "function") {
    console.error("[bridge.js] window.nativeBridge transport is unavailable");
    return;
  }

  const pending = new Map();
  const peerListeners = new Set();
  const messageListeners = new Set();
  const failureListeners = new Set();
  const nativeEventListeners = new Map();

  let nextId = 0;
  let currentPubkeyHex = null;
  let currentPeers = [];
  let closed = false;

  function nextRequestId() {
    const id = nextId;
    nextId = (nextId + 1) & 0x7fffffff;
    return id;
  }

  function parseResponse(data) {
    let response;

    try {
      response = JSON.parse(data);
    } catch {
      return null;
    }

    if (typeof response !== "object" || response === null) return null;
    if (response.status !== "SUCCESS" && response.status !== "ERROR") return null;
    if (response.id !== null && !Number.isInteger(response.id)) return null;
    if (!("data" in response)) return null;

    return response;
  }

  function call(callback, params = null) {
    if (typeof callback !== "string" || callback.length === 0) {
      return Promise.reject(new TypeError("callback must be a non-empty string"));
    }

    const id = nextRequestId();

    return new Promise((resolve, reject) => {
      pending.set(id, { resolve, reject });

      try {
        transport.postMessage(JSON.stringify({ id, callback, params }));
      } catch (error) {
        pending.delete(id);
        reject(error instanceof Error ? error : new Error(String(error)));
      }
    });
  }

  transport.onmessage = function (event) {
    const response = parseResponse(event.data);

    if (response === null) {
      console.error("[bridge.js] Invalid response format:", event.data);
      return;
    }

    if (response.id === null) {
      console.error("[bridge.js] Kotlin could not resolve a request ID:", response.data);
      return;
    }

    const entry = pending.get(response.id);

    if (!entry) {
      console.error("[bridge.js] Response ID is not pending:", response.id);
      return;
    }

    pending.delete(response.id);

    if (response.status === "ERROR") {
      entry.reject(new Error(String(response.data)));
    } else {
      entry.resolve(response.data);
    }
  };

  function assertPubkeyHex(pubkeyHex, fieldName) {
    if (typeof pubkeyHex !== "string" || !/^[0-9a-fA-F]{64}$/.test(pubkeyHex)) {
      throw new TypeError(`${fieldName} must be a 64-character hexadecimal public key`);
    }
  }

  function assertUint8Array(payload) {
    if (!(payload instanceof Uint8Array)) {
      throw new TypeError("payload must be a Uint8Array");
    }
  }

  function bytesToBase64(bytes) {
    let binary = "";
    const chunkSize = 0x8000;

    for (let offset = 0; offset < bytes.length; offset += chunkSize) {
      const chunk = bytes.subarray(offset, offset + chunkSize);
      binary += String.fromCharCode.apply(null, chunk);
    }

    return btoa(binary);
  }

  function base64ToBytes(value) {
    if (typeof value !== "string") {
      throw new TypeError("Base64 payload must be a string");
    }

    const binary = atob(value);
    const bytes = new Uint8Array(binary.length);

    for (let index = 0; index < binary.length; index += 1) {
      bytes[index] = binary.charCodeAt(index);
    }

    return bytes;
  }

  function clonePeers(peers) {
    return peers.map((peer) => ({
      pubkeyHex: peer.pubkeyHex,
      connectedAt: peer.connectedAt,
    }));
  }

  function emit(listeners, value) {
    for (const listener of Array.from(listeners)) {
      try {
        listener(value);
      } catch (error) {
        console.error("[bridge.js] Event listener failed:", error);
      }
    }
  }

  function subscribe(listeners, listener, initialValue) {
    if (typeof listener !== "function") {
      throw new TypeError("listener must be a function");
    }

    listeners.add(listener);

    if (initialValue !== undefined) {
      listener(initialValue);
    }

    let subscribed = true;

    return function unsubscribe() {
      if (!subscribed) return;
      subscribed = false;
      listeners.delete(listener);
    };
  }

  function emitNativeEvent(type, data) {
    const listeners = nativeEventListeners.get(type);
    if (!listeners) return;
    emit(listeners, data);
  }

  function onNativeEvent(type, listener) {
    if (typeof type !== "string" || type.length === 0) {
      throw new TypeError("event type must be a non-empty string");
    }

    if (typeof listener !== "function") {
      throw new TypeError("listener must be a function");
    }

    let listeners = nativeEventListeners.get(type);

    if (!listeners) {
      listeners = new Set();
      nativeEventListeners.set(type, listeners);
    }

    listeners.add(listener);

    return function unsubscribe() {
      listeners.delete(listener);
      if (listeners.size === 0) nativeEventListeners.delete(type);
    };
  }

  function handleNativeEvent(type, data) {
    try {
      switch (type) {
        case "peers": {
          if (!Array.isArray(data) || !data.every((value) => typeof value === "string")) {
            throw new TypeError("Invalid peers event payload");
          }

          currentPeers = data.map((pubkeyHex) => ({
            pubkeyHex,
            connectedAt: null,
          }));

          emit(peerListeners, clonePeers(currentPeers));
          break;
        }

        case "message": {
          if (typeof data !== "object" || data === null) {
            throw new TypeError("Invalid message event payload");
          }

          const message = {
            fromPubkeyHex: data.fromPubkeyHex,
            toPubkeyHex: currentPubkeyHex || "",
            timestamp: Date.now(),
            payload: base64ToBytes(data.payload),
          };

          assertPubkeyHex(message.fromPubkeyHex, "fromPubkeyHex");
          emit(messageListeners, message);
          break;
        }

        case "terminalFailure": {
          if (typeof data !== "object" || data === null) {
            throw new TypeError("Invalid terminalFailure event payload");
          }

          const failure = {
            pubkeyHex: data.pubkeyHex,
            attemptCount: data.attemptsMade,
            reason: "retry-exhausted",
          };

          assertPubkeyHex(failure.pubkeyHex, "pubkeyHex");

          if (!Number.isInteger(failure.attemptCount) || failure.attemptCount < 0) {
            throw new TypeError("attemptCount must be a non-negative integer");
          }

          emit(failureListeners, failure);
          break;
        }

        default:
          break;
      }
    } catch (error) {
      console.error(`[bridge.js] Failed to process ${type} event:`, error);
    }

    emitNativeEvent(type, data);
  }


  function resolveLocalStorage() {
    try {
      const storage = window.localStorage;
      if (!storage) return null;

      const probeKey = "__coralie_storage_probe__";
      storage.setItem(probeKey, "1");
      storage.removeItem(probeKey);

      return storage;
    } catch {
      return null;
    }
  }

  function isMissingStorageEntryError(error) {
    const message = error instanceof Error ? error.message : String(error);
    return /no entry named ['"][^'"]+['"] in this scope/i.test(message);
  }

  let localStorageBackend = resolveLocalStorage();

  const coralieStorage = Object.freeze({
    async getItem(key) {
      const normalizedKey = String(key);

      if (localStorageBackend) {
        try {
          return localStorageBackend.getItem(normalizedKey);
        } catch {
          localStorageBackend = null;
        }
      }

      try {
        const value = await call("retrieveValue", { name: normalizedKey });
        return value === null || value === undefined ? null : String(value);
      } catch (error) {
        // Match localStorage.getItem(): a missing key is not an error.
        if (isMissingStorageEntryError(error)) return null;
        throw error;
      }
    },

    async setItem(key, value) {
      const normalizedKey = String(key);
      const normalizedValue = String(value);

      if (localStorageBackend) {
        try {
          localStorageBackend.setItem(normalizedKey, normalizedValue);
          return;
        } catch {
          localStorageBackend = null;
        }
      }

      await call("updateValue", {
        name: normalizedKey,
        value: normalizedValue,
        upsert: true,
      });
    },

    async removeItem(key) {
      const normalizedKey = String(key);

      if (localStorageBackend) {
        try {
          localStorageBackend.removeItem(normalizedKey);
          return;
        } catch {
          localStorageBackend = null;
        }
      }

      try {
        await call("deleteValue", { name: normalizedKey });
      } catch (error) {
        // Match localStorage.removeItem(): deleting a missing key is a no-op.
        if (!isMissingStorageEntryError(error)) throw error;
      }
    },
  });

  const nativeBridge = {
    call,
    onEvent: handleNativeEvent,
    on: onNativeEvent,
  };

  const coralie = {
    storage: coralieStorage,

    async getPubkey() {
      const pubkeyHex = await call("getPubkey");
      assertPubkeyHex(pubkeyHex, "pubkeyHex");
      currentPubkeyHex = pubkeyHex;
      return pubkeyHex;
    },

    async addPeer(pubkeyHex) {
      if (closed) throw new Error("Coralie host is closed");
      assertPubkeyHex(pubkeyHex, "pubkeyHex");
      await call("addPeer", { pubkeyHex });
    },

    async sendMessage(toPubkeyHex, payload) {
      if (closed) throw new Error("Coralie host is closed");
      assertPubkeyHex(toPubkeyHex, "toPubkeyHex");
      assertUint8Array(payload);

      await call("sendMessage", {
        toPubkeyHex,
        payload: bytesToBase64(payload),
      });
    },

    async getPeers() {
      if (closed) return [];

      const pubkeys = await call("getPeers");

      if (!Array.isArray(pubkeys) || !pubkeys.every((value) => typeof value === "string")) {
        throw new TypeError("Invalid getPeers response");
      }

      currentPeers = pubkeys.map((pubkeyHex) => ({
        pubkeyHex,
        connectedAt: null,
      }));

      return clonePeers(currentPeers);
    },

    async reset() {
      const pubkeyHex = await call("resetMesh");
      assertPubkeyHex(pubkeyHex, "pubkeyHex");

      closed = false;
      currentPubkeyHex = pubkeyHex;
      currentPeers = [];
      emit(peerListeners, []);

      return pubkeyHex;
    },

    async close() {
      if (closed) return;

      await call("closeMesh");
      closed = true;
      currentPeers = [];
      emit(peerListeners, []);
    },

    onPeers(listener) {
      return subscribe(peerListeners, listener, clonePeers(currentPeers));
    },

    onMessage(listener) {
      return subscribe(messageListeners, listener);
    },

    onTerminalFailure(listener) {
      return subscribe(failureListeners, listener);
    },
  };

  // Existing Android-only services continue to use NativeBridge.call(...).
  Object.defineProperty(window, "NativeBridge", {
    value: nativeBridge,
    writable: false,
    configurable: false,
    enumerable: true,
  });

  // Portable interface shared with the regular browser implementation.
  Object.defineProperty(window, "Coralie", {
    value: coralie,
    writable: false,
    configurable: false,
    enumerable: true,
  });

  // Prime cached state without delaying document startup.
  Promise.allSettled([coralie.getPubkey(), coralie.getPeers()]).catch(() => {});
})();
