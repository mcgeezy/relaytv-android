# RelayTV Android Development Guide

This document covers architecture, server integration contracts, local build and test workflows, screenshot tooling, and release automation for `relaytv-android`. For installation, daily usage, and self-hosted operator guidance, see [`README.md`](../README.md).

## Stack and SDK Targets

- **Language & Toolchain:** Kotlin (`jvmTarget = 17`), Java 17, checked-in Gradle wrapper
- **Application ID / Namespace:** `pro.relaytv`
- **SDK Levels:** `minSdk 26` (Android 8.0), `targetSdk 35` / `compileSdk 35` (Android 15)
- **Key Libraries:**
  - AndroidX AppCompat, Activity, Core KTX, and Material Components
  - OkHttp + `okhttp-sse` (`4.12.0`) for REST calls, multipart uploads, WebSocket, and SSE
  - AndroidX WorkManager (`2.9.1`) for expedited share and upload execution
  - AndroidX Media3 (`media3-session:1.7.1`) for `MediaSessionService` and `SimpleBasePlayer`
- **FOSS Packaging:** `dependenciesInfo` is disabled for both APKs and App Bundles in [`app/build.gradle`](../app/build.gradle), and store metadata lives under `fastlane/metadata/android/en-US/`.

## Architecture Overview

All application code lives in `app/src/main/java/pro/relaytv/`:

| Component | Responsibility |
| --- | --- |
| `MainActivity` & `WebBridgePolicy` | Edge-to-edge `WebView` hosting `<base>/ui`, same-origin `RelayTVAndroid` JS bridge, theme/system-bar synchronization, WebView `localStorage` token sync, `<input type="file">` document picker (`ACTION_OPEN_DOCUMENT`), foreground `/health` heartbeat, Server Picker `BottomSheetDialog`, and legacy/offline bottom fallback pill (`900ms` grace). |
| `ShareActivity` & `ShareWorker` | Exported `SharePlayNowAlias` and `ShareQueueAlias` targets for `ACTION_SEND` (`text/plain`, `audio/*`, `video/*`) and `ACTION_VIEW` (`rtsp://`, `rtsps://`). Stages local streams into `cacheDir/shared-media` (pruned after 48h), dispatches expedited `WorkManager` jobs without `NetworkType.CONNECTED` (`NET_CAPABILITY_VALIDATED` would block offline LAN/VPN shares), runs fast in-worker socket retries (`1s`, `2s`), and strips credentials/sensitive query keys from notification URLs. |
| `MediaControlService`, `RelayRemotePlayer`, `MediaStatusState`, & `MediaControlPolicy` | Exported Media3 `MediaSessionService` (`foregroundServiceType="mediaPlayback"`) mirroring active TV playback with zero local audio decoding. Extrapolates playback position locally from an elapsed-realtime anchor so routine 1x ticks avoid SystemUI binder IPC unless state, metadata, DVR bounds, volume, or drift (`>= 1.5s`) changes. Retains status across short dropouts (`30s` grace) and stops itself after `5 min` idle or `90s` unreachable. |
| `RelayRealtimeClient` & `RemoteStatus` | Discovers `/realtime/capabilities` and negotiates push transport in priority order: versioned WebSocket (`/ui/ws`, subprotocol `relaytv.realtime.v1`) → SSE (`/ui/events`, re-probing capabilities every 5 minutes) → adaptive `/status` polling (`3s` playing, `5s` paused, `10s–60s` idle/backoff). Enforces protocol v1 sequence tracking and a `2.4 × heartbeat_sec` activity watchdog. |
| `NsdRelayTvScanner` | Discovers `_relaytv._tcp.` services via Android `NsdManager` under a `WifiManager.MulticastLock`, serializing `resolveService` calls and handling IPv4/IPv6 addresses and TXT attributes (`service`, `name`, `path`). |
| `HostStore`, `AppSettings`, & `Net` | Persists server profiles and optional `apiToken` values in `MODE_PRIVATE` `SharedPreferences` (excluded from cloud backup and device transfer in `backup_rules.xml` and `data_extraction_rules.xml`); provides shared OkHttp clients (`client`, `uploadClient` with 20m write timeout, and `streamingClient` with unbounded read timeout). |
| `SettingsActivity` | Native settings screen toggling `MediaControlService` and deep-linking back into `MainActivity`'s Server Picker (`open_servers=true`). |

