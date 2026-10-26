# SOAR Mobile Client — Android Native (Kotlin + Jetpack Compose + AGSL)

High-performance native Android email & automated security triage client for the SOAR pipeline. Built with Kotlin, Jetpack Compose, Room DB for offline-first caching, and AGSL (Android Graphics Shading Language) for true Liquid Glass aesthetics running on Android's HWUI RenderThread.

---

## Architecture Overview

```
soar_client/
├── app/
│   ├── build.gradle.kts           # App-level build config (Compose, Room, Retrofit, NDK)
│   ├── proguard-rules.pro         # Proguard rules for Room & models
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── java/com/soar/client/
│           │   ├── SoarApplication.kt           # App singleton (initializes Room, Auth, JNI)
│           │   ├── MainActivity.kt              # Entry point with Compose persistent routing
│           │   ├── data/
│           │   │   ├── local/
│           │   │   │   ├── EmailEntity.kt       # Room cached email table
│           │   │   │   ├── EmailDao.kt          # Flow queries for inbox & auto-blocked
│           │   │   │   └── AppDatabase.kt       # Room database singleton
│           │   │   ├── remote/
│           │   │   │   ├── NetworkModels.kt     # DTOs (Inbox, Detail, Threats, Events)
│           │   │   │   ├── SoarApiService.kt    # Retrofit REST interface
│           │   │   │   └── WebSocketManager.kt  # OkHttp live WebSocket threat client
│           │   │   └── repository/
│           │   │       ├── AuthRepository.kt    # Persistent credentials (eliminates relogin)
│           │   │       └── EmailRepository.kt   # Offline cache (100 initial + 50 pagination)
│           │   ├── nativecore/
│           │   │   └── NativeCore.kt            # JNI bridge to C++ soar_core
│           │   ├── ui/
│           │   │   ├── theme/                   # Obsidian & cyber security color tokens
│           │   │   ├── shaders/
│           │   │   │   └── LiquidGlassShader.kt # AGSL RuntimeShader + hardware blur chain
│           │   │   ├── components/
│           │   │   │   ├── LiquidGlassComponents.kt # Glass Card, TopBar, BottomNav, Pill
│           │   │   │   ├── EmailRowItem.kt      # CLEAN inbox email row (no audit clutter)
│           │   │   │   └── BlockedEmailRowItem.kt # Quarantined threat item
│           │   │   └── screens/
│           │   │       ├── LoginScreen.kt       # Persistent login with one-tap demo
│           │   │       ├── InboxScreen.kt       # Clean inbox with Room cache & pagination
│           │   │       ├── EmailReaderScreen.kt # Clean distraction-free reader (NO audit)
│           │   │       ├── AutoBlockedScreen.kt # DEDICATED quarantined threats tab
│           │   │       ├── BlockedDetailScreen.kt # In-depth security analysis & MITRE
│           │   │       ├── ComposeEmailScreen.kt# Send/Reply dialog
│           │   │       ├── TriageLiveScreen.kt  # Live WebSocket threat stream
│           │   │       ├── SettingsScreen.kt    # Account, JNI diagnostics, logout
│           │   │       └── MainScreen.kt        # Shell with floating Liquid Glass bottom nav
│           │   └── viewmodel/                   # State management & coroutines
│           └── res/                             # Themes, strings, layouts
├── native_core/
│   └── cpp/
│       ├── soar_core.h            # C header
│       ├── soar_core.cpp          # C++ implementation + JNI exports
│       └── CMakeLists.txt         # NDK build config
├── prototype/                     # HTML/JS reference prototype
├── build.gradle.kts               # Root build script
├── settings.gradle.kts            # Project settings
└── gradle.properties              # Memory allocation & AndroidX settings
```

---

## Key Features & Design Choices

### 1. Clean Email Reader (Zero Clutter)
- **Inbox & Reader (`EmailReaderScreen`)**: Displays **strictly** the email — sender monogram, subject, date, formatted HTML/plain-text body, and attachment chips.
- Zero phishing verdicts, risk percentages, or threat badges are displayed during normal reading.

### 2. Dedicated "Auto Blocked" Section
- Quarantined phishing emails are segregated into their own tab (`AutoBlockedScreen`).
- In this section only, viewing an email opens `BlockedDetailScreen`, presenting:
  - Phishing confidence score (e.g., `98% Phishing Risk`)
  - Threat vectors breakdown (Credential Harvesting, Domain Spoofing, Malicious Links)
  - Extracted suspicious URLs with risk ratings
  - Plaintext sandboxed body preview
  - Mitigation actions (`Restore to Inbox` or `Delete Threat`)

### 3. Persistent Authentication
- Managed by `AuthRepository`.
- Credentials and user session are saved locally in device storage.
- Opening the app automatically restores the session and routes straight to the inbox — no repeating login prompts.

### 4. Zero-Latency Offline Cache & Smart Pagination
- **Room Database (`AppDatabase`)**: All emails are stored locally in SQLite via Room.
- **Instant Display**: On launch, the inbox renders immediately from local database cache (zero spinner delay).
- **Background Sync**: Syncs the 100 most recent emails from the backend in an asynchronous coroutine (`syncRecent100Emails`).
- **Pagination**: When scrolling to the bottom of the 100 emails, it loads 50 more older emails (`loadMore50PastEmails`) without blocking the UI.

### 5. True Liquid Glass with AGSL (Android Graphics Shading Language)
- On Android 13+ (API 33+), `LiquidGlassShader.kt` uses `android.graphics.RuntimeShader` running directly on Android's HWUI RenderThread (Vulkan/SkiaGL).
- `RenderEffect.createBlurEffect` chains into `RenderEffect.createRuntimeShaderEffect`, ensuring blur is hardware-downsampled before running the optical refraction and chromatic dispersion fragment shader.
- Graceful Compose frosted gradient fallback on older devices.

### 6. C++ Native Core (JNI Bridge)
- `NativeCore.kt` loads `soar_core.so` compiled via Android NDK/CMake.
- High-speed URL heuristics and local IOC evaluation running in native C++.

---

## How to Build & Run

### In Android Studio
1. Open Android Studio.
2. Select **Open** and choose `c:\MDC Project\soar_client`.
3. Gradle will sync automatically using the Android SDK and NDK.
4. Run on an Android Emulator (API 33+ recommended for AGSL shaders) or physical device.

### Via Gradle CLI
```bash
cd soar_client
./gradlew assembleDebug
```
The output APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## Backend Connectivity
- Default emulator backend host: `http://10.0.2.2:8000`
- To use an ngrok tunnel or custom host, change the backend URL in **Settings** or on the **Login** screen.
