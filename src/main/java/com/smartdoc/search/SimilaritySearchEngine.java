package com.smartdoc.search;

import com.smartdoc.db.DatabaseManager;
import com.smartdoc.db.DocumentDAO;
import com.smartdoc.db.FeatureDAO;
import com.smartdoc.db.OcrIndexDAO;
import com.smartdoc.model.Document;
import com.smartdoc.nlp.LLMProcessor;
import com.smartdoc.ocr.OCRProcessor;
import com.smartdoc.vision.ORBFeatureExtractor;
import org.opencv.core.*;
import org.opencv.features2d.BFMatcher;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.logging.Logger;

/**
 * Similarity Search Engine using ORB features and BFMatcher
 * Performs image similarity search using Hamming distance
 */
public class SimilaritySearchEngine {
    private static final Logger logger = Logger.getLogger(SimilaritySearchEngine.class.getName());
    private static final int MAX_RESULTS = 10;
    private static final double MIN_SIMILARITY_THRESHOLD = 0.12; // Min 12% of keypoints must match well
    private static final double MIN_RELATED_SIMILARITY_THRESHOLD = 0.005; // Allow low-confidence related items
    private static final Set<String> SUPPORTED_IMAGE_EXTENSIONS = Set.of(
        "jpg", "jpeg", "png", "webp", "bmp", "tif", "tiff"
    );
    private static final Set<String> MEMO_KEYWORDS = Set.of(
        "memo", "memorandum", "marks", "marksheet", "grade", "grades",
        "statement of marks", "mark memo", "pass certificate", "result",
        "semester", "examination", "sgpa", "cgpa", "transcript"
    );

    private final ORBFeatureExtractor featureExtractor;
    private final FeatureDAO featureDAO;
    private final DocumentDAO documentDAO;
    private final OcrIndexDAO ocrIndexDAO;
    private final OCRProcessor ocrProcessor;
    private final LLMProcessor llmProcessor;
    private final BFMatcher matcher;

    public SimilaritySearchEngine() throws Exception {
        boolean opencvAvailable = true;
        try {
            nu.pattern.OpenCV.loadLocally();
        } catch (Throwable t1) {
            try {
                System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
            } catch (Throwable t2) {
                opencvAvailable = false;
            }
        }
        if (!opencvAvailable) {
            Logger.getLogger(SimilaritySearchEngine.class.getName())
                .warning("OpenCV unavailable; similarity matching disabled on this platform.");
        }

        this.featureExtractor = new ORBFeatureExtractor();
        DatabaseManager dbManager = DatabaseManager.getInstance();
        this.featureDAO = new FeatureDAO(dbManager.getConnection());
        this.documentDAO = new DocumentDAO(dbManager.getConnection());
        this.ocrIndexDAO = new OcrIndexDAO(dbManager.getConnection());
        this.ocrProcessor = new OCRProcessor();

        // Initialize BFMatcher with Hamming distance for ORB descriptors if OpenCV is available
        this.matcher = opencvAvailable ? BFMatcher.create(Core.NORM_HAMMING, true) : null;
        this.llmProcessor = new LLMProcessor();
    }

