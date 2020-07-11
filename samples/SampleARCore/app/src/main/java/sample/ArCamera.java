package sample;

import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.SharedCamera;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import sample.rendering.BackgroundRenderer;
import sample.rendering.CameraPermissionHelper;

public class ArCamera implements GLSurfaceView.Renderer, ImageReader.OnImageAvailableListener {
    private final static String TAG = ArCamera.class.getSimpleName();

    // Whether the surface texture has been attached to the GL context.
    boolean isGlAttached;

    // GL Surface used to draw camera preview image.
    private GLSurfaceView surfaceView;

    // ARCore session that supports camera sharing.
    private Session sharedSession;

    // Camera capture session. Used by both non-AR and AR modes.
    private CameraCaptureSession captureSession;

    // Reference to the camera system service.
    private CameraManager cameraManager;

    // A list of CaptureRequest keys that can cause delays when switching between AR and non-AR modes.
    private List<CaptureRequest.Key<?>> keysThatCanCauseCaptureDelaysWhenModified;

    // Camera device. Used by both non-AR and AR modes.
    private CameraDevice cameraDevice;

    // Looper handler thread.
    private HandlerThread backgroundThread;

    // Looper handler.
    private Handler backgroundHandler;

    // ARCore shared camera instance, obtained from ARCore session that supports sharing.
    private SharedCamera sharedCamera;

    // Camera ID for the camera used by ARCore.
    private String cameraId;

    // Ensure GL surface draws only occur when new frames are available.
    private final AtomicBoolean shouldUpdateSurfaceTexture = new AtomicBoolean(false);

    // Whether ARCore is currently active.
    private boolean arcoreActive;

    // Whether the GL surface has been created.
    private boolean surfaceCreated;

    // Camera preview capture request builder
    private CaptureRequest.Builder previewCaptureRequestBuilder;

    // Image reader that continuously processes CPU images.
    private ImageReader cpuImageReader;

    // Renderers, see hello_ar_java sample to learn more.
    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();

    // Prevent any changes to camera capture session after CameraManager.openCamera() is called, but
    // before camera device becomes active.
    private boolean captureSessionChangesPossible = true;

    // ----------------------------------------------------------------------------------------------------
    private Activity activity;
    private Context context;
    // ----------------------------------------------------------------------------------------------------
    private int viewportWidth = 0;
    private int viewportHeight = 0;
    private boolean viewportChanged = false;
    private int displayRotation;
    private Size captureSize = new Size(640, 480);
    // ----------------------------------------------------------------------------------------------------
    // ----------------------------------------------------------------------------------------------------

    public ArCamera(Activity activity, GLSurfaceView surfaceView) {
        this.activity = activity;
        this.context = this.activity.getApplicationContext();
        // GL surface view that renders camera preview image.
        this.surfaceView = surfaceView;
        this.surfaceView.setPreserveEGLContextOnPause(true);
        this.surfaceView.setEGLContextClientVersion(2);
        this.surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        this.surfaceView.setRenderer(this);
        this.surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        WindowManager windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        Display display = windowManager.getDefaultDisplay();
        this.displayRotation = display.getRotation();

    }

    public void switchMode() {
        if (this.arcoreActive) {
            pauseARCore();
            resumeCamera2();
        } else {
            resumeARCore();
        }
    }

    protected void onResume() {
        waitUntilCameraCaptureSesssionIsActive();
        startBackgroundThread();
        this.surfaceView.onResume();

        // When the activity starts and resumes for the first time, openCamera() will be called
        // from onSurfaceCreated(). In subsequent resumes we call openCamera() here.
        if (this.surfaceCreated) {
            openCamera();
        }

    }

    public void onPause() {
        this.surfaceView.onPause();
        waitUntilCameraCaptureSesssionIsActive();
        pauseARCore();
        closeCamera();
        stopBackgroundThread();
    }

    private void resumeCamera2() {
        setRepeatingCaptureRequest();
        this.sharedCamera.getSurfaceTexture().setOnFrameAvailableListener(this.onFrameAvailableListener);
    }

