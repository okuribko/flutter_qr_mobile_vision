package com.github.rmtmckenzie.qrmobilevision;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.google.firebase.ml.vision.common.FirebaseVisionImage;
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_AUTO;
import static android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
import static android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO;
import static android.hardware.camera2.CameraMetadata.LENS_FACING_BACK;

/**
 * Implements QrCamera using Camera2 API
 */
@TargetApi(21)
@RequiresApi(21)
class QrCameraC2 implements QrCamera {

    private static final String TAG = "cgr.qrmv.QrCameraC2";
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private static final int SQUARE_SIZE = 200;

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private final int targetWidth;
    private final int targetHeight;
    private final Context context;
    private final SurfaceTexture texture;
    private Size size;
    private ImageReader reader;
    private CaptureRequest.Builder previewBuilder;
    private CameraCaptureSession previewSession;
    private Size[] jpegSizes = null;
    private QrDetector detector;
    private int sensorOrientation;
    private CameraDevice cameraDevice;
    private CameraCharacteristics cameraCharacteristics;

    QrCameraC2(int width, int height, SurfaceTexture texture, Context context, QrDetector detector) {
        this.targetWidth = width;
        this.targetHeight = height;
        this.context = context;
        this.texture = texture;
        this.detector = detector;
    }

    @Override
    public int getWidth() {
        return size.getWidth();
    }

    @Override
    public int getHeight() {
        return size.getHeight();
    }

    @Override
    public int getOrientation() {
        // ignore sensor orientation of devices with 'reverse landscape' orientation of sensor
        // as camera2 api seems to already rotate the output.
        return sensorOrientation == 270 ? 90 : sensorOrientation;
    }

    private int getFirebaseOrientation() {
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        int deviceRotation = windowManager.getDefaultDisplay().getRotation();
        int rotationCompensation = (ORIENTATIONS.get(deviceRotation) + sensorOrientation + 270) % 360;

        // Return the corresponding FirebaseVisionImageMetadata rotation value.
        int result;
        switch (rotationCompensation) {
            case 0:
                result = FirebaseVisionImageMetadata.ROTATION_0;
                break;
            case 90:
                result = FirebaseVisionImageMetadata.ROTATION_90;
                break;
            case 180:
                result = FirebaseVisionImageMetadata.ROTATION_180;
                break;
            case 270:
                result = FirebaseVisionImageMetadata.ROTATION_270;
                break;
            default:
                result = FirebaseVisionImageMetadata.ROTATION_0;
                Log.e(TAG, "Bad rotation value: " + rotationCompensation);
        }
        return result;
    }