    /**
     * Search for similar documents using a query image
     * @param queryImagePath Path to the query image
     * @return List of similar documents with similarity scores
     */
    public List<SearchResult> searchSimilarDocuments(Path queryImagePath) throws Exception {
        logger.info("Searching for similar documents to: " + queryImagePath);
        Path normalizedQueryPath = queryImagePath.toAbsolutePath().normalize();
        String queryFileHash = null;
        try {
            queryFileHash = computeFileSha256(normalizedQueryPath);  
        } catch (Exception e) {
            logger.fine("Could not compute query file hash: " + e.getMessage());
        }

        if (matcher == null) {
            logger.warning("Similarity search unavailable because OpenCV is not available; using aHash fallback.");
            return withSameTypeInjection(queryImagePath, fallbackSearchByAverageHash(queryImagePath));
        }

        // Extract features from query image
        ORBFeatureExtractor.FeatureResult queryFeatures = featureExtractor.extractFeatures(queryImagePath);
        logger.info("Query image features: " + queryFeatures.getKeypointsCount() + " keypoints");

        if (!queryFeatures.hasFeatures()) {
            logger.warning("No features found in query image; falling back to type+OCR search.");
            return withSameTypeInjection(queryImagePath, new ArrayList<>());
        }

        // Get all stored document features
        List<FeatureDAO.DocumentFeatures> storedFeatures = featureDAO.getAllFeatures();
        logger.info("Retrieved " + storedFeatures.size() + " feature sets from database");

        for (FeatureDAO.DocumentFeatures features : storedFeatures) {
            logger.info("Stored features for: " + features.filePath + " (" + features.keypointsCount + " keypoints)");
        }

        if (storedFeatures.isEmpty()) {
            logger.info("No indexed features found; using aHash fallback across documents.");
            return fallbackSearchByAverageHash(queryImagePath);
        }

        // Calculate similarity scores
        List<SearchResult> strongResults = new ArrayList<>();
        List<SearchResult> relatedResults = new ArrayList<>();
        for (FeatureDAO.DocumentFeatures docFeatures : storedFeatures) {
            Path storedPath = Path.of(docFeatures.filePath).toAbsolutePath().normalize();
            if (!isSupportedImagePath(storedPath)) {
                continue;
            }

            double similarity = calculateSimilarity(queryFeatures.descriptors, docFeatures.descriptors);

            // Exact same image should always rank as a perfect match.
            if (storedPath.equals(normalizedQueryPath)) {
                similarity = 1.0;
            } else if (queryFileHash != null) {
                try {
                    String storedFileHash = computeFileSha256(storedPath);
                    if (queryFileHash.equals(storedFileHash)) {
                        similarity = 1.0;
                    }
                } catch (Exception e) {
                    logger.fine("Could not compute stored file hash for " + storedPath + ": " + e.getMessage());
                }
            }

            logger.info("Similarity between query and %s: %.4f".formatted(
                storedPath.getFileName(), similarity));

            if (similarity >= MIN_RELATED_SIMILARITY_THRESHOLD) {
                Document document = documentDAO.getDocumentByPath(storedPath);
                if (document != null) {
                    SearchResult result = new SearchResult(document, similarity);
                    if (similarity >= MIN_SIMILARITY_THRESHOLD) {
                        strongResults.add(result);
                    } else {
                        relatedResults.add(result);
                    }
                }
            }
        }

        // Sort by similarity score (descending)
        strongResults.sort((a, b) -> Double.compare(b.similarity, a.similarity));
        relatedResults.sort((a, b) -> Double.compare(b.similarity, a.similarity));

        // Return strong matches first, then fill with related matches
        List<SearchResult> finalResults = new ArrayList<>();
        finalResults.addAll(strongResults);
        for (SearchResult related : relatedResults) {
            if (finalResults.size() >= MAX_RESULTS) {
                break;
            }
            finalResults.add(related);
        }

        // If ORB matching yields nothing useful, try aHash then type+OCR fallback
        if (finalResults.isEmpty()) {
            logger.info("No ORB matches above related threshold; using aHash fallback.");
            List<SearchResult> aHashResults = fallbackSearchByAverageHash(queryImagePath);
            if (!aHashResults.isEmpty()) return aHashResults;
            logger.info("aHash fallback also empty; using document-type and OCR text fallback.");
            return withSameTypeInjection(queryImagePath, fallbackSearchByDocumentTypeAndOcr(queryImagePath));
        }

        logger.info("Returning %d visual results (%d strong, %d related)".formatted(
            finalResults.size(), strongResults.size(), Math.max(0, finalResults.size() - strongResults.size())));
        List<SearchResult> topVisual = finalResults.subList(0, Math.min(finalResults.size(), MAX_RESULTS));
        List<SearchResult> reranked = rerankWithOcrSignal(queryImagePath, topVisual);
        return withSameTypeInjection(queryImagePath, reranked);
    }