    private void resumeARCore() {
        // Ensure that session is valid before triggering ARCore resume. Handles the case where the user
        // manually uninstalls ARCore while the app is paused and then resumes.
        if (this.sharedSession == null) {
            return;
        }

        if (!this.arcoreActive) {
            try {
                // Resume ARCore.
                this.sharedSession.resume();
                this.arcoreActive = true;

                // Set capture session callback while in AR mode.
                this.sharedCamera.setCaptureCallback(this.captureSessionCallback, this.backgroundHandler);
            } catch (CameraNotAvailableException e) {
                Log.e(TAG, "Failed to resume ARCore session", e);
            }
        }
    }

    private void pauseARCore() {
        this.shouldUpdateSurfaceTexture.set(false);
        if (this.arcoreActive) {
            // Pause ARCore.
            this.sharedSession.pause();
            this.arcoreActive = false;
        }
    }

    // Called when starting non-AR mode or switching to non-AR mode.
    // Also called when app starts in AR mode, or resumes in AR mode.
    private void setRepeatingCaptureRequest() {
        try {
            setCameraEffects(previewCaptureRequestBuilder);

            this.captureSession.setRepeatingRequest(this.previewCaptureRequestBuilder.build(), this.captureSessionCallback, this.backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to set repeating request", e);
        }
    }

    private void createCameraPreviewSession() {
        try {
            // Note that isGlAttached will be set to true in AR mode in onDrawFrame().
            this.sharedSession.setCameraTextureName(this.backgroundRenderer.getTextureId());
            this.sharedCamera.getSurfaceTexture().setOnFrameAvailableListener(this.onFrameAvailableListener);

            // Create an ARCore compatible capture request using `TEMPLATE_RECORD`.
            this.previewCaptureRequestBuilder = this.cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

            // Build surfaces list, starting with ARCore provided surfaces.
            List<Surface> surfaceList = this.sharedCamera.getArCoreSurfaces();

            // Add a CPU image reader surface. On devices that don't support CPU image access, the image
            // may arrive significantly later, or not arrive at all.
            surfaceList.add(this.cpuImageReader.getSurface());

            // Surface list should now contain three surfaces:
            // 0. sharedCamera.getSurfaceTexture()
            // 1. …
            // 2. cpuImageReader.getSurface()

            // Add ARCore surfaces and CPU image surface targets.
            for (Surface surface : surfaceList) {
                this.previewCaptureRequestBuilder.addTarget(surface);
            }

            // Wrap our callback in a shared camera callback.
            CameraCaptureSession.StateCallback wrappedCallback =
                    this.sharedCamera.createARSessionStateCallback(this.cameraCaptureCallback, this.backgroundHandler);

            // Create camera capture session for camera preview using ARCore wrapped callback.
            this.cameraDevice.createCaptureSession(surfaceList, wrappedCallback, this.backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "CameraAccessException", e);
        }
    }

    // Start background handler thread, used to run callbacks without blocking UI thread.
    private void startBackgroundThread() {
        this.backgroundThread = new HandlerThread("sharedCameraBackground");
        this.backgroundThread.start();
        this.backgroundHandler = new Handler(this.backgroundThread.getLooper());
    }

    // Stop background handler thread.
    private void stopBackgroundThread() {
        if (this.backgroundThread != null) {
            this.backgroundThread.quitSafely();
            try {
                this.backgroundThread.join();
                this.backgroundThread = null;
                this.backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while trying to join background handler thread", e);
            }
        }
    }

    // Perform various checks, then open camera device and create CPU image reader.
    private void openCamera() {
        // Don't open camera if already opened.
        if (this.cameraDevice != null) {
            return;
        }

        // Verify CAMERA_PERMISSION has been granted.
        if (!CameraPermissionHelper.hasCameraPermission(this.activity)) {
            CameraPermissionHelper.requestCameraPermission(this.activity);
            return;
        }

        // Make sure that ARCore is installed, up to date, and supported on this device.
        if (!isARCoreSupportedAndUpToDate()) {
            return;
        }

        if (this.sharedSession == null) {
            try {
                // Create ARCore session that supports camera sharing.
                this.sharedSession = new Session(this.context, EnumSet.of(Session.Feature.SHARED_CAMERA));
            } catch (UnavailableException e) {
                Log.e(TAG, "Failed to create ARCore session that supports camera sharing", e);
                return;
            }

            // Enable auto focus mode while ARCore is running.
            Config config = this.sharedSession.getConfig();
            config.setFocusMode(Config.FocusMode.AUTO);
            this.sharedSession.configure(config);
        }

        // Store the ARCore shared camera reference.
        this.sharedCamera = this.sharedSession.getSharedCamera();

        // Store the ID of the camera used by ARCore.
        this.cameraId = this.sharedSession.getCameraConfig().getCameraId();

        this.cpuImageReader = ImageReader.newInstance(this.captureSize.getWidth(), this.captureSize.getHeight(),
                ImageFormat.YUV_420_888, 1);
        this.cpuImageReader.setOnImageAvailableListener(this, backgroundHandler);

        // When ARCore is running, make sure it also updates our CPU image surface.
        this.sharedCamera.setAppSurfaces(this.cameraId, Collections.singletonList(this.cpuImageReader.getSurface()));

        try {

            // Wrap our callback in a shared camera callback.
            CameraDevice.StateCallback wrappedCallback =
                    this.sharedCamera.createARDeviceStateCallback(this.cameraDeviceCallback, this.backgroundHandler);

            // Store a reference to the camera system service.
            this.cameraManager = (CameraManager) this.context.getSystemService(Context.CAMERA_SERVICE);

            // Get the characteristics for the ARCore camera.
            CameraCharacteristics characteristics = this.cameraManager.getCameraCharacteristics(this.cameraId);

            // On Android P and later, get list of keys that are difficult to apply per-frame and can
            // result in unexpected delays when modified during the capture session lifetime.
            if (Build.VERSION.SDK_INT >= 28) {
                this.keysThatCanCauseCaptureDelaysWhenModified = characteristics.getAvailableSessionKeys();
                if (this.keysThatCanCauseCaptureDelaysWhenModified == null) {
                    // Initialize the list to an empty list if getAvailableSessionKeys() returns null.
                    this.keysThatCanCauseCaptureDelaysWhenModified = new ArrayList<>();
                }
            }

            // Prevent app crashes due to quick operations on camera open / close by waiting for the
            // capture session's onActive() callback to be triggered.
            this.captureSessionChangesPossible = false;

            // Open the camera device using the ARCore wrapped callback.
            this.cameraManager.openCamera(this.cameraId, wrappedCallback, this.backgroundHandler);
        } catch (CameraAccessException | IllegalArgumentException | SecurityException e) {
            Log.e(TAG, "Failed to open camera", e);
        }
    }

    private <T> boolean checkIfKeyCanCauseDelay(CaptureRequest.Key<T> key) {
        if (Build.VERSION.SDK_INT >= 28) {
            // On Android P and later, return true if key is difficult to apply per-frame.
            return this.keysThatCanCauseCaptureDelaysWhenModified.contains(key);
        } else {
            // On earlier Android versions, log a warning since there is no API to determine whether
            // the key is difficult to apply per-frame. Certain keys such as CONTROL_AE_TARGET_FPS_RANGE
            // are known to cause a noticeable delay on certain devices.
            // If avoiding unexpected capture delays when switching between non-AR and AR modes is
            // important, verify the runtime behavior on each pre-Android P device on which the app will
            // be distributed. Note that this device-specific runtime behavior may change when the
            // device's operating system is updated.
            Log.w(TAG,
                    "Changing "
                            + key
                            + " may cause a noticeable capture delay. Please verify actual runtime behavior on"
                            + " specific pre-Android P devices that this app will be distributed on.");
            // Allow the change since we're unable to determine whether it can cause unexpected delays.
            return false;
        }
    }

    // If possible, apply effect in non-AR mode, to help visually distinguish between from AR mode.
    private void setCameraEffects(CaptureRequest.Builder captureBuilder) {
        if (checkIfKeyCanCauseDelay(CaptureRequest.CONTROL_EFFECT_MODE)) {
            Log.w(TAG, "Not setting CONTROL_EFFECT_MODE since it can cause delays between transitions.");
        } else {
            Log.d(TAG, "Setting CONTROL_EFFECT_MODE to SEPIA in non-AR mode.");
            captureBuilder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_SEPIA);
        }
    }

