package com.smartdoc.db;

import com.smartdoc.model.Document;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * Unit tests for DocumentDAO CRUD operations.
 *
 * TC-DAO-01: insertDocument assigns a non-zero ID
 * TC-DAO-02: getDocumentByPath retrieves previously inserted document
 * TC-DAO-03: getDocumentByPath returns null for unknown path
 * TC-DAO-04: updateTypeAndSummary persists changes
 * TC-DAO-05: markProcessed sets is_processed flag
 * TC-DAO-06: getDocumentById retrieves correct document
 */
@DisplayName("DocumentDAO Unit Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocumentDAOTest {

    private static DocumentDAO dao;
    private static long insertedId;
    private static final Path TEST_PATH = Path.of(
        System.getProperty("java.io.tmpdir"), "smartdoc_test_doc_" + System.currentTimeMillis() + ".jpg");

    @BeforeAll
    static void setUpAll() throws Exception {
        dao = new DocumentDAO(DatabaseManager.getInstance().getConnection());
    }

    // TC-DAO-01
    @Test
    @Order(1)
    @DisplayName("TC-DAO-01: insertDocument returns valid non-zero ID")
    void testInsertDocument() throws Exception {
        Document doc = new Document(TEST_PATH);
        doc.setFileSize(1024L);
        doc.setLastModified(LocalDateTime.now());
        insertedId = dao.insertDocument(doc);
        assertTrue(insertedId > 0, "Inserted document must have ID > 0");
    }

    // TC-DAO-02
    @Test
    @Order(2)
    @DisplayName("TC-DAO-02: getDocumentByPath retrieves inserted document")
    void testGetDocumentByPath() throws Exception {
        Document found = dao.getDocumentByPath(TEST_PATH);
        assertNotNull(found, "Document must be retrievable by path");
        assertEquals(TEST_PATH.toAbsolutePath().normalize().toString(),
            found.getFilePath().toAbsolutePath().normalize().toString());
    }

    // TC-DAO-03
    @Test
    @Order(3)
    @DisplayName("TC-DAO-03: getDocumentByPath returns null for unknown path")
    void testGetDocumentByUnknownPath() throws Exception {
        Document found = dao.getDocumentByPath(Path.of("/no/such/file_xyz.jpg"));
        assertNull(found, "Unknown path must return null");
    }

    // TC-DAO-04
    @Test
    @Order(4)
    @DisplayName("TC-DAO-04: updateTypeAndSummary persists type and summary")
    void testUpdateTypeAndSummary() throws Exception {
        dao.updateTypeAndSummary(insertedId, "invoice", "Test invoice summary");
        Document updated = dao.getDocumentById(insertedId);
        assertNotNull(updated);
        assertEquals("invoice", updated.getDocumentType());
        assertEquals("Test invoice summary", updated.getSummary());
    }

    // TC-DAO-05
    @Test
    @Order(5)
    @DisplayName("TC-DAO-05: markProcessed sets processed flag to true")
    void testMarkProcessed() throws Exception {
        dao.markProcessed(insertedId);
        Document doc = dao.getDocumentById(insertedId);
        assertNotNull(doc);
        assertTrue(doc.isProcessed(), "Document should be marked as processed");
    }

    // TC-DAO-06
    @Test
    @Order(6)
    @DisplayName("TC-DAO-06: getDocumentById returns correct document")
    void testGetDocumentById() throws Exception {
        Document found = dao.getDocumentById(insertedId);
        assertNotNull(found, "Document must be retrievable by ID");
        assertEquals(insertedId, found.getId(), "IDs must match");
    }
}
