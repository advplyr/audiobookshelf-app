# mTLS Integration Planning

## Current State

mTLS client certificate support has been added across multiple commits on the `local-build` branch (commits `72c39483` through `22a18c68`). The current implementation patches mTLS into each HTTP client individually, leading to maintenance burden and timing/state issues.

### Files Modified for mTLS

| File | Purpose |
|------|---------|
| `android/.../managers/MtlsManager.kt` | Core mTLS manager — cert selection, OkHttpClient building, global SSL factory |
| `android/.../server/ApiHandler.kt` | API requests — maintains its own mTLS-aware OkHttpClient pair |
| `android/.../managers/InternalDownloadManager.kt` | File downloads — builds its own mTLS OkHttpClient in constructor |
| `android/.../MainActivity.kt` | WebView `onReceivedClientCertRequest` handler for socket.io |
| `android/.../plugins/AbsDatabase.kt` | Capacitor plugin bridge for cert selection/clearing from frontend |
| `android/.../plugins/AbsDownloader.kt` | Download plugin (minor mTLS-related change) |
| `components/connection/ServerConnectForm.vue` | Frontend UI for certificate selection |
| `plugins/capacitor/AbsDatabase.js` | JS bridge for cert operations |
| `plugins/db.js` | JS database helpers for cert state |
| `plugins/server.js` | Socket.io connection (runs in WebView, covered by WebViewClient) |

---

## HTTP Connection Points (Full Inventory)

### Native Kotlin (Android)

| Location | Client Type | mTLS Status | Notes |
|----------|-------------|-------------|-------|
| `ApiHandler.kt:49-50` | OkHttpClient (default + ping) | YES (via `refreshMtlsClients`) | Has own state tracking (`mtlsConfiguredFor`) |
| `ApiHandler.kt:162` | `makeRequest()` — all API calls | YES | Calls `ensureMtlsConfigured()` before each request |
| `InternalDownloadManager.kt:21-25` | OkHttpClient (downloads) | YES | Built once in constructor; won't update if cert changes mid-session |
| `PlayerNotificationService.kt:496` | `DefaultHttpDataSource.Factory()` (direct streaming) | INDIRECT | Uses `HttpURLConnection` internally — covered by global SSL factory |
| `PlayerNotificationService.kt:513` | `DefaultHttpDataSource.Factory()` (HLS streaming) | INDIRECT | Same as above — relies on global SSL factory being set before playback starts |

### JavaScript (WebView/Frontend)

| Location | Client Type | mTLS Status | Notes |
|----------|-------------|-------------|-------|
| `plugins/nativeHttp.js` | `CapacitorHttp` | INDIRECT | Uses `HttpURLConnection` on Android — covered by global SSL factory |
| `plugins/server.js` | `socket.io-client` | YES | Runs in WebView; handled by `onReceivedClientCertRequest` in MainActivity |

---

## Known Issue: "sync: Local progress sync failed"

### Error Source
`MediaProgressSyncer.kt:288` — calls `apiHandler.sendLocalProgressSync()` → `postRequest()` → `makeRequest()`

### Likely Cause
The mTLS configuration in `ApiHandler` depends on per-request lazy initialization via `ensureMtlsConfigured()`. This checks `mtlsConfiguredFor != serverConfigId` and rebuilds clients if needed. Potential failure modes:

1. **Timing**: `ensureMtlsConfigured()` is called on whatever thread `makeRequest()` runs on. If the main thread dispatches to `mtlsExecutor` (line 165-167), the original callback may not receive the mTLS-configured client in time.
2. **State drift**: `InternalDownloadManager` builds its client once at construction. If the cert alias changes or `DeviceManager.serverConnectionConfigId` isn't set yet at construction time, the download manager has a stale/plain client for the session lifetime.
3. **Global default not applied**: If `applyAsGlobalDefault()` hasn't been called before ExoPlayer or CapacitorHttp makes a connection, those paths will fail silently (SSL handshake failure, no client cert presented).

---

## Proposed Refactor: Centralized OkHttpClient Factory

### Architecture

Move all OkHttpClient creation into `MtlsManager` as a centralized factory. All components request clients from MtlsManager instead of building their own.

