package com.smartdoc.vision;

import org.opencv.core.Mat;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.features2d.ORB;
import org.opencv.imgcodecs.Imgcodecs;

/**
 * Minimal ORB feature extraction test harness.
 * Usage: run with an image path argument or it will try "test.jpg" in CWD.
 */
public class ORBQuickTest {
    public static void main(String[] args) {
        // Try OpenPnP helper; on failure, fall back to System.loadLibrary
        try {
            nu.pattern.OpenCV.loadLocally();
            System.out.println("OpenCV loaded via OpenPnP helper.");
        } catch (Throwable t1) {
            System.err.println("OpenPnP load failed: " + t1.getMessage());
            try {
                System.loadLibrary(org.opencv.core.Core.NATIVE_LIBRARY_NAME);
                System.out.println("OpenCV loaded via System.loadLibrary.");
            } catch (Throwable t2) {
                System.err.println("OpenCV load failed via System.loadLibrary: " + t2.getMessage());
            }
        }

        String imagePath = args.length > 0 ? args[0] : "test.jpg";
        System.out.println("Loading image: " + imagePath);
        Mat image = Imgcodecs.imread(imagePath, Imgcodecs.IMREAD_GRAYSCALE);
        if (image.empty()) {
            System.out.println("Failed to load image: " + imagePath);
            return;
        }

        ORB orb = ORB.create();
        MatOfKeyPoint keypoints = new MatOfKeyPoint();
        Mat descriptors = new Mat();
        orb.detectAndCompute(image, new Mat(), keypoints, descriptors);

        int kpCount = keypoints.rows();
        System.out.println("Keypoints: " + kpCount);
        System.out.println("Descriptors: " + descriptors.rows() + "x" + descriptors.cols());
    }
}
