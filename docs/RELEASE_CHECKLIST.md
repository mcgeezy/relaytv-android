# RelayTV Android Release Checklist

Use this checklist before cutting a public release or uploading a new Play Console build.

## Build Readiness

1. Verify Android toolchain prerequisites:
   - `./scripts/check-android-env.sh all`
2. Install or refresh required SDK components:
   - `./scripts/bootstrap-android.sh`
3. Validate debug build, lint, and unit tests:
   - `./scripts/build-debug.sh`
4. Validate release bundle and release lint:
   - `./scripts/build-release.sh`
5. If you have a device connected, run instrumentation tests:
   - `./scripts/test-connected.sh`

## Versioning

1. Confirm the rolling `chore(main): release …` pull request contains every intended change.
2. Verify its `version.txt` and `CHANGELOG.md` updates.
3. Do not edit `versionCode` directly; Gradle derives it from `version.txt`.
4. Verify the user agent string and release notes match the version being shipped.

## Play Console Requirements

1. Keep `targetSdk` at the current Google Play minimum.
   - As of April 3, 2026, Play requires new apps and updates to target Android 15 / API 35.
2. Upload an Android App Bundle:
   - `app/build/outputs/bundle/release/app-release.aab`
3. Use Play App Signing in Play Console.
4. Complete the Data safety form for this release.
   - The media controls feature adds no off-device data collection; no Data safety changes are expected.
5. Provide a valid support email in Play Console.
6. Publish a public privacy policy URL.
   - This repo includes a draft policy in `docs/PRIVACY_POLICY.md`.
   - The in-app privacy menu points to the GitHub-hosted copy on `main`.
7. Complete the Foreground service permissions declaration (App content -> Foreground service permissions).
   - Declares `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, used by the media controls notification that mirrors playback on the user's RelayTV server.
   - Requires a short demo video showing the media controls notification while a RelayTV server is playing.

## App-Specific Verification

1. Confirm manual server entry still works for both HTTP and HTTPS RelayTV servers.
   - For proxied HTTPS, confirm `/ui/ws` upgrades to `wss://` and remains
     connected through the proxy's idle timeout.
2. Confirm LAN discovery finds `_relaytv._tcp` services on the local network.
3. Confirm both Android share targets work:
   - `RelayTV Queue` -> `/smart`
   - `RelayTV Play` -> `/play_now`
4. Confirm the WebView reconnects after the RelayTV server restarts or the network drops.
5. Confirm the privacy menu opens the published policy URL.
6. Confirm system media controls appear while the active server is playing:
   - Play/pause, next/previous, seek, and the volume slider control the server.
   - The notification disappears after the server goes idle.
   - The Settings toggle ("Media controls") removes and restores the controls.
   - A current server uses `/ui/ws`; blocking WebSocket falls back to `/ui/events`.
   - Blocking both push transports activates `/status` polling without clearing
     the last valid snapshot during the reconnect grace window.
   - Switching servers clears the previous notification state and artwork
     before bootstrapping the new server; a failed bootstrap or compact event
     never reuses the outgoing server's metadata.
   - A realtime pause, stop, or seek received while `/status` is in flight is
     not overwritten when the older HTTP response completes.
   - Silently block an established push connection without changing Android's
     active network. Media state must fall back within the advertised heartbeat
     grace instead of leaving the notification attached to a zombie socket.
   - Advance the queue while a `/status` refresh is in flight, then deliver a
     compact playback update. The invalidated refresh must retry and publish the
     new item's full title and artwork.
   - Switching networks retires the old connection and updates only from the
     active generation.
   - Background and resume the app, then turn the screen off and use lock-screen
     controls. Realtime updates and actions must remain attached to the active
     server without duplicate notifications.
   - Roam between Wi-Fi networks while playback is active. The previous
     connection must retire, the current network must reconnect, and retained
     state must not be replaced by a late callback.
7. Confirm the Settings screen opens from the toolbar menu and "Manage servers" opens the server picker.
8. Confirm a server without `RELAYTV_API_TOKEN` still supports WebView controls, shares, uploads, and media controls.
9. Confirm a protected server accepts a valid per-server API token and rejects a missing or incorrect token.
10. Confirm a server without `/realtime/capabilities` uses legacy SSE, keeps
    command confirmation polling after media-key actions, and discovers
    WebSocket without an app restart after the server is upgraded.

## Signing And Distribution

1. Configure your release signing key or Play App Signing flow.
2. Merge the Release Please chore only after its Android CI check succeeds.
3. Confirm the resulting GitHub Release remains a draft until the signed build completes.
4. Confirm the workflow verifies and uploads both the release-signed `.apk` and `.aab` before publishing.
5. Confirm the GitHub Release does not expose a debug-signed APK.
6. Archive the signed artifacts, release notes, and the exact commit SHA used for the build.

## Official References

1. Target API requirement:
   - https://developer.android.com/google/play/requirements/target-sdk
2. App setup, versioning, and Play App Signing:
   - https://support.google.com/googleplay/android-developer/answer/9859152
3. Developer support contact requirements:
   - https://support.google.com/googleplay/android-developer/answer/113477
