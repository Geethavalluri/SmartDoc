package com.smartdoc.db;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class OcrIndexDAO {
    private final Connection conn;

    public OcrIndexDAO(Connection conn) { this.conn = conn; }

    public void ensureSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE VIRTUAL TABLE IF NOT EXISTS ocr_index USING fts5(doc_id, content);");
        }
    }

    public void upsert(long documentId, String content) throws SQLException {
        if (content == null) content = "";
        // Simple replace: delete and insert
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM ocr_index WHERE doc_id = ?")) {
            del.setLong(1, documentId);
            del.executeUpdate();
        }
        try (PreparedStatement ins = conn.prepareStatement("INSERT INTO ocr_index(doc_id, content) VALUES (?, ?)")) {
            ins.setLong(1, documentId);
            ins.setString(2, content);
            ins.executeUpdate();
        }
    }

    public List<Long> search(String query, int limit) throws SQLException {
        List<Long> ids = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return ids;
        String sql = "SELECT doc_id FROM ocr_index WHERE ocr_index MATCH ? LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, query);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

}
