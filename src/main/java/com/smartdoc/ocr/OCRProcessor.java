package com.smartdoc.ocr;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

import javax.imageio.ImageIO;

import org.opencv.core.Mat;
import org.opencv.core.Size;

import net.sourceforge.tess4j.Tesseract;

/**
 * OCR Processor using Tesseract OCR
 * Extracts text from images and scanned PDFs
 */
public class OCRProcessor {
    private static final Logger logger = Logger.getLogger(OCRProcessor.class.getName());
    private final Tesseract tesseract;
    private volatile boolean tesseractAvailable;

    public OCRProcessor() {
        configureNativeLibraryPath();

        // Attempt to initialize Tesseract if tessdata can be found
        String tessDataPath = findTessDataPath();
        Tesseract tess = null;
        boolean available = false;
        try {
            if (tessDataPath != null) {
                tess = new Tesseract();
                tess.setDatapath(tessDataPath);
                tess.setLanguage("eng");
                // Page segmentation mode: 3 = Fully automatic page segmentation
                tess.setPageSegMode(3);
                // Hint DPI to improve accuracy on scanned images
                tess.setTessVariable("user_defined_dpi", "300");
                available = true;
                logger.info("Tesseract initialized with datapath: " + tessDataPath);
            } else {
                logger.warning("Tesseract tessdata not found. OCR will be disabled.");
            }
        } catch (Throwable e) {
            logger.warning("Failed to initialize Tesseract: " + e.getMessage());
            tess = null;
            available = false;
        }

        this.tesseract = tess;
        this.tesseractAvailable = available;
    }

    /**
     * Extract text from image file
     * @param imagePath Path to image file
     * @return Extracted text, or empty string if OCR fails or is unavailable
     */
    public String extractText(Path imagePath) {
        if (!tesseractAvailable || tesseract == null) {
            logger.info("OCR unavailable, skipping text extraction from: " + imagePath);
            return "";
        }

        try {
            logger.info("Extracting text from: " + imagePath);
            BufferedImage original = ImageIO.read(new File(imagePath.toString()));
            if (original == null) {
                logger.warning("Unsupported or unreadable image for OCR: " + imagePath);
                return "";
            }
            return extractBestText(original);
        } catch (Throwable e) {
            logger.warning("OCR failed for " + imagePath + ": " + e.getMessage());
            // Do not disable OCR globally; fail fast for this file only
            return ""; // Return empty string instead of crashing
        }
    }

    /**
     * Extract text from BufferedImage
     * @param image BufferedImage to process
     * @return Extracted text, or empty string if OCR fails or is unavailable
     */
    public String extractText(BufferedImage image) {
        if (!tesseractAvailable || tesseract == null) {
            logger.info("OCR unavailable, skipping text extraction from BufferedImage");
            return "";
        }

        try {
            logger.info("Extracting text from BufferedImage");
            if (image == null) {
                logger.warning("BufferedImage is null; skipping OCR");
                return "";
            }
            return extractBestText(image);
        } catch (Throwable e) {
            logger.warning("OCR failed for BufferedImage: " + e.getMessage());
            // Do not disable OCR globally; fail fast for this image only
            return ""; // Return empty string instead of crashing
        }
    }

    /**
     * Extract text from OpenCV Mat
     * @param imageMat OpenCV Mat containing image
     * @return Extracted text, or empty string if OCR fails or is unavailable
     */
    public String extractText(Mat imageMat) {
        if (!tesseractAvailable || tesseract == null) {
            logger.info("OCR unavailable, skipping text extraction from OpenCV Mat");
            return "";
        }

        try {
            logger.info("Extracting text from OpenCV Mat");

            // Preprocess Mat and convert to BufferedImage
            BufferedImage bufferedImage = matToBufferedImage(preprocessMat(imageMat));
            return extractText(bufferedImage);
        } catch (Throwable e) {
            logger.warning("OCR failed for OpenCV Mat: " + e.getMessage());
            // Do not disable OCR globally; fail fast for this Mat only
            return ""; // Return empty string instead of crashing
        }
    }

    /**
     * Check if Tesseract is properly configured
     */
    public boolean isConfigured() {
        if (!tesseractAvailable || tesseract == null) {
            return false;
        }
        try {
            // Try a simple OCR operation to test configuration
            BufferedImage testImage = new BufferedImage(100, 100, BufferedImage.TYPE_BYTE_GRAY);
            tesseract.doOCR(testImage);
            return true;
        } catch (Throwable e) {
            logger.warning("Tesseract configuration test failed: " + e.getMessage());
            return false;
        }
    }