## Server Integration Contracts

### HTTP and Realtime Endpoints

When an API token is configured for a server profile, native requests send `Authorization: Bearer <token>`.

| Feature | Method & Path | Notes |
| --- | --- | --- |
| Server health probe | `GET /health` | Expects HTTP 2xx (and `{"ok": true}` during server profile creation/editing). |
| Token verification | `POST /auth/check` | Validates `apiToken` on save; `401` rejects, `404`/`405` treats server as legacy/unprotected. |
| Embedded web remote | `GET /ui` | Loaded in `MainActivity` `WebView` with `RelayTV/<versionName>` appended to User-Agent; off-origin main-frame navigations open externally. |
| Share link / stream (**Play**) | `POST /play_now` | JSON `{"url": "...", "request_id": "..."}` (`http`, `https`, `rtsp`, `rtsps`, playlists). |
| Share link / stream (**Queue**) | `POST /smart` | JSON `{"url": "...", "request_id": "..."}` (`http`, `https`, `rtsp`, `rtsps`, playlists). |
| Share local file (**Play**) | `POST /ingest/media/play` | Multipart form upload (`file`, optional `title`). |
| Share local file (**Queue**) | `POST /ingest/media/enqueue` | Multipart form upload (`file`, optional `title`). |
| Realtime discovery | `GET /realtime/capabilities` | Advertises `protocol_version`, `websocket`, `sse`, and `heartbeat_sec`; `404` falls back to legacy SSE (`/ui/events`). |
| Realtime WebSocket | `GET /ui/ws` | Upgraded to `ws://` or `wss://` with `Sec-WebSocket-Protocol: relaytv.realtime.v1`; expects initial `hello` envelope (`version = 1`). |
| Realtime SSE fallback | `GET /ui/events` | `Accept: text/event-stream`; handles `status`, `playback`, `queue`, and `jellyfin` events. |
| Authoritative status | `GET /status` | Bootstrap snapshot, sequence-gap recovery, and adaptive polling fallback (includes optional `dvr` seek window). |
| Media session commands | `POST /pause`, `/resume`, `/next`, `/previous`, `/stop`, `/seek_abs`, `/volume` | Sent by `MediaControlService` when lock-screen / notification controls or hardware volume actions fire. |

### `window.RelayTVAndroid` WebView Bridge

`MainActivity` registers a `RelayTVAndroid` JavaScript interface. Mutating/UI actions execute only when `WebBridgePolicy.isTrustedBridgeOrigin(activeBaseUrl, web.url)` matches the configured server origin (`scheme`, `host`, `port`, and base path prefix):

- `onBridgeReady()` — hides the legacy fallback pill and switches top safe-area handling to CSS (`--relaytv-android-top: <dp>px` on `document.documentElement`).
- `openServers()` — opens the native Server Picker bottom sheet.
- `openSettings()` — opens `SettingsActivity`.
- `reload()` — re-probes `/health` and reloads `/ui`.
- `openPrivacy()` — opens [`PRIVACY_POLICY.md`](PRIVACY_POLICY.md) on GitHub in an external browser.
- `onThemeChanged(effectiveTheme, bgColorHex)` — syncs root/WebView background color and light/dark Android status and navigation bar icons.
- `getAppVersion()` — returns the installed Android `versionName`.

On page start and finish for matching server origins, `MainActivity` also synchronizes `window.localStorage['relaytv_api_token']` with the active server profile's token (or removes the key when blank).

## Local Setup, Build, and Test

RelayTV Android requires **Java 17** and the **Android SDK** (`platforms;android-35` and `build-tools;35.0.0`). Set `ANDROID_SDK_ROOT` (or `ANDROID_HOME` / `local.properties`) and have `sdkmanager` available when bootstrapping.

### Bootstrap and verify environment

```bash
./scripts/bootstrap-android.sh
./scripts/check-android-env.sh all
```

Set `INSTALL_EMULATOR=1 ./scripts/bootstrap-android.sh` if you also want `sdkmanager` to install the Android emulator and system image.

### Debug build, lint, and unit tests

```bash
./scripts/build-debug.sh
```

By default this runs `clean assembleDebug lint testDebugUnitTest` (pass explicit Gradle tasks to override, e.g., `./scripts/build-debug.sh testDebugUnitTest`). The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Connected instrumentation tests

