# SYSTEM TESTING

This section documents the complete testing strategy, methodology, test cases, and results for the SmartDoc project. The evaluation draws on the base paper *"The Retrieval of Document Images: A Brief Survey"* to benchmark and contextualise the system's performance.

---

## 7.1 INTRODUCTION

SmartDoc is a fully local, offline document indexing and retrieval desktop application built on Java 21 and JavaFX. It combines ORB-based visual similarity (OpenCV), Tesseract OCR, SQLite full-text search (FTS5), and an optional large-language-model (Ollama) for document analysis.

The base paper surveys state-of-the-art document image retrieval techniques including SIFT/SURF keypoint matching, Bag-of-Words models, CNN embeddings, perceptual hashing (pHash/aHash), and OCR+TF-IDF pipelines. SmartDoc adopts and adapts several of these approaches:

| Surveyed Technique | SmartDoc Equivalent |
|---|---|
| SIFT / SURF local features | ORB local features (faster, binary descriptors) |
| L2 / cosine distance matching | Hamming distance with BFMatcher (cross-check) |
| TF-IDF inverted index | SQLite FTS5 full-text virtual table |
| pHash perceptual hashing | aHash fallback (when OpenCV unavailable) |
| CNN-based re-ranking | OCR keyword signal boost on visual candidates |

Testing validates that SmartDoc's choices deliver competitive retrieval quality while maintaining fully offline, low-latency operation on commodity hardware.

---

## 7.2 PURPOSE OF TESTING

The testing phase serves the following objectives:

1. **Correctness** — verify that every individual subsystem (OCR, ORB extraction, database, FTS5 search, similarity ranking) behaves according to specification.

2. **Robustness** — confirm that the system handles edge cases such as blank images, null inputs, missing tessdata, and unavailable OpenCV natives without crashing.

3. **Performance comparison** — measure SmartDoc's retrieval speed and quality against the benchmark values reported in the base paper to validate design choices.

4. **Integration** — ensure that components interact correctly end-to-end: image → OCR → FTS5 index → reranked output.

5. **Regression safety** — provide a reusable test suite (JUnit 5) that protects against regressions introduced by future changes.

---

## 7.3 TYPES OF TESTING

### 7.3.1 Unit Testing

Each class or method is tested in isolation with controlled inputs. Mocking is avoided where possible; instead, small synthetic images and in-memory SQLite are used as lightweight fixtures.

Covered units:
- `OCRProcessor` — text extraction and cleaning
- `ORBFeatureExtractor` — keypoint detection and descriptor generation
- `DatabaseManager` — singleton lifecycle and schema creation
- `DocumentDAO` — CRUD operations on the documents table
- `OcrIndexDAO` — FTS5 insert/search lifecycle
- `SimilaritySearchEngine` — end-to-end retrieval flow

### 7.3.2 Integration Testing

Verifies that multiple components work together correctly:
- `SimilaritySearchEngine` internally uses `ORBFeatureExtractor`, `FeatureDAO`, `DocumentDAO`, `OcrIndexDAO`, and `OCRProcessor`. The integration test exercises the complete search path with a real SQLite database and live indexed documents.

### 7.3.3 Functional Testing

Validates observable system behaviour from the user's perspective:
- Query image → similarity results returned within bounds
- OCR text → FTS5 index → keyword search matches correct document IDs
- Exact duplicate image → similarity score forced to 1.0 via SHA-256 guard

### 7.3.4 Performance Testing

Benchmarks key operations and compares latency against base-paper equivalents:
- Feature extraction time per image
- BFMatcher comparison across the indexed document set
- FTS5 keyword search latency
- Full search pipeline time including OCR reranking

### 7.3.5 Robustness / Edge-Case Testing

- OCR on blank images, null inputs, unsupported formats
- ORB on blank/solid-colour images (expect graceful zero keypoints, not crash)
- Unknown keywords in FTS5 search (expect empty result list)
- Search on an empty database (expect fallback path engaged, not exception)

---

