# RelayTV Android Privacy Policy

Last updated: July 6, 2026

## Summary

RelayTV Android is a companion remote for self-hosted RelayTV servers. The app does not require an account, does not include advertising, and does not use third-party analytics SDKs.

## Information The App Processes

RelayTV Android may process the following categories of data in order to operate:

1. RelayTV server information
   - Server names and base URLs you add in the app
   - The currently selected active server
2. Shared content
   - Links you share into the app using Android share targets such as `RelayTV Queue` and `RelayTV Play`
3. Local network discovery data
   - RelayTV service advertisements discovered on your local network via mDNS / NSD
4. Device-local notification data
   - Notification text used to confirm a queue or play action
5. Playback status data
   - Now-playing metadata (title, artwork, playback position, volume) fetched from your selected RelayTV server to render system media controls

## How The App Uses Data

RelayTV Android uses the data above only to:

1. Connect to the RelayTV server you selected
2. Load the RelayTV web UI in an embedded WebView
3. Send shared links to RelayTV endpoints such as `/smart` or `/play_now`
4. Discover compatible RelayTV servers on your local network
5. Show local notifications about share results and reconnect state
6. Show optional system media controls (lock screen / quick settings) that mirror and control playback on your selected server

## Data Storage

The app stores configured server names and base URLs locally on your device using Android shared preferences.

The app does not operate its own cloud backend and does not sync your app data to a RelayTV-operated online service.

## Data Sharing

RelayTV Android sends requests only to the RelayTV server you configured or discovered and chose to use.

The app does not sell personal information and does not share data with advertising networks or analytics providers.

## Network Security

RelayTV is designed for local-first and self-hosted use. Because many self-hosted RelayTV installations run on trusted LAN HTTP endpoints, the app allows cleartext HTTP connections in addition to HTTPS. If your RelayTV server is exposed outside a trusted private network, you should use HTTPS.

## Permissions

RelayTV Android may request or use these permissions:

1. `INTERNET`
   - Required to contact your RelayTV server
2. `ACCESS_NETWORK_STATE`
   - Required to detect connectivity changes and recover the UI after network interruptions
3. `CHANGE_WIFI_MULTICAST_STATE`
   - Required for local network RelayTV discovery on some devices
4. `POST_NOTIFICATIONS`
   - Required on newer Android versions to show queue/play result notifications and the media controls notification
5. `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
   - Required to keep the optional media controls notification active while your RelayTV server is playing

## Children

RelayTV Android is not directed to children and does not knowingly collect personal information from children.

## Changes

This privacy policy may be updated when app behavior changes. Update the "Last updated" date whenever this document changes materially.

## Contact

For project updates or issue reporting, use the project repository:

https://github.com/mcgeezy/relaytv-android