With an unlocked physical Android device or emulator visible over `adb`:

```bash
adb devices
./scripts/test-connected.sh
```

### Release build and local signing

```bash
./scripts/build-release.sh
```

Without release signing properties, Gradle writes an unsigned APK to `app/build/outputs/apk/release/app-release-unsigned.apk` and an unsigned bundle to `app/build/outputs/bundle/release/app-release.aab`.

To produce signed release artifacts locally, supply the four Gradle project properties (via `~/.gradle/gradle.properties` or `ORG_GRADLE_PROJECT_*` environment variables—never commit keystores or passwords):

- `MYAPP_RELEASE_STORE_FILE`
- `MYAPP_RELEASE_STORE_PASSWORD`
- `MYAPP_RELEASE_KEY_ALIAS`
- `MYAPP_RELEASE_KEY_PASSWORD`

## Regenerating Screenshots

[`scripts/readme-screenshots.js`](../scripts/readme-screenshots.js) uses `adb` and Playwright (Chromium) to capture live screenshots from an unlocked Android device with a configured RelayTV server and compose the framed marketing visuals in `docs/images/readme/`:

```bash
npm install
npx playwright install chromium
npm run readme:screenshots -- --serial="$(adb devices | awk '$2 == "device" { print $1; exit }')"
```

To re-compose artwork from already-captured PNGs without `adb`, pass `--main=<path>`, `--servers=<path>`, `--share=<path>`, and `--media=<path>`. Update `fastlane/metadata/android/en-US/images/phoneScreenshots/` alongside major UI changes when refreshing store screenshots.

## Versioning and Releases

### Semantic version and `versionCode`

[`version.txt`](../version.txt) is the single source of truth for the semantic version (`major.minor.patch`). [`app/build.gradle`](../app/build.gradle) validates that `minor` and `patch` are `< 1000` and derives a monotonic Android `versionCode` automatically:

```text
versionCode = major * 1_000_000 + minor * 1_000 + patch
```

Never hardcode or manually edit `versionCode` in `app/build.gradle`.

### Conventional Commits and Release Please

Merges to `main` follow Conventional Commits (configured in [`release-please-config.json`](../release-please-config.json) and `.github/workflows/release-please.yml`):

- `feat:` bumps the **minor** version.
- `fix:` bumps the **patch** version.
- Breaking changes (`feat!:` / `BREAKING CHANGE:`) bump the **major** version.
- `docs:`, `ci:`, `refactor:`, `perf:`, `test:`, `build:`, and `chore:` appear in `CHANGELOG.md` sections without forcing a release on their own.

Regular pull requests run debug validation only (`.github/workflows/ci.yml`). Release Please maintains a rolling `chore(main): release …` pull request:

1. Review the generated `version.txt` and `CHANGELOG.md` diff on the release PR.
2. Complete the manual and Play Console checks in [`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md).
3. Ensure **Android CI** passes on the release PR, then merge it.
4. Release Please creates the `vX.Y.Z` tag and a **draft** GitHub Release.
5. `.github/workflows/release.yml` checks out the exact release SHA, verifies `version.txt` matches the tag and the release is still a draft, runs debug tests/lint, decodes `RELEASE_KEYSTORE_BASE64`, builds signed release artifacts, verifies signatures with `jarsigner` (`.aab`) and `apksigner` (`.apk`), uploads `relaytv-vX.Y.Z.apk` and `relaytv-vX.Y.Z.aab`, and promotes the draft release to published.

### Required GitHub Actions Secrets

- `RELEASE_KEYSTORE_BASE64` — base64-encoded Android release keystore
- `RELEASE_STORE_PASSWORD` — keystore password
- `RELEASE_KEY_ALIAS` — signing key alias
- `RELEASE_KEY_PASSWORD` — signing key password
- `RELEASE_PLEASE_TOKEN` *(optional but recommended)* — fine-grained PAT with repository `contents`, `issues`, and `pull-requests` write permissions so Release Please PRs trigger `Android CI` automatically without manual workflow approval.

If a signed build fails after Release Please creates the draft release, fix the issue or secret and trigger **Build Android release** (`.github/workflows/release.yml`) via `workflow_dispatch` with the existing draft `tag_name` and `release_sha`.