```
MtlsManager (singleton)
├── getClient(connectTimeout?, callTimeout?) → OkHttpClient
│   ├── Has cert alias for current server? → mTLS-configured client
│   └── No cert alias? → plain OkHttpClient
├── refreshForServer(serverConfigId) → rebuilds cached clients + global default
└── resetToPlain() → clears mTLS state, restores global default
```

### Key Design Decisions

- **Non-mTLS servers still work**: When no cert alias exists for a server, `getClient()` returns a standard OkHttpClient. This is the same behavior as today, just centralized.
- **Single refresh point**: When the user selects/clears a cert, one call to `refreshForServer()` updates everything — cached OkHttpClients and the global `HttpsURLConnection` default.
- **No per-component state tracking**: Remove `mtlsConfiguredFor` from `ApiHandler`, remove constructor-time client building from `InternalDownloadManager`.

### Implementation Steps

1. **Extend `MtlsManager`** with cached client management:
   - Add `getClient(connectTimeout, callTimeout)` that returns a cached or freshly built OkHttpClient
   - Add `refreshForServer(serverConfigId)` that rebuilds all cached clients and applies global default
   - Add `resetToPlain()` for disconnect/logout

2. **Simplify `ApiHandler`**:
   - Remove `defaultClient`, `pingClient`, `mtlsConfiguredFor`, `refreshMtlsClients()`, `ensureMtlsConfigured()`, `needsMtlsRefresh()`, `mtlsExecutor`
   - In `makeRequest()`, call `MtlsManager.getClient()` directly
   - Ping calls use `MtlsManager.getClient(callTimeout = 3)`

3. **Simplify `InternalDownloadManager`**:
   - Replace constructor-time OkHttpClient creation with `MtlsManager.getClient(connectTimeout = 30)`

4. **Ensure `refreshForServer()` is called at the right time**:
   - On server connection/login (after cert alias is known)
   - On cert selection/clearing from the UI
   - Before any streaming session starts (covers ExoPlayer's `DefaultHttpDataSource` via global default)

5. **ExoPlayer streaming** (`PlayerNotificationService.kt`):
   - `DefaultHttpDataSource.Factory()` uses `HttpURLConnection` which reads from `HttpsURLConnection.getDefaultSSLSocketFactory()`
   - No code change needed as long as `refreshForServer()` is called before playback — which it will be since playback requires login first
   - Alternative: switch to `OkHttpDataSource.Factory(MtlsManager.getClient())` for explicit control (requires `media3-datasource-okhttp` dependency)

### Files to Modify

| File | Change |
|------|--------|
| `MtlsManager.kt` | Add centralized client cache, `getClient()`, `refreshForServer()` |
| `ApiHandler.kt` | Remove all mTLS state; use `MtlsManager.getClient()` |
| `InternalDownloadManager.kt` | Replace constructor client with `MtlsManager.getClient()` |
| `AbsDatabase.kt` | Call `MtlsManager.refreshForServer()` after cert selection/clearing |

### Files That Need No Changes

| File | Reason |
|------|--------|
| `MainActivity.kt` | WebView cert handler is already correct and separate from OkHttp |
| `PlayerNotificationService.kt` | Covered by global default SSL factory (set by `refreshForServer()`) |
| `plugins/nativeHttp.js` | CapacitorHttp uses HttpURLConnection, covered by global default |
| `plugins/server.js` | Socket.io runs in WebView, covered by MainActivity's WebViewClient |

---

## Risk Assessment

- **Low risk**: ApiHandler and InternalDownloadManager changes are straightforward — replacing local client creation with centralized calls.
- **Medium risk**: Timing of `refreshForServer()` must be validated — it must complete before any HTTP request on a new server connection. The current `ensureMtlsConfigured()` lazy-init pattern handles this but adds complexity; the centralized approach should call `refreshForServer()` eagerly at login/connect time.
- **No regression for non-mTLS**: When no cert alias is configured, `getClient()` returns a plain client — identical to current fallback behavior.

## Branch Notes

- Current work is on `local-build` branch
- Changes will be stashed and moved to `http-ssl-fix` branch where SSL fixes reside
