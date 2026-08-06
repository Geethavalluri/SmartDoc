package com.smartdoc.nlp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdoc.model.ExtractedField;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * LLM Processor using Ollama for document analysis
 * Analyzes OCR text to extract document type, fields, tags, and summary
 */
public class LLMProcessor {
    private static final Logger logger = Logger.getLogger(LLMProcessor.class.getName());
    private static final String OLLAMA_BASE_URL = "http://localhost:11434";
    private static final String MODEL_NAME = "llama2:7b"; // Can be changed to phi or other models

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public LLMProcessor() {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Analyze document text and extract metadata.
     * Document type is auto-detected from the OCR text so that a type-specific
     * prompt is sent to the LLM, improving extraction accuracy for structured
     * documents such as Aadhaar cards, PAN cards, mark memos, and invoices.
     *
     * @param ocrText The OCR extracted text
     * @return DocumentAnalysisResult containing analysis
     */
    public DocumentAnalysisResult analyzeDocument(String ocrText) throws Exception {
        if (ocrText == null || ocrText.trim().isEmpty()) {
            return createEmptyAnalysis();
        }

        String detectedType = detectDocumentType(ocrText);
        String prompt = buildAnalysisPrompt(ocrText, detectedType);
        String response = callOllama(prompt);

        DocumentAnalysisResult result = parseAnalysisResponse(response);
        // If the LLM returned "unknown" but our heuristic found a specific type, keep it.
        if ("unknown".equalsIgnoreCase(result.documentType) && !"unknown".equals(detectedType)) {
            result.documentType = detectedType;
        }
        return result;
    }

    /**
     * Detect the document type from OCR text using keyword heuristics.
    * Returns one of: "aadhar", "pan_card", "passport", "passbook", "mark_memo", "invoice", or "unknown".
     */
    public String detectDocumentType(String ocrText) {
        if (ocrText == null || ocrText.isBlank()) return "unknown";
        String lower = ocrText.toLowerCase();

        int aadharScore = scoreContains(lower,
            "aadhaar", "aadhar", "uidai", "enrolment no", "unique identification authority",
            "government of india", "dob", "year of birth");
        // Aadhaar-specific structure: VID + 12-digit Aadhaar number is a strong signal.
        if (lower.contains("vid") && lower.matches("(?s).*\\b\\d{4}\\s?\\d{4}\\s?\\d{4}\\b.*")) {
            aadharScore += 2;
        }
        // Government of India + demographic fields are common on Aadhaar cards.
        if (lower.contains("government of india")
                && (lower.contains("male") || lower.contains("female") || lower.contains("dob"))) {
            aadharScore += 1;
        }

        int panScore = scoreContains(lower,
            "permanent account number", "income tax department", "father", "signature",
            "govt. of india", "govt of india");
        if (lower.contains(" pan ") && lower.contains("gov")) panScore += 2;

        int passportScore = scoreContains(lower,
            "passport", "republic of india", "passport no", "nationality", "place of birth",
            "date of issue", "date of expiry", "surname", "given name", "sex");

        int passbookScore = scoreContains(lower,
            "passbook", "account number", "ifsc", "branch", "opening balance",
            "closing balance", "withdrawal", "deposit", "transaction", "debit", "credit");
        if (lower.contains("bank") && lower.contains("account")) passbookScore += 2;

        int memoScore = scoreContains(lower,
            "mark sheet", "marksheet", "marks obtained", "roll number", "subject", "marks",
            "semester", "grade", "sgpa", "cgpa", "credits obtained", "examination",
            "result", "transcript", "memorandum of marks", "mark memo");
        if ((lower.contains("subject") && lower.contains("marks"))
                || (lower.contains("semester") && lower.contains("grade"))) {
            memoScore += 2;
        }

        int invoiceScore = scoreContains(lower,
            "invoice", "tax invoice", "bill to", "ship to", "gst no", "gst number",
            "receipt", "amount due", "total amount", "subtotal", "unit price", "quantity",
            "qty", "rate", "description", "tax", "cgst", "sgst", "igst", "invoice no",
            "invoice number", "invoice date");
        if (lower.contains("total") && lower.contains("amount")) invoiceScore += 2;
        if (lower.contains("bill") && (lower.contains("amount") || lower.contains("date"))) invoiceScore += 2;

        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("aadhar", aadharScore);
        scores.put("pan_card", panScore);
        scores.put("passport", passportScore);
        scores.put("passbook", passbookScore);
        scores.put("mark_memo", memoScore);
        scores.put("invoice", invoiceScore);

        String bestType = "unknown";
        int bestScore = 0;
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            if (entry.getValue() > bestScore) {
                bestScore = entry.getValue();
                bestType = entry.getKey();
            }
        }

        return bestScore >= 2 ? bestType : "unknown";
    }

