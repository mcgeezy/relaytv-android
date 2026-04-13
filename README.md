# RelayTV Android Companion App

RelayTV Android is the Android companion app for the self-hosted RelayTV server. It gives you a fast mobile remote, embedded RelayTV web UI access, multi-server management, and Android share targets for queueing or immediately playing links on a RelayTV device.

RelayTV server:  
https://github.com/mcgeezy/relaytv

Home Assistant integration:  
https://github.com/mcgeezy/relaytv-ha

Support the project:  
https://buymeacoffee.com/relaytv

---

## Features

- Multi-server support
- Health verification before saving a server
- LAN auto-discovery of `_relaytv._tcp` servers using Android NSD / mDNS
- Embedded RelayTV `/ui` WebView access
- Reconnect and heartbeat recovery
- Dedicated Android share targets:
  - `RelayTV Queue` → `POST /smart`
  - `RelayTV Play` → `POST /play_now`
- HTTP and HTTPS support for self-hosted RelayTV servers
- Lightweight remote-friendly workflow for local RelayTV control

---

## Why Use The Android App

RelayTV Android is designed to make self-hosted TV playback feel easy from your phone.

Use it to:

- Quickly open and control your RelayTV server
- Share links from YouTube and other apps directly to your TV
- Queue media without interrupting what is already playing
- Start playback immediately when needed
- Manage more than one RelayTV server from one app

---

## Screenshots

<!-- Suggested screenshots:
1. Server list screen
2. Add server screen
3. Embedded RelayTV UI in WebView
4. Android share sheet showing RelayTV Queue / RelayTV Play
5. Remote/control screen if available
-->

_Add screenshots here for release._

---

## Requirements

- Android 8.0+ (`minSdk 26`)
- A reachable RelayTV server on your LAN or trusted remote URL

---

## Companion Projects

- RelayTV server: https://github.com/mcgeezy/relaytv
- RelayTV Home Assistant integration: https://github.com/mcgeezy/relaytv-ha

### Planned / work in progress

- iPhone companion app
- Continued Android UX polish
- Expanded companion app ecosystem

---

## Build And Test

Validate host environment:

```bash
./scripts/check-android-env.sh all
```

Install or refresh Android SDK components:

```bash
./scripts/bootstrap-android.sh
```

Debug build, lint, and unit tests:

```bash
./scripts/build-debug.sh
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release bundle and release lint:

```bash
./scripts/build-release.sh
```

GitHub release assets are generated automatically when you push a tag like:

```bash
git tag v1.2.0
git push origin v1.2.0
```

Connected tests on a physical device or emulator:

```bash
./scripts/test-connected.sh
```

---

## Release Notes

- Current Play target: Android 15 / API 35
- Release bundle output: `app/build/outputs/bundle/release/app-release.aab`
- Release checklist: [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md)
- Privacy policy draft: [docs/PRIVACY_POLICY.md](docs/PRIVACY_POLICY.md)

---

## Network Notes

RelayTV Android supports both:

- `http://`
- `https://`

HTTP support is intentionally retained because RelayTV is often used as a trusted LAN-first service on private IPs and hostnames. If your RelayTV server is exposed outside your private network, HTTPS is recommended.

---

## API Surface Used By The App

- `GET /health`
- `GET /ui`
- `POST /smart`
- `POST /play_now`

---

## Typical Workflows

### Queue from Android share sheet

Share a link from another app and choose **RelayTV Queue** to send it to RelayTV’s smart play/enqueue behavior.

### Play immediately from Android share sheet

Share a link and choose **RelayTV Play** to start playback right away.

### Open your RelayTV UI from your phone

Use the app as a quick launcher and controller for one or more RelayTV servers.

---

## Support The Project

If RelayTV Android saves you time or improves your setup, consider supporting development across the RelayTV ecosystem.

Buy me a coffee:  
https://buymeacoffee.com/relaytv

---

## Contributing

- Keep Android UX aligned with RelayTV’s self-hosted, local-first model
- Preserve the queue vs play-now share behavior
- Avoid regressions in reconnect and server discovery flows

---

## License

Same license as the RelayTV core project:  
https://github.com/mcgeezy/relaytv
