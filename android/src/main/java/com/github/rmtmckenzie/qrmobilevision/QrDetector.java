package com.github.rmtmckenzie.qrmobilevision;

import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.ml.vision.FirebaseVision;
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcode;
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetector;
import com.google.firebase.ml.vision.barcode.FirebaseVisionBarcodeDetectorOptions;
import com.google.firebase.ml.vision.common.FirebaseVisionImage;

import java.util.List;

/**
 * Allows QrCamera classes to send frames to a Detector
 */

class QrDetector implements OnSuccessListener<List<FirebaseVisionBarcode>>, OnFailureListener {
    private static final String TAG = "cgr.qrmv.QrDetector";
    private final QrReaderCallbacks communicator;
    private final FirebaseVisionBarcodeDetector detector;

    private final boolean supportInvertedBarcodes;
    private boolean invertImageFlag = false;

    public interface Frame {
        FirebaseVisionImage toImage();
        FirebaseVisionImage toInvertedImage();

        void close();
    }

    @GuardedBy("this")
    private Frame latestFrame;

    @GuardedBy("this")
    private Frame processingFrame;

    QrDetector(QrReaderCallbacks communicator, FirebaseVisionBarcodeDetectorOptions options, boolean supportInvertedBarcodes) {
        this.communicator = communicator;
        this.detector = FirebaseVision.getInstance().getVisionBarcodeDetector(options);
        this.supportInvertedBarcodes = supportInvertedBarcodes;
    }

    void detect(Frame frame) {
        if (latestFrame != null) latestFrame.close();
        latestFrame = frame;

        if (processingFrame == null) {
            processLatest();
        }
    }

    private synchronized void processLatest() {
        if (processingFrame != null) processingFrame.close();
        processingFrame = latestFrame;
        latestFrame = null;
        if (processingFrame != null) {
            processFrame(processingFrame);
        }
    }

    private void processFrame(Frame frame) {
        FirebaseVisionImage image;
        try {
            if (supportInvertedBarcodes) {
                if (!invertImageFlag) {
                    image = frame.toImage();
                } else {
                    image = frame.toInvertedImage();
                }
                invertImageFlag = !invertImageFlag;
            } else {
                image = frame.toImage();
            }
        } catch (IllegalStateException ex) {
            // ignore state exception from making frame to image
            // as the image may be closed already.
            return;
        }

        detector.detectInImage(image)
            .addOnSuccessListener(this)
            .addOnFailureListener(this);
    }

    @Override
    public void onSuccess(List<FirebaseVisionBarcode> firebaseVisionBarcodes) {
        for (FirebaseVisionBarcode barcode : firebaseVisionBarcodes) {
            communicator.qrRead(barcode.getRawValue());
        }
        processLatest();
    }

    @Override
    public void onFailure(@NonNull Exception e) {
        Log.w(TAG, "Barcode Reading Failure: ", e);
    }
}
