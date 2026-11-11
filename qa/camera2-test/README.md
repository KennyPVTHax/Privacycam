# Owned Camera2 regression app

This test app uses only its own camera permission and a `SurfaceView`. It logs
whether the BranchCam media bridge can open the selected video and whether a
Camera2 preview session starts. Its UI is intentionally just the preview.

From the repository root, build with:

```powershell
.\android-module\src\BranchCamXposed\gradlew.bat -p qa\camera2-test assembleDebug
```

Install `qa/camera2-test/build/outputs/apk/debug/BranchCamCamera2Test-debug.apk`
on a disposable emulator. Grant camera permission through the Android prompt.
Use `adb shell am start -n com.branchcam.cameratest/.MainActivity` for the
legacy session overload. Add `--ez modern true` for the API 28+ overload.

Only scope `com.branchcam.cameratest` in LSPosed and select it in BranchCam's
existing Target Apps screen. With a synthetic video selected, expect
`bridge-readable=true` and `preview-running-legacy` or
`preview-running-modern` in logcat. Compare the visible output with the known
synthetic source and repeat with the LSPosed module disabled. A running preview
alone does not prove frame replacement.
