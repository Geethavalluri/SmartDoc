package com.smartdoc.watcher;

import com.smartdoc.model.Document;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Folder Watcher Service using Java WatchService
 * Monitors directories for new or modified document files
 */
public class FolderWatcherService {
    private static final Logger logger = Logger.getLogger(FolderWatcherService.class.getName());
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "pdf");
    private static final java.util.concurrent.atomic.AtomicInteger WATCHER_THREAD_COUNTER =
        new java.util.concurrent.atomic.AtomicInteger(1);

    private final ExecutorService executorService;
    private final Map<Path, WatchService> watchServices;
    private final Map<WatchKey, Path> watchKeyToPath;
    private final Set<Path> watchedDirectories;
    private volatile boolean isRunning = false;

    private Consumer<Document> onDocumentChanged;

    public FolderWatcherService() {
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("FolderWatcher-" + WATCHER_THREAD_COUNTER.getAndIncrement());
            return t;
        });

        this.watchServices = new ConcurrentHashMap<>();
        this.watchKeyToPath = new ConcurrentHashMap<>();
        this.watchedDirectories = ConcurrentHashMap.newKeySet();
    }

    /**
     * Start watching the specified directories
     */
    public void startWatching(List<Path> directories, Consumer<Document> onDocumentChanged) {
        if (isRunning) {
            stopWatching();
        }

        this.onDocumentChanged = onDocumentChanged;
        this.isRunning = true;

        logger.info("Starting folder watcher for " + directories.size() + " directories");

        for (Path directory : directories) {
            if (!watchedDirectories.contains(directory)) {
                registerDirectory(directory);
            }
        }

        // Start the monitoring thread
        executorService.submit(this::monitorChanges);
    }

    /**
     * Stop watching all directories
     */
    public void stopWatching() {
        logger.info("Stopping folder watcher");
        isRunning = false;

        // Close all watch services
        for (WatchService ws : watchServices.values()) {
            try {
                ws.close();
            } catch (IOException e) {
                logger.warning("Error closing watch service: " + e.getMessage());
            }
        }

        watchServices.clear();
        watchKeyToPath.clear();
        watchedDirectories.clear();
    }

    /**
     * Register a directory and all its subdirectories for watching
     */
    private void registerDirectory(Path directory) {
        try {
            if (!Files.exists(directory) || !Files.isDirectory(directory)) {
                logger.warning("Directory does not exist or is not a directory: " + directory);
                return;
            }

            // Skip system directories
            if (isSystemDirectory(directory)) {
                return;
            }

            WatchService watchService = FileSystems.getDefault().newWatchService();
            watchServices.put(directory, watchService);
            watchedDirectories.add(directory);

            // Register the directory
            WatchKey key = directory.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);

            watchKeyToPath.put(key, directory);

            // Recursively register subdirectories
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!isRunning) return FileVisitResult.TERMINATE;

                    if (isSystemDirectory(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    try {
                        if (!watchedDirectories.contains(dir)) {
                            WatchKey subKey = dir.register(watchService,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_MODIFY,
                                StandardWatchEventKinds.ENTRY_DELETE);

                            watchKeyToPath.put(subKey, dir);
                            watchedDirectories.add(dir);
                        }
                    } catch (IOException e) {
                        logger.warning("Failed to register subdirectory: " + dir + " - " + e.getMessage());
                    }

                    return FileVisitResult.CONTINUE;
                }
            });

            logger.info("Registered directory for watching: " + directory);

        } catch (IOException e) {
            logger.warning("Failed to register directory: " + directory + " - " + e.getMessage());
        }
    }

    /**
     * Monitor for file system changes
     */
    private void monitorChanges() {
        logger.info("Folder monitoring started");

        while (isRunning) {
            for (Map.Entry<Path, WatchService> entry : watchServices.entrySet()) {
                Path rootDir = entry.getKey();
                WatchService watchService = entry.getValue();

                try {
                    // Poll with timeout to allow for shutdown
                    WatchKey key = watchService.poll(1, TimeUnit.SECONDS);

                    if (key == null) continue;

                    Path dir = watchKeyToPath.get(key);

                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (!isRunning) break;

                        WatchEvent.Kind<?> kind = event.kind();

                        // Skip overflow events
                        if (kind == StandardWatchEventKinds.OVERFLOW) {
                            continue;
                        }

                        @SuppressWarnings("unchecked")
                        WatchEvent<Path> ev = (WatchEvent<Path>) event;
                        Path fileName = ev.context();
                        Path fullPath = dir.resolve(fileName);

                        processFileEvent(kind, fullPath);
                    }

                    // Reset key and remove from set if directory is no longer accessible
                    boolean valid = key.reset();
                    if (!valid) {
                        watchKeyToPath.remove(key);
                        logger.warning("Watch key no longer valid for: " + dir);
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.warning("Error monitoring directory " + rootDir + ": " + e.getMessage());
                }
            }
        }

        logger.info("Folder monitoring stopped");
    }

    /**
     * Process a file system event
     */
    private void processFileEvent(WatchEvent.Kind<?> kind, Path filePath) {
        try {
            if (!isSupportedDocument(filePath)) {
                return;
            }

            Document document = null;

            if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                logger.info("New document detected: " + filePath);
                document = createDocumentFromPath(filePath);

            } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                logger.info("Document modified: " + filePath);
                document = createDocumentFromPath(filePath);

            } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                logger.info("Document deleted: " + filePath);
                // For deletions, we might want to handle cleanup
                // document = createDocumentFromPath(filePath); // Mark as deleted
            }

            if (document != null && onDocumentChanged != null) {
                onDocumentChanged.accept(document);
            }

        } catch (Exception e) {
            logger.warning("Error processing file event for " + filePath + ": " + e.getMessage());
        }
    }

    /**
     * Create a Document object from file path
     */
    private Document createDocumentFromPath(Path filePath) throws IOException {
        Document doc = new Document(filePath);

        if (Files.exists(filePath)) {
            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
            doc.setFileSize(attrs.size());
            doc.setLastModified(java.time.LocalDateTime.ofInstant(
                attrs.lastModifiedTime().toInstant(),
                java.time.ZoneId.systemDefault()));
        }

        return doc;
    }

    /**
     * Check if file is a supported document type
     */
    private boolean isSupportedDocument(Path filePath) {
        if (!Files.isRegularFile(filePath)) {
            return false;
        }

        String fileName = filePath.getFileName().toString().toLowerCase();
        return SUPPORTED_EXTENSIONS.stream().anyMatch(ext -> fileName.endsWith("." + ext));
    }

    /**
     * Check if directory should be skipped (system directories)
     */
    private boolean isSystemDirectory(Path directory) {
        String dirName = directory.getFileName().toString().toLowerCase();
        Set<String> skipDirs = Set.of(
            "windows", "winnt", "program files", "program files (x86)",
            "programdata", "appdata", "temp", "tmp", "$recycle.bin",
            "system volume information", "recovery", "boot", "msocache",
            "inetpub", "perflogs", "microsoft", "common files",
            ".git", ".svn", ".hg", "node_modules", ".gradle", ".m2"
        );

        return skipDirs.contains(dirName) || dirName.startsWith(".");
    }

    /**
     * Get currently watched directories
     */
    public Set<Path> getWatchedDirectories() {
        return new HashSet<>(watchedDirectories);
    }

    /**
     * Check if service is currently running
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Shutdown the service
     */
    public void shutdown() {
        stopWatching();
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