    private List<SearchResult> rerankWithOcrSignal(Path queryImagePath, List<SearchResult> visualResults) {
        try {
            String text = OCRProcessor.cleanExtractedText(ocrProcessor.extractText(queryImagePath));
            boolean memoQuery = isMemoLikeText(text) || isMemoLikePath(queryImagePath);
            Set<Long> memoDocIds = memoQuery ? findMemoCandidateDocumentIds() : Set.of();

            if (text == null || text.isBlank()) {
                if (memoQuery) {
                    return getMemoOnlyResultsFromVisualOrIndex(visualResults, memoDocIds);
                }
                return visualResults;
            }

            String[] rawTokens = text.toLowerCase().split("\\W+");
            LinkedHashSet<String> uniq = new LinkedHashSet<>();
            for (String tok : rawTokens) {
                if (tok == null || tok.isBlank()) continue;
                if (tok.length() >= 3 || tok.matches("\\d{2,}")) {
                    uniq.add(tok);
                }
                if (uniq.size() >= 20) break;
            }
            if (uniq.size() < 4) {
                // Too little OCR signal, keep visual ranking unchanged.
                if (memoQuery) {
                    return getMemoOnlyResultsFromVisualOrIndex(visualResults, memoDocIds);
                }
                return visualResults;
            }

            String query = String.join(" OR ", uniq);
            List<Long> ocrIds = ocrIndexDAO.search(query, MAX_RESULTS * 3);
            if (ocrIds.isEmpty()) {
                if (memoQuery) {
                    return getMemoOnlyResultsFromVisualOrIndex(visualResults, memoDocIds);
                }
                return visualResults;
            }

            Map<Long, SearchResult> byDocId = new LinkedHashMap<>();
            for (SearchResult r : visualResults) {
                if (r.document != null && isSupportedImagePath(r.document.getFilePath())) {
                    if (memoQuery && !isMemoLikeDocument(r.document, memoDocIds)) {
                        continue;
                    }
                    byDocId.put(r.document.getId(), r);
                }
            }

            int size = ocrIds.size();
            for (int i = 0; i < size; i++) {
                long docId = ocrIds.get(i);
                // Stronger boost for top OCR matches, gradually decreasing.
                double boost = 0.35 * (1.0 - (double) i / (size + 1));

                SearchResult existing = byDocId.get(docId);
                if (existing != null) {
                    double blended = Math.min(1.0, existing.similarity + boost);
                    byDocId.put(docId, new SearchResult(existing.document, blended));
                } else {
                    Document doc = documentDAO.getDocumentById(docId);
                    if (doc != null && isSupportedImagePath(doc.getFilePath())) {
                        if (memoQuery && !isMemoLikeDocument(doc, memoDocIds)) {
                            continue;
                        }
                        // OCR-only candidate still gets a meaningful score.
                        byDocId.put(docId, new SearchResult(doc, Math.min(1.0, boost * 0.85)));
                    }
                }
            }

            List<SearchResult> reranked = new ArrayList<>(byDocId.values());
            reranked.sort((a, b) -> Double.compare(b.similarity, a.similarity));

            if (memoQuery) {
                reranked = reranked.stream()
                    .filter(r -> r.document != null && isMemoLikeDocument(r.document, memoDocIds))
                    .toList();
                if (reranked.isEmpty()) {
                    return getMemoOnlyResultsFromVisualOrIndex(visualResults, memoDocIds);
                }
            }

            logger.info("Returning %d OCR-assisted reranked results".formatted(
                Math.min(reranked.size(), MAX_RESULTS)));
            return reranked.subList(0, Math.min(reranked.size(), MAX_RESULTS));
        } catch (Throwable e) {
            logger.warning("OCR-assisted reranking failed, using visual ranking only: " + e.getMessage());
            return visualResults;
        }
    }

