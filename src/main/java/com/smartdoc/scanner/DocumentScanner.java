package com.smartdoc.scanner;

import com.smartdoc.db.DatabaseManager;
import com.smartdoc.db.DocumentDAO;
import com.smartdoc.model.Document;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Document Scanner - scans local drives for document files
 * Supports .jpg, .png, .pdf files
 * Skips system folders (Windows, Program Files, AppData)
 */
public class DocumentScanner {
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "pdf");
    private static final Set<String> SKIP_FOLDERS = Set.of(
        "windows", "winnt", "program files", "program files (x86)",
        "programdata", "appdata", "temp", "tmp", "$recycle.bin",
        "system volume information", "recovery", "boot", "msocache",
        "inetpub", "perflogs", "microsoft", "common files"
    );

    private final DocumentDAO documentDAO;
    private volatile boolean isScanning = false;

    public DocumentScanner() throws Exception {
        DatabaseManager dbManager = DatabaseManager.getInstance();
        this.documentDAO = new DocumentDAO(dbManager.getConnection());
    }

    /**
     * Scan specified directories for documents
     */
    public CompletableFuture<Void> scanDirectories(List<Path> directories,
                                                  Consumer<Document> onDocumentFound,
                                                  Consumer<String> onProgress) {
        return CompletableFuture.runAsync(() -> {
            isScanning = true;
            try {
                for (Path directory : directories) {
                    if (!isScanning) break;

                    onProgress.accept("Scanning: " + directory);
                    scanDirectory(directory, onDocumentFound, onProgress);
                }
                onProgress.accept("Scan completed");
            } catch (Exception e) {
                onProgress.accept("Scan error: " + e.getMessage());
            } finally {
                isScanning = false;
            }
        });
    }

    /**
     * Scan a single directory recursively
     */
    private void scanDirectory(Path directory, Consumer<Document> onDocumentFound, Consumer<String> onProgress) {
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!isScanning) return FileVisitResult.TERMINATE;

                    // Handle root directories (getFileName() returns null)
                    Path fileName = dir.getFileName();
                    if (fileName == null) {
                        // This is a root directory, allow scanning but don't apply folder name filters
                        return FileVisitResult.CONTINUE;
                    }

                    String dirName = fileName.toString().toLowerCase();
                    if (SKIP_FOLDERS.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    // Skip hidden directories
                    if (dirName.startsWith(".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!isScanning) return FileVisitResult.TERMINATE;

                    try {
                        if (isSupportedDocument(file)) {
                            Document doc = createDocumentFromFile(file, attrs);
                            if (doc != null) {
                                onDocumentFound.accept(doc);
                            }
                        }
                    } catch (Exception e) {
                        // Log error but continue scanning
                        onProgress.accept("Error processing file " + file + ": " + e.getMessage());
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // Skip files we can't access (permission issues, etc.)
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            onProgress.accept("Error scanning directory " + directory + ": " + e.getMessage());
        }
    }

    /**
     * Check if file is a supported document type
     */
    private boolean isSupportedDocument(Path file) {
        Path fileName = file.getFileName();
        if (fileName == null) {
            return false; // Root directory or invalid path
        }
        String fileNameStr = fileName.toString().toLowerCase();
        return SUPPORTED_EXTENSIONS.stream().anyMatch(ext -> fileNameStr.endsWith("." + ext));
    }

    /**
     * Create Document object from file
     */
    private Document createDocumentFromFile(Path file, BasicFileAttributes attrs) throws Exception {
        Document doc = new Document(file);
        doc.setFileSize(attrs.size());
        doc.setLastModified(LocalDateTime.ofInstant(attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault()));
        doc.setProcessed(false);

        // Check if document already exists in database
        if (documentDAO.documentExists(file)) {
            Document existingDoc = documentDAO.getDocumentByPath(file);
            boolean needsMetadataRepair = existingDoc != null
                && "image".equalsIgnoreCase(existingDoc.getDocumentType())
                && "Certificate image processed".equalsIgnoreCase(existingDoc.getSummary());

            // Update if file has been modified
            if (existingDoc != null && (existingDoc.getLastModified().isBefore(doc.getLastModified()) || needsMetadataRepair)) {
                existingDoc.setFileSize(doc.getFileSize());
                existingDoc.setLastModified(doc.getLastModified());
                existingDoc.setProcessed(false); // Mark for reprocessing
                return existingDoc;
            }
            return null; // Skip if already exists and not modified
        }

        return doc;
    }

    /**
     * Stop ongoing scan
     */
    public void stopScan() {
        isScanning = false;
    }

    /**
     * Check if scanner is currently scanning
     */
    public boolean isScanning() {
        return isScanning;
    }

    /**
     * Get default scan directories (empty by default for safety)
     * Users should manually add specific folders to scan
     */
    public static List<Path> getDefaultScanDirectories() {
        // Return empty list by default for safety
        // Users should manually add specific folders they want to scan
        return new ArrayList<>();
    }
}