    @Override
    public void start() throws QrReader.Exception {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        if (manager == null) {
            throw new RuntimeException("Unable to get camera manager.");
        }

        String cameraId = null;
        try {
            String[] cameraIdList = manager.getCameraIdList();
            for (String id : cameraIdList) {
                CameraCharacteristics cameraCharacteristics = manager.getCameraCharacteristics(id);
                Integer integer = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
                if (integer != null && integer == LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }
        } catch (CameraAccessException e) {
            Log.w(TAG, "Error getting back camera.", e);
            throw new RuntimeException(e);
        }

        if (cameraId == null) {
            throw new QrReader.Exception(QrReader.Exception.Reason.noBackCamera);
        }

        try {
            cameraCharacteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Integer sensorOrientationInteger = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = sensorOrientationInteger == null ? 0 : sensorOrientationInteger;

            size = getAppropriateSize(map.getOutputSizes(SurfaceTexture.class));
            jpegSizes = map.getOutputSizes(ImageFormat.JPEG);

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice device) {
                    cameraDevice = device;
                    startCamera();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice device) {
                }

                @Override
                public void onError(@NonNull CameraDevice device, int error) {
                    Log.w(TAG, "Error opening camera: " + error);
                }
            }, null);
        } catch (CameraAccessException e) {
            Log.w(TAG, "Error getting camera configuration.", e);
        }
    }

    private Integer afMode(CameraCharacteristics cameraCharacteristics) {

        int[] afModes = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);

        if (afModes == null) {
            return null;
        }

        HashSet<Integer> modes = new HashSet<>(afModes.length * 2);
        for (int afMode : afModes) {
            modes.add(afMode);
        }

        if (modes.contains(CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            return CONTROL_AF_MODE_CONTINUOUS_VIDEO;
        } else if (modes.contains(CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            return CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        } else if (modes.contains(CONTROL_AF_MODE_AUTO)) {
            return CONTROL_AF_MODE_AUTO;
        } else {
            return null;
        }
    }

    static class Frame implements QrDetector.Frame {
        final Image image;
        final int firebaseOrientation;

        Frame(Image image, int firebaseOrientation) {
            this.image = image;
            this.firebaseOrientation = firebaseOrientation;
        }

        @Override
        public FirebaseVisionImage toImage() {
            return FirebaseVisionImage.fromMediaImage(image, firebaseOrientation);
        }

        @Override
        public FirebaseVisionImage toInvertedImage() {
            Bitmap src = imageToBitmap(image, firebaseOrientation * 90);
            int cx = src.getWidth() / 2 - SQUARE_SIZE / 2;
            int cy = src.getHeight() / 2 - SQUARE_SIZE / 2;

            Bitmap cropped = Bitmap.createBitmap(src, cx, cy, SQUARE_SIZE, SQUARE_SIZE);
            Bitmap inverted = invert(cropped);
            return FirebaseVisionImage.fromBitmap(inverted);
        }

        @Override
        public void close() {
            image.close();
        }

        public Bitmap imageToBitmap(Image image, float rotationDegrees) {

            assert (image.getFormat() == ImageFormat.NV21);

            // NV21 is a plane of 8 bit Y values followed by interleaved  Cb Cr
            ByteBuffer ib = ByteBuffer.allocate(image.getHeight() * image.getWidth() * 2);

            ByteBuffer y = image.getPlanes()[0].getBuffer();
            ByteBuffer cr = image.getPlanes()[1].getBuffer();
            ByteBuffer cb = image.getPlanes()[2].getBuffer();
            ib.put(y);
//            ib.put(cb);
//            ib.put(cr);

            YuvImage yuvImage = new YuvImage(ib.array(),
                ImageFormat.NV21, image.getWidth(), image.getHeight(), null);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0,
                image.getWidth(), image.getHeight()), 50, out);
            byte[] imageBytes = out.toByteArray();
            Bitmap bm = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
            Bitmap bitmap = bm;

            if (rotationDegrees != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotationDegrees);
                Bitmap scaledBitmap = Bitmap.createScaledBitmap(bm,
                    bm.getWidth(), bm.getHeight(), true);
                bitmap = Bitmap.createBitmap(scaledBitmap, 0, 0,
                    scaledBitmap.getWidth(), scaledBitmap.getHeight(), matrix, true);
            }
            return bitmap;
        }

        public Bitmap invert(Bitmap src)
        {
            int height = src.getHeight();
            int width = src.getWidth();

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint();

            ColorMatrix matrixGrayscale = new ColorMatrix();
            matrixGrayscale.setSaturation(0);

            ColorMatrix matrixInvert = new ColorMatrix();
            matrixInvert.set(new float[]
                {
                    -1.0f, 0.0f, 0.0f, 0.0f, 255.0f,
                    0.0f, -1.0f, 0.0f, 0.0f, 255.0f,
                    0.0f, 0.0f, -1.0f, 0.0f, 255.0f,
                    0.0f, 0.0f, 0.0f, 1.0f, 0.0f
                });
            matrixInvert.preConcat(matrixGrayscale);

            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(matrixInvert);
            paint.setColorFilter(filter);

            canvas.drawBitmap(src, 0, 0, paint);
            return bitmap;
        }

    }

    private void startCamera() {
        List<Surface> list = new ArrayList<>();

        Size jpegSize = getAppropriateSize(jpegSizes);

        final int width = jpegSize.getWidth(), height = jpegSize.getHeight();
        reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 5);

        list.add(reader.getSurface());

        ImageReader.OnImageAvailableListener imageAvailableListener = new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                try {
                    Image image = reader.acquireLatestImage();
                    if (image == null) return;
                    detector.detect(new Frame(image, getFirebaseOrientation()));
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        };

        reader.setOnImageAvailableListener(imageAvailableListener, null);

        texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
        list.add(new Surface(texture));
        try {
            previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(list.get(0));
            previewBuilder.addTarget(list.get(1));

            Integer afMode = afMode(cameraCharacteristics);

            previewBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            if (afMode != null) {
                previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, afMode);
                Log.i(TAG, "Setting af mode to: " + afMode);
                if (afMode == CONTROL_AF_MODE_AUTO) {
                    previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                } else {
                    previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                }
            }
        } catch (java.lang.Exception e) {
            e.printStackTrace();
            return;
        }

        try {
            cameraDevice.createCaptureSession(list, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    previewSession = session;
                    startPreview();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    System.out.println("### Configuration Fail ###");
                }
            }, null);
        } catch (Throwable t) {
            t.printStackTrace();

        }
    }

    private void startPreview() {
        CameraCaptureSession.CaptureCallback listener = new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                super.onCaptureCompleted(session, request, result);
            }
        };

        if (cameraDevice == null) return;

        try {
            previewSession.setRepeatingRequest(previewBuilder.build(), listener, null);
        } catch (java.lang.Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void stop() {
        if (cameraDevice != null) {
            cameraDevice.close();
        }
        if (reader != null) {
            reader.close();
        }
    }

    private Size getAppropriateSize(Size[] sizes) {
        // assume sizes is never 0
        if (sizes.length == 1) {
            return sizes[0];
        }

        Size s = sizes[0];
        Size s1 = sizes[1];

        if (s1.getWidth() > s.getWidth() || s1.getHeight() > s.getHeight()) {
            // ascending
            if (sensorOrientation % 180 == 0) {
                for (Size size : sizes) {
                    s = size;
                    if (size.getHeight() > targetHeight && size.getWidth() > targetWidth) {
                        break;
                    }
                }
            } else {
                for (Size size : sizes) {
                    s = size;
                    if (size.getHeight() > targetWidth && size.getWidth() > targetHeight) {
                        break;
                    }
                }
            }
        } else {
            // descending
            if (sensorOrientation % 180 == 0) {
                for (Size size : sizes) {
                    if (size.getHeight() < targetHeight || size.getWidth() < targetWidth) {
                        break;
                    }
                    s = size;
                }
            } else {
                for (Size size : sizes) {
                    if (size.getHeight() < targetWidth || size.getWidth() < targetHeight) {
                        break;
                    }
                    s = size;
                }
            }
        }
        return s;
    }
}
