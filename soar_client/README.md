# SOAR Client — Mobile Phishing Pipeline

Ultra-lightweight, cross-platform (iOS & Android) mobile client for a three-tiered Security Orchestration, Automation, and Response (SOAR) pipeline. Built with Flutter for native ARM compilation, and Dart FFI for zero-GC overhead when parsing Indicator of Compromise (IOC) datasets.

## Architecture

```
soar_client/
├── lib/
│   ├── main.dart                 # App entry point, navigation shell
│   ├── theme/
│   │   └── soar_theme.dart       # Design tokens, colour palette, typography
│   ├── models/
│   │   └── soar_models.dart      # Domain models: PhishingEvent, SandboxAlert, IntelReport
│   ├── screens/
│   │   ├── dashboard_screen.dart # Tier 1 — Real-time triage dashboard
│   │   ├── alerts_screen.dart    # Tier 2 — Sandbox detonation alerts
│   │   └── reporting_screen.dart # Tier 3 — CERT-In escalation reports
│   ├── services/
│   │   ├── websocket_service.dart    # WebSocket client with auto-reconnect
│   │   ├── notification_service.dart # Local push notification bridge
│   │   └── api_service.dart          # HTTP client for report API
│   ├── ffi/
│   │   └── soar_core_bindings.dart   # Dart FFI bindings to native C library
│   └── widgets/
│       └── shared_widgets.dart       # GlassCard, RiskBadge, StatusPill, PulseDot, SoarButton
└── native_core/
    └── cpp/
        ├── soar_core.h           # C header: TriageResult struct, bloom filter API
        ├── soar_core.cpp         # C implementation: URL heuristics, IOC lookup
        └── CMakeLists.txt        # NDK build config
```

## Features

| Tier | Feature | Implementation |
|------|---------|----------------|
| 1 | Real-Time Triage Dashboard | WebSockets streaming, live event feed, risk score visualisation |
| 2 | Dynamic Analysis Alerts | Local push notifications for sandbox detonation results |
| 3 | Law Enforcement Escalation | CERT-In compliant report submission with status tracking |

## Dependencies (minimal)

| Package | Purpose | Why not native? |
|---------|---------|-----------------|
| `web_socket_channel` | WebSocket I/O | Dart's `dart:io` WebSocket lacks the channel abstraction needed for clean stream-based architecture |
| `flutter_local_notifications` | Tier 2 alerts | Requires platform channel boilerplate that this package encapsulates |
| `flutter_svg` | SVG asset rendering | No built-in SVG decoder in Flutter |
| `http` | HTTP client | `dart:io` HttpClient API is verbose; `http` is the official Dart team package |
| `ffi` | FFI utilities | Part of the Dart SDK ecosystem; provides `Utf8` and struct helpers |

## Getting Started

```bash
# Ensure Flutter SDK is in PATH
flutter pub get
flutter run
```

## Build & Verify Size

```bash
flutter build apk --split-per-abi
# Check: build/app/outputs/flutter-apk/app-arm64-v8a-release.apk
# Expected: ~5–7 MB (well under 15 MB threshold)
```

## Backend Integration

The client expects a FastAPI backend running at `http://10.0.2.2:8000` (Android emulator loopback). Endpoints:

- `ws://host:8000/ws/triage` — WebSocket for live phishing events
- `GET /api/v1/reports` — Fetch generated reports
- `POST /api/v1/reports/{id}/submit` — Submit intelligence package
- `GET /api/v1/reports/{id}/pdf` — Download report PDF

When the backend is offline, the app runs in **demo mode** with synthetic data.