    private void configureNativeLibraryPath() {
        String homebrewLib = "/opt/homebrew/lib";
        if (!Paths.get(homebrewLib).toFile().exists()) {
            return;
        }

        try {
            String jna = System.getProperty("jna.library.path");
            if (jna == null || jna.isBlank()) {
                System.setProperty("jna.library.path", homebrewLib);
            } else if (!jna.contains(homebrewLib)) {
                System.setProperty("jna.library.path", jna + File.pathSeparator + homebrewLib);
            }

            String javaLib = System.getProperty("java.library.path");
            if (javaLib == null || javaLib.isBlank()) {
                System.setProperty("java.library.path", homebrewLib);
            } else if (!javaLib.contains(homebrewLib)) {
                System.setProperty("java.library.path", javaLib + File.pathSeparator + homebrewLib);
            }
        } catch (Throwable t) {
            logger.warning("Unable to set native library path: " + t.getMessage());
        }
    }

    /**
     * Get OCR confidence score for extracted text
     */
    public float getConfidence(String text) {
        // Tesseract doesn't provide direct confidence, so we use a heuristic
        if (text == null || text.trim().isEmpty()) {
            return 0.0f;
        }

        // Simple heuristic: longer text with more alphanumeric characters = higher confidence
        long alphaNumericChars = text.chars()
            .filter(c -> Character.isLetterOrDigit(c) || Character.isWhitespace(c))
            .count();

        float ratio = (float) alphaNumericChars / text.length();
        return Math.min(ratio * 100, 100.0f); // Cap at 100%
    }

    private synchronized String extractBestText(BufferedImage original) {
        if (original == null) {
            return "";
        }

        BufferedImage preprocessed = preprocessForOCR(original);
        BufferedImage softPreprocessed = preprocessForOCRSoft(original);

        List<OcrAttempt> attempts = new java.util.ArrayList<>();
        attempts.add(runOcrAttempt(original, 6));
        attempts.add(runOcrAttempt(preprocessed, 6));
        attempts.add(runOcrAttempt(preprocessed, 11));
        attempts.add(runOcrAttempt(softPreprocessed, 6));
        attempts.add(runOcrAttempt(softPreprocessed, 3));

        OcrAttempt best = attempts.stream()
            .filter(Objects::nonNull)
            .max(Comparator.comparingDouble(a -> a.score))
            .orElse(new OcrAttempt("", 0.0));

        return best.text;
    }

    private OcrAttempt runOcrAttempt(BufferedImage image, int pageSegMode) {
        try {
            tesseract.setPageSegMode(pageSegMode);
            String raw = tesseract.doOCR(image);
            String cleaned = cleanExtractedText(raw);
            double score = scoreOcrText(cleaned);
            return new OcrAttempt(cleaned, score);
        } catch (Throwable e) {
            logger.fine("OCR attempt failed (psm=" + pageSegMode + "): " + e.getMessage());
            return new OcrAttempt("", 0.0);
        } finally {
            try {
                tesseract.setPageSegMode(3);
            } catch (Throwable ignored) {
            }
        }
    }

    private double scoreOcrText(String text) {
        if (text == null || text.isBlank()) {
            return 0.0;
        }

        double confidence = getConfidence(text);
        double lengthBoost = Math.min(text.length() / 12.0, 25.0);
        double structuredBoost = 0.0;
        String lower = text.toLowerCase();
        if (lower.contains("invoice") || lower.contains("bill") || lower.contains("receipt")) structuredBoost += 12.0;
        if (lower.contains("account") || lower.contains("bank") || lower.contains("ifsc")) structuredBoost += 12.0;
        if (lower.contains("passport") || lower.contains("nationality")) structuredBoost += 12.0;
        if (lower.contains("aadhaar") || lower.contains("uidai")) structuredBoost += 12.0;
        if (lower.contains("marks") || lower.contains("semester") || lower.contains("sgpa")) structuredBoost += 12.0;
        return confidence + lengthBoost + structuredBoost;
    }

    /**
     * Convert OpenCV Mat to BufferedImage
     */
    private BufferedImage matToBufferedImage(Mat mat) {
        // Convert to 3-channel BGR if needed
        Mat rgbMat = new Mat();
        if (mat.channels() == 1) {
            org.opencv.imgproc.Imgproc.cvtColor(mat, rgbMat, org.opencv.imgproc.Imgproc.COLOR_GRAY2BGR);
        } else {
            rgbMat = mat;
        }

        int width = rgbMat.cols();
        int height = rgbMat.rows();
        int channels = rgbMat.channels();

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        byte[] data = new byte[width * height * channels];
        rgbMat.get(0, 0, data);

        // Set the pixel data
        image.getRaster().setDataElements(0, 0, width, height, data);

        return image;
    }

