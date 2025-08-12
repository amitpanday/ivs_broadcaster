package com.example.ivs_broadcaster;


import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.media.Image;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.amazonaws.ivs.broadcast.AudioDevice;
import com.amazonaws.ivs.broadcast.BroadcastConfiguration;
import com.amazonaws.ivs.broadcast.BroadcastException;
import com.amazonaws.ivs.broadcast.BroadcastSession;
import com.amazonaws.ivs.broadcast.Device;
import com.amazonaws.ivs.broadcast.ImageDevice;
import com.amazonaws.ivs.broadcast.Presets;
import com.amazonaws.ivs.broadcast.SurfaceSource;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.Gson;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import ai.deepar.ar.ARErrorType;
import ai.deepar.ar.AREventListener;
import ai.deepar.ar.CameraResolutionPreset;
import ai.deepar.ar.DeepAR;
import ai.deepar.ar.DeepARImageFormat;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.platform.PlatformView;

public class StreamView implements PlatformView, MethodChannel.MethodCallHandler, EventChannel.StreamHandler, AREventListener, LifecycleOwner {

    private static final String TAG = "StreamView";

    // Constants
    private static final int NUMBER_OF_BUFFERS = 2;
    private static final boolean useExternalCameraTexture = false;

    // Lifecycle
    private LifecycleRegistry lifecycleRegistry;

    // Flutter method names
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
    private static final String METHOD_SWITCH_EFFECT = "switchEffect"; // New for Flutter control

    // Argument keys
    private static final String ARG_IMGSET = "imgset";
    private static final String ARG_STREAM_KEY = "streamKey";
    private static final String ARG_QUALITY = "quality";
    private static final String ARG_AUTO_RECONNECT = "autoReconnect";
    private static final String ARG_ZOOM = "zoom";
    private static final String ARG_LENS = "lens";
    private static final String ARG_TYPE = "type";
    private static final String ARG_SECONDS = "seconds";
    private static final String ARG_EFFECT = "effect"; // New for Flutter effect switching

    // UI & context
    private final LinearLayout layout;
    private final Context context;
    private final Handler mainHandler;

    // DeepAR
    private DeepAR deepAR;
    private ArrayList<String> effects;

    // Camera
    private CameraType defaultCameraType = CameraType.FRONT;
    private ARSurfaceProvider surfaceProvider = null;
    private ListenableFuture<ProcessCameraProvider> cameraProviderFuture;
    private ByteBuffer[] buffers;
    private int currentBuffer = 0;

    // Broadcast
    private BroadcastSession broadcastSession;
    private AudioDevice audioDevice;
    private boolean isMuted = false;
    private SurfaceSource surfaceSource;
    private Surface surface;
    private String streamUrl;
    private String streamKey;
    private String quality;
    private Boolean autoReconnect;
    private Device.Descriptor currentCamera;
    private int width;
    private int height;

    // Event handling
    private EventChannel.EventSink eventSink;

    @SuppressLint("ClickableViewAccessibility")
    StreamView(Context context, BinaryMessenger messenger) {
        this.context = context;
        layout = new LinearLayout(context);
        mainHandler = new Handler(Looper.getMainLooper());

        // Initialize lifecycle
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);

        MethodChannel methodChannel = new MethodChannel(messenger, "ivs_broadcaster");
        EventChannel eventChannel = new EventChannel(messenger, "ivs_broadcaster_event");

        methodChannel.setMethodCallHandler(this);
        eventChannel.setStreamHandler(this);

        layout.setOnTouchListener((v, event) -> {
//            if (event.getAction() == MotionEvent.ACTION_DOWN) {
//                // TODO: setFocusPoint(event, layout);
//            }
            return true;
        });

        initializeFilters();
        initializeDeepAR();
        
