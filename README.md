# PerfectTV Enhanced v2

A clean-room Android TV/IPTV player project for **authorized M3U/XMLTV sources**.

## v2 highlights

- Branded launcher icon and animated in-app splash
- Premium dark 3D/glass-style home dashboard
- Real `tvg-logo` rendering with Coil memory/disk caching
- Lazy lists instead of rendering thousands of channels at once
- Indexed EPG lookup by `tvg-id` for fast "Now" display
- Category chips for large channel libraries
- Visual Live Now / Movies rows
- Custom Media3 player chrome
- Left-side swipe = brightness with on-screen percentage HUD
- Right-side swipe = volume with on-screen percentage HUD
- Tap player to show/hide controls
- Live playback does not save VOD resume state
- VOD/Series keep playback cache and resume
- Connection/read timeouts and controlled automatic retry
- PiP and rotation controls
- Background parsing/loading of large M3U/XMLTV files

## Default PerfectTV source

- M3U: `https://ptv2026.com/PerfecttvFree3.m3u`
- XMLTV: `https://ptv2026.com/EPGPerfecttv/epgtvku.xml`

Use only streams/playlists you are authorized to access.

## Build

Requires JDK 17 and Android SDK 35.

Windows:

```bat
gradlew.bat assembleDebug
```

Linux/macOS:

```bash
./gradlew assembleDebug
```

APK:

`app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions

Push to `main` or run **Actions → Build Android APK → Run workflow**.
The workflow uploads the installable debug APK as an artifact.

## Notes

No proprietary APK source, DRM bypass, scraper/resolver, ad SDK, or third-party native player binaries are included.
