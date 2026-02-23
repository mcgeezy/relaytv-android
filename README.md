# 📺 RelayTV Android Remote

**RelayTV Android** is the official companion remote app for
**RelayTV**, a powerful local-first media relay and playback system.

Control, launch, and share content to your RelayTV server instantly from
Android --- with multi-server support and seamless share integration.

------------------------------------------------------------------------

## 🚀 What is RelayTV?

RelayTV is a local media relay and playback server designed for:

-   YouTube & streaming links
-   Direct media URLs
-   Queue management
-   HDMI playback (mpv-based)
-   LAN-first operation
-   Smart `/smart` auto-routing endpoint

🔗 RelayTV Server:\
https://github.com/mcgeezy/relaytv

🔗 Home Assistant Integration:\
https://github.com/mcgeezy/relaytv-ha

------------------------------------------------------------------------

# ✨ Features

## 📡 Multi-Server Support

-   Add unlimited RelayTV servers
-   Name each server (Living Room, Office, Bedroom, etc.)
-   Instantly switch active server
-   Remove or edit servers anytime
-   Built-in `/health` verification before saving

------------------------------------------------------------------------

## 🔗 Share to RelayTV

-   Share any link from Android to RelayTV

-   Automatically sends to:

    POST /smart

-   Smart endpoint handles play vs queue logic

-   Instant feedback toast:

    Sent to "Living Room"

------------------------------------------------------------------------

## 🎨 Clean RelayTV UI

-   Material 3 design
-   Dark-mode optimized
-   RelayTV branding (electric blue + charcoal theme)
-   Quick server switch from toolbar
-   Pull-to-refresh WebView

------------------------------------------------------------------------

## 🧠 Smart URL Handling

-   Auto-normalizes URLs:
    -   10.0.55.2:8787 → http://10.0.55.2:8787
-   Validates server before saving
-   Prevents crash loops from invalid input

------------------------------------------------------------------------

## 📱 WebView Relay Mode

Loads:

    /ui

from the active server for full RelayTV interface access.

------------------------------------------------------------------------

# 🏗 Requirements

-   Android 8.0+
-   RelayTV server running locally or remotely
-   HTTP or HTTPS supported

------------------------------------------------------------------------

# 🔐 HTTPS Notes

RelayTV supports both:

    http://
    https://

For best compatibility:

-   Prefer HTTPS
-   Use a valid certificate (reverse proxy, Nginx Proxy Manager, etc.)
-   Or use HTTP on trusted LAN environments

------------------------------------------------------------------------

# 🛠 Build Instructions

Clone:

    git clone https://github.com/mcgeezy/relaytv-android.git
    cd relaytv-android

Debug build:

    ./gradlew assembleDebug

Release bundle (for Play):

    ./gradlew bundleRelease

------------------------------------------------------------------------

# 📦 Play Store Ready

Release build includes:

-   Resource shrinking
-   Code minification
-   Material 3 theming
-   Clean package namespace

Before Play upload:

-   Configure signing keystore
-   Increment versionCode
-   Generate .aab

------------------------------------------------------------------------

# 🏠 Home Assistant Integration

RelayTV integrates directly with Home Assistant via:

https://github.com/mcgeezy/relaytv-ha

Features include:

-   Media player entity
-   Queue control
-   Playback control
-   Side panel iframe UI
-   Multi-server support

------------------------------------------------------------------------

# 🔄 API Reference

RelayTV Android primarily uses:

    POST /smart
    GET  /health
    GET  /ui

Full API reference available in:

https://github.com/mcgeezy/relaytv

------------------------------------------------------------------------

# 🤝 Contributing

Contributions welcome!

-   Keep UI consistent with RelayTV branding
-   Maintain `/smart` routing behavior
-   Preserve multi-server architecture

------------------------------------------------------------------------

# 📄 License

Same license as RelayTV core project.\
See: https://github.com/mcgeezy/relaytv
