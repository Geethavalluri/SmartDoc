package com.smartdoc.ocr;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

import org.opencv.core.Mat;

public interface OcrService {
    String extractText(Path imagePath);
    String extractText(BufferedImage image);
    String extractText(Mat imageMat);
    String name();
}