    private int scoreContains(String text, String... keywords) {
        int score = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                score++;
            }
        }
        return score;
    }

    /**
     * Detect document type from the filename alone (no OCR needed).
     * Used when OCR fails or produces empty text, and during re-indexing
     * of previously stored documents with stale "unknown" type.
     */
    public String detectDocumentTypeFromFilename(String filename) {
        if (filename == null || filename.isBlank()) return "unknown";
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("memo") || lower.contains("marksheet") || lower.contains("marks")
                || lower.contains("mark") || lower.contains("grade") || lower.contains("result")
                || lower.contains("transcript") || lower.contains("sgpa")
                || lower.contains("semester") || lower.contains("btech") || lower.contains("b.tech")) {
            return "mark_memo";
        }
        if (lower.contains("aadhar") || lower.contains("aadhaar") || lower.contains("adhar")
                || lower.contains("uid")) {
            return "aadhar";
        }
        if (lower.contains("pan") && !lower.contains("panel") && !lower.contains("panorama")) {
            return "pan_card";
        }
        if (lower.contains("passport")) {
            return "passport";
        }
        if (lower.contains("passbook") || (lower.contains("bank") && lower.contains("account"))) {
            return "passbook";
        }
        if (lower.contains("invoice") || lower.contains("bill") || lower.contains("receipt")) {
            return "invoice";
        }
        // patterns like in-01.png, in-02.jpg, in-03, test-in-000 are invoice files
        if (lower.matches("in-\\d+.*") || lower.startsWith("test-in-") || lower.startsWith("inv-")) {
            return "invoice";
        }
        return "unknown";
    }

    /**
     * Check if Ollama service is available
     */
    public boolean isOllamaAvailable() {
        try {
            Request request = new Request.Builder()
                .url(OLLAMA_BASE_URL + "/api/tags")
                .get()
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            logger.warning("Ollama service not available: " + e.getMessage());
            return false;
        }
    }

    /**
     * Call Ollama API with prompt
     */
    private String callOllama(String prompt) throws IOException {
        // Prepare request body
        String requestBody = """
            {
                "model": "%s",
                "prompt": "%s",
                "stream": false
            }
            """.formatted(MODEL_NAME, prompt.replace("\"", "\\\"").replace("\n", "\\n"));

        RequestBody body = RequestBody.create(
            requestBody,
            MediaType.parse("application/json")
        );

        Request request = new Request.Builder()
            .url(OLLAMA_BASE_URL + "/api/generate")
            .post(body)
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Ollama API call failed: " + response.code());
            }

            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Empty response from Ollama");
            }

            String responseJson = responseBody.string();
            JsonNode jsonNode = objectMapper.readTree(responseJson);
            return jsonNode.get("response").asText();
        }
    }

    /**
     * Dispatch to a type-specific prompt builder so the LLM receives
     * structured instructions tailored to each document category.
     */
    private String buildAnalysisPrompt(String ocrText, String docType) {
        String truncated = ocrText.substring(0, Math.min(ocrText.length(), 2000));
        return switch (docType) {
            case "aadhar"    -> buildAadharPrompt(truncated);
            case "pan_card"  -> buildPanCardPrompt(truncated);
            case "mark_memo" -> buildMarkMemoPrompt(truncated);
            case "passport"  -> buildPassportPrompt(truncated);
            case "passbook"  -> buildPassbookPrompt(truncated);
            case "invoice"   -> buildInvoicePrompt(truncated);
            default          -> buildGenericPrompt(truncated);
        };
    }

    private String buildAadharPrompt(String text) {
        return """
            You are a document recognition system specialised in Indian government identity cards.
            Extract all key details from the following Aadhaar card text.

            Document Text:
            """ + text + """

            Respond ONLY with a valid JSON object in this exact format:
            {
                "document_type": "aadhar",
                "extracted_fields": [
                    {"field_name": "full_name",      "field_value": "", "confidence": 0.0},
                    {"field_name": "aadhaar_number",  "field_value": "", "confidence": 0.0},
                    {"field_name": "date_of_birth",   "field_value": "", "confidence": 0.0},
                    {"field_name": "gender",          "field_value": "", "confidence": 0.0},
                    {"field_name": "address",         "field_value": "", "confidence": 0.0}
                ],
                "semantic_tags": ["aadhaar", "identity", "government_id"],
                "short_summary": "Aadhaar card for <name>"
            }
            Fill in the values strictly from the document text. Do not infer or fabricate data.
            """;
    }

        private String buildPassportPrompt(String text) {
                return """
                        You are a document recognition system specialised in passports.
                        Extract key fields from this passport OCR text.

                        Document Text:
                        """ + text + """

                        Respond ONLY with valid JSON in this format:
                        {
                            "document_type": "passport",
                            "extracted_fields": [
                                {"field_name": "full_name", "field_value": "", "confidence": 0.0},
                                {"field_name": "passport_number", "field_value": "", "confidence": 0.0},
                                {"field_name": "date_of_birth", "field_value": "", "confidence": 0.0},
                                {"field_name": "date_of_expiry", "field_value": "", "confidence": 0.0},
                                {"field_name": "nationality", "field_value": "", "confidence": 0.0}
                            ],
                            "semantic_tags": ["passport", "identity", "travel_document"],
                            "short_summary": "Passport document"
                        }
                        """;
        }

        private String buildPassbookPrompt(String text) {
                return """
                        You are a document recognition system specialised in bank passbooks.
                        Extract key fields from this passbook OCR text.

                        Document Text:
                        """ + text + """

                        Respond ONLY with valid JSON in this format:
                        {
                            "document_type": "passbook",
                            "extracted_fields": [
                                {"field_name": "account_holder", "field_value": "", "confidence": 0.0},
                                {"field_name": "account_number", "field_value": "", "confidence": 0.0},
                                {"field_name": "ifsc", "field_value": "", "confidence": 0.0},
                                {"field_name": "bank_name", "field_value": "", "confidence": 0.0},
                                {"field_name": "branch", "field_value": "", "confidence": 0.0}
                            ],
                            "semantic_tags": ["passbook", "bank", "account"],
                            "short_summary": "Bank passbook document"
                        }
                        """;
        }

    private String buildPanCardPrompt(String text) {
        return """
            You are a document recognition system specialised in Indian government identity cards.
            Extract all key details from the following PAN card text.

            Document Text:
            """ + text + """

            Respond ONLY with a valid JSON object in this exact format:
            {
                "document_type": "pan_card",
                "extracted_fields": [
                    {"field_name": "full_name",    "field_value": "", "confidence": 0.0},
                    {"field_name": "pan_number",   "field_value": "", "confidence": 0.0},
                    {"field_name": "date_of_birth","field_value": "", "confidence": 0.0},
                    {"field_name": "father_name",  "field_value": "", "confidence": 0.0}
                ],
                "semantic_tags": ["pan", "identity", "income_tax", "government_id"],
                "short_summary": "PAN card for <name>"
            }
            Fill in the values strictly from the document text. Do not infer or fabricate data.
            """;
    }

    private String buildMarkMemoPrompt(String text) {
        return """
            You are a document recognition system specialised in academic records.
            Extract all key details from the following student mark memo / mark sheet text.

            Document Text:
            """ + text + """

            Respond ONLY with a valid JSON object in this exact format:
            {
                "document_type": "mark_memo",
                "extracted_fields": [
                    {"field_name": "student_name", "field_value": "", "confidence": 0.0},
                    {"field_name": "roll_number",  "field_value": "", "confidence": 0.0},
                    {"field_name": "subjects",     "field_value": "<subject1>:<marks1>, <subject2>:<marks2>", "confidence": 0.0},
                    {"field_name": "total_marks",  "field_value": "", "confidence": 0.0},
                    {"field_name": "percentage",   "field_value": "", "confidence": 0.0},
                    {"field_name": "grade",        "field_value": "", "confidence": 0.0}
                ],
                "semantic_tags": ["marks", "academic", "student", "grade"],
                "short_summary": "Mark memo for <student_name> – <percentage>%"
            }
            List each subject and its marks in the subjects field as "SubjectName:MarksObtained"
            pairs separated by commas. Do not extract information from unrelated documents.
            """;
    }

    private String buildInvoicePrompt(String text) {
        return """
            You are a document recognition system specialised in financial documents.
            Extract all key details from the following invoice or bill text.

            Document Text:
            """ + text + """

            Respond ONLY with a valid JSON object in this exact format:
            {
                "document_type": "invoice",
                "extracted_fields": [
                    {"field_name": "invoice_number", "field_value": "", "confidence": 0.0},
                    {"field_name": "invoice_date",   "field_value": "", "confidence": 0.0},
                    {"field_name": "vendor_name",    "field_value": "", "confidence": 0.0},
                    {"field_name": "customer_name",  "field_value": "", "confidence": 0.0},
                    {"field_name": "total_amount",   "field_value": "", "confidence": 0.0},
                    {"field_name": "gst_number",     "field_value": "", "confidence": 0.0}
                ],
                "semantic_tags": ["invoice", "billing", "financial"],
                "short_summary": "Invoice from <vendor> – total <amount>"
            }
            Fill in the values strictly from the document text. Do not infer or fabricate data.
            """;
    }

    private String buildGenericPrompt(String text) {
        return """
            Analyze the following document text and provide a structured analysis in JSON format.

            Document Text:
            """ + text + """

            Respond ONLY with a valid JSON object containing:
            {
                "document_type": "type of document (e.g., invoice, receipt, letter, contract, etc.)",
                "extracted_fields": [
                    {"field_name": "field name", "field_value": "extracted value", "confidence": 0.0}
                ],
                "semantic_tags": ["tag1", "tag2", "tag3"],
                "short_summary": "brief summary of the document content"
            }

            Focus on extracting:
            - Document type
            - Key information like dates, amounts, names, addresses
            - Important keywords as tags
            - Concise summary

            If you cannot determine certain information, use empty arrays/strings or null values.
            """;
    }

    /**
     * Parse the LLM response into structured data
     */
    private DocumentAnalysisResult parseAnalysisResponse(String response) {
        try {
            // Clean the response to extract JSON
            String jsonStr = extractJsonFromResponse(response);
            JsonNode jsonNode = objectMapper.readTree(jsonStr);

            DocumentAnalysisResult result = new DocumentAnalysisResult();

            // Extract document type
            result.documentType = jsonNode.get("document_type").asText("");

            // Extract fields
            result.extractedFields = new ArrayList<>();
            JsonNode fieldsNode = jsonNode.get("extracted_fields");
            if (fieldsNode != null && fieldsNode.isArray()) {
                for (JsonNode fieldNode : fieldsNode) {
                    ExtractedField field = new ExtractedField();
                    field.setFieldName(fieldNode.get("field_name").asText(""));
                    field.setFieldValue(fieldNode.get("field_value").asText(""));
                    field.setConfidence(fieldNode.get("confidence").asDouble(0.5));
                    result.extractedFields.add(field);
                }
            }

            // Extract tags
            result.semanticTags = new ArrayList<>();
            JsonNode tagsNode = jsonNode.get("semantic_tags");
            if (tagsNode != null && tagsNode.isArray()) {
                for (JsonNode tagNode : tagsNode) {
                    result.semanticTags.add(tagNode.asText());
                }
            }

            // Extract summary
            result.summary = jsonNode.get("short_summary").asText("");

            return result;

        } catch (Exception e) {
            logger.warning("Failed to parse LLM response: " + e.getMessage());
            logger.warning("Response was: " + response);
            return createEmptyAnalysis();
        }
    }

    /**
     * Extract JSON from LLM response (handles cases where LLM adds extra text)
     */
    private String extractJsonFromResponse(String response) {
        // Find the first '{' and last '}'
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');

        if (start != -1 && end != -1 && end > start) {
            return response.substring(start, end + 1);
        }

        // If no JSON found, wrap the entire response as summary
        return """
            {
                "document_type": "unknown",
                "extracted_fields": [],
                "semantic_tags": ["unclassified"],
                "short_summary": "%s"
            }
            """.formatted(response.replace("\"", "\\\"").substring(0, Math.min(response.length(), 200)));
    }

    /**
     * Create empty analysis for failed processing
     */
    private DocumentAnalysisResult createEmptyAnalysis() {
        DocumentAnalysisResult result = new DocumentAnalysisResult();
        result.documentType = "unknown";
        result.extractedFields = new ArrayList<>();
        result.semanticTags = new ArrayList<>();
        result.summary = "Document analysis failed";
        return result;
    }

    /**
     * Result class for document analysis
     */
    public static class DocumentAnalysisResult {
        public String documentType;
        public List<ExtractedField> extractedFields;
        public List<String> semanticTags;
        public String summary;

        public DocumentAnalysisResult() {
            this.extractedFields = new ArrayList<>();
            this.semanticTags = new ArrayList<>();
        }
    }
}