    // Close the camera device.
    private void closeCamera() {
        if (this.captureSession != null) {
            this.captureSession.close();
            this.captureSession = null;
        }
        if (this.cameraDevice != null) {
            waitUntilCameraCaptureSesssionIsActive();
            this.cameraDevice.close();
        }
        if (this.cpuImageReader != null) {
            this.cpuImageReader.close();
            this.cpuImageReader = null;
        }
    }

    // CPU image reader callback.
    @Override
    public void onImageAvailable(ImageReader imageReader) {
        Image image = imageReader.acquireLatestImage();
        if (image == null) {
            Log.w(TAG, "onImageAvailable: Skipping null image.");
            return;
        }

        image.close();

    }

    // GL surface created callback. Will be called on the GL thread.
    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        this.surfaceCreated = true;

        // Set GL clear color to black.
        GLES20.glClearColor(0f, 0f, 0f, 1.0f);

        // Prepare the sample.rendering objects. This involves reading shaders, so may throw an IOException.
        try {
            // Create the camera preview image texture. Used in non-AR and AR mode.
            this.backgroundRenderer.createOnGlThread(this.context);

            openCamera();
        } catch (IOException e) {
            Log.e(TAG, "Failed to read an asset file", e);
        }
    }

    // GL surface changed callback. Will be called on the GL thread.
    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        this.viewportWidth = width;
        this.viewportHeight = height;
        this.viewportChanged = true;

    }

    // GL draw callback. Will be called each frame on the GL thread.
    @Override
    public void onDrawFrame(GL10 gl) {
        // Use the cGL clear color specified in onSurfaceCreated() to erase the GL surface.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (!this.shouldUpdateSurfaceTexture.get()) {
            // Not ready to draw.
            return;
        }

        if (this.viewportChanged) {
            this.sharedSession.setDisplayGeometry(this.displayRotation, this.viewportWidth, this.viewportHeight);
            this.viewportChanged = false;
        }

        try {
            if (this.arcoreActive) {
                onDrawFrameARCore();
            } else {
                onDrawFrameCamera2();
            }
        } catch (Throwable t) {
            // Avoid crashing the application due to unhandled exceptions.
            Log.e(TAG, "Exception on the OpenGL thread", t);
        }
    }

    // Draw frame when in non-AR mode. Called on the GL thread.
    private void onDrawFrameCamera2() {
        SurfaceTexture texture = this.sharedCamera.getSurfaceTexture();

        // Ensure the surface is attached to the GL context.
        if (!this.isGlAttached) {
            texture.attachToGLContext(this.backgroundRenderer.getTextureId());
            this.isGlAttached = true;
        }

        // Update the surface.
        texture.updateTexImage();

        // Account for any difference between camera sensor orientation and display orientation.
        int rotationDegrees = getCameraSensorToDisplayRotation(this.cameraId);

        // Determine size of the camera preview image.
        Size size = this.sharedSession.getCameraConfig().getTextureSize();

        // Determine aspect ratio of the output GL surface, accounting for the current display rotation
        // relative to the camera sensor orientation of the device.
        float displayAspectRatio =
                getCameraSensorRelativeViewportAspectRatio(this.cameraId);

        // Render camera preview image to the GL surface.
        this.backgroundRenderer.draw(size.getWidth(), size.getHeight(), displayAspectRatio, rotationDegrees);
    }

    // Draw frame when in AR mode. Called on the GL thread.
    private void onDrawFrameARCore() throws CameraNotAvailableException {
        if (!this.arcoreActive) {
            // ARCore not yet active, so nothing to draw yet.
            return;
        }

        // Perform ARCore per-frame update.
        Frame frame = this.sharedSession.update();
        Camera camera = frame.getCamera();

        // ARCore attached the surface to GL context using the texture ID we provided
        // in createCameraPreviewSession() via sharedSession.setCameraTextureName(...).
        this.isGlAttached = true;

        Pose pose = frame.getAndroidSensorPose();

        // If frame is ready, render camera preview image to the GL surface.
        this.backgroundRenderer.draw(frame);

    }

    private boolean isARCoreSupportedAndUpToDate() {
        // Make sure ARCore is installed and supported on this device.
        ArCoreApk.Availability availability = ArCoreApk.getInstance().checkAvailability(this.context);
        switch (availability) {
            case SUPPORTED_INSTALLED:
                break;
            case SUPPORTED_APK_TOO_OLD:
            case SUPPORTED_NOT_INSTALLED:
                try {
                    // Request ARCore installation or update if needed.
                    ArCoreApk.InstallStatus installStatus =
                            ArCoreApk.getInstance().requestInstall(this.activity, /*userRequestedInstall=*/ true);
                    switch (installStatus) {
                        case INSTALL_REQUESTED:
                            Log.e(TAG, "ARCore installation requested.");
                            return false;
                        case INSTALLED:
                            break;
                    }
                } catch (UnavailableException e) {
                    Log.e(TAG, "ARCore not installed", e);
                    this.activity.runOnUiThread(() ->
                            Toast.makeText(this.activity.getApplicationContext(), "ARCore not installed\n" + e, Toast.LENGTH_LONG).show());
                    return false;
                }
                break;
            case UNKNOWN_ERROR:
            case UNKNOWN_CHECKING:
            case UNKNOWN_TIMED_OUT:
            case UNSUPPORTED_DEVICE_NOT_CAPABLE:
                Log.e(TAG, "ARCore is not supported on this device, ArCoreApk.checkAvailability() returned " + availability);
                this.activity.runOnUiThread(() ->
                                Toast.makeText(this.activity.getApplicationContext(),
                                        "ARCore is not supported on this device, "
                                                + "ArCoreApk.checkAvailability() returned "
                                                + availability,
                                        Toast.LENGTH_LONG)
                                        .show());
                return false;
        }
        return true;
    }

    // ----------------------------------------------------------------------------------------------------
    // callback
    // ----------------------------------------------------------------------------------------------------

    // Camera device state callback.
    private final CameraDevice.StateCallback cameraDeviceCallback =
            new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice cameraDevice) {
                    Log.d(TAG, "Camera device ID " + cameraDevice.getId() + " opened.");
                    ArCamera.this.cameraDevice = cameraDevice;
                    createCameraPreviewSession();
                }

                @Override
                public void onClosed(@NonNull CameraDevice cameraDevice) {
                    Log.d(TAG, "Camera device ID " + cameraDevice.getId() + " closed.");
                    ArCamera.this.cameraDevice = null;
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice cameraDevice) {
                    Log.w(TAG, "Camera device ID " + cameraDevice.getId() + " disconnected.");
                    cameraDevice.close();
                    ArCamera.this.cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice cameraDevice, int error) {
                    Log.e(TAG, "Camera device ID " + cameraDevice.getId() + " error " + error);
                    cameraDevice.close();
                    ArCamera.this.cameraDevice = null;
                    // Fatal error. Quit application.
                }
            };

    // Repeating camera capture session state callback.
    CameraCaptureSession.StateCallback cameraCaptureCallback =
            new CameraCaptureSession.StateCallback() {

                // Called when the camera capture session is first configured after the app
                // is initialized, and again each time the activity is resumed.
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    Log.d(TAG, "Camera capture session configured.");
                    captureSession = session;
                    setRepeatingCaptureRequest();
                    // Note, resumeARCore() must be called in onActive(), not here.
                }

                @Override
                public void onSurfacePrepared(
                        @NonNull CameraCaptureSession session, @NonNull Surface surface) {
                    Log.d(TAG, "Camera capture surface prepared.");
                }

                @Override
                public void onReady(@NonNull CameraCaptureSession session) {
                    Log.d(TAG, "Camera capture session ready.");
                }

                @Override
                public void onActive(@NonNull CameraCaptureSession session) {
                    Log.d(TAG, "Camera capture session active.");
                    if (!arcoreActive) {
                        resumeARCore();
                    }
                    synchronized (ArCamera.this) {
                        captureSessionChangesPossible = true;
                        ArCamera.this.notify();
                    }
                }

                @Override
                public void onCaptureQueueEmpty(@NonNull CameraCaptureSession session) {
                    Log.w(TAG, "Camera capture queue empty.");
                }

                @Override
                public void onClosed(@NonNull CameraCaptureSession session) {
                    Log.d(TAG, "Camera capture session closed.");
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "Failed to configure camera capture session.");
                }
            };

    // Repeating camera capture session capture callback.
    private final CameraCaptureSession.CaptureCallback captureSessionCallback =
            new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureCompleted(
                        @NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request,
                        @NonNull TotalCaptureResult result) {
                    shouldUpdateSurfaceTexture.set(true);
                }

                @Override
                public void onCaptureBufferLost(
                        @NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request,
                        @NonNull Surface target,
                        long frameNumber) {
                    Log.e(TAG, "onCaptureBufferLost: " + frameNumber);
                }

                @Override
                public void onCaptureFailed(
                        @NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request,
                        @NonNull CaptureFailure failure) {
                    Log.e(TAG, "onCaptureFailed: " + failure.getFrameNumber() + " " + failure.getReason());
                }

                @Override
                public void onCaptureSequenceAborted(
                        @NonNull CameraCaptureSession session, int sequenceId) {
                    Log.e(TAG, "onCaptureSequenceAborted: " + sequenceId + " " + session);
                }
            };

    private SurfaceTexture.OnFrameAvailableListener onFrameAvailableListener = surfaceTexture -> {
    };


    // ----------------------------------------------------------------------------------------------------
    // display rotation
    // ----------------------------------------------------------------------------------------------------

    /**
     * Returns the aspect ratio of the GL surface viewport while accounting for the display rotation
     * relative to the device camera sensor orientation.
     */
    private float getCameraSensorRelativeViewportAspectRatio(String cameraId) {
        float aspectRatio;
        int cameraSensorToDisplayRotation = getCameraSensorToDisplayRotation(cameraId);
        switch (cameraSensorToDisplayRotation) {
            case 90:
            case 270:
                aspectRatio = (float) viewportHeight / (float) viewportWidth;
                break;
            case 0:
            case 180:
                aspectRatio = (float) viewportWidth / (float) viewportHeight;
                break;
            default:
                throw new RuntimeException("Unhandled rotation: " + cameraSensorToDisplayRotation);
        }
        return aspectRatio;
    }

    /**
     * Returns the rotation of the back-facing camera with respect to the display. The value is one of
     * 0, 90, 180, 270.
     */
    private int getCameraSensorToDisplayRotation(String cameraId) {
        CameraCharacteristics characteristics;
        try {
            characteristics = this.cameraManager.getCameraCharacteristics(cameraId);
        } catch (CameraAccessException e) {
            throw new RuntimeException("Unable to determine display orientation", e);
        }

        // Camera sensor orientation.
        int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

        // Current display orientation.
        int displayOrientation = toDegrees(this.displayRotation);

        // Make sure we return 0, 90, 180, or 270 degrees.
        return (sensorOrientation - displayOrientation + 360) % 360;
    }

    private int toDegrees(int rotation) {
        switch (rotation) {
            case Surface.ROTATION_0:
                return 0;
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                throw new RuntimeException("Unknown rotation " + rotation);
        }
    }


    // ----------------------------------------------------------------------------------------------------
    // other util
    // ----------------------------------------------------------------------------------------------------

    private synchronized void waitUntilCameraCaptureSesssionIsActive() {
        while (!this.captureSessionChangesPossible) {
            try {
                this.wait();
            } catch (InterruptedException e) {
                Log.e(TAG, "Unable to wait for a safe time to make changes to the capture session", e);
            }
        }
    }


}
