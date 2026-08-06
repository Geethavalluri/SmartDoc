package com.smartdoc.ocr;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Path;
import java.util.logging.Logger;

import javax.imageio.ImageIO;

import org.opencv.core.Mat;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class PaddleOcrService implements OcrService {
    private static final Logger logger = Logger.getLogger(PaddleOcrService.class.getName());
    private final OkHttpClient client = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private String baseUrl;

    public PaddleOcrService() {
        this.baseUrl = System.getenv("PADDLE_OCR_URL");
        if (baseUrl == null) {
            logger.warning("Paddle OCR URL not set. Set PADDLE_OCR_URL.");
        }
    }

    public void configure(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public String extractText(Path imagePath) {
        if (baseUrl == null) return "";
        try {
            File file = imagePath.toFile();
            RequestBody fileBody = RequestBody.create(file, MediaType.parse("image/*"));
            RequestBody reqBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.getName(), fileBody)
                    .build();
            Request req = new Request.Builder().url(baseUrl + "/ocr").post(reqBody).build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful()) return "";
                JsonNode root = mapper.readTree(resp.body().string());
                // Expect { "text": "..." } or { "results": [ { "text": "..." } ] }
                if (root.has("text")) return root.path("text").asText("");
                StringBuilder sb = new StringBuilder();
                JsonNode results = root.path("results");
                if (results.isArray()) {
                    for (JsonNode item : results) {
                        String t = item.path("text").asText("");
                        if (!t.isEmpty()) sb.append(t).append('\n');
                    }
                }
                return sb.toString();
            }
        } catch (Exception e) {
            logger.warning("Paddle OCR failed: " + e.getMessage());
            return "";
        }
    }

    @Override
    public String extractText(BufferedImage image) {
        if (baseUrl == null) return "";
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            baos.flush();
            RequestBody fileBody = RequestBody.create(baos.toByteArray(), MediaType.parse("image/png"));
            RequestBody reqBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", "image.png", fileBody)
                    .build();
            Request req = new Request.Builder().url(baseUrl + "/ocr").post(reqBody).build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful()) return "";
                JsonNode root = mapper.readTree(resp.body().string());
                if (root.has("text")) return root.path("text").asText("");
                StringBuilder sb = new StringBuilder();
                JsonNode results = root.path("results");
                if (results.isArray()) {
                    for (JsonNode item : results) {
                        String t = item.path("text").asText("");
                        if (!t.isEmpty()) sb.append(t).append('\n');
                    }
                }
                return sb.toString();
            }
        } catch (Exception e) {
            logger.warning("Paddle OCR failed: " + e.getMessage());
            return "";
        }
    }

    @Override
    public String extractText(Mat imageMat) {
        try {
            Mat rgbMat = new Mat();
            if (imageMat.channels() == 1) {
                org.opencv.imgproc.Imgproc.cvtColor(imageMat, rgbMat, org.opencv.imgproc.Imgproc.COLOR_GRAY2BGR);
            } else {
                rgbMat = imageMat;
            }
            int width = rgbMat.cols();
            int height = rgbMat.rows();
            int channels = rgbMat.channels();
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            byte[] data = new byte[width * height * channels];
            rgbMat.get(0, 0, data);
            image.getRaster().setDataElements(0, 0, width, height, data);
            return extractText(image);
        } catch (Exception e) {
            logger.warning("Paddle OCR (Mat) failed: " + e.getMessage());
            return "";
        }
    }

    @Override
    public String name() { return "Paddle OCR"; }
}
