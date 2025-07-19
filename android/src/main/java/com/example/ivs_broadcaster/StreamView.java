package com.example.ivs_broadcaster;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.amazonaws.ivs.broadcast.AudioDevice;
import com.amazonaws.ivs.broadcast.BroadcastConfiguration;
import com.amazonaws.ivs.broadcast.BroadcastException;
import com.amazonaws.ivs.broadcast.BroadcastSession;
import com.amazonaws.ivs.broadcast.Device;
import com.amazonaws.ivs.broadcast.ImageDevice;
import com.amazonaws.ivs.broadcast.ImagePreviewView;
import com.amazonaws.ivs.broadcast.Presets;
import com.amazonaws.ivs.broadcast.SurfaceSource;
import com.amazonaws.ivs.broadcast.TransmissionStats;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.platform.PlatformView;

public class StreamView implements PlatformView, MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    private static final String TAG = "StreamView";
    private final LinearLayout layout;
    private EventChannel.EventSink eventSink;
    private BroadcastSession broadcastSession;
    private AudioDevice audioDevice;
    private final Context context;
    private final Handler mainHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private String defaultCameraType = "0"; // Default to rear camera
    private ImagePreviewView previewView;
    private int sensorOrientation = 0;
    private int deviceRotation = Surface.ROTATION_0;
    private Size cameraOutputSize;
    private Size previewSize;

    private boolean isMuted = false;

    StreamView(Context context, BinaryMessenger messenger) {
        this.context = context;
        layout = new LinearLayout(context);
        // Make sure the layout fills its parent
        layout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        layout.setBackgroundColor(Color.BLACK); // Less distracting than red

        mainHandler = new Handler(Looper.getMainLooper());

        MethodChannel methodChannel = new MethodChannel(messenger, "ivs_broadcaster");
        EventChannel eventChannel = new EventChannel(messenger, "ivs_broadcaster_event");

        methodChannel.setMethodCallHandler(this);
        eventChannel.setStreamHandler(this);
        layout.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                setFocusPoint(event, layout);
            }
            return true;
        });

        // Get device rotation from the activity - update this dynamically
        updateDeviceRotation();
    }

    @Override
    public View getView() {
        return layout;
    }

    @Override
    public void dispose() {
        stopBroadcast();
        releaseCamera();
    }

    // Method to update device rotation dynamically
    private void updateDeviceRotation() {
        if (context instanceof Activity) {
            deviceRotation = ((Activity) context).getWindowManager().getDefaultDisplay().getRotation();
            Log.d(TAG, "Device rotation updated to: " + deviceRotation);
        }
    }

    // --- Method Names and Argument Keys ---
    private static final String METHOD_START_PREVIEW = "startPreview";
    private static final String METHOD_START_BROADCAST = "startBroadcast";
    private static final String METHOD_GET_CAMERA_ZOOM_FACTOR = "getCameraZoomFactor";
    private static final String METHOD_ZOOM_CAMERA = "zoomCamera";
    private static final String METHOD_UPDATE_CAMERA_LENS = "updateCameraLens";
    private static final String METHOD_MUTE = "mute";
    private static final String METHOD_IS_MUTED = "isMuted";
    private static final String METHOD_CHANGE_CAMERA = "changeCamera";
    private static final String METHOD_GET_AVAILABLE_CAMERA_LENS = "getAvailableCameraLens";
    private static final String METHOD_STOP_BROADCAST = "stopBroadcast";
    private static final String METHOD_SET_FOCUS_MODE = "setFocusMode";
    private static final String METHOD_CAPTURE_VIDEO = "captureVideo";
    private static final String METHOD_STOP_VIDEO_CAPTURE = "stopVideoCapture";
    private static final String METHOD_SEND_TIME_METADATA = "sendTimeMetaData";

    private static final String ARG_IMGSET = "imgset";
    private static final String ARG_STREAM_KEY = "streamKey";
    private static final String ARG_QUALITY = "quality";
    private static final String ARG_AUTO_RECONNECT = "autoReconnect";
    private static final String ARG_ZOOM = "zoom";
    private static final String ARG_LENS = "lens";
    private static final String ARG_TYPE = "type";
    private static final String ARG_SECONDS = "seconds";

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        switch (call.method) {
            case METHOD_START_PREVIEW:
                startPreview(call.argument(ARG_IMGSET), call.argument(ARG_STREAM_KEY), call.argument(ARG_QUALITY), call.argument(ARG_AUTO_RECONNECT));
                result.success(true);
                break;
            case METHOD_START_BROADCAST:
                startBroadcast();
                result.success("Broadcasting Started");
                break;
            case METHOD_STOP_BROADCAST:
                stopBroadcast();
                result.success("Broadcast Stopped");
                break;
            case METHOD_MUTE:
                toggleMute();
                result.success(isMuted ? "Muted" : "Unmuted");
                break;
            case METHOD_IS_MUTED:
                result.success(isMuted);
                break;
            case METHOD_SEND_TIME_METADATA:
//                sendMetaData(call.argument("metadata"));
                result.success(true);
                break;
            case METHOD_GET_CAMERA_ZOOM_FACTOR:
                result.success(getCameraZoomFactor());
                break;
            case METHOD_ZOOM_CAMERA:
                zoomCamera(call.argument(ARG_ZOOM));
                result.success(true);
                break;
            case METHOD_CHANGE_CAMERA:
                changeCamera(call.argument(ARG_TYPE));
                result.success("Camera Changed");
                break;
            case METHOD_SET_FOCUS_MODE:
                setFocusMode(call.argument(ARG_TYPE));
                result.success(true);
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    // --- Camera Control Methods ---
    private void setFocusPoint(MotionEvent event, View previewView) {
        if (cameraDevice == null || captureRequestBuilder == null) {
            Log.e(TAG, "No Camera Device Available");
            return;
        }

        Integer currentFocusMode = captureRequestBuilder.get(CaptureRequest.CONTROL_AF_MODE);
        if (currentFocusMode != null && currentFocusMode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) {
            Log.d(TAG, "Camera is on Continuous Auto Focus. Set it to Auto Focus first.");
            return;
        }

        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
            Rect sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (sensorArraySize == null) return;

            // Convert touch coordinates to sensor coordinates.
            final int y = (int) ((event.getX() / (float) previewView.getWidth()) * (float) sensorArraySize.height());
            final int x = (int) ((event.getY() / (float) previewView.getHeight()) * (float) sensorArraySize.width());
            final int halfTouchWidth = 150;
            final int halfTouchHeight = 150;
            MeteringRectangle focusArea = new MeteringRectangle(Math.max(x - halfTouchWidth, 0),
                    Math.max(y - halfTouchHeight, 0),
                    halfTouchWidth * 2,
                    halfTouchHeight * 2,
                    MeteringRectangle.METERING_WEIGHT_MAX - 1);

            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{focusArea});
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, mainHandler);

            Map<Object, Object> data = new HashMap<>();
            data.put("focusPoint", (event.getX() + "_" + event.getY()));
            sendEvent(data);
            Log.d(TAG, "Focus point set at: " + event.getX() + ", " + event.getY());
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error setting focus point", e);
        }
    }

    private boolean setFocusMode(String type) {
        if (cameraDevice == null || captureRequestBuilder == null) return false;

        int focusMode;
        switch (type) {
            case "0":
                focusMode = CaptureRequest.CONTROL_AF_MODE_OFF;
                break;
            case "1":
                focusMode = CaptureRequest.CONTROL_AF_MODE_AUTO;
                break;
            case "2":
                focusMode = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
                break;
            default:
                Log.e(TAG, "Invalid focus mode type");
                return false;
        }

        try {
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, focusMode);
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, mainHandler);
            return true;
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error setting focus mode", e);
            return false;
        }
    }

    private void zoomCamera(Double zoomLevel) {
        if (cameraDevice == null) return;
        try {
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraDevice.getId());
            Rect sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Float maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);

            if (maxZoom == null || sensorArraySize == null) return;

            if (zoomLevel < 1.0f) zoomLevel = 1.0;
            if (zoomLevel > maxZoom) zoomLevel = (double) maxZoom;

            int cropWidth = (int) (sensorArraySize.width() / zoomLevel);
            int cropHeight = (int) (sensorArraySize.height() / zoomLevel);
            int left = (sensorArraySize.width() - cropWidth) / 2;
            int top = (sensorArraySize.height() - cropHeight) / 2;

            Rect zoomRect = new Rect(left, top, left + cropWidth, top + cropHeight);
            captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoomRect);
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, mainHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error applying zoom", e);
        }
    }

    private Map<String, Object> getCameraZoomFactor() {
        Map<String, Object> zoomData = new HashMap<>();
        try {
            CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cameraManager != null && cameraDevice != null) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraDevice.getId());
                Float maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                zoomData.put("minZoom", 1.0f);
                zoomData.put("maxZoom", maxZoom != null ? maxZoom : 1.0f);
            } else {
                zoomData.put("minZoom", 1.0f);
                zoomData.put("maxZoom", 1.0f);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error getting zoom factor", e);
            zoomData.put("minZoom", 1.0f);
            zoomData.put("maxZoom", 1.0f);
        }
        return zoomData;
    }

    // --- Broadcast Session Management ---
    private String streamUrl;
    private String streamKey;
    private String quality;
    private Boolean autoReconnect;
    private SurfaceSource source;

    private void startPreview(String url, String key, String quality, Boolean autoReconnect) {
        this.streamUrl = url;
        this.streamKey = key;
        this.autoReconnect = autoReconnect;
        this.quality = quality;

        // Use standard broadcast configuration
//        BroadcastConfiguration config = new   BroadcastConfiguration();
//
//        // Configure the mixer slot
//        config.mixer.slots = new BroadcastConfiguration.Mixer.Slot[] {
//                BroadcastConfiguration.Mixer.Slot.with(slot -> {
//                    slot.setPreferredAudioInput(
//                            Device.Descriptor.DeviceType.MICROPHONE);
//                    slot.setPreferredVideoInput(
//                            Device.Descriptor.DeviceType.USER_IMAGE);
//                    slot.setMatchCanvasAspectMode(true);
//                    slot.setName("custom");
//                    return slot;
//                }),
//        };
//
//        config.autoReconnect.setEnabled(autoReconnect != null ? autoReconnect : false);

        try {
//            broadcastSession = new BroadcastSession(context, broadcastListener, config, Presets.Devices.MICROPHONE(context));
            broadcastSession = new BroadcastSession(context,
                    broadcastListener,
                    Presets.Configuration.STANDARD_PORTRAIT,
                    Presets.Devices.BACK_CAMERA(context));

            broadcastSession.awaitDeviceChanges(() -> {
                for(Device device: broadcastSession.listAttachedDevices()) {
                    if(device.getDescriptor().type == Device.Descriptor.DeviceType.CAMERA) {
                        ImagePreviewView preview = ((ImageDevice)device).getPreviewView(BroadcastConfiguration.AspectMode.FILL);
                        preview.setLayoutParams(new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.MATCH_PARENT));
                        layout.addView(preview);
                    }
                }
            });
//            this.source = broadcastSession.createImageInputSource();
//
//            for (Device device : broadcastSession.listAttachedDevices()) {
//                if (device.getDescriptor().type == Device.Descriptor.DeviceType.MICROPHONE) {
//                    audioDevice = (AudioDevice) device;
//                }
//                if (device.getDescriptor().type == Device.Descriptor.DeviceType.CAMERA) {
//                    broadcastSession.getMixer().bind(device, "custom");
//                }
//            }
//
//
//            if (source == null || source.getInputSurface() == null) {
//                sendErrorEvent("Failed to create image input source or surface is null");
//                return;
//            }
//
////            Surface ivsSurface = source.getInputSurface();
//
//            previewView = broadcastSession.getPreviewView(BroadcastConfiguration.AspectMode.FIT);
//            previewView.setLayoutParams(new LinearLayout.LayoutParams(
//                    LinearLayout.LayoutParams.MATCH_PARENT,
//                    LinearLayout.LayoutParams.MATCH_PARENT));
//            previewView.setVisibility(View.VISIBLE);
//            layout.addView(previewView);
//
//            previewView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
//                @Override
//                public void onGlobalLayout() {
//                    if (previewView.getWidth() > 0 && previewView.getHeight() > 0) {
//                        previewView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
//                        Log.d(TAG, "PreviewView ready: " + previewView.getWidth() + "x" + previewView.getHeight());
//
//                        // Store preview dimensions
//                        previewSize = new Size(previewView.getWidth(), previewView.getHeight());
//
//                        // Start camera with proper surface setup
//                        startCamera2(ivsSurface, previewView.getWidth(), previewView.getHeight());
//                    }
//                }
//            });

        } catch (Exception e) {
            Log.e(TAG, "Error setting up broadcast session", e);
            sendErrorEvent("Broadcast setup error: " + e.getMessage());
        }
    }

    private int getJpegOrientation() {
        // Check if this is a front camera
        boolean isFrontFacing = false;
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
            Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            isFrontFacing = lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_FRONT;
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error checking camera facing", e);
        }

        int rotationDegrees = getRotationDegrees(deviceRotation);
        
        int orientation;
        if (isFrontFacing) {
            // Front camera needs to account for mirroring
            orientation = (sensorOrientation + rotationDegrees) % 360;
            // Mirror horizontally for front camera
            orientation = (360 - orientation) % 360;
        } else {
            // Back camera - standard calculation
            orientation = (sensorOrientation - rotationDegrees + 360) % 360;
        }
        
        Log.d(TAG, "Camera orientation calculation - sensor: " + sensorOrientation + 
              ", device rotation: " + rotationDegrees + 
              ", front facing: " + isFrontFacing + 
              ", final orientation: " + orientation);
        
        return orientation;
    }

    private void startCamera2(Surface ivsSurface, int viewWidth, int viewHeight) {
        Log.d(TAG, "Starting camera with view dimensions: " + viewWidth + "x" + viewHeight);

        if (ivsSurface == null || !ivsSurface.isValid()) {
            Log.e(TAG, "IVS Surface is null or invalid");
            sendErrorEvent("IVS Surface is not valid");
            return;
        }

        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions((Activity) context, new String[]{Manifest.permission.CAMERA}, 100);
                sendErrorEvent("Camera permission not granted");
                return;
            }

            String cameraId = defaultCameraType;

            // Map the camera type to actual facing direction for proper camera selection
            if (defaultCameraType.equals("0") || defaultCameraType.equals("1")) {
                int desiredFacing = defaultCameraType.equals("0") ?
                        CameraCharacteristics.LENS_FACING_BACK :
                        CameraCharacteristics.LENS_FACING_FRONT;

                for (String id : manager.getCameraIdList()) {
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == desiredFacing) {
                        cameraId = id;
                        Log.d(TAG, "Found camera ID " + id + " for facing direction " + 
                              (desiredFacing == CameraCharacteristics.LENS_FACING_BACK ? "back" : "front"));
                        break;
                    }
                }
            }

            Log.d(TAG, "Opening camera with ID: " + cameraId);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            defaultCameraType = cameraId;

            // Get camera sensor orientation
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (orientation != null) {
                sensorOrientation = orientation;
                Log.d(TAG, "Camera sensor orientation: " + sensorOrientation);
            }

            // Get optimal size for camera - choose based on view dimensions for proper aspect ratio
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            if (map != null) {
                Size[] outputSizes = map.getOutputSizes(SurfaceTexture.class);

                // Choose size that matches the view aspect ratio and orientation
                cameraOutputSize = chooseOptimalSize(outputSizes, viewWidth, viewHeight);
                Log.d(TAG, "Selected camera output size: " + cameraOutputSize.getWidth() + "x" + cameraOutputSize.getHeight());
            }

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    Log.d(TAG, "Camera opened successfully");
                    cameraDevice = camera;
                    createCaptureSession(ivsSurface);
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.d(TAG, "Camera disconnected");
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    camera.close();
                    cameraDevice = null;
                    sendErrorEvent("Camera error: " + error);
                }
            }, mainHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access exception: ", e);
            sendErrorEvent("Camera access error: " + e.getMessage());
        }
    }

    private void sendErrorEvent(String message) {
        Log.e(TAG, "Error: " + message);
        Map<Object, Object> event = new HashMap<>();
        event.put("error", message);
        sendEvent(event);
    }

    private void createCaptureSession(Surface ivsSurface) {
        try {
            if (cameraDevice == null) {
                Log.e(TAG, "cameraDevice is null, cannot create capture session.");
                return;
            }

            // Create a capture request for preview
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(ivsSurface);

            // Essential: Set auto-exposure and auto-focus modes
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            // Update device rotation before setting orientation
            updateDeviceRotation();
            
            // Set orientation based on sensor and device rotation
            int rotation = getJpegOrientation();
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, rotation);
            Log.d(TAG, "Setting JPEG orientation to: " + rotation);

            // Create capture session
            cameraDevice.createCaptureSession(Collections.singletonList(ivsSurface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    Log.d(TAG, "Capture session configured successfully");
                    captureSession = session;
                    try {
                        // Start the preview
                        captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, mainHandler);

                        // Configure transform for proper aspect ratio - don't apply to ImagePreviewView
                        // The ImagePreviewView handles orientation internally
                        
                        // Notify success
                        Map<Object, Object> event = new HashMap<>();
                        event.put("previewState", "READY");
                        sendEvent(event);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Failed to start camera preview.", e);
                        sendErrorEvent("Failed to start camera preview: " + e.getMessage());
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "CaptureSession Configuration Failed");
                    sendErrorEvent("Camera configuration failed");
                }

                @Override
                public void onClosed(@NonNull CameraCaptureSession session) {
                    Log.d(TAG, "Capture session closed");
                    super.onClosed(session);
                }
            }, mainHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "createCaptureSession CameraAccessException", e);
            sendErrorEvent("Camera access error in createCaptureSession: " + e.getMessage());
        }
    }

    // Convert Surface rotation to degrees
    private int getRotationDegrees(int rotation) {
        switch (rotation) {
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            default: return 0; // Surface.ROTATION_0
        }
    }

    private void startBroadcast() {
        if (broadcastSession != null && broadcastSession.isReady()) {
            try {
                broadcastSession.start(streamUrl, streamKey);
                Log.d(TAG, "Broadcast started successfully");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start broadcast", e);
                sendErrorEvent("Failed to start broadcast: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "Broadcast session not ready, cannot start.");
            sendErrorEvent("Broadcast session not ready");
        }
    }

    private void stopBroadcast() {
        if (broadcastSession != null) {
            try {
                broadcastSession.stop();
                broadcastSession.release();
                broadcastSession = null;
                Map<Object, Object> event = new HashMap<>();
                event.put("state", "DISCONNECTED");
                sendEvent(event);
            } catch (Exception e) {
                Log.e(TAG, "Error stopping broadcast", e);
            } finally {
                layout.removeAllViews();
                releaseCamera();
            }
        } else {
            releaseCamera();
        }
    }

    private void toggleMute() {
        if (audioDevice != null) {
            isMuted = !isMuted;
            audioDevice.setGain(isMuted ? 0.0f : 1.0f);
            Map<Object, Object> event = new HashMap<>();
            event.put("audioMuted", isMuted);
            sendEvent(event);
        }
    }

    private void changeCamera(String type) {
        if (broadcastSession == null || source == null) {
            Log.e(TAG, "Cannot change camera, session or source is null");
            return;
        }

        // Get current surface
        Surface ivsSurface = source.getInputSurface();

        // Release current camera first
        releaseCamera();

        // Set new camera type
        defaultCameraType = type;

        // Update device rotation when changing camera
        updateDeviceRotation();

        // Start camera with existing surface
        if (previewView != null && previewView.getWidth() > 0 && previewView.getHeight() > 0) {
            startCamera2(ivsSurface, previewView.getWidth(), previewView.getHeight());
        } else {
            startCamera2(ivsSurface, 1280, 720); // Fallback dimensions
        }
    }

    // Choose optimal size based on target dimensions and available sizes
    private Size chooseOptimalSize(Size[] choices, int targetWidth, int targetHeight) {
        // Ensure we have valid target dimensions
        final int w = (targetWidth > 0) ? targetWidth : 1280;
        final int h = (targetHeight > 0) ? targetHeight : 720;

        // Calculate target aspect ratio
        final double targetRatio = (double) w / h;

        Log.d(TAG, "Target dimensions: " + w + "x" + h + ", ratio: " + targetRatio);

        // Convert to list for easier manipulation
        List<Size> suitableSizes = new ArrayList<>(Arrays.asList(choices));

        // Filter out sizes that are too small or too large
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            suitableSizes.removeIf(size ->
                    size.getWidth() < 480 || size.getHeight() < 320 ||
                            size.getWidth() > 3840 || size.getHeight() > 2160
            );
        }

        if (suitableSizes.isEmpty()) {
            Log.w(TAG, "No suitable sizes found, using first available");
            return choices.length > 0 ? choices[0] : new Size(1280, 720);
        }

        // Sort by aspect ratio match first, then by resolution preference
        Collections.sort(suitableSizes, (a, b) -> {
            double aRatio = (double) a.getWidth() / a.getHeight();
            double bRatio = (double) b.getWidth() / b.getHeight();
            double ratioDiffA = Math.abs(aRatio - targetRatio);
            double ratioDiffB = Math.abs(bRatio - targetRatio);

            // If aspect ratios are similar (within 10%), prefer higher resolution
            if (Math.abs(ratioDiffA - ratioDiffB) < 0.1) {
                int aArea = a.getWidth() * a.getHeight();
                int bArea = b.getWidth() * b.getHeight();

                // Prefer sizes closer to target area but not too much larger
                int targetArea = w * h;
                int aDiff = Math.abs(aArea - targetArea);
                int bDiff = Math.abs(bArea - targetArea);

                return Integer.compare(aDiff, bDiff);
            } else {
                // Choose closer aspect ratio
                return Double.compare(ratioDiffA, ratioDiffB);
            }
        });

        // Log available sizes for debugging
        for (Size size : suitableSizes) {
            double ratio = (double) size.getWidth() / size.getHeight();
            Log.d(TAG, "Available size: " + size.getWidth() + "x" + size.getHeight() +
                    " (ratio: " + String.format("%.2f", ratio) + ")");
        }

        // Select the best match
        Size bestSize = suitableSizes.get(0);
        double bestRatio = (double) bestSize.getWidth() / bestSize.getHeight();

        Log.d(TAG, "Selected camera size: " + bestSize.getWidth() + "x" + bestSize.getHeight() +
                " (ratio: " + String.format("%.2f", bestRatio) +
                ", target ratio: " + String.format("%.2f", targetRatio) + ")");

        return bestSize;
    }

    private BroadcastConfiguration getConfig(String quality) {
        // Create configuration that matches the aspect ratio we want
        BroadcastConfiguration config = new BroadcastConfiguration();

        // Set video configuration based on quality - Portrait orientation for mobile apps
        switch (quality) {
            case "360":
                config.video.setSize(360, 640); // Portrait
                config.video.setInitialBitrate(800_000);
                config.video.setMaxBitrate(1_200_000);
                config.video.setMinBitrate(400_000);
                break;
            case "720":
                config.video.setSize(720, 1280); // Portrait
                config.video.setInitialBitrate(2_500_000);
                config.video.setMaxBitrate(3_500_000);
                config.video.setMinBitrate(1_500_000);
                break;
            case "1080":
            default:
                config.video.setSize(1080, 1920); // Portrait
                config.video.setInitialBitrate(5_000_000);
                config.video.setMaxBitrate(6_000_000);
                config.video.setMinBitrate(3_000_000);
                break;
        }

        // Set frame rate
        config.video.setTargetFramerate(30);

        // Set audio
        config.audio.setBitrate(128_000);
        config.audio.setChannels(2);

        return config;
    }

    /**
     * Creates a broadcast configuration similar to the Swift implementation
     */
    private BroadcastConfiguration getPortraitConfig(String quality) {
        return getConfig(quality);
    }

    private final BroadcastSession.Listener broadcastListener = new BroadcastSession.Listener() {
        @Override
        public void onStateChanged(@NonNull BroadcastSession.State state) {
            Map<Object, Object> event = new HashMap<>();
            event.put("state", state.name().toUpperCase());
            sendEvent(event);
        }

        @Override
        public void onError(@NonNull BroadcastException exception) {
            Map<Object, Object> event = new HashMap<>();
            event.put("error", exception.getError().name() + ": " + exception.getDetail());
            sendEvent(event);
        }

        @Override
        public void onTransmissionStatsChanged(@NonNull TransmissionStats statistics) {
            // Send stats periodically if needed
            Map<Object, Object> event = new HashMap<>();
            event.put("health", statistics.networkHealth.name());
            sendEvent(event);
        }
    };

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        this.eventSink = events;
    }

    @Override
    public void onCancel(Object arguments) {
        this.eventSink = null;
    }

    private void sendEvent(Map<Object, Object> event) {
        if (eventSink != null) {
            mainHandler.post(() -> eventSink.success(new Gson().toJson(event)));
        }
    }

    private void releaseCamera() {
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            captureRequestBuilder = null;
            cameraOutputSize = null;
        } catch (Exception e) {
            Log.e(TAG, "Error releasing camera", e);
        }
    }
}