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

1. Increment `versionCode` for every public upload.
2. Set `versionName` to the user-facing release number.
3. Verify the user agent string and release notes match the version being shipped.

## Play Console Requirements

1. Keep `targetSdk` at the current Google Play minimum.
   - As of April 3, 2026, Play requires new apps and updates to target Android 15 / API 35.
2. Upload an Android App Bundle:
   - `app/build/outputs/bundle/release/app-release.aab`
3. Use Play App Signing in Play Console.
4. Complete the Data safety form for this release.
5. Provide a valid support email in Play Console.
6. Publish a public privacy policy URL.
   - This repo includes a draft policy in `docs/PRIVACY_POLICY.md`.
   - The in-app privacy menu points to the GitHub-hosted copy on `main`.

## App-Specific Verification

1. Confirm manual server entry still works for both HTTP and HTTPS RelayTV servers.
2. Confirm LAN discovery finds `_relaytv._tcp` services on the local network.
3. Confirm both Android share targets work:
   - `RelayTV Queue` -> `/smart`
   - `RelayTV Play` -> `/play_now`
4. Confirm the WebView reconnects after the RelayTV server restarts or the network drops.
5. Confirm the privacy menu opens the published policy URL.

## Signing And Distribution

1. Configure your release signing key or Play App Signing flow.
2. Archive the signed `.aab`, release notes, and the exact commit SHA used for the build.
3. Tag the release in git after the Play upload is verified.

## Official References

1. Target API requirement:
   - https://developer.android.com/google/play/requirements/target-sdk
2. App setup, versioning, and Play App Signing:
   - https://support.google.com/googleplay/android-developer/answer/9859152
3. Developer support contact requirements:
   - https://support.google.com/googleplay/android-developer/answer/113477
