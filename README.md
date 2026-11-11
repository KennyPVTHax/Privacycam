# BranchCam 🎥

Privacy camera shield — Chrome Extension + Android LSPosed Module

**Access / Support:** DM [@PolsBranch](https://t.me/PolsBranch) on Telegram

---

## Chrome Extension

Replaces your real webcam with a pre-recorded video on **any site** — Google Meet, Zoom, Teams, Snapchat Web, etc.

- Works on CSP-strict sites (Google Meet, etc.)
- No physical webcam needed
- Injects fake device into device list so sites don't show "Camera not found"
- Real mic still passes through

**Install:** Load `chrome-extension/` as an unpacked extension in Chrome Developer Mode

---

## Android LSPosed Module

System-level Camera2 API hook — works for **all apps**:
- WhatsApp calls ✓
- Snapchat ✓
- Instagram ✓
- Zoom ✓
- Any app using Camera2 API ✓

**Build:** Push changes → GitHub Actions auto-builds APK → download from Actions > Artifacts

---

## License Tools

`license-tools/license_gen.py` — generate device-bound AES-256 license keys for buyers.

See usage instructions inside the script.
