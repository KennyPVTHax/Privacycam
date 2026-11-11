package com.branchcam.hook

import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.Executor

/**
 * Hooks Camera2 API at three levels:
 *
 * 1. CameraManager.openCamera  → wrap the StateCallback
 * 2. CameraDevice.createCaptureSession (legacy) → intercept output surfaces
 * 3. CameraDevice.createCaptureSession (SessionConfiguration, API 28+) → same
 *
 * When a capture session is created the FrameInjector starts pushing
 * decoded video frames to every Surface the app wants output on.
 */
class Camera2Hook(
    private val classLoader: ClassLoader,
    private val videoUri: String
) {

    private val tag = MainHook.TAG

    fun install() {
        hookOpenCamera()
    }

    // ── 1. openCamera ──────────────────────────────────────────────────────

    private fun hookOpenCamera() {
        try {
            XposedHelpers.findAndHookMethod(
                "android.hardware.camera2.CameraManager",
                classLoader,
                "openCamera",
                String::class.java,
                CameraDevice.StateCallback::class.java,
                Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val origCallback = param.args[1] as? CameraDevice.StateCallback ?: return
                        param.args[1] = WrappedStateCallback(origCallback)
                        XposedBridge.log("[$tag] openCamera intercepted")
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("[$tag] openCamera hook failed: ${e.message}")
        }
    }

    // ── 2. Wrapped StateCallback ───────────────────────────────────────────

    inner class WrappedStateCallback(
        private val orig: CameraDevice.StateCallback
    ) : CameraDevice.StateCallback() {

        override fun onOpened(camera: CameraDevice) {
            XposedBridge.log("[$tag] onOpened — wrapping CameraDevice")
            hookCaptureSession(camera)
            orig.onOpened(camera)
        }

        override fun onDisconnected(camera: CameraDevice) = orig.onDisconnected(camera)
        override fun onError(camera: CameraDevice, error: Int) = orig.onError(camera, error)
    }

    // ── 3. Hook createCaptureSession on the opened CameraDevice ───────────

    private fun hookCaptureSession(camera: CameraDevice) {
        // Legacy API (API 21–27): createCaptureSession(List<Surface>, ...)
        try {
            XposedHelpers.findAndHookMethod(
                camera.javaClass,
                "createCaptureSession",
                List::class.java,
                android.hardware.camera2.CameraCaptureSession.StateCallback::class.java,
                Handler::class.java,
                object : XC_MethodHook() {
                    @Suppress("UNCHECKED_CAST")
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val surfaces = (param.args[0] as? List<Surface>) ?: return
                        startInjecting(surfaces)
                    }
                }
            )
        } catch (_: Throwable) {}

        // Modern API (API 28+): createCaptureSession(SessionConfiguration)
        try {
            XposedHelpers.findAndHookMethod(
                camera.javaClass,
                "createCaptureSession",
                "android.hardware.camera2.params.SessionConfiguration",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val sessionCfg = param.args.firstOrNull() ?: return
                        // Reflect over API 28+ types so the hook module can still load on API 26–27.
                        val outputs = sessionCfg.javaClass
                            .getMethod("getOutputConfigurations")
                            .invoke(sessionCfg) as? List<*> ?: return
                        val surfaces = outputs.mapNotNull { output ->
                            output?.javaClass?.getMethod("getSurface")?.invoke(output) as? Surface
                        }
                        startInjecting(surfaces)
                    }
                }
            )
        } catch (_: Throwable) {}
    }

    // ── 4. Start frame injection ───────────────────────────────────────────

    /**
     * For each [Surface] the app wants camera output on, we start
     * a [FrameInjector] that feeds decoded video frames into that surface.
     */
    private fun startInjecting(surfaces: List<Surface>) {
        XposedBridge.log("[$tag] createCaptureSession — ${surfaces.size} surface(s)")
        surfaces.forEach { surface ->
            if (!surface.isValid) return@forEach
            try {
                FrameInjector(surface, videoUri).start()
                XposedBridge.log("[$tag] FrameInjector started on surface $surface")
            } catch (e: Throwable) {
                XposedBridge.log("[$tag] FrameInjector error: ${e.message}")
            }
        }
    }
}
