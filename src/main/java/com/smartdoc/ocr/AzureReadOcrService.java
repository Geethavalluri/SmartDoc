package com.smartdoc.ocr;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.logging.Logger;

import javax.imageio.ImageIO;

import org.opencv.core.Mat;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class AzureReadOcrService implements OcrService {
    private static final Logger logger = Logger.getLogger(AzureReadOcrService.class.getName());
    private final OkHttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private String endpoint;
    private String apiKey;

    public AzureReadOcrService() {
        this.endpoint = System.getenv("AZURE_VISION_ENDPOINT");
        this.apiKey = System.getenv("AZURE_VISION_KEY");
        this.client = new OkHttpClient.Builder().readTimeout(Duration.ofSeconds(30)).build();
        if (endpoint == null || apiKey == null) {
            logger.warning("Azure OCR not configured. Set AZURE_VISION_ENDPOINT and AZURE_VISION_KEY.");
        }
    }

    public void configure(String endpoint, String apiKey) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
    }

    @Override
    public String extractText(Path imagePath) {
        if (endpoint == null || apiKey == null) return "";
        try {
            byte[] bytes = Files.readAllBytes(imagePath);
            return analyzeBinary(bytes);
        } catch (Exception e) {
            logger.warning("Azure OCR failed: " + e.getMessage());
            return "";
        }
    }

    @Override
    public String extractText(BufferedImage image) {
        if (endpoint == null || apiKey == null) return "";
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            baos.flush();
            return analyzeBinary(baos.toByteArray());
        } catch (Exception e) {
            logger.warning("Azure OCR failed: " + e.getMessage());
            return "";
        }
    }

    @Override
    public String extractText(Mat imageMat) {
        try {
            // Ensure BGR 3-channel
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
            logger.warning("Azure OCR (Mat) failed: " + e.getMessage());
            return "";
        }
    }

    @Override
    public String name() { return "Azure Read"; }

    private String analyzeBinary(byte[] imageBytes) throws Exception {
        // Submit analyze
        String url = endpoint.endsWith("/") ? (endpoint + "vision/v3.2/read/analyze") : (endpoint + "/vision/v3.2/read/analyze");
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Ocp-Apim-Subscription-Key", apiKey)
                .addHeader("Content-Type", "application/octet-stream")
                .post(RequestBody.create(imageBytes, MediaType.parse("application/octet-stream")))
                .build();
        try (Response resp = client.newCall(request).execute()) {
            if (!resp.isSuccessful()) throw new RuntimeException("Analyze request failed: " + resp);
            String operationLocation = resp.header("Operation-Location");
            if (operationLocation == null) throw new RuntimeException("Missing Operation-Location header");
            // Poll result
            for (int i = 0; i < 10; i++) {
                Thread.sleep(500);
                Request poll = new Request.Builder()
                        .url(operationLocation)
                        .addHeader("Ocp-Apim-Subscription-Key", apiKey)
                        .get()
                        .build();
                try (Response pr = client.newCall(poll).execute()) {
                    String body = pr.body().string();
                    JsonNode root = mapper.readTree(body);
                    String status = root.path("status").asText("");
                    if ("succeeded".equalsIgnoreCase(status)) {
                        StringBuilder sb = new StringBuilder();
                        JsonNode lines = root.path("analyzeResult").path("readResults")
                                .isArray() ? root.path("analyzeResult").path("readResults").get(0).path("lines") : null;
                        if (lines != null && lines.isArray()) {
                            for (JsonNode line : lines) {
                                String text = line.path("text").asText("");
                                if (!text.isEmpty()) sb.append(text).append('\n');
                            }
                        }
                        return sb.toString();
                    }
                }
            }
        }
        return "";
    }
}