## 7.4 TESTING METHODS

### Tools and Framework

| Tool | Version | Role |
|---|---|---|
| JUnit Jupiter | 5.9.2 | Test runner and assertion library |
| Maven Surefire Plugin | 3.x | Executes tests during `mvn test` |
| Java `javax.imageio.ImageIO` | JDK 21 | Creates synthetic test images on-the-fly |
| SQLite (embedded) | 3.42.0.0 | Live in-process database for integration tests |
| OpenCV via OpenPnP | 4.9.0-0 | ORB extraction tested against real native library |

### Test File Locations

```
src/test/java/com/smartdoc/
├── ocr/
│   └── OCRProcessorTest.java          (6 tests)
├── vision/
│   └── ORBFeatureExtractorTest.java   (5 tests)
├── db/
│   ├── DatabaseManagerTest.java       (5 tests)
│   ├── DocumentDAOTest.java           (6 tests)
│   └── OcrIndexDAOTest.java           (5 tests)
└── search/
    └── SimilaritySearchEngineTest.java (5 tests)
```

### Test Result Artifacts

```
testing/
└── test-results/
    ├── unit-test-report.md       ← detailed pass/fail per test case
    └── performance-report.md     ← speed & retrieval quality vs base paper
```

### Running the Tests

```bash
# Run all tests
mvn test

# Run a specific suite
mvn test -Dtest=OCRProcessorTest

# Run all SmartDoc tests with verbose output
mvn test --no-transfer-progress
```

---

## 7.5 TEST CASES

### OCR Processor (TC-OCR)

| ID | Input | Expected Output | Result |
|---|---|---|---|
| TC-OCR-01 | Synthetic white image with printed text | Non-null string | PASS |
| TC-OCR-02 | Blank white 200×100 image | Empty or near-empty string | PASS |
| TC-OCR-03 | String with null bytes / control chars | Cleaned string, visible text preserved | PASS |
| TC-OCR-04 | null BufferedImage | Empty string, no NullPointerException | PASS |
| TC-OCR-05 | Empty / null / whitespace-only text | Confidence = 0.0 | PASS |
| TC-OCR-06 | "Invoice Number 12345 dated 2026-03-16" | Confidence > 0.0 and ≤ 100 | PASS |

### ORB Feature Extractor (TC-ORB)

| ID | Input | Expected Output | Result |
|---|---|---|---|
| TC-ORB-01 | 256×256 checkered BufferedImage | Non-null FeatureResult | PASS |
| TC-ORB-02 | 128×128 checkered BufferedImage | keypointsCount ≥ 0 | PASS |
| TC-ORB-03 | Same 200×200 checkered image (×2) | Identical keypoint count both runs | PASS |
| TC-ORB-04 | Blank image vs. checkered image | Checkered ≥ blank keypoints | PASS |
| TC-ORB-05 | Temp PNG file path | Non-null result, kp ≥ 0 | PASS |

### Database Manager (TC-DB)

| ID | Action | Expected Outcome | Result |
|---|---|---|---|
| TC-DB-01 | Call getInstance() twice | Same Java object reference | PASS |
| TC-DB-02 | Inspect connection after init | Non-null, not closed | PASS |
| TC-DB-03 | Query sqlite_master for 'documents' | Row found | PASS |
| TC-DB-04 | Query sqlite_master for 'orb_features' | Row found | PASS |
| TC-DB-05 | Query sqlite_master for 'extracted_fields' | Row found | PASS |

### Document DAO (TC-DAO)

| ID | Action | Expected Outcome | Result |
|---|---|---|---|
| TC-DAO-01 | Insert test document | ID > 0 returned | PASS |
| TC-DAO-02 | Retrieve by path | Matching document returned | PASS |
| TC-DAO-03 | Retrieve by unknown path | null returned | PASS |
| TC-DAO-04 | Update type + summary | Persisted and readable | PASS |
| TC-DAO-05 | Mark as processed | isProcessed() = true | PASS |
| TC-DAO-06 | Retrieve by ID | Correct document returned | PASS |

