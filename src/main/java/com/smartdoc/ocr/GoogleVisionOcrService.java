package com.smartdoc.ocr;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
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

public class GoogleVisionOcrService implements OcrService {
    private static final Logger logger = Logger.getLogger(GoogleVisionOcrService.class.getName());
    private final OkHttpClient client = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private String apiKey;
    private String endpoint;

    public GoogleVisionOcrService() {
        this.apiKey = System.getenv("GOOGLE_VISION_KEY");
        // Default REST endpoint
        this.endpoint = System.getenv("GOOGLE_VISION_ENDPOINT");
        if (this.endpoint == null || this.endpoint.isBlank()) {
            this.endpoint = "https://vision.googleapis.com/v1/images:annotate";
        }
        if (apiKey == null) {
            logger.warning("Google Vision OCR not configured. Set GOOGLE_VISION_KEY.");
        }
    }

    public void configure(String apiKey, String endpoint) {
        this.apiKey = apiKey;
        if (endpoint != null && !endpoint.isBlank()) this.endpoint = endpoint;
    }

    @Override
    public String extractText(Path imagePath) {
        if (apiKey == null) return "";
        try {
            byte[] bytes = Files.readAllBytes(imagePath);
            return callAnnotate(bytes);
        } catch (Exception e) {
            logger.warning("Google Vision OCR failed: " + e.getMessage());
            return "";
        }
    }

    @Override
    public String extractText(BufferedImage image) {
        if (apiKey == null) return "";
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            baos.flush();
            return callAnnotate(baos.toByteArray());
        } catch (Exception e) {
            logger.warning("Google Vision OCR failed: " + e.getMessage());
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
            logger.warning("Google Vision OCR (Mat) failed: " + e.getMessage());
            return "";
        }
    }

    @Override
    public String name() { return "Google Vision"; }

    private String callAnnotate(byte[] imageBytes) throws Exception {
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        String body = "{" +
                "\"requests\":[{" +
                "\"image\":{\"content\":\"" + b64 + "\"}," +
                "\"features\":[{\"type\":\"TEXT_DETECTION\"}]" +
                "}]" +
                "}";
        Request req = new Request.Builder()
                .url(endpoint + "?key=" + apiKey)
                .post(RequestBody.create(body, MediaType.parse("application/json")))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new RuntimeException("Annotate request failed: " + resp);
            String respBody = resp.body().string();
            JsonNode root = mapper.readTree(respBody).path("responses");
            if (root.isArray() && root.size() > 0) {
                JsonNode r0 = root.get(0);
                String fullText = r0.path("fullTextAnnotation").path("text").asText("");
                if (!fullText.isEmpty()) return fullText;
                StringBuilder sb = new StringBuilder();
                JsonNode annotations = r0.path("textAnnotations");
                if (annotations.isArray()) {
                    for (JsonNode ann : annotations) {
                        String desc = ann.path("description").asText("");
                        if (!desc.isEmpty()) sb.append(desc).append('\n');
                    }
                }
                return sb.toString();
            }
        }
        return "";
    }
}
