package com.smartdoc.tools;

import com.smartdoc.db.DatabaseManager;
import com.smartdoc.db.DocumentDAO;
import com.smartdoc.model.Document;
import com.smartdoc.nlp.LLMProcessor;
import com.smartdoc.ocr.OCRProcessor;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

public class TypeReclassifier {
    public static void main(String[] args) throws Exception {
        DatabaseManager dbManager = DatabaseManager.getInstance();
        Connection conn = dbManager.getConnection();
        DocumentDAO documentDAO = new DocumentDAO(conn);
        OCRProcessor ocrProcessor = new OCRProcessor();
        LLMProcessor llmProcessor = new LLMProcessor();

        List<Document> unknownDocs = documentDAO.searchByDocumentType("unknown");
        int updated = 0;
        System.out.println("Found " + unknownDocs.size() + " documents with type 'unknown'.");
        for (Document doc : unknownDocs) {
            Path path = doc.getFilePath();
            String detectedType = "unknown";
            try {
                String ocrText = OCRProcessor.cleanExtractedText(ocrProcessor.extractText(path));
                detectedType = llmProcessor.detectDocumentType(ocrText);
                if ("unknown".equals(detectedType)) {
                    detectedType = llmProcessor.detectDocumentTypeFromFilename(path.getFileName().toString());
                }
            } catch (Exception e) {
                System.out.println("Failed OCR for " + path + ": " + e.getMessage());
            }
            if (!"unknown".equals(detectedType)) {
                documentDAO.updateTypeAndSummary(doc.getId(), detectedType, "Reclassified");
                System.out.printf("Reclassified: %s -> %s\n", path.getFileName(), detectedType);
                updated++;
            }
        }
        System.out.println("Reclassification complete. Updated " + updated + " documents.");
    }
}
