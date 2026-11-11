# BranchCam — LSPosed Camera Privacy Module

Replace your real camera feed with any video at the **system level** — works in every app, no per-app setup needed. Privacy shield against age-verification sites, nosy video calls, or any app that demands camera access.

---

## How it works

```
Your app calls CameraManager.openCamera()
       ↓
LSPosed intercepts the callback via IXposedHookLoadPackage
       ↓
Camera2Hook wraps the CaptureSession creation
       ↓
FrameInjector pushes your video's decoded frames
  to the app's output Surface via MediaPlayer
       ↓
App sees your chosen video — not your real camera
```

**No root shell tricks. No /dev/video hacks. Pure Java-level hooks.**

---

## Requirements

| Thing | Min version |
|---|---|
| Android | 8.0 (API 26) |
| LSPosed | Any recent build (framework 82+) |
| Magisk / KernelSU | For LSPosed installation |

---

## Build

### Via GitHub Actions (recommended)

1. Push this repo to GitHub
2. Actions → "Build BranchCam APK" runs automatically
3. Download `BranchCam-LSPosed-debug.apk` from the Artifacts tab

### Local (Android Studio)

```bash
# Open BranchCamXposed/ in Android Studio
# Build → Build Bundle(s) / APK(s) → Build APK(s)
```

---

## Install

1. **Install the APK** on your rooted device
2. Open **LSPosed Manager** → Modules
3. Enable **BranchCam** ✓
4. Set the **scope** to "All apps" or specific apps
5. **Reboot** (required for LSPosed to inject)

---

## Usage

Open the **BranchCam** companion app:

| Control | What it does |
|---|---|
| **Master toggle** | Enable / disable all hooks |
| **Select Video** | Choose a video from your storage to use as fake feed |
| **Remove** | Revert to black-screen fallback |
| **Target Apps** | Pick specific apps to intercept (empty = all apps) |

---

## Architecture

```
BranchCamXposed/
├── hook/                      ← LSPosed hook module (pure hooks, no UI)
│   └── java/com/branchcam/hook/
│       ├── MainHook.kt        ← IXposedHookLoadPackage entry point
│       ├── Camera2Hook.kt     ← Hooks openCamera + createCaptureSession
│       └── FrameInjector.kt   ← MediaPlayer → Surface frame feeding
│
└── app/                       ← Companion app (settings + video picker)
    └── java/com/branchcam/xposed/
        ├── Prefs.kt           ← MODE_WORLD_READABLE SharedPreferences bridge
        └── ui/
            ├── MainActivity.kt      ← Material You main screen
            └── AppPickerActivity.kt ← Per-app targeting list
```

---

## Notes

- The hook reads settings via `XSharedPreferences` — changes take effect **next time the target app is opened** (no reboot needed after first install).
- If no video is selected, the module pushes a **black frame** so the app still sees a valid camera — it won't crash.
- Camera1 (deprecated `android.hardware.Camera`) is **not** hooked in this version. Most modern apps use Camera2.
- Strong Play Integrity attestation will still see LSPosed itself as a modification — this module doesn't address that (see RootHideSuite for that).
