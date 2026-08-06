package com.smartdoc.db;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Unit tests for DatabaseManager.
 *
 * TC-DB-01: Singleton returns same instance
 * TC-DB-02: Connection is open and non-null after init
 * TC-DB-03: documents table exists after init
 * TC-DB-04: orb_features table exists after init
 * TC-DB-05: extracted_fields table exists after init
 */
@DisplayName("DatabaseManager Unit Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseManagerTest {

    private DatabaseManager manager;

    @BeforeEach
    void setUp() throws Exception {
        manager = DatabaseManager.getInstance();
    }

    // TC-DB-01
    @Test
    @Order(1)
    @DisplayName("TC-DB-01: getInstance() returns the same singleton")
    void testSingleton() throws Exception {
        DatabaseManager second = DatabaseManager.getInstance();
        assertSame(manager, second, "DatabaseManager must be a singleton");
    }

    // TC-DB-02
    @Test
    @Order(2)
    @DisplayName("TC-DB-02: Connection is non-null and open")
    void testConnectionOpen() throws Exception {
        Connection conn = manager.getConnection();
        assertNotNull(conn, "Connection must not be null");
        assertFalse(conn.isClosed(), "Connection must be open");
    }

    // TC-DB-03
    @Test
    @Order(3)
    @DisplayName("TC-DB-03: 'documents' table exists")
    void testDocumentsTableExists() throws Exception {
        assertTableExists("documents");
    }

    // TC-DB-04
    @Test
    @Order(4)
    @DisplayName("TC-DB-04: 'orb_features' table exists")
    void testOrbFeaturesTableExists() throws Exception {
        assertTableExists("orb_features");
    }

    // TC-DB-05
    @Test
    @Order(5)
    @DisplayName("TC-DB-05: 'extracted_fields' table exists")
    void testExtractedFieldsTableExists() throws Exception {
        assertTableExists("extracted_fields");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------
    private void assertTableExists(String tableName) throws Exception {
        Connection conn = manager.getConnection();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT name FROM sqlite_master WHERE type='table' AND name='" + tableName + "'")) {
            assertTrue(rs.next(), "Table '" + tableName + "' must exist in the database");
        }
    }
}