    /**
     * Fallback that combines document-type detection with OCR FTS5 search.
     * Used when ORB and aHash both fail to find candidates — common for text-heavy
     * structured documents (mark memos, Aadhaar cards, PAN cards, invoices) where
     * visual keypoints between different instances do not overlap.
     */
    private List<SearchResult> fallbackSearchByDocumentTypeAndOcr(Path queryImagePath) {
        List<SearchResult> candidates = new ArrayList<>();
        try {
            // Extract OCR text from the query image
            String text = "";
            try {
                text = OCRProcessor.cleanExtractedText(ocrProcessor.extractText(queryImagePath));
            } catch (Exception e) {
                logger.fine("OCR failed in type fallback: " + e.getMessage());
            }

            // Detect document type with keyword heuristic (no Ollama call needed)
            String detectedType = llmProcessor.detectDocumentType(text);
            logger.info("Type+OCR fallback: detected type = '" + detectedType + "'");

            Set<Long> seenIds = new LinkedHashSet<>();

            // Step 1: Inject all stored documents that share the same DB document_type
            if (!"unknown".equals(detectedType)) {
                List<Document> byType = documentDAO.searchByDocumentType(detectedType);
                for (Document doc : byType) {
                    if (doc != null && isSupportedImagePath(doc.getFilePath())) {
                        candidates.add(new SearchResult(doc, 0.40));
                        seenIds.add(doc.getId());
                    }
                }
                logger.info("Type+OCR fallback: %d documents from DB type '%s'".formatted(
                    candidates.size(), detectedType));
            }

            // Step 2: Run FTS5 OCR token search for additional candidates
            if (!text.isBlank()) {
                String[] rawTokens = text.toLowerCase().split("\\W+");
                LinkedHashSet<String> uniq = new LinkedHashSet<>();
                for (String tok : rawTokens) {
                    if (tok == null || tok.isBlank()) continue;
                    if (tok.length() >= 3 || tok.matches("\\d{2,}")) uniq.add(tok);
                    if (uniq.size() >= 20) break;
                }
                if (!uniq.isEmpty()) {
                    String ocrQuery = String.join(" OR ", uniq);
                    List<Long> ocrIds = ocrIndexDAO.search(ocrQuery, MAX_RESULTS * 3);
                    int ocrSize = ocrIds.size();
                    for (int i = 0; i < ocrSize; i++) {
                        long docId = ocrIds.get(i);
                        if (seenIds.contains(docId)) continue;
                        Document doc = documentDAO.getDocumentById(docId);
                        if (doc != null && isSupportedImagePath(doc.getFilePath())) {
                            double score = Math.max(0.05, 0.38 * (1.0 - (double) i / (ocrSize + 1)));
                            candidates.add(new SearchResult(doc, score));
                            seenIds.add(docId);
                        }
                    }
                    logger.info("Type+OCR fallback: %d total candidates after FTS5 pass".formatted(
                        candidates.size()));
                }
            }

            candidates.sort((a, b) -> Double.compare(b.similarity, a.similarity));
            return candidates.subList(0, Math.min(candidates.size(), MAX_RESULTS));
        } catch (Exception e) {
            logger.warning("Type+OCR fallback failed: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Fallback search using average-hash (aHash) when OpenCV/Natives are unavailable or DB has no features.
     * Computes a simple 64-bit hash and compares Hamming distance; higher similarity means closer hashes.
     */
    private List<SearchResult> fallbackSearchByAverageHash(Path queryImagePath) {
        List<SearchResult> results = new ArrayList<>();
        try {
            long queryHash = computeAverageHash(readImage(queryImagePath));

            // Fetch documents and compare only image types
            List<Document> documents = documentDAO.getAllDocuments();
            for (Document document : documents) {
                Path path = document.getFilePath();
                if (!isSupportedImagePath(path)) {
                    continue; // skip non-images
                }
                try {
                    BufferedImage img = readImage(path);
                    long docHash = computeAverageHash(img);
                    double sim = hashSimilarity(queryHash, docHash);
                    if (sim >= 0.50) { // reasonable threshold for aHash
                        results.add(new SearchResult(document, sim));
                    }
                } catch (Exception e) {
                    logger.fine("Skip non-readable image: " + path + " - " + e.getMessage());
                }
            }

            results.sort((a, b) -> Double.compare(b.similarity, a.similarity));
            return results.subList(0, Math.min(results.size(), MAX_RESULTS));
        } catch (Exception e) {
            logger.warning("Fallback aHash search failed: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private BufferedImage readImage(Path path) throws Exception {
        BufferedImage img = ImageIO.read(path.toFile());
        if (img == null) throw new Exception("Unsupported or unreadable image: " + path);
        return img;
    }

    /**
     * Compute a 64-bit average hash of an image.
     */
    private long computeAverageHash(BufferedImage src) {
        // Resize to 8x8 grayscale
        BufferedImage gray = new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(src, 0, 0, 8, 8, null);
        g.dispose();

        // Compute average brightness
        byte[] data = ((java.awt.image.DataBufferByte) gray.getRaster().getDataBuffer()).getData();
        long sum = 0;
        for (byte b : data) sum += (b & 0xFF);
        int avg = (int) (sum / data.length);

        // Build hash: 1 if pixel >= avg, else 0
        long hash = 0L;
        for (int i = 0; i < data.length; i++) {
            int val = data[i] & 0xFF;
            hash <<= 1;
            if (val >= avg) hash |= 1L;
        }
        return hash;
    }

    private double hashSimilarity(long h1, long h2) {
        int dist = Long.bitCount(h1 ^ h2);
        return 1.0 - (dist / 64.0);
    }

    private String computeFileSha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = Files.readAllBytes(file);
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private boolean isSupportedImagePath(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }

        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0 || lastDot == fileName.length() - 1) {
            return false;
        }

        String ext = fileName.substring(lastDot + 1);
        return SUPPORTED_IMAGE_EXTENSIONS.contains(ext);
    }

    private boolean isMemoLikeText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String normalized = text.toLowerCase(Locale.ROOT);
        int keywordHits = 0;
        for (String keyword : MEMO_KEYWORDS) {
            if (normalized.contains(keyword)) {
                keywordHits++;
            }
        }

        boolean strongPhrase = normalized.contains("statement of marks")
            || normalized.contains("memorandum of marks")
            || normalized.contains("mark memo")
            || normalized.contains("marksheet");
        return strongPhrase || keywordHits >= 2;
    }

    private boolean isMemoLikePath(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }

        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.contains("memo")
            || name.contains("mark")
            || name.contains("marks")
            || name.contains("marksheet")
            || name.contains("grade")
            || name.contains("result")
            || name.contains("semester");
    }

    private Set<Long> findMemoCandidateDocumentIds() {
        Set<Long> ids = new LinkedHashSet<>();
        // Method A: FTS5 OCR index keyword search
        try {
            String memoQuery = "memo OR memorandum OR marks OR marksheet OR grade OR sgpa OR cgpa OR semester OR examination OR result";
            ids.addAll(ocrIndexDAO.search(memoQuery, MAX_RESULTS * 20));
        } catch (Exception e) {
            logger.fine("Failed to search OCR index for memo candidates: " + e.getMessage());
        }
        // Method B: DB document_type column — surfaces mark memos even when OCR indexing is incomplete
        try {
            for (String typeHint : new String[]{"mark_memo", "marks", "memo", "marksheet", "grade"}) {
                for (Document d : documentDAO.searchByDocumentType(typeHint)) {
                    ids.add(d.getId());
                }
            }
        } catch (Exception e) {
            logger.fine("Failed to fetch memo candidates from document types: " + e.getMessage());
        }
        return ids;
    }

    private List<SearchResult> getMemoOnlyResultsFromVisualOrIndex(List<SearchResult> visualResults, Set<Long> memoDocIds) {
        List<SearchResult> filteredVisual = visualResults.stream()
            .filter(r -> r.document != null && isMemoLikeDocument(r.document, memoDocIds))
            .limit(MAX_RESULTS)
            .toList();
        if (!filteredVisual.isEmpty()) {
            return filteredVisual;
        }

        List<SearchResult> fallback = new ArrayList<>();
        int rank = 0;
        for (Long id : memoDocIds) {
            try {
                Document doc = documentDAO.getDocumentById(id);
                if (doc == null || !isSupportedImagePath(doc.getFilePath()) || !isMemoLikeDocument(doc, memoDocIds)) {
                    continue;
                }

                // Decreasing confidence for fallback candidates from OCR-only memo lookup.
                double score = Math.max(0.20, 0.55 - (rank * 0.03));
                fallback.add(new SearchResult(doc, score));
                rank++;
                if (fallback.size() >= MAX_RESULTS) {
                    break;
                }
            } catch (Exception e) {
                logger.fine("Skipping memo fallback docId=" + id + ": " + e.getMessage());
            }
        }

        return fallback;
    }

    private boolean isMemoLikeDocument(Document doc, Set<Long> memoDocIds) {
        if (doc == null) {
            return false;
        }

        if (memoDocIds.contains(doc.getId())) {
            return true;
        }

        String type = Optional.ofNullable(doc.getDocumentType()).orElse("").toLowerCase(Locale.ROOT);
        String summary = Optional.ofNullable(doc.getSummary()).orElse("").toLowerCase(Locale.ROOT);
        String fileName = Optional.ofNullable(doc.getFileName()).orElse("").toLowerCase(Locale.ROOT);
        String combined = type + " " + summary + " " + fileName;
        return isMemoLikeText(combined);
    }

    /**
     * Calculate similarity between two ORB descriptor sets using BFMatcher
     * @param queryDescriptors Query image descriptors
     * @param storedDescriptors Stored document descriptors
     * @return Similarity score (0.0 to 1.0, higher is more similar)
     */
    private double calculateSimilarity(Mat queryDescriptors, Mat storedDescriptors) {
        if (queryDescriptors.empty() || storedDescriptors.empty()) {
            return 0.0;
        }

        try {
            // Find matches using BFMatcher
            MatOfDMatch matches = new MatOfDMatch();
            matcher.match(queryDescriptors, storedDescriptors, matches);

            if (matches.empty()) {
                return 0.0;
            }

            DMatch[] matchArray = matches.toArray();

            // Count matches with Hamming distance < 60 (good ORB match threshold)
            int goodMatches = 0;
            double totalDistance = 0.0;
            for (DMatch match : matchArray) {
                if (match.distance < 60.0) {
                    goodMatches++;
                    totalDistance += match.distance;
                }
            }

            if (goodMatches == 0) {
                return 0.0;
            }

            // Primary metric: ratio of well-matched keypoints
            double matchRatio = (double) goodMatches / Math.min(queryDescriptors.rows(), storedDescriptors.rows());
            // Quality factor: how good the matches are (lower distance = better)
            double avgDistance = totalDistance / goodMatches;
            double quality = 1.0 - (avgDistance / 60.0); // 1.0 = perfect, 0.0 = at threshold

            return Math.min(matchRatio * quality, 1.0);

        } catch (Exception e) {
            logger.warning("Error calculating similarity: " + e.getMessage());
            return 0.0;
        }
    }

    /**
     * Detects the type of the query document (fast: filename first, then OCR) and injects
     * any same-type indexed documents that are missing from the current result list.
     * This is the primary mechanism that surfaces mark memos, Aadhaar cards, PAN cards,
     * and invoices when ORB visual matching fails due to different content per document.
     */
    private List<SearchResult> withSameTypeInjection(Path queryImagePath, List<SearchResult> current) {
        try {
            // Content-first detection: OCR text is primary, filename is fallback.
            // This avoids hard dependency on file names like "invoice_01.png".
            String detectedType = "unknown";
            String ocrText = "";
            try {
                ocrText = OCRProcessor.cleanExtractedText(ocrProcessor.extractText(queryImagePath));
                detectedType = llmProcessor.detectDocumentType(ocrText);
            } catch (Exception e) {
                logger.fine("OCR for type injection failed: " + e.getMessage());
            }

            // Filename fallback only when OCR cannot determine the type.
            if ("unknown".equals(detectedType) && queryImagePath != null && queryImagePath.getFileName() != null) {
                detectedType = llmProcessor.detectDocumentTypeFromFilename(
                    queryImagePath.getFileName().toString());
            }

            if ("unknown".equals(detectedType)) {
                return current;
            }
            logger.info("Type injection: detected query type = '" + detectedType + "'");

            // Collect IDs already in results to avoid duplicates
            Set<Long> existingIds = new LinkedHashSet<>();
            for (SearchResult r : current) {
                if (r.document != null) existingIds.add(r.document.getId());
            }
            Set<Long> typeCandidateIds = new LinkedHashSet<>();

            List<SearchResult> injected = new ArrayList<>(current);

            // Method A: inject by stored document_type column
            List<Document> byType = documentDAO.searchByDocumentType(detectedType);
            for (Document doc : byType) {
                if (doc == null || !isSupportedImagePath(doc.getFilePath())) continue;
                typeCandidateIds.add(doc.getId());
                if (existingIds.contains(doc.getId())) continue;
                injected.add(new SearchResult(doc, 0.40));
                existingIds.add(doc.getId());
            }

            // Method B: inject via FTS5 type-specific keyword search (finds docs
            // with 'unknown' type that were OCR-indexed but not classified)
            String typeKeywords = typeKeywordsFor(detectedType);
            if (!typeKeywords.isEmpty()) {
                List<Long> ocrIds = ocrIndexDAO.search(typeKeywords, MAX_RESULTS * 3);
                int sz = ocrIds.size();
                for (int i = 0; i < sz; i++) {
                    long docId = ocrIds.get(i);
                    if (existingIds.contains(docId)) continue;
                    Document doc = documentDAO.getDocumentById(docId);
                    if (doc != null && isSupportedImagePath(doc.getFilePath())) {
                        typeCandidateIds.add(doc.getId());
                        double score = Math.max(0.15, 0.35 * (1.0 - (double) i / (sz + 1)));
                        injected.add(new SearchResult(doc, score));
                        existingIds.add(docId);
                    }
                }
            }

            // Method C: filename-based search – most reliable when document_type has stale
            // "unknown" values and OCR index is incomplete.  Matches any document whose
            // file_name contains structural keywords like "memo", "marks", "result".
            List<String> fnKeywords = fileNameKeywordsFor(detectedType);
            if (!fnKeywords.isEmpty()) {
                int beforeC = injected.size();
                List<Document> byName = documentDAO.searchByFileNameKeywords(fnKeywords);
                for (Document doc : byName) {
                    if (doc == null || !isSupportedImagePath(doc.getFilePath())) continue;
                    typeCandidateIds.add(doc.getId());
                    if (existingIds.contains(doc.getId())) continue;
                    injected.add(new SearchResult(doc, 0.30));
                    existingIds.add(doc.getId());
                }
                logger.info("Type injection: Method C (filename) added %d candidate(s)".formatted(
                    injected.size() - beforeC));
            }

            // For structured documents, keep results strictly type-consistent.
            if (requiresStrictTypeFilter(detectedType)) {
                Path normalizedQueryPath = queryImagePath.toAbsolutePath().normalize();
                final String strictType = detectedType;
                List<SearchResult> filtered = injected.stream()
                    .filter(r -> {
                        if (r == null || r.document == null) return false;
                        Path p = r.document.getFilePath();
                        if (p != null && p.toAbsolutePath().normalize().equals(normalizedQueryPath)) {
                            return true; // always keep query image itself
                        }
                        return matchesDetectedType(r.document, strictType, typeCandidateIds);
                    })
                    .toList();
                if (!filtered.isEmpty()) {
                    injected = new ArrayList<>(filtered);
                }
            }

            if (injected.size() == current.size()) return current;

            injected.sort((a, b) -> Double.compare(b.similarity, a.similarity));
            logger.info("Type injection ('%s'): total %d results".formatted(detectedType, injected.size()));
            return injected.subList(0, Math.min(injected.size(), MAX_RESULTS));
        } catch (Exception e) {
            logger.warning("Type injection failed, using original results: " + e.getMessage());
            return current;
        }
    }

    /** FTS5 query keywords that reliably identify each structured document type. */
    private String typeKeywordsFor(String docType) {
        return switch (docType) {
            case "mark_memo" -> "sgpa OR cgpa OR marksheet OR semester OR grade OR examination";
            case "aadhar"    -> "aadhaar OR aadhar OR uidai";  // specific to Aadhaar only
            case "pan_card"  -> "income OR permanent";
            case "passport"  -> "passport OR nationality OR surname OR expiry OR issue OR birth";
            case "passbook"  -> "passbook OR account OR ifsc OR branch OR bank OR debit OR credit OR balance";
            case "invoice"   -> "invoice OR gst OR receipt OR bill OR amount OR subtotal OR quantity OR tax OR total";
            default          -> "";
        };
    }

    /**
     * Search result class
     */
    public static class SearchResult {
        public final Document document;
        public final double similarity;

        public SearchResult(Document document, double similarity) {
            this.document = document;
            this.similarity = similarity;
        }

        public String getFilePath() {
            return document.getFilePath().toString();
        }

        public String getDocumentType() {
            return document.getDocumentType();
        }

        public String getSummary() {
            return document.getSummary();
        }

        @Override
        public String toString() {
            return "SearchResult{path='%s', similarity=%.3f, type='%s'}".formatted(
                getFilePath(), similarity, getDocumentType());
        }
    }

    /**
     * File name substrings used by Method C of withSameTypeInjection.
     * Generic structural terms only – not institution-specific.
     */
    private List<String> fileNameKeywordsFor(String docType) {
        return switch (docType) {
            case "mark_memo" -> List.of("memo", "mark", "marks", "marksheet", "result",
                                        "grade", "transcript", "sgpa", "semester");
            case "aadhar"    -> List.of("aadhar", "aadhaar", "adhar", "uid");
            case "pan_card"  -> List.of("pan");
            case "passport", "passbook", "invoice" -> List.of();
            default          -> List.of();
        };
    }

    private boolean requiresStrictTypeFilter(String detectedType) {
        return switch (detectedType) {
            case "invoice", "mark_memo", "aadhar", "pan_card", "passport", "passbook" -> true;
            default -> false;
        };
    }

    private boolean matchesDetectedType(Document doc, String detectedType, Set<Long> typeCandidateIds) {
        if (doc == null) return false;
        if (typeCandidateIds.contains(doc.getId())) return true;

        String type = Optional.ofNullable(doc.getDocumentType()).orElse("").toLowerCase(Locale.ROOT);
        String summary = Optional.ofNullable(doc.getSummary()).orElse("").toLowerCase(Locale.ROOT);
        String name = Optional.ofNullable(doc.getFileName()).orElse("").toLowerCase(Locale.ROOT);

        return switch (detectedType) {
            case "invoice" -> type.contains("invoice") || summary.contains("invoice")
                || summary.contains("bill") || summary.contains("receipt") || summary.contains("gst");
            case "mark_memo" -> type.contains("mark_memo") || isMemoLikeText(summary + " " + name + " " + type);
            case "aadhar" -> type.contains("aadhar") || summary.contains("aadhaar")
                || summary.contains("uidai") || name.contains("aadhaar") || name.contains("aadhar");
            case "pan_card" -> type.contains("pan") || summary.contains("pan") || name.contains("pan");
            case "passport" -> type.contains("passport") || summary.contains("passport")
                || summary.contains("nationality") || summary.contains("expiry");
            case "passbook" -> type.contains("passbook") || summary.contains("passbook")
                || summary.contains("account") || summary.contains("bank") || summary.contains("balance");
            default -> false;
        };
    }

}

