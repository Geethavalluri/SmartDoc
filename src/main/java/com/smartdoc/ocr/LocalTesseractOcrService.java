package com.smartdoc.ocr;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

import org.opencv.core.Mat;

public class LocalTesseractOcrService implements OcrService {
    private final OCRProcessor processor = new OCRProcessor();

    @Override
    public String extractText(Path imagePath) {
        return processor.extractText(imagePath);
    }

    @Override
    public String extractText(BufferedImage image) {
        return processor.extractText(image);
    }

    @Override
    public String extractText(Mat imageMat) {
        return processor.extractText(imageMat);
    }

    @Override
    public String name() { return "Local (Tesseract)"; }
}
