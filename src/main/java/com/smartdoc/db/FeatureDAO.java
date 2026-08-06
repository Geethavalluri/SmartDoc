package com.smartdoc.db;

import org.opencv.core.MatOfKeyPoint;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Data Access Object for ORB Feature operations
 */
public class FeatureDAO {
    private final Connection connection;

    public FeatureDAO(Connection connection) {
        this.connection = connection;
    }

    /**
     * Store ORB features for a document
     */
    public void insertFeatures(long documentId, org.opencv.core.Mat descriptors, MatOfKeyPoint keypoints) throws SQLException {
        String sql = "INSERT INTO orb_features (document_id, feature_data, keypoints_count) VALUES (?, ?, ?)";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            // Serialize the descriptors matrix
            byte[] featureData = serializeMat(descriptors);
            int keypointsCount = keypoints.rows();

            pstmt.setLong(1, documentId);
            pstmt.setBytes(2, featureData);
            pstmt.setInt(3, keypointsCount);

            pstmt.executeUpdate();
        }
    }

    /**
     * Get features for a document
     */
    public FeatureData getFeatures(long documentId) throws SQLException {
        String sql = "SELECT feature_data, keypoints_count FROM orb_features WHERE document_id = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, documentId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    byte[] featureData = rs.getBytes("feature_data");
                    int keypointsCount = rs.getInt("keypoints_count");
                    org.opencv.core.Mat descriptors = deserializeMat(featureData);

                    return new FeatureData(descriptors, keypointsCount);
                }
            }
        }
        return null;
    }

    /**
     * Get all features for similarity search
     */
    public java.util.List<DocumentFeatures> getAllFeatures() throws SQLException {
        java.util.List<DocumentFeatures> features = new java.util.ArrayList<>();
        // Include ALL documents that have ORB features stored, not just fully-processed ones.
        // A document can have valid feature data even if the LLM/type step failed.
        String sql = """
            SELECT d.id, d.file_path, f.feature_data, f.keypoints_count
            FROM documents d
            INNER JOIN orb_features f ON d.id = f.document_id
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                long documentId = rs.getLong("id");
                String filePath = rs.getString("file_path");
                byte[] featureData = rs.getBytes("feature_data");
                int keypointsCount = rs.getInt("keypoints_count");

                org.opencv.core.Mat descriptors = deserializeMat(featureData);
                features.add(new DocumentFeatures(documentId, filePath, descriptors, keypointsCount));
            }
        }
        return features;
    }

    /**
     * Delete features for a document
     */
    public void deleteFeatures(long documentId) throws SQLException {
        String sql = "DELETE FROM orb_features WHERE document_id = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, documentId);
            pstmt.executeUpdate();
        }
    }

    private byte[] serializeMat(org.opencv.core.Mat mat) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            // Write Mat dimensions and type
            dos.writeInt(mat.rows());
            dos.writeInt(mat.cols());
            dos.writeInt(mat.type());

            // Convert Mat to byte array - for ORB descriptors, this is typically float data
            int totalBytes = (int) (mat.total() * mat.channels() * mat.elemSize());
            byte[] data = new byte[totalBytes];
            mat.get(0, 0, data);

            // Write the data length and data
            dos.writeInt(data.length);
            dos.write(data);

            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize Mat", e);
        }
    }

    private org.opencv.core.Mat deserializeMat(byte[] data) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             DataInputStream dis = new DataInputStream(bais)) {

            // Read Mat dimensions and type
            int rows = dis.readInt();
            int cols = dis.readInt();
            int type = dis.readInt();

            // Read data length and data
            int dataLength = dis.readInt();
            byte[] matData = new byte[dataLength];
            dis.readFully(matData);

            // Reconstruct Mat
            org.opencv.core.Mat mat = new org.opencv.core.Mat(rows, cols, type);
            mat.put(0, 0, matData);
            return mat;

        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize Mat", e);
        }
    }

    /**
     * Data class for feature information
     */
    public static class FeatureData {
        public final org.opencv.core.Mat descriptors;
        public final int keypointsCount;

        public FeatureData(org.opencv.core.Mat descriptors, int keypointsCount) {
            this.descriptors = descriptors;
            this.keypointsCount = keypointsCount;
        }
    }

    /**
     * Data class for document features
     */
    public static class DocumentFeatures {
        public final long documentId;
        public final String filePath;
        public final org.opencv.core.Mat descriptors;
        public final int keypointsCount;

        public DocumentFeatures(long documentId, String filePath, org.opencv.core.Mat descriptors, int keypointsCount) {
            this.documentId = documentId;
            this.filePath = filePath;
            this.descriptors = descriptors;
            this.keypointsCount = keypointsCount;
        }
    }
}
