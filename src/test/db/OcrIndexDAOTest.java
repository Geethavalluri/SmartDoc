package com.smartdoc.db;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

/**
 * Unit tests for OcrIndexDAO (FTS5 full-text search).
 *
 * TC-FTS-01: ensureSchema creates ocr_index table without exception
 * TC-FTS-02: upsert stores document content without exception
 * TC-FTS-03: search finds document by exact keyword
 * TC-FTS-04: search returns results within the specified limit
 * TC-FTS-05: search returns empty list for unknown keyword
 */
@DisplayName("OcrIndexDAO (FTS5) Unit Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OcrIndexDAOTest {

    private static OcrIndexDAO ocrDao;
    private static final long TEST_DOC_ID = 99999L;

    @BeforeAll
    static void setUpAll() throws Exception {
        ocrDao = new OcrIndexDAO(DatabaseManager.getInstance().getConnection());
        ocrDao.ensureSchema();
    }

    // TC-FTS-01
    @Test
    @Order(1)
    @DisplayName("TC-FTS-01: ensureSchema runs without exception")
    void testEnsureSchema() {
        assertDoesNotThrow(() -> ocrDao.ensureSchema());
    }

    // TC-FTS-02
    @Test
    @Order(2)
    @DisplayName("TC-FTS-02: upsert stores OCR content without exception")
    void testUpsert() {
        assertDoesNotThrow(() ->
            ocrDao.upsert(TEST_DOC_ID,
                "Invoice number 42 dated January 2026 total amount five hundred dollars"));
    }

    // TC-FTS-03
    @Test
    @Order(3)
    @DisplayName("TC-FTS-03: search finds document by keyword")
    void testSearchFindsKeyword() throws Exception {
        List<Long> results = ocrDao.search("Invoice", 10);
        assertTrue(results.contains(TEST_DOC_ID),
            "Indexed document must appear in FTS search results");
    }

    // TC-FTS-04
    @Test
    @Order(4)
    @DisplayName("TC-FTS-04: search respects result limit")
    void testSearchRespectsLimit() throws Exception {
        // Insert several docs
        for (long id = 88000L; id < 88010L; id++) {
            ocrDao.upsert(id, "sample document content for limit test recurring word");
        }
        List<Long> results = ocrDao.search("recurring", 5);
        assertTrue(results.size() <= 5, "Result count must not exceed the requested limit");
    }

    // TC-FTS-05
    @Test
    @Order(5)
    @DisplayName("TC-FTS-05: search returns empty list for unknown keyword")
    void testSearchUnknownKeywordReturnsEmpty() throws Exception {
        List<Long> results = ocrDao.search("xyzzy_no_such_word_ever_12345", 10);
        assertTrue(results.isEmpty(), "Unknown keyword must produce no results");
    }
}