        // Set lifecycle to STARTED
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
    }

    /* -----------------------------
     * DeepAR & Filters
     * ----------------------------- */
    private void initializeDeepAR() {
        deepAR = new DeepAR(context);
        deepAR.setLicenseKey("427ca0498f77f9bd52869ed79f841ff5b27a693b9409b6621083e588b820547adad3d3b51db1f68f");
        deepAR.initialize(context, this);
        setupCamera();
    }

    private void initializeFilters() {
        effects = new ArrayList<>();
        effects.add("none");
        effects.add("aviators.deepar");
        effects.add("bigmouth.deepar");
        effects.add("dalmatian.deepar");
        effects.add("flowers.deepar");
        effects.add("koala.deepar");
        effects.add("lion.deepar");
        effects.add("smallface.deepar");
        effects.add("teddycigar.deepar");
        effects.add("background_segmentation.deepar");
        effects.add("tripleface.deepar");
        effects.add("sleepingmask.deepar");
        effects.add("fatify.deepar");
        effects.add("mudmask.deepar");
        effects.add("pug.deepar");
        effects.add("twistedface.deepar");
        effects.add("grumpycat.deepar");
        effects.add("Helmet_PBR_V1.deepar");
    }

    private String getFilterPath(String filterName) {
        if (filterName.equals("none")) {
            return null;
        }
        return "file:///android_asset/" + filterName;
    }

    private void switchEffect(String effectName) {
        if (deepAR != null) {
            deepAR.switchEffect("effect", getFilterPath(effectName));
            Log.d(TAG, "Switched effect to: " + effectName);
        }
    }

    /* -----------------------------
     * Camera Setup
     * ----------------------------- */
    private void setupCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(context);
        cameraProviderFuture.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                    bindImageAnalysis(cameraProvider);
                } catch (ExecutionException | InterruptedException e) {
                    Log.e(TAG, "Use case binding failed", e);
                }
            }
        }, ContextCompat.getMainExecutor(context));
    }


    private int getScreenOrientation() {
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        int rotation = windowManager.getDefaultDisplay().getRotation();
        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(dm);
        width = dm.widthPixels;
        height = dm.heightPixels;
        int orientation;
        // if the device's natural orientation is portrait:
        if ((rotation == Surface.ROTATION_0
                || rotation == Surface.ROTATION_180) && height > width ||
                (rotation == Surface.ROTATION_90
                        || rotation == Surface.ROTATION_270) && width > height) {
            switch(rotation) {
                case Surface.ROTATION_90:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                    break;
                case Surface.ROTATION_180:
                    orientation =
                            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
                    break;
                case Surface.ROTATION_270:
                    orientation =
                            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                    break;
                default:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                    break;
            }
        }
        // if the device's natural orientation is landscape or if the device
        // is square:
        else {
            switch(rotation) {
                case Surface.ROTATION_90:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                    break;
                case Surface.ROTATION_180:
                    orientation =
                            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                    break;
                case Surface.ROTATION_270:
                    orientation =
                            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
                    break;
                default:
                    orientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                    break;
            }
        }

        return orientation;
    }

    private void bindImageAnalysis(@NonNull ProcessCameraProvider cameraProvider) {
        CameraResolutionPreset cameraResolutionPreset = CameraResolutionPreset.P1920x1080;
        int width;
        int height;
        int orientation = getScreenOrientation();
        if (orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE || orientation ==ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE){
            width = cameraResolutionPreset.getWidth();
            height =  cameraResolutionPreset.getHeight();
        } else {
            width = cameraResolutionPreset.getHeight();
            height = cameraResolutionPreset.getWidth();
        }

        Size cameraResolution = new Size(width, height);
        CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(CameraType.fromValue(defaultCameraType)).build();

        if(useExternalCameraTexture) {
            Preview preview = new Preview.Builder()
                    .setTargetResolution(cameraResolution)
                    .build();

            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(this, cameraSelector, preview);
            if(surfaceProvider == null) {
                surfaceProvider = new ARSurfaceProvider(context, deepAR);
            }
            preview.setSurfaceProvider(surfaceProvider);
            surfaceProvider.setMirror(defaultCameraType == CameraType.FRONT);
        } else {
            buffers = new ByteBuffer[NUMBER_OF_BUFFERS];
            for (int i = 0; i < NUMBER_OF_BUFFERS; i++) {
                buffers[i] = ByteBuffer.allocateDirect(width * height * 4);
                buffers[i].order(ByteOrder.nativeOrder());
                buffers[i].position(0);
            }

            ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetResolution(cameraResolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
            imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context), imageAnalyzer);
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);
        }
    }

    private ImageAnalysis.Analyzer imageAnalyzer = new ImageAnalysis.Analyzer() {
        @Override
        public void analyze(@NonNull ImageProxy image) {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            buffer.rewind();
            buffers[currentBuffer].put(buffer);
            buffers[currentBuffer].position(0);
            if (deepAR != null) {
                deepAR.receiveFrame(buffers[currentBuffer],
                        image.getWidth(), image.getHeight(),
                        image.getImageInfo().getRotationDegrees(),
                        true,
                        DeepARImageFormat.RGBA_8888,
                        image.getPlanes()[0].getPixelStride()
                );
            }
            currentBuffer = (currentBuffer + 1) % NUMBER_OF_BUFFERS;
            image.close();
        }
    };

    /* -----------------------------
     * Broadcast Management
     * ----------------------------- */
    private void startPreview(String url, String key, String quality, Boolean autoReconnect) {
        this.streamUrl = url;
        this.streamKey = key;
        this.autoReconnect = autoReconnect;
        this.quality = quality;
        BroadcastConfiguration config = Presets.Configuration.STANDARD_PORTRAIT;
        config.mixer.slots =  new BroadcastConfiguration.Mixer.Slot[] {
                BroadcastConfiguration.Mixer.Slot.with(slot -> {
                    slot.setPreferredAudioInput(Device.Descriptor.DeviceType.MICROPHONE);
                    slot.setPreferredVideoInput(Device.Descriptor.DeviceType.USER_IMAGE);
                    slot.setName("custom");
                    return slot;
                }),
        };
        config.autoReconnect.setEnabled(autoReconnect);
        broadcastSession = new BroadcastSession(context, broadcastListener, config, Presets.Devices.MICROPHONE(context));
        for (Device device : broadcastSession.listAttachedDevices()) {
            if (device.getDescriptor().type == Device.Descriptor.DeviceType.MICROPHONE) {
                audioDevice = (AudioDevice) device;
            }
        }
        surfaceSource = broadcastSession.createImageInputSource();
        surfaceSource.setRotation(ImageDevice.Rotation.ROTATION_0);
        surface = surfaceSource.getInputSurface();
        broadcastSession.getMixer().bind(surfaceSource, "custom");
        deepAR.setRenderSurface(surface, 720, 1280);
        TextureView view = broadcastSession.getPreviewView(BroadcastConfiguration.AspectMode.FILL);
        setImagePreviewView(view);
    }

    private void startBroadcast() {
        if (broadcastSession != null && broadcastSession.isReady()) {
            broadcastSession.start(streamUrl, streamKey);
        } else {
            Log.w(TAG, "Broadcast session not ready, cannot start.");
        }
    }

    private void stopBroadcast() {
        if (broadcastSession != null) {
            broadcastSession.stop();
            broadcastSession.release();
            broadcastSession = null;
            Map<Object, Object> event = new HashMap<>();
            event.put("state", "DISCONNECTED");
            sendEvent(event);
            layout.removeAllViews();
        }
    }

    private void setImagePreviewView(View preview) {
        if (preview == null){
            layout.removeAllViews();
            return;
        }
        layout.removeAllViews();
        preview.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        layout.addView(preview);
    }

    private void toggleMute() {
        if (audioDevice != null) {
            isMuted = !isMuted;
            audioDevice.setGain(isMuted ? 0.0f : 1.0f);
        }
    }

    /* -----------------------------
     * TODO: To Be Implemented
     * ----------------------------- */
    private float getCameraZoomFactor() {
        // TODO: Implement camera zoom factor retrieval
        return 1.0f;
    }

    private void zoomCamera(float zoomLevel) {
        // TODO: Implement camera zoom control
    }

    private void setFocusMode(String mode) {

    }

    private void sendMetaData(String metadata) {
        broadcastSession.sendTimedMetadata(metadata);
    }

    private  void changeCamera(String type) {
        defaultCameraType = CameraType.fromValue(type);
        CameraType cameraType = CameraType.fromValue(type);

        if (cameraType != null && surfaceProvider != null) {
            surfaceProvider.setMirror(cameraType == CameraType.FRONT);
            ProcessCameraProvider cameraProvider = null;
            try {
                cameraProvider = cameraProviderFuture.get();
                cameraProvider.unbindAll();
                bindImageAnalysis(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error changing camera", e);
            }
        } else {
            Log.w(TAG, "Invalid camera type: " + type);
        }
    }

    /* -----------------------------
     * Method Call Handler
     * ----------------------------- */
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
                sendMetaData(call.argument("metadata"));
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
                changeCamera(Objects.requireNonNull(call.argument(ARG_TYPE)));
                result.success("Camera Changed");
                break;
            case METHOD_SET_FOCUS_MODE:
                setFocusMode(call.argument(ARG_TYPE));
                result.success(true);
                break;
            case METHOD_SWITCH_EFFECT:
                switchEffect(call.argument(ARG_EFFECT));
                result.success("Effect Switched");
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    /* -----------------------------
     * Event Handling
     * ----------------------------- */
    private void sendEvent(Map<Object, Object> event) {
        if (eventSink != null) {
            Log.d(TAG, "Sending Event: " + event.toString());
            mainHandler.post(() -> eventSink.success(new Gson().toJson(event)));
        }
    }

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        this.eventSink = events;
    }

    @Override
    public void onCancel(Object arguments) {
        this.eventSink = null;
    }

    /* -----------------------------
     * View Lifecycle
     * ----------------------------- */
    @Override
    public View getView() {
        return layout;
    }

    @Override
    public void dispose() {
        // Set lifecycle to DESTROYED
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        
        stopBroadcast();
        ProcessCameraProvider cameraProvider = null;
        try {
            cameraProvider = cameraProviderFuture.get();
            cameraProvider.unbindAll();
        } catch (ExecutionException | InterruptedException e) {
            Log.e(TAG, "Use case binding failed", e);
        }
        if (surfaceProvider != null) {
            surfaceProvider.stop();
            surfaceProvider = null;
        }
        deepAR.release();
        deepAR = null;
    }

    /* -----------------------------
     * LifecycleOwner Implementation
     * ----------------------------- */
    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    /* -----------------------------
     * DeepAR Callbacks
     * ----------------------------- */
    @Override public void screenshotTaken(Bitmap bitmap) {}
    @Override public void videoRecordingStarted() {}
    @Override public void videoRecordingFinished() {}
    @Override public void videoRecordingFailed() {}
    @Override public void videoRecordingPrepared() {}
    @Override public void shutdownFinished() {}
    @Override public void initialized() {}
    @Override public void faceVisibilityChanged(boolean b) {}
    @Override public void imageVisibilityChanged(String s, boolean b) {}
    @Override public void frameAvailable(Image image) {}
    @Override public void error(ARErrorType arErrorType, String s) {}
    @Override public void effectSwitched(String s) {}

    /* -----------------------------
     * Broadcast Listener
     * ----------------------------- */
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
    };
}

enum CameraType {
    FRONT("1"),
    BACK("0");

    private final String value;

    CameraType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static CameraType fromValue(String value) {
        for (CameraType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return FRONT;
    }

    public static int fromValue(CameraType type) {
        if (type == CameraType.FRONT) {
            return CameraSelector.LENS_FACING_FRONT;
        }
        return 1;
    }
}
