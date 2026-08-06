package com.smartdoc.vision;

import org.opencv.core.*;
import org.opencv.features2d.ORB;
import org.opencv.imgcodecs.Imgcodecs;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * ORB Feature Extractor using OpenCV
 * Extracts ORB features from images for similarity matching
 */
public class ORBFeatureExtractor {
    private final ORB orb;

    public ORBFeatureExtractor() {
        ORB created = null;
        try {
            // Primary: OpenPnP helper loads natives by extracting to a temp dir
            nu.pattern.OpenCV.loadLocally();
        } catch (Throwable t1) {
            // Fallback: load via system library path (e.g., -Djava.library.path)
            try {
                System.loadLibrary(org.opencv.core.Core.NATIVE_LIBRARY_NAME);
            } catch (Throwable t2) {
                java.util.logging.Logger.getLogger(ORBFeatureExtractor.class.getName())
                    .warning("Failed to load OpenCV via OpenPnP and System.loadLibrary: " + t1.getMessage() + "; " + t2.getMessage());
            }
        }

        try {
            // Initialize ORB detector with optimized parameters for document images
            created = ORB.create();
            created.setMaxFeatures(500); // Good balance for document images
            created.setScaleFactor(1.2f);
            created.setNLevels(8);
            created.setEdgeThreshold(31);
            created.setFirstLevel(0);
            created.setWTA_K(2);
            created.setScoreType(ORB.HARRIS_SCORE);
            created.setPatchSize(31);
            created.setFastThreshold(20);
        } catch (Throwable t) {
            java.util.logging.Logger.getLogger(ORBFeatureExtractor.class.getName())
                .warning("OpenCV ORB initialization failed: " + t.getMessage());
        }
        this.orb = created;
    }

    /**
     * Extract ORB features from an image file
     * @param imagePath Path to the image file
     * @return FeatureResult containing keypoints and descriptors
     * @throws Exception if feature extraction fails
     */
    public FeatureResult extractFeatures(Path imagePath) throws Exception {
        // Read image in grayscale
        Mat image = Imgcodecs.imread(imagePath.toString(), Imgcodecs.IMREAD_GRAYSCALE);

        if (image.empty()) {
            throw new Exception("Failed to load image: " + imagePath);
        }

        return extractFeatures(image);
    }

    /**
     * Extract ORB features from a BufferedImage
     * @param bufferedImage Input image
     * @return FeatureResult containing keypoints and descriptors
     * @throws Exception if feature extraction fails
     */
    public FeatureResult extractFeatures(BufferedImage bufferedImage) throws Exception {
        // Convert BufferedImage to OpenCV Mat
        Mat image = bufferedImageToMat(bufferedImage);
        return extractFeatures(image);
    }

    /**
     * Extract ORB features from OpenCV Mat
     * @param image Input image as OpenCV Mat (grayscale)
     * @return FeatureResult containing keypoints and descriptors
     */
    public FeatureResult extractFeatures(Mat image) {
        MatOfKeyPoint keypoints = new MatOfKeyPoint();
        Mat descriptors = new Mat();

        if (orb != null) {
            // Detect and compute ORB features
            orb.detectAndCompute(image, new Mat(), keypoints, descriptors);
        }

        return new FeatureResult(keypoints, descriptors);
    }

    /**
     * Convert BufferedImage to OpenCV Mat (grayscale)
     */
    private Mat bufferedImageToMat(BufferedImage bufferedImage) {
        // Convert to grayscale if needed
        BufferedImage grayImage;
        if (bufferedImage.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            grayImage = bufferedImage;
        } else {
            grayImage = new BufferedImage(
                bufferedImage.getWidth(),
                bufferedImage.getHeight(),
                BufferedImage.TYPE_BYTE_GRAY
            );
            grayImage.getGraphics().drawImage(bufferedImage, 0, 0, null);
        }

        // Convert to Mat
        Mat mat = new Mat(grayImage.getHeight(), grayImage.getWidth(), CvType.CV_8UC1);
        byte[] data = ((java.awt.image.DataBufferByte) grayImage.getRaster().getDataBuffer()).getData();
        mat.put(0, 0, data);

        return mat;
    }

    /**
     * Result class containing ORB features
     */
    public static class FeatureResult {
        public final MatOfKeyPoint keypoints;
        public final Mat descriptors;

        public FeatureResult(MatOfKeyPoint keypoints, Mat descriptors) {
            this.keypoints = keypoints;
            this.descriptors = descriptors;
        }

        public int getKeypointsCount() {
            return keypoints.rows();
        }

        public boolean hasFeatures() {
            return getKeypointsCount() > 0 && descriptors.rows() > 0;
        }
    }
}
