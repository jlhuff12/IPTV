# UltraMate IPTV — Samsung Galaxy S24 Ultra Edition (Android Native + WebAPK)

A TiviMate-inspired, mobile-first IPTV player engineered specifically for the **Samsung Galaxy S24 Ultra** (6.8" QHD+ Dynamic AMOLED 2X, 19.5:9 aspect ratio, 120Hz adaptive refresh rate, punch-hole safe area, and one-handed thumb ergonomics).

## Key Features
- **Xtream Codes API Client (`player_api.php` & `xmltv.php`)**: Native authentication, account expiration/connection status diagnostics, Live TV categories, VOD/Series metadata, and Catch-Up archive tags.
- **Multi-Playlist Support (2+ Concurrent Playlists)**: Add, toggle, edit, and merge multiple Xtream Codes servers simultaneously (e.g., Primary & Backup providers) with unified or per-playlist filtering.
- **Built-in EPG (Electronic Program Guide)**: Automatic ingestion of Xtream Codes `xmltv.php` and `get_short_epg`, horizontal time-grid guide, live progress bars, program reminders, and EPG time-shift offset (-12h to +12h).
- **Mobile Touch Player (Media3 ExoPlayer)**:
  - Left-edge vertical swipe for screen brightness; right-edge vertical swipe for audio volume.
  - Double-tap to play/pause or cycle aspect ratios (`16:9 Fit`, `19.5:9 S24 Ultra Fullscreen`, `Zoom`, `4:3`).
  - Picture-in-Picture (PiP) mode & in-app Mini-Player while browsing the EPG or switching playlists.
  - TiviMate-style translucent quick-zap overlay and recent channel history ribbon.
- **Comprehensive Mobile IPTV Settings**:
  - **Player & Decoder**: Hardware/Software decoder toggle, buffer duration (1s–10s), HLS low-latency mode, Auto Frame Rate (120Hz/60Hz), audio track selector.
  - **Playlists**: Multi-server Xtream Codes manager, stream format selector (`.m3u8` HLS vs `.ts` MPEG-TS), auto-sync on startup.
  - **EPG**: Update interval, EPG time offset, catch-up retention days, cache management.
  - **Appearance & Controls**: Pure AMOLED Black (`#000000`) battery-saving theme, touch gesture toggles, channel logo & progress bar visibility.
  - **Parental Control**: PIN protection (`0000`), adult category lock, and channel lock manager.

---

## How to Get & Install the APK on Your Samsung Galaxy S24 Ultra

### Option A: 1-Click Cloud APK Build via GitHub Actions (No PC Setup Required)
Because this repository includes `.github/workflows/build-apk.yml`, GitHub will compile and package the signed `.apk` for you automatically in the cloud:
1. Create a new repository on [GitHub](https://github.com/new) and upload the contents of this folder.
2. Open the **Actions** tab in your GitHub repository and select **Build S24 Ultra IPTV APK**.
3. Download the compiled `UltraMate-IPTV-S24Ultra-debug.apk` from the **Artifacts** section directly onto your Samsung Galaxy S24 Ultra and tap to install.

### Option B: Build Locally in Android Studio
1. Open Android Studio (Ladybug / Koala or newer) and select **Open** -> choose this project folder.
2. Let Gradle sync dependencies (Media3 ExoPlayer 1.4.1, Jetpack Compose Material3, OkHttp, Coroutines).
3. In the top menu, click **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. Copy `app/build/outputs/apk/debug/app-debug.apk` to your Samsung Galaxy S24 Ultra via USB or Quick Share.

### Option C: Instant Mobile Installation via the Included Standalone HTML5/PWA Player
You can also transfer `s24_ultra_iptv_player.html` directly to your S24 Ultra, open it in Chrome or Samsung Internet, and tap **Add to Home Screen** to run it immediately in full-screen standalone mode.
