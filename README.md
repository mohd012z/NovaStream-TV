# NovaStream TV v3

Clean-room Android/Kotlin reconstruction inspired by useful architectural ideas observed in the supplied APKs. No proprietary source code, brand assets, native libraries, DRM bypass logic, ad SDKs, trackers, or third-party content resolvers were copied.

## APK-derived feature mapping

### PerfectTV 6.3
Observed binary strings/components include `#EXTM3U`, `#EXTINF`, `tvg-id`, `group-title`, `epg_url`, `HlsMediaSource.Factory`, `DashMediaSource.Factory`, subtitle controls, Picture-in-Picture references, and screen-brightness handling.

Integrated into NovaStream v3:
- M3U parser
- tvg-id / tvg-name / tvg-logo / group-title mapping
- XMLTV EPG parser and Now-playing lookup
- HLS / DASH / RTSP support through Media3
- custom User-Agent / Referer handling when present in an authorized playlist
- Picture-in-Picture

### MovieBox 3.0.12
Observed rich subtitle/download/retry/player state architecture and a large native playback stack.

Integrated as clean concepts:
- unified search
- track information surface
- retry state
- structured download states

Not copied:
- native `.so` player/downloader libraries
- proprietary subtitle services
- ad SDKs / trackers

### LoveShots 2.42
Observed `SMART_PRELOAD`, M3U parsing strings, PiP callbacks, retry logic and subtitle infrastructure.

Integrated as clean concepts:
- VOD/Series media cache (512 MB LRU)
- no persistent cache for Live TV
- automatic playback retry (up to 3 attempts)
- PiP-ready player

### DramaBox 5.6
Main DEX is protected/packed, but native/file structure exposes SaaS core player/downloader and FFmpeg/Cicada components.

Integrated as clean concepts:
- download state model: queued/downloading/paused/completed/failed
- resume/history architecture
- cache/retry separation

Not copied:
- Jiagu/protection components
- native downloader/player files
- membership/ads/tracking

## v3 features

- Startup orientation picker: Portrait / Landscape / Auto
- Player rotate button
- Smooth swipe controls: left brightness, right volume
- Adjustable brightness and volume sensitivity
- M3U file import using Android file picker
- XMLTV EPG file import
- Live / Movies / Series classification
- Unified Search
- Current EPG programme display using tvg-id
- Media3 playback for HLS, DASH and RTSP
- Per-item User-Agent and Referer support from playlist metadata
- Resume playback / Continue Watching / History
- Picture-in-Picture button
- Automatic retry on playback errors
- 512 MB LRU cache for Movie/Series playback only
- Download Center state architecture for authorized offline media
- No ads, trackers, billing SDKs or proprietary scraper/resolver code

## Build

Open the project in a current Android Studio installation and allow Gradle to sync. The project targets SDK 35 and uses Media3 1.5.1.

This source tree does not include a Gradle wrapper binary in this generated package, so use Android Studio's configured Gradle or add a standard wrapper before CI builds.

## Usage note

NovaStream is designed for media sources you are authorized to access. Importing a playlist does not grant access to protected, geo-restricted, subscription-only, or DRM-controlled content.

## Gradle Wrapper

This package includes `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`, and a wrapper bootstrap JAR configured for Gradle 8.9. AGP 8.7.x requires Gradle 8.9 and JDK 17. On first run, the wrapper downloads the Gradle 8.9 binary distribution from services.gradle.org.

Build commands:

```bash
./gradlew --version
./gradlew assembleDebug
```

Windows:

```bat
gradlew.bat --version
gradlew.bat assembleDebug
```

## PerfectTV Enhanced integration
This build keeps a separate application ID (`com.perfecttv.enhanced`) so it does not overwrite the original signed APK.

Included remote-source buttons:
- PerfectTV Free M3U
- PerfectTV XMLTV EPG
- Local M3U import
- Local XMLTV import

GitHub Actions workflow: `.github/workflows/build-apk.yml`

The workflow builds `app-debug.apk` using JDK 17, Android SDK 35, and Gradle wrapper.
