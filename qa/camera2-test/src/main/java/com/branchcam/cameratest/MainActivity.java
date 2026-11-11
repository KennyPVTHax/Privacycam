package com.branchcam.cameratest;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.camera2.*;
import android.os.Bundle;
import android.os.Build;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import java.util.Collections;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;

public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private CameraDevice camera;
    private CameraCaptureSession session;
    private HandlerThread thread;
    private Handler handler;
    private SurfaceView view;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try (android.os.ParcelFileDescriptor video = getContentResolver().openFileDescriptor(
                Uri.parse("content://com.branchcam.xposed.selectedvideo/video"), "r")) {
            Log.i("Camera2QA", "bridge-readable=" + (video != null && video.getStatSize() > 0));
        } catch (Exception e) { Log.e("Camera2QA", "bridge-failed=" + e.getMessage()); }
        view = new SurfaceView(this);
        setContentView(view);
        view.getHolder().addCallback(this);
        thread = new HandlerThread("camera-qa"); thread.start(); handler = new Handler(thread.getLooper());
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 1 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) open();
    }
    @Override public void surfaceCreated(SurfaceHolder holder) { open(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { close(); }

    private void open() {
        if (camera != null || !view.getHolder().getSurface().isValid() ||
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            CameraManager mgr = getSystemService(CameraManager.class);
            String id = mgr.getCameraIdList()[0];
            mgr.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    camera = device;
                    try {
                        Surface surface = view.getHolder().getSurface();
                        CameraCaptureSession.StateCallback callback = new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession s) {
                                session = s;
                                try {
                                    CaptureRequest.Builder req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                    req.addTarget(surface);
                                    s.setRepeatingRequest(req.build(), null, handler);
                                    Log.i("Camera2QA", "preview-running" + (getIntent().getBooleanExtra("modern", false) ? "-modern" : "-legacy"));
                                } catch (Exception e) { Log.e("Camera2QA", "preview-failed", e); }
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession s) { Log.e("Camera2QA", "session-failed"); }
                        };
                        if (Build.VERSION.SDK_INT >= 28 && getIntent().getBooleanExtra("modern", false)) {
                            SessionConfiguration config = new SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                Collections.singletonList(new OutputConfiguration(surface)),
                                command -> handler.post(command), callback);
                            device.createCaptureSession(config);
                        } else {
                            device.createCaptureSession(Collections.singletonList(surface), callback, handler);
                        }
                    } catch (Exception e) { Log.e("Camera2QA", "session-open-failed", e); }
                }
                @Override public void onDisconnected(CameraDevice device) { close(); }
                @Override public void onError(CameraDevice device, int error) { Log.e("Camera2QA", "camera-error="+error); close(); }
            }, handler);
        } catch (Exception e) { Log.e("Camera2QA", "camera-open-failed", e); }
    }
    private void close() { if (session != null) session.close(); session = null; if (camera != null) camera.close(); camera = null; }
    @Override public void onDestroy() { close(); if (thread != null) thread.quitSafely(); super.onDestroy(); }
}
