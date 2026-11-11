package com.branchcam.hook

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.view.Surface
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.IdentityHashMap
import java.util.concurrent.Executor

/** Routes camera output into a drained reader and selected video onto the app surface. */
class Camera2Hook(private val classLoader: ClassLoader, private val videoUri: String) {
    private val tag = MainHook.TAG
    private val readers = IdentityHashMap<Surface, ImageReader>()
    private val replacements = IdentityHashMap<Surface, Surface>()
    private val injectors = IdentityHashMap<Surface, FrameInjector>()
    private val hookedClasses = mutableSetOf<Class<*>>()
    private val readerThread = HandlerThread("branchcam-camera-drain").also { it.start() }
    private val readerHandler = Handler(readerThread.looper)

    fun install() {
        hookRequestTargets()
        try {
            XposedHelpers.findAndHookMethod(
                "android.hardware.camera2.CameraManager", classLoader, "openCamera",
                String::class.java, CameraDevice.StateCallback::class.java, Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val original = param.args[1] as? CameraDevice.StateCallback ?: return
                        param.args[1] = object : CameraDevice.StateCallback() {
                            override fun onOpened(camera: CameraDevice) {
                                hookCaptureSession(camera)
                                original.onOpened(camera)
                            }
                            override fun onDisconnected(camera: CameraDevice) {
                                stopAll()
                                original.onDisconnected(camera)
                            }
                            override fun onError(camera: CameraDevice, error: Int) {
                                stopAll()
                                original.onError(camera, error)
                            }
                            override fun onClosed(camera: CameraDevice) {
                                stopAll()
                                original.onClosed(camera)
                            }
                        }
                        XposedBridge.log("[$tag] openCamera intercepted")
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("[$tag] openCamera hook failed: ${e.message}")
        }
    }

    private fun hookRequestTargets() {
        try {
            XposedHelpers.findAndHookMethod(
                CaptureRequest.Builder::class.java, "addTarget", Surface::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val original = param.args[0] as? Surface ?: return
                        synchronized(replacements) {
                            replacements[original]?.let { param.args[0] = it }
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("[$tag] request target hook failed: ${e.message}")
        }
    }

    private fun hookCaptureSession(camera: CameraDevice) {
        synchronized(hookedClasses) {
            if (!hookedClasses.add(camera.javaClass)) return
        }
        try {
            XposedHelpers.findAndHookMethod(
                camera.javaClass, "createCaptureSession", List::class.java,
                CameraCaptureSession.StateCallback::class.java, Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val surfaces = param.args[0] as? List<*> ?: return
                        val originals = surfaces.filterIsInstance<Surface>()
                        if (originals.size != surfaces.size || !canReadVideo()) return
                        val mapped = prepareReaders(originals) ?: return
                        param.args[0] = mapped
                        val callback = param.args[1] as? CameraCaptureSession.StateCallback ?: return
                        param.args[1] = wrapSessionCallback(callback, originals)
                        XposedBridge.log("[$tag] createCaptureSession — ${originals.size} isolated surface(s)")
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("[$tag] legacy session hook failed: ${e.message}")
        }

        if (Build.VERSION.SDK_INT >= 28) hookModernSession(camera)
    }

    private fun hookModernSession(camera: CameraDevice) {
        try {
            val sessionClass = Class.forName("android.hardware.camera2.params.SessionConfiguration")
            val outputClass = Class.forName("android.hardware.camera2.params.OutputConfiguration")
            XposedHelpers.findAndHookMethod(camera.javaClass, "createCaptureSession", sessionClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val config = param.args[0] ?: return
                        val originals = (XposedHelpers.callMethod(config, "getOutputConfigurations")
                            as? List<*>)?.mapNotNull { output ->
                            output?.let { XposedHelpers.callMethod(it, "getSurface") as? Surface }
                        } ?: return
                        if (originals.isEmpty() || !canReadVideo()) return
                        val input = XposedHelpers.callMethod(config, "getInputConfiguration")
                        if (input != null) return // Reprocessable sessions need a separate adapter.
                        val sessionType = XposedHelpers.callMethod(config, "getSessionType") as Int
                        if (sessionType != 0) return // High speed sessions use different surfaces.
                        val callback = XposedHelpers.callMethod(config, "getStateCallback")
                            as? CameraCaptureSession.StateCallback ?: return
                        val executor = XposedHelpers.callMethod(config, "getExecutor") as? Executor ?: return
                        val mapped = prepareReaders(originals) ?: return
                        val outputs = mapped.map { outputClass.getConstructor(Surface::class.java)
                            .newInstance(it) }
                        val newConfig = sessionClass.getConstructor(Int::class.javaPrimitiveType,
                            List::class.java, Executor::class.java,
                            CameraCaptureSession.StateCallback::class.java)
                            .newInstance(sessionType, outputs, executor,
                                wrapSessionCallback(callback, originals))
                        val parameters = XposedHelpers.callMethod(config, "getSessionParameters")
                        if (parameters != null) XposedHelpers.callMethod(newConfig,
                            "setSessionParameters", parameters)
                        param.args[0] = newConfig
                        XposedBridge.log("[$tag] modern createCaptureSession — ${originals.size} isolated surface(s)")
                    }
                })
        } catch (e: Throwable) {
            XposedBridge.log("[$tag] modern session hook failed: ${e.message}")
        }
    }

    private fun prepareReaders(originals: List<Surface>): List<Surface>? {
        val created = mutableListOf<Pair<Surface, ImageReader>>()
        try {
            for (original in originals) {
                if (!original.isValid) throw IllegalStateException("Invalid output surface")
                val reader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
                reader.setOnImageAvailableListener({ source ->
                    source.acquireLatestImage()?.close()
                }, readerHandler)
                created += original to reader
            }
            synchronized(replacements) {
                created.forEach { (original, reader) ->
                    readers[original] = reader
                    replacements[original] = reader.surface
                }
            }
            return created.map { it.second.surface }
        } catch (e: Throwable) {
            created.forEach { it.second.close() }
            XposedBridge.log("[$tag] Camera output isolation failed: ${e.message}")
            return null
        }
    }

    private fun wrapSessionCallback(original: CameraCaptureSession.StateCallback,
                                    outputs: List<Surface>): CameraCaptureSession.StateCallback =
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                val context = currentContext()
                if (context != null) outputs.forEach { surface ->
                    try {
                        FrameInjector(context, surface, playbackUri()).also {
                            it.start()
                            synchronized(injectors) { injectors[surface] = it }
                        }
                        XposedBridge.log("[$tag] FrameInjector started on isolated app surface")
                    } catch (e: Throwable) {
                        XposedBridge.log("[$tag] FrameInjector error: ${e.message}")
                    }
                }
                original.onConfigured(session)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                stopAll()
                original.onConfigureFailed(session)
            }
            override fun onClosed(session: CameraCaptureSession) {
                stopAll()
                original.onClosed(session)
            }
        }

    private fun playbackUri(): String = if (videoUri.startsWith("content://"))
        "content://com.branchcam.xposed.selectedvideo/video" else videoUri

    private fun canReadVideo(): Boolean {
        if (videoUri.isEmpty()) return true
        if (!videoUri.startsWith("content://")) return true
        return try {
            val ctx: Context = currentContext() ?: return false
            ctx.contentResolver.openFileDescriptor(Uri.parse(playbackUri()), "r")?.use { true } ?: false
        } catch (e: Throwable) {
            XposedBridge.log("[$tag] Selected media unavailable to target: ${e.message}")
            false
        }
    }

    private fun stopAll() {
        synchronized(injectors) {
            injectors.values.forEach { it.stop() }
            injectors.clear()
        }
        synchronized(replacements) {
            replacements.clear()
            readers.values.forEach { it.close() }
            readers.clear()
        }
    }

    private fun currentContext(): Context? = try {
        XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityThread", classLoader),
            "currentApplication"
        ) as? Context
    } catch (_: Throwable) { null }
}
