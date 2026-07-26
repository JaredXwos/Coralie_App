# Coralie for Android

Android host for portable, single-file HTML applications.

Coralie allows an imported HTML page to use persistent storage, explicit peer-to-peer messaging, HTTPS requests, and host-managed timers through the shared `window.Coralie` API. The same page can also be hosted as a static website and communicate with compatible Coralie clients running in a browser.

This repository contains the multi-module Android application distributed through Google Play, together with the Android implementations of Coralie's identity, signalling, WebRTC transport, and mesh-connection layers.

> [!NOTE]
> This README is primarily for contributors and developers working on the Android application. End users should install the published app through Google Play when a production release is available.

> [!WARNING]
> This repository is unlicensed. No permission is granted to copy, modify, redistribute, or publish this project's source code except where separately agreed by the copyright holder. Third-party components remain subject to their own licences.

---

## Contents

- [Overview](#overview)
- [What the Android host provides](#what-the-android-host-provides)
- [Project status and limitations](#project-status-and-limitations)
- [Architecture](#architecture)
- [Gradle modules](#gradle-modules)
- [Repository structure](#repository-structure)
- [The page runtime](#the-page-runtime)
- [Capabilities and permissions](#capabilities-and-permissions)
- [Networking](#networking)
- [Storage spaces](#storage-spaces)
- [Native HTTP](#native-http)
- [Timers](#timers)
- [Building the app](#building-the-app)
- [Testing](#testing)
- [Development guidance](#development-guidance)
- [Release builds](#release-builds)
- [Security](#security)
- [Related repository](#related-repository)
- [Licence](#licence)

---

## Overview

Coralie is designed for small applications that developers want to distribute as a single HTML file.

Typical uses include:

- live party games;
- classroom and workshop activities;
- small collaborative tools;
- rapidly editable multiplayer prototypes; and
- portable utilities that need local persistence or controlled HTTPS access.

A page developer does not need to create a separate Android project. The page uses the same JavaScript interface in both supported environments:

```js
window.Coralie
```

The Android application imports and manages HTML pages, provides a capability-controlled native runtime, and renders each page inside a WebView.

A page may therefore be:

1. written as one HTML file;
2. tested from a static web host;
3. imported into the Android app; and
4. used in a mixed Android/browser peer session.

Coralie deliberately focuses on explicit, live sessions. It does not provide public discovery, matchmaking, user accounts, durable multiplayer servers, or offline peer delivery.

---

## What the Android host provides

Imported pages can request the following capabilities:

| Capability | Android implementation |
|---|---|
| `mesh` | Nostr-signalled WebRTC peer connections and binary application messages |
| `storage` | Persistent key-value storage scoped through an app-managed storage space |
| `http` | App-mediated HTTPS requests with domain permission checks |
| `timers` | Named native timers that emit Coralie timer events |
| Runtime facade | Coralie API v2 exposed as `window.Coralie` |

The app also provides:

- importing, replacing, and deleting HTML pages;
- per-page capability declarations;
- session and persistent permission decisions;
- per-domain HTTP permissions;
- reusable storage spaces;
- storage-usage management;
- a Compose-based page library and settings interface;
- WebView lifecycle and bridge management;
- peer-state and message events delivered to JavaScript; and
- interoperability with the Coralie browser runtime.

---

## Project status and limitations

### Explicit joining only

Coralie does not discover nearby devices or publish a list of rooms.

Applications exchange a participant's Coralie public key through an application-defined mechanism such as:

- a room code;
- a QR code;
- a copied link;
- a messaging application; or
- direct entry.

The runtime itself accepts a 64-character hexadecimal public key.

### Live sessions

Coralie is intended for sessions in which participants are online at the same time.

It does not provide:

- offline messages;
- persistent shared game servers;
- server-authoritative state;
- matchmaking;
- account authentication; or
- automatic state recovery after every participant leaves.

Applications are responsible for their own message protocol, state replication, validation, and conflict rules.

### No TURN

The current peer stack uses STUN but does not provide TURN relay fallback.

Connections may fail on restrictive corporate networks, carrier networks, VPNs, firewalls, or NAT configurations that do not permit a direct WebRTC route. Same-LAN operation is generally the most reliable.

### Browser topology limitation

Android clients can communicate with browser clients through the shared Coralie protocol.

Because browsers conceal some local ICE candidates behind mDNS, browser-only rooms are not supported as a general topology in the current design. A mixed room requires an Android participant where the browser topology cannot form directly.

Coralie does not automatically forward arbitrary application messages through an intermediary. Pages must explicitly replicate or rebroadcast state when their protocol requires it.

### External infrastructure

Coralie avoids an application-specific multiplayer backend, but it is not infrastructure-free.

The connection stack currently relies on:

- Nostr relays for encrypted WebRTC signalling; and
- STUN servers for network-path discovery.

Normal application messages travel through WebRTC data channels after peers connect.

---

## Architecture

The project is split into five Gradle modules:

```text
:app
:connection
:identity
:signalling
:transport
```

Conceptually, the runtime is layered as follows:

```text
┌──────────────────────────────────────────────────────────┐
│ :app                                                     │
│ Compose UI, page library, WebView runtime, permissions,  │
│ storage spaces, native HTTP, timers, and JS bridge       │
└───────────────────────────┬──────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────┐
│ :connection                                              │
│ Mesh orchestration, retries, peer announcements,         │
│ application frames, and connection lifecycle             │
└───────────────┬───────────────────────┬──────────────────┘
                │                       │
                ▼                       ▼
┌─────────────────────────┐   ┌────────────────────────────┐
│ :signalling             │   │ :transport                 │
│ Encrypted Nostr offer/  │   │ WebRTC offer/answer        │
│ answer exchange, relay  │   │ contexts, data channels,   │
│ sessions, and deduping  │   │ ICE, and link lifecycle    │
└───────────────┬─────────┘   └────────────────────────────┘
                │
                ▼
┌──────────────────────────────────────────────────────────┐
│ :identity                                                │
│ Nostr-compatible signing, events, and key utilities      │
└──────────────────────────────────────────────────────────┘
```

The exact Gradle dependency graph is defined by the module build scripts. The diagram describes responsibility rather than every direct dependency.

---

## Gradle modules

### `:app`

The installable Android application.

Primary responsibilities:

- application startup and dependency wiring;
- Compose navigation and screens;
- importing and managing HTML pages;
- Room-backed page, storage, and permission data;
- page capability declarations;
- storage-space management;
- the WebView viewer;
- the private Android JavaScript bridge;
- the public `window.Coralie` facade;
- native HTTP dispatch;
- native timer management;
- runtime permission prompts; and
- lifecycle coordination for the application mesh.

Notable areas:

```text
app/src/main/java/com/jaredxwos/coralie/
├── app/
├── data/
├── feature/
│   ├── editor/
│   ├── home/
│   ├── settings/
│   └── viewer/
└── ui/
```

The viewer runtime is concentrated under:

```text
app/src/main/java/com/jaredxwos/coralie/feature/viewer/
├── bridge/
├── runtime/
│   ├── http/
│   ├── mesh/
│   ├── permission/
│   └── timer/
└── webview/
```

### `:connection`

Coordinates the live peer mesh.

Primary responsibilities:

- initiating explicit peer connections;
- consuming inbound signalling offers and answers;
- retry and handshake-timeout handling;
- tracking connected peers;
- framing application and peer-announcement messages;
- learning additional peers from announcements;
- emitting incoming application messages;
- reporting terminal connection failures; and
- composing the identity, signalling, and transport modules into a live mesh.

The connection manager uses a confined coroutine dispatcher for mesh-state mutation and exposes peer state through Kotlin flows and channels.

### `:identity`

Contains Nostr-compatible identity primitives.

Primary responsibilities:

- signer operations;
- public-key derivation;
- event construction and signing;
- conversation-key support used by signalling; and
- shared identity utilities.

This module is intentionally independent of Android UI concerns.

### `:signalling`

Provides encrypted Nostr signalling.

Primary responsibilities:

- relay WebSocket management;
- relay reconnect backoff;
- Nostr subscription and publishing sessions;
- signalling event parsing;
- NIP-44 encryption and decryption;
- duplicate-event suppression; and
- delivery of inbound offer/answer messages to the connection layer.

Signalling messages coordinate WebRTC setup. They are not the normal transport for application messages.

### `:transport`

Provides the WebRTC transport layer.

Primary responsibilities:

- initiator and answerer contexts;
- SDP offer and answer handling;
- ICE configuration;
- data-channel creation and acceptance;
- handshake timeout tracking;
- link-state reporting;
- incoming byte streams; and
- peer-link lifecycle management.

This module contains the Android/WebRTC-specific transport implementation but does not manage rooms, application state, or page permissions.

---

## Repository structure

A complete checkout is expected to resemble:

```text
.
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       ├── test/
│       └── androidTest/
├── connection/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       ├── test/
│       └── androidTest/
├── identity/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       └── test/
├── signalling/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       ├── test/
│       └── androidTest/
├── transport/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       ├── test/
│       └── androidTest/
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
└── README.md
```

Generated output, local IDE state, signing material, and machine-specific configuration should not be committed.

At minimum, `.gitignore` should exclude:

```gitignore
.idea/
.gradle/
local.properties
**/build/
*.iml
*.apk
*.aab
*.jks
*.keystore
signing.properties
service-account.json
```

Commit the Gradle wrapper.

---

## The page runtime

An imported page interacts with the Android host through:

```js
window.Coralie
```

The Android bridge object is private implementation detail:

```text
CoralieNative
```

Pages must not call it directly.

The runtime serves or intercepts the standard host path:

```html
<script src="./Coralie/v2/host.js"></script>
```

The host script wraps the private native bridge and exposes the same page-facing facade used by the browser runtime.

### API version

The current Android host implements:

```text
Coralie API v2
```

Pages should verify the version during startup:

```js
const host = window.Coralie;

if (!host) {
  throw new Error("window.Coralie is unavailable");
}

if (Number(await host.apiVersion()) !== 2) {
  throw new Error("Coralie API v2 is required");
}
```

The Android implementation reports:

```js
await host.hostKind(); // "android-native"
```

### Portable method groups

The v2 facade includes:

```text
Host
  apiVersion()
  hostKind()

Mesh
  getPubkey()
  addPeer()
  sendMessage()
  getPeersJson()
  reset()
  close()

Storage
  storageGetItem()
  storageSetItem()
  storageRemoveItem()

HTTP
  httpRequestJson()

Timers
  timerQueue()
  timerCancel()
  timerListJson()
```

Runtime events include:

```text
coralie:peers
coralie:message
coralie:terminalFailure
coralie:httpResult
coralie:timerFired
```

The canonical page-facing API specification should remain in the browser-runtime repository so both hosts implement one contract.

<!-- Replace OWNER/REPOSITORY with the browser runtime repository. -->
See: `https://github.com/OWNER/REPOSITORY/blob/main/docs/runtime-api-v2.md`

---

## Capabilities and permissions

Each imported page declares which native capabilities it may use:

```text
mesh
storage
http
timers
```

Capability declarations do not silently grant every operation. The viewer session authorises each native call and can request a user decision.

Supported decision scopes should be documented in the UI and implementation, including:

- allow for the current session;
- allow persistently for the page;
- reject.

HTTP access also uses destination-domain permissions.

### Capability boundaries

| Capability | Examples |
|---|---|
| `mesh` | Read the local public key, add peers, send messages, reset or close the mesh |
| `storage` | Read, write, and remove values in the selected storage space |
| `http` | Start HTTPS requests through the native HTTP dispatcher |
| `timers` | Queue, list, and cancel named timers |

A page that does not declare a capability must not be able to use it.

Permission rejection is surfaced to JavaScript as a runtime error rather than being silently ignored.

---

## Networking

### Signalling flow

```text
Room code or exchanged public key
                │
                ▼
Encrypted Nostr signalling event
                │
                ▼
SDP offer/answer exchange
                │
                ▼
Direct WebRTC data channel
                │
                ▼
Application byte messages
```

The signalling layer:

- uses Nostr-compatible signed events;
- encrypts signalling content using NIP-44;
- publishes the designated Coralie signalling event kind;
- subscribes for events addressed to the local public key;
- deduplicates events received from several relays; and
- reconnects relay sockets with backoff.

### Mesh behaviour

The connection layer:

- accepts explicitly supplied peer keys;
- ignores self-connections and duplicate additions;
- retries failed initiations;
- applies handshake timeouts;
- reports a terminal failure after the configured attempt limit;
- tracks directly connected peers;
- broadcasts peer announcements to established links; and
- opens additional direct links when a new peer is learned.

Peer announcements are not global discovery and do not guarantee that every network topology can form a complete mesh.

### Application protocol

Coralie transports byte payloads. It does not define game messages.

Pages should define and validate an application protocol containing fields such as:

```json
{
  "protocol": 1,
  "type": "state-update",
  "sessionId": "example-session",
  "sequence": 12,
  "payload": {}
}
```

Every peer is an untrusted client. Pages must validate message types, sizes, identifiers, state transitions, and sender authority.

---

## Storage spaces

Android storage is app-managed rather than browser-origin storage.

Imported pages can be assigned to storage spaces. A storage space allows selected pages to share persistent key-value data deliberately, while unrelated pages can remain separated.

Storage methods mirror common `localStorage` behaviour:

- a missing key returns `null`;
- setting an existing key replaces it;
- removing a missing key is a no-op; and
- values are strings.

The app persists page, space, value, and permission records through Room.

Storage is not encrypted merely because it is accessed through Coralie. Do not store secrets without an application-level encryption and key-management design.

---

## Native HTTP

The Android host provides app-mediated HTTPS requests.

The JavaScript facade creates a request ID and Promise, invokes the native dispatcher without blocking the JavaScript bridge thread, and receives completion through a Coralie event.

The native implementation includes:

- capability authorisation;
- per-domain permission checks;
- asynchronous execution;
- cancellation;
- response metadata;
- structured errors; and
- a maximum response size.

The current maximum response body is:

```text
64 MiB
```

Pages should still avoid large responses and use pagination where possible.

Only HTTPS destinations should be accepted. Redirects must continue to satisfy the runtime's security and permission policy.

Android native HTTP is not identical to browser Fetch. In particular, Android is not constrained by browser CORS, so every page must be tested in both environments when browser compatibility is required.

---

## Timers

Pages with the `timers` capability can queue named timers.

```js
const id = await window.Coralie.timerQueue(
  "round-deadline",
  90,
  JSON.stringify({ round: 4 })
);
```

Timer IDs are application-defined. Passing `null` requests an automatically generated ID.

A timer can be:

- listed;
- replaced by reusing its ID;
- cancelled; and
- observed through `coralie:timerFired`.

Pages should store absolute deadlines in replicated application state and treat a timer event as a request to re-evaluate that deadline. Mobile suspension, process death, or platform scheduling can affect exact delivery time.

---

## Building the app

### Requirements

Use:

- a current Android Studio installation compatible with the project;
- the JDK selected by the checked-in Gradle configuration;
- the Android SDK platforms and build tools requested by Gradle; and
- an Android device or emulator for instrumentation tests.

The Gradle wrapper is authoritative for the Gradle version.

### Clone and build

```bash
git clone <repository-url>
cd <repository-directory>
./gradlew :app:assembleDebug
```

On Windows:

```powershell
git clone <repository-url>
cd <repository-directory>
.\gradlew.bat :app:assembleDebug
```

The debug APK is generated under:

```text
app/build/outputs/apk/debug/
```

### Install a debug build

```bash
./gradlew :app:installDebug
```

A connected device or running emulator is required.

### Clean

```bash
./gradlew clean
```

Do not commit generated `build/` directories.

---

## Testing

### All local unit tests

```bash
./gradlew test
```

### Module-specific unit tests

```bash
./gradlew :identity:test
./gradlew :signalling:test
./gradlew :transport:test
./gradlew :connection:test
./gradlew :app:testDebugUnitTest
```

Exact task names may vary by Android Gradle Plugin and module type. Use:

```bash
./gradlew tasks
```

to inspect available tasks.

### Instrumented tests

```bash
./gradlew connectedAndroidTest
```

These require an emulator or physical Android device.

Instrumented coverage includes areas that cannot be validated reliably on the local JVM, such as:

- the WebView bridge;
- WebRTC implementation behaviour;
- page loading;
- runtime injection;
- Android permissions;
- UI interaction; and
- real relay or device loopback scenarios.

### Network-dependent tests

Tests that use public relays, STUN, or real WebRTC networking may fail because of external service availability or the current network environment.

Keep deterministic local tests separate from real-network validation where possible.

### Recommended pre-commit checks

```bash
./gradlew test
./gradlew :app:assembleDebug
```

Before a release candidate:

```bash
./gradlew test
./gradlew connectedAndroidTest
./gradlew :app:bundleRelease
```

---

## Development guidance

### Preserve module boundaries

New code should live in the module that owns the responsibility:

- identity and event signing in `:identity`;
- relay and encrypted signalling in `:signalling`;
- WebRTC link mechanics in `:transport`;
- mesh orchestration in `:connection`;
- Android UI, persistence, permissions, and WebView hosting in `:app`.

Avoid making lower-level modules depend on the application module.

### Keep the page API host-neutral

Page code should use only `window.Coralie`.

Do not expose Android implementation details as part of the portable contract unless the browser host can implement equivalent behaviour or the API explicitly defines a platform-specific extension.

### Keep the native bridge private

The JavaScript interface installed as `CoralieNative` exists to support the v2 facade. It is not a stable public API.

Changes to bridge method names or event plumbing are acceptable only when the public `window.Coralie` contract remains compatible.

### Maintain Android/browser conformance

Any observable change to the page-facing API should be tested against both hosts.

Conformance checks should cover:

```text
apiVersion() returns 2
all required methods exist
public keys use lowercase 64-character hex
peer events contain complete snapshots
message payloads contain unsigned bytes
missing storage keys return null
HTTP responses share the same top-level schema
timer events share the same detail schema
reset returns the replacement identity
close emits an empty peer snapshot
```

Known, deliberate host differences belong in the compatibility documentation.

### Prefer source changes over generated patches

Where JavaScript runtime assets are generated from the browser repository, update the source runtime and regenerate the Android-consumed asset rather than editing a compiled file manually.

Pin or record the browser runtime version consumed by Android.

---

## Release builds

Production releases are distributed through Google Play.

Release configuration should remain outside version control where it contains secrets.

Do not commit:

- signing keystores;
- signing passwords;
- Play Console service-account credentials;
- private API credentials; or
- local release configuration.

A release build is typically produced with:

```bash
./gradlew :app:bundleRelease
```

The Android App Bundle is generated under:

```text
app/build/outputs/bundle/release/
```

Before publishing:

1. run unit and instrumentation tests;
2. verify Android/browser API conformance;
3. confirm the target and minimum SDK configuration;
4. regenerate the packaged Coralie host asset;
5. review WebView and network-security policy;
6. verify native debug-symbol output where required;
7. inspect third-party licence obligations;
8. increment `versionCode` and `versionName`; and
9. test the signed bundle through an internal or closed Play track.

The Play Store listing should be treated as the end-user documentation surface. This repository README remains focused on implementation and contribution.

---

## Security

Imported HTML is active code and must be treated as untrusted.

The Android host limits page authority through:

- declared capabilities;
- runtime permission checks;
- session and persistent decisions;
- per-domain HTTP permissions;
- storage-space boundaries;
- a private native bridge;
- controlled WebView resource handling; and
- app-mediated network operations.

These controls reduce risk but do not make unknown HTML safe automatically.

### Contributor requirements

Changes affecting the viewer must consider:

- JavaScript-interface exposure;
- WebView navigation;
- direct network access;
- file and content URI handling;
- cross-page storage access;
- domain redirects;
- HTTP response limits;
- lifecycle cleanup;
- wake-lock duration;
- message-size limits; and
- permission persistence.

Do not log:

- full HTTP request bodies;
- authentication tokens;
- sensitive stored values;
- complete user-provided URLs with private query parameters; or
- private application payloads.

Security issues should be reported privately to the repository owner rather than disclosed through a public issue containing exploit details.

---

## Related repository

The Coralie browser runtime provides:

- the standalone `Coralie/v2/host.js`;
- the browser implementation of `window.Coralie`;
- the JavaScript/TypeScript connection library;
- the canonical Coralie API v2 documentation; and
- browser compatibility guidance.

<!-- Replace OWNER/REPOSITORY with the browser runtime repository. -->
Browser repository:

```text
https://github.com/OWNER/REPOSITORY
```

The Android and browser repositories should remain version-aligned through shared conformance tests and explicit runtime-version updates.

---

## Licence

This project is unlicensed.

Unless the copyright holder grants separate permission, no permission is provided to use, copy, modify, distribute, sublicense, or publish this source code.

Third-party libraries and bundled components retain their respective licences and notice requirements. Those obligations apply independently of this project's unlicensed status.