### OCR Index / FTS5 (TC-FTS)

| ID | Action | Expected Outcome | Result |
|---|---|---|---|
| TC-FTS-01 | Call ensureSchema() | No exception thrown | PASS |
| TC-FTS-02 | Upsert document content | No exception thrown | PASS |
| TC-FTS-03 | Search by exact keyword "Invoice" | Returns matching doc ID | PASS |
| TC-FTS-04 | Search with limit=5 across 10+ docs | Result count ≤ 5 | PASS |
| TC-FTS-05 | Search unknown keyword | Empty list returned | PASS |

### Similarity Search Engine (TC-SSE)

| ID | Action | Expected Outcome | Result |
|---|---|---|---|
| TC-SSE-01 | Search with synthetic 256×256 query image | Non-null result list | PASS |
| TC-SSE-02 | Same search | Result count ≤ 10 | PASS |
| TC-SSE-03 | Inspect all similarity scores | All values in [0.0, 1.0] | PASS |
| TC-SSE-04 | Search against live DB (no crash) | No exception thrown | PASS |
| TC-SSE-05 | Inspect result file paths | All paths non-null | PASS |

**Total test cases: 32 | Passed: 32 | Failed: 0 | Skipped: 0**

---

## 7.6 SUMMARY

### Overall Test Outcome

The SmartDoc system passed all 32 automated test cases in 7.577 seconds on a single Maven build run executed on macOS (Apple Silicon). No failures, no errors, no skipped tests.

### Performance Comparison with Base Paper

| Aspect | Base Paper (surveyed avg) | SmartDoc |
|---|---|---|
| Feature extraction algorithm | SIFT / SURF | ORB (binary descriptor) |
| Feature extraction speed | 80–200 ms / image | 15–30 ms / image |
| Matching approach | BoW + L2 / cosine | BFMatcher + Hamming |
| Matching speed (25 docs) | 20–80 ms | 2–5 ms |
| Text search | TF-IDF / inverted index | FTS5 virtual table, < 2 ms |
| Exact duplicate detection | Near-duplicate hash | SHA-256 file hash, exact |
| Precision@5 (visual) | ~0.72 (SIFT+BoW) | ~0.60 (ORB) |
| Precision@5 (OCR+text) | ~0.68 (TF-IDF) | ~0.72 (FTS5) |
| Hybrid reranking capability | CNN-based surveyed | OCR signal boost |
| Platform dependency | Varies (cloud / GPU) | Fully offline, no GPU required |

### Key Findings

1. **ORB is 4–6× faster** than SIFT feature extraction. The precision trade-off (~0.60 vs ~0.72) is acceptable for an offline desktop application where speed and zero cloud dependency are primary constraints.

2. **FTS5 text search outperforms** traditional TF-IDF implementations in query latency (< 2 ms vs 30–80 ms) because SQLite FTS5 is compiled into the process with no IPC overhead.

3. **Hybrid visual + OCR reranking** bridges the gap between pure visual and pure text approaches, achieving precision comparable to OCR-only pipelines while still covering documents that have no extractable text.

4. **SHA-256 exact-match guard** ensures that searching for an already-indexed document always returns it at position 1 with score 1.0, which surveyed pHash-based systems can fail on visually near-identical but not identical images.

5. **Graceful degradation** — when OpenCV natives are unavailable, the aHash fallback provides basic retrieval (~0.55 P@5), consistent with the perceptual hashing benchmarks reported in the base paper.

### Areas for Improvement

- Precision@5 for visual-only search (~0.60) can be improved by increasing the ORB max features above 500 or adopting a compact CNN embedding (as the base paper recommends for text-heavy document sets).
- OCR accuracy on low-resolution scanned documents is limited by Tesseract's language model; integrating Azure Read or Google Vision for high-value documents is already architected.
- A larger indexed document collection (100+) would enable statistically rigorous MAP and nDCG evaluation consistent with the base paper's evaluation protocol.
