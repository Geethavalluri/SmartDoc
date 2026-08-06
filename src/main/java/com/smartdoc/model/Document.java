package com.smartdoc.model;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Document model representing a scanned document
 */
public class Document {
    private long id;
    private Path filePath;
    private String fileName;
    private String fileExtension;
    private long fileSize;
    private LocalDateTime lastModified;
    private String documentType;
    private String summary;
    private LocalDateTime indexedAt;
    private boolean processed;

    // Additional metadata from LLM processing
    private List<String> tags;
    private List<ExtractedField> extractedFields;

    public Document() {
        this.tags = new ArrayList<>();
        this.extractedFields = new ArrayList<>();
    }

    public Document(Path filePath) {
        this();
        this.filePath = filePath;
        this.fileName = filePath.getFileName().toString();
        this.fileExtension = getFileExtensionFromPath(filePath);
    }

    private String getFileExtensionFromPath(Path path) {
        String fileName = path.getFileName().toString();
        int lastDotIndex = fileName.lastIndexOf('.');
        return lastDotIndex > 0 ? fileName.substring(lastDotIndex + 1).toLowerCase() : "";
    }

    // Getters and Setters
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public Path getFilePath() { return filePath; }
    public void setFilePath(Path filePath) {
        this.filePath = filePath;
        this.fileName = filePath.getFileName().toString();
        this.fileExtension = getFileExtensionFromPath(filePath);
    }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFileExtension() { return fileExtension; }
    public void setFileExtension(String fileExtension) { this.fileExtension = fileExtension; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public LocalDateTime getLastModified() { return lastModified; }
    public void setLastModified(LocalDateTime lastModified) { this.lastModified = lastModified; }

    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public LocalDateTime getIndexedAt() { return indexedAt; }
    public void setIndexedAt(LocalDateTime indexedAt) { this.indexedAt = indexedAt; }

    public boolean isProcessed() { return processed; }
    public void setProcessed(boolean processed) { this.processed = processed; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public List<ExtractedField> getExtractedFields() { return extractedFields; }
    public void setExtractedFields(List<ExtractedField> extractedFields) { this.extractedFields = extractedFields; }

    @Override
    public String toString() {
        return "Document{id=%d, fileName='%s', type='%s', processed=%s}".formatted(
            id, fileName, documentType, processed);
    }
}