    /**
     * Convert BufferedImage to OpenCV Mat (BGR)
     */
    private Mat bufferedImageToMat(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Mat mat = new Mat(height, width, org.opencv.core.CvType.CV_8UC3);
        byte[] data = (byte[]) image.getRaster().getDataElements(0, 0, width, height, null);
        mat.put(0, 0, data);
        return mat;
    }

    /**
     * Preprocess an OpenCV Mat for OCR: grayscale, denoise, contrast enhancement,
     * and adaptive thresholding.
     */
    private Mat preprocessMat(Mat src) {
        Mat work = new Mat();
        // Ensure grayscale
        if (src.channels() > 1) {
            org.opencv.imgproc.Imgproc.cvtColor(src, work, org.opencv.imgproc.Imgproc.COLOR_BGR2GRAY);
        } else {
            work = src.clone();
        }
        // Resize up for small images
        if (work.cols() < 800) {
            double scale = 800.0 / work.cols();
            org.opencv.imgproc.Imgproc.resize(work, work, new Size(0, 0), scale, scale, org.opencv.imgproc.Imgproc.INTER_CUBIC);
        }
        // Denoise
        org.opencv.photo.Photo.fastNlMeansDenoising(work, work, 15, 7, 21);
        // Contrast enhancement
        org.opencv.imgproc.CLAHE clahe = org.opencv.imgproc.Imgproc.createCLAHE(2.0, new Size(8, 8));
        clahe.apply(work, work);
        // Adaptive threshold
        org.opencv.imgproc.Imgproc.adaptiveThreshold(work, work, 255,
                org.opencv.imgproc.Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                org.opencv.imgproc.Imgproc.THRESH_BINARY, 31, 10);
        return work;
    }

    /**
     * Preprocess a BufferedImage for OCR using OpenCV pipeline.
     */
    private BufferedImage preprocessForOCR(BufferedImage image) {
        Mat mat = bufferedImageToMat(image);
        Mat processed = preprocessMat(mat);
        return matToBufferedImage(processed);
    }

    private BufferedImage preprocessForOCRSoft(BufferedImage image) {
        Mat mat = bufferedImageToMat(image);
        Mat work = new Mat();
        if (mat.channels() > 1) {
            org.opencv.imgproc.Imgproc.cvtColor(mat, work, org.opencv.imgproc.Imgproc.COLOR_BGR2GRAY);
        } else {
            work = mat.clone();
        }
        if (work.cols() < 1000) {
            double scale = 1000.0 / work.cols();
            org.opencv.imgproc.Imgproc.resize(work, work, new Size(0, 0), scale, scale, org.opencv.imgproc.Imgproc.INTER_CUBIC);
        }
        org.opencv.imgproc.CLAHE clahe = org.opencv.imgproc.Imgproc.createCLAHE(2.5, new Size(8, 8));
        clahe.apply(work, work);
        return matToBufferedImage(work);
    }

    private static final class OcrAttempt {
        private final String text;
        private final double score;

        private OcrAttempt(String text, double score) {
            this.text = text == null ? "" : text;
            this.score = score;
        }
    }

    /**
     * Find Tesseract data path
     */
    private String findTessDataPath() {
        // Common Tesseract installation paths
        String[] possiblePaths = {
            // Windows
            "C:\\Program Files\\Tesseract-OCR\\tessdata",
            "C:\\Program Files (x86)\\Tesseract-OCR\\tessdata",
            // Environment override
            System.getenv("TESSDATA_PREFIX"),
            // Linux
            "/usr/share/tesseract-ocr/5/tessdata",
            "/usr/share/tesseract-ocr/tessdata",
            "/usr/share/tessdata",
            // macOS Homebrew common locations
            "/opt/homebrew/share/tessdata",
            "/usr/local/share/tessdata"
        };

        for (String path : possiblePaths) {
            if (path != null && new File(path).exists()) {
                logger.info("Found Tesseract data path: " + path);
                return path;
            }
        }

        logger.warning("Tesseract data path not found, using default");
        return null; // Let Tesseract use its default
    }

    /**
     * Clean extracted text
     */
    public static String cleanExtractedText(String text) {
        if (text == null) return "";

        return text
            .trim()
            .replaceAll("\\s+", " ") // Replace multiple whitespace with single space
            .replaceAll("[^\\x20-\\x7E\\n\\r\\t]", "") // Remove non-printable characters
            .trim();
    }
}
