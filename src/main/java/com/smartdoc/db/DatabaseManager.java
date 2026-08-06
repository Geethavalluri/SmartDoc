package com.smartdoc.db;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite Database Manager for SmartDoc
 * Handles database initialization and connections
 */
public class DatabaseManager {
    private static final String DB_NAME = "smartdoc.db";
    private static DatabaseManager instance;
    private Connection connection;
    private Path dbPath;

    private DatabaseManager() throws SQLException {
        initializeDatabase();
    }

    public static synchronized DatabaseManager getInstance() throws SQLException {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    private void initializeDatabase() throws SQLException {
        // Create database in user's home directory
        this.dbPath = Path.of(System.getProperty("user.home"), DB_NAME);
        String url = "jdbc:sqlite:" + dbPath.toString();

        connection = DriverManager.getConnection(url);
        createTables();
    }

    private void createTables() throws SQLException {
        String[] createTableStatements = {
            // Documents table
            """
            CREATE TABLE IF NOT EXISTS documents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file_path TEXT NOT NULL UNIQUE,
                file_name TEXT NOT NULL,
                file_extension TEXT NOT NULL,
                file_size INTEGER,
                last_modified TIMESTAMP,
                document_type TEXT,
                summary TEXT,
                indexed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                is_processed BOOLEAN DEFAULT FALSE
            );
            """,

            // ORB Features table
            """
            CREATE TABLE IF NOT EXISTS orb_features (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                document_id INTEGER NOT NULL,
                feature_data BLOB NOT NULL,
                keypoints_count INTEGER NOT NULL,
                FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
            );
            """,

            // Document tags table
            """
            CREATE TABLE IF NOT EXISTS document_tags (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                document_id INTEGER NOT NULL,
                tag TEXT NOT NULL,
                FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
                UNIQUE(document_id, tag)
            );
            """,

            // Extracted fields table (for key-value pairs from documents)
            """
            CREATE TABLE IF NOT EXISTS extracted_fields (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                document_id INTEGER NOT NULL,
                field_name TEXT NOT NULL,
                field_value TEXT,
                confidence REAL,
                FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
            );
            """,

            // Indexes for performance
            "CREATE INDEX IF NOT EXISTS idx_documents_path ON documents(file_path);",
            "CREATE INDEX IF NOT EXISTS idx_documents_type ON documents(document_type);",
            "CREATE INDEX IF NOT EXISTS idx_orb_features_doc_id ON orb_features(document_id);",
            "CREATE INDEX IF NOT EXISTS idx_tags_doc_id ON document_tags(document_id);",
            "CREATE INDEX IF NOT EXISTS idx_fields_doc_id ON extracted_fields(document_id);"
        };

        try (Statement stmt = connection.createStatement()) {
            for (String sql : createTableStatements) {
                stmt.execute(sql);
            }
        }
    }

    public Connection getConnection() {
        return connection;
    }

    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    public Path getDatabasePath() {
        return dbPath;
    }

    public void demoQuery() throws Exception {
        String dbPath = System.getProperty("user.home") + "/smartdoc.db";
        String url = "jdbc:sqlite:" + dbPath;

        try (Connection conn = DriverManager.getConnection(url)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, file_name, file_path FROM documents ORDER BY indexed_at DESC LIMIT 10")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        System.out.println(rs.getLong("id") + " | " + rs.getString("file_name") + " | " + rs.getString("file_path"));
                    }
                }
            }
        }
    }
}
