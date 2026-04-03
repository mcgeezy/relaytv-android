# RelayTV Android Remote

RelayTV Android is the Android companion app for the self-hosted RelayTV server. It gives you a lightweight remote, embedded `/ui` access, multi-server management, and Android share targets for queueing or immediately playing links on a RelayTV box.

RelayTV server:
https://github.com/mcgeezy/relaytv

Home Assistant integration:
https://github.com/mcgeezy/relaytv-ha

## Features

- Multi-server support with health verification before save
- LAN auto-discovery of `_relaytv._tcp` servers with Android NSD / mDNS
- Embedded RelayTV `/ui` WebView with reconnect and heartbeat recovery
- Dedicated Android share targets:
  - `RelayTV Queue` -> `POST /smart`
  - `RelayTV Play` -> `POST /play_now`
- HTTP and HTTPS support for self-hosted RelayTV servers

## Requirements

- Android 8.0+ (`minSdk 26`)
- RelayTV server reachable on your LAN or via a trusted remote URL

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

Release bundle and release lint:

```bash
./scripts/build-release.sh
```

Connected tests on a physical device or emulator:

```bash
./scripts/test-connected.sh
```

## Release Notes

- Current Play target: Android 15 / API 35
- Release bundle output: `app/build/outputs/bundle/release/app-release.aab`
- Release checklist: [docs/RELEASE_CHECKLIST.md](/opt/dev/relaytv-android/docs/RELEASE_CHECKLIST.md)
- Privacy policy draft: [docs/PRIVACY_POLICY.md](/opt/dev/relaytv-android/docs/PRIVACY_POLICY.md)

## Network Notes

RelayTV Android intentionally supports both:

- `http://`
- `https://`

HTTP support is retained because RelayTV is commonly deployed as a trusted LAN-first service on local IPs and hostnames. If your server is reachable outside your private network, use HTTPS.

## API Surface Used By The App

- `GET /health`
- `GET /ui`
- `POST /smart`
- `POST /play_now`

## Contributing

- Keep Android UX aligned with RelayTV’s self-hosted, local-first model
- Preserve the queue vs play-now share behavior
- Avoid regressions in reconnect and server discovery flows

## License

Same license as the RelayTV core project:
https://github.com/mcgeezy/relaytv
