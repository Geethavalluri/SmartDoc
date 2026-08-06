package com.smartdoc.db;

import com.smartdoc.model.Document;

import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object for Document operations
 */
public class DocumentDAO {
    private final Connection connection;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public DocumentDAO(Connection connection) {
        this.connection = connection;
    }

    /**
     * Insert a new document into the database
     */
    public long insertDocument(Document document) throws SQLException {
        String sql = """
            INSERT INTO documents (file_path, file_name, file_extension, file_size,
                                 last_modified, document_type, summary, is_processed)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, document.getFilePath().toString());
            pstmt.setString(2, document.getFileName());
            pstmt.setString(3, document.getFileExtension());
            pstmt.setLong(4, document.getFileSize());
            pstmt.setString(5, document.getLastModified().format(FORMATTER));
            pstmt.setString(6, document.getDocumentType());
            pstmt.setString(7, document.getSummary());
            pstmt.setBoolean(8, document.isProcessed());

            pstmt.executeUpdate();

            try (ResultSet rs = pstmt.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        throw new SQLException("Failed to insert document");
    }

    /**
     * Update document metadata after processing
     */
    public void updateDocumentMetadata(long documentId, String documentType, String summary) throws SQLException {
        String sql = "UPDATE documents SET document_type = ?, summary = ?, is_processed = TRUE WHERE id = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, documentType);
            pstmt.setString(2, summary);
            pstmt.setLong(3, documentId);
            pstmt.executeUpdate();
        }
    }

    /**
     * Update document type and summary without changing processed flag
     */
    public void updateTypeAndSummary(long documentId, String documentType, String summary) throws SQLException {
        String sql = "UPDATE documents SET document_type = ?, summary = ? WHERE id = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, documentType);
            pstmt.setString(2, summary);
            pstmt.setLong(3, documentId);
            pstmt.executeUpdate();
        }
    }

    /**
     * Mark document as processed (sets is_processed = TRUE)
     */
    public void markProcessed(long documentId) throws SQLException {
        String sql = "UPDATE documents SET is_processed = TRUE WHERE id = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, documentId);
            pstmt.executeUpdate();
        }
    }

    /**
     * Check if document exists
     */
    public boolean documentExists(Path filePath) throws SQLException {
        String sql = "SELECT id FROM documents WHERE file_path = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, filePath.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Get document by file path
     */
    public Document getDocumentByPath(Path filePath) throws SQLException {
        String sql = """
            SELECT id, file_path, file_name, file_extension, file_size,
                   last_modified, document_type, summary, indexed_at, is_processed
            FROM documents WHERE file_path = ?
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, filePath.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return mapResultSetToDocument(rs);
                }
            }
        }
        return null;
    }

    /**
     * Get all documents
     */
    public List<Document> getAllDocuments() throws SQLException {
        List<Document> documents = new ArrayList<>();
        String sql = """
            SELECT id, file_path, file_name, file_extension, file_size,
                   last_modified, document_type, summary, indexed_at, is_processed
            FROM documents ORDER BY indexed_at DESC
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                documents.add(mapResultSetToDocument(rs));
            }
        }
        return documents;
    }

    /**
     * Get document by id
     */
    public Document getDocumentById(long id) throws SQLException {
        String sql = """
            SELECT id, file_path, file_name, file_extension, file_size,
                   last_modified, document_type, summary, indexed_at, is_processed
            FROM documents WHERE id = ?
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return mapResultSetToDocument(rs);
                }
            }
        }
        return null;
    }

    /**
     * Search documents by type
     */
    public List<Document> searchByDocumentType(String documentType) throws SQLException {
        List<Document> documents = new ArrayList<>();
        String sql = """
            SELECT id, file_path, file_name, file_extension, file_size,
                   last_modified, document_type, summary, indexed_at, is_processed
            FROM documents WHERE document_type LIKE ? ORDER BY indexed_at DESC
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, "%" + documentType + "%");
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    documents.add(mapResultSetToDocument(rs));
                }
            }
        }
        return documents;
    }

    /**
     * Search documents whose file_name contains any of the given keywords (case-insensitive).
     * Reliable fallback when document_type or OCR index are stale/incomplete.
     */
    public List<Document> searchByFileNameKeywords(List<String> keywords) throws SQLException {
        if (keywords == null || keywords.isEmpty()) return new ArrayList<>();
        StringBuilder sb = new StringBuilder(
            "SELECT id, file_path, file_name, file_extension, file_size, " +
            "last_modified, document_type, summary, indexed_at, is_processed " +
            "FROM documents WHERE ");
        for (int i = 0; i < keywords.size(); i++) {
            if (i > 0) sb.append(" OR ");
            sb.append("LOWER(file_name) LIKE ?");
        }
        sb.append(" ORDER BY file_name ASC");
        List<Document> docs = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sb.toString())) {
            for (int i = 0; i < keywords.size(); i++) {
                ps.setString(i + 1, "%" + keywords.get(i).toLowerCase() + "%");
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) docs.add(mapResultSetToDocument(rs));
            }
        }
        return docs;
    }

    /**
     * Delete document by path
     */
    public void deleteDocument(Path filePath) throws SQLException {
        String sql = "DELETE FROM documents WHERE file_path = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, filePath.toString());
            pstmt.executeUpdate();
        }
    }

    private Document mapResultSetToDocument(ResultSet rs) throws SQLException {
        Document doc = new Document();
        doc.setId(rs.getLong("id"));
        doc.setFilePath(Path.of(rs.getString("file_path")));
        doc.setFileName(rs.getString("file_name"));
        doc.setFileExtension(rs.getString("file_extension"));
        doc.setFileSize(rs.getLong("file_size"));
        doc.setLastModified(LocalDateTime.parse(rs.getString("last_modified"), FORMATTER));
        doc.setDocumentType(rs.getString("document_type"));
        doc.setSummary(rs.getString("summary"));
        doc.setIndexedAt(LocalDateTime.parse(rs.getString("indexed_at"), FORMATTER));
        doc.setProcessed(rs.getBoolean("is_processed"));
        return doc;
    }
}
