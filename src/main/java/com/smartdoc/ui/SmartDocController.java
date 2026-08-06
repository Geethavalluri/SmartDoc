package com.smartdoc.ui;

import com.smartdoc.db.DatabaseManager;
import com.smartdoc.db.DocumentDAO;
import com.smartdoc.db.FeatureDAO;
import com.smartdoc.db.OcrIndexDAO;
import com.smartdoc.model.Document;
import com.smartdoc.ocr.OCRProcessor;
import com.smartdoc.ocr.OcrService;
import com.smartdoc.ocr.LocalTesseractOcrService;
import com.smartdoc.ocr.AzureReadOcrService;
import com.smartdoc.ocr.GoogleVisionOcrService;
import com.smartdoc.scanner.DocumentScanner;
import com.smartdoc.search.SimilaritySearchEngine;
import com.smartdoc.nlp.LLMProcessor;
import com.smartdoc.vision.ORBFeatureExtractor;
import com.smartdoc.watcher.FolderWatcherService;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.GridPane;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
// Removed unused Image/ImageView imports
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Main Controller for SmartDoc JavaFX UI
 */
public class SmartDocController implements Initializable {
    private static final Logger logger = Logger.getLogger(SmartDocController.class.getName());

    @FXML private ListView<String> folderListView;
    @FXML private ListView<SearchResultItem> resultsListView;
    @FXML private ListView<DocumentItem> documentsListView;
    @FXML private TextArea statusTextArea;
    @FXML private TextArea summaryTextArea;
    @FXML private TextField filePathTextField;
    @FXML private Label selectedImageLabel;
    @FXML private Label docTypeLabel;
    @FXML private Label similarityLabel;
    @FXML private ImageView matchedImageView;
    @FXML private ProgressBar progressBar;
    @FXML private Label progressLabel;
    @FXML private ComboBox<String> ocrEngineComboBox;
    @FXML private TabPane centerTabPane;
    @FXML private Tab developerTab;
    @FXML private TableView<ModelMetric> modelMetricsTable;
    @FXML private TableColumn<ModelMetric, String> modelNameColumn;
    @FXML private TableColumn<ModelMetric, Number> accuracyColumn;
    @FXML private TableColumn<ModelMetric, Number> latencyColumn;
    @FXML private TableColumn<ModelMetric, Number> successRateColumn;
    @FXML private TableColumn<ModelMetric, Number> retrievalColumn;
    @FXML private BarChart<String, Number> modelPerformanceChart;
    @FXML private CategoryAxis modelChartXAxis;
    @FXML private NumberAxis modelChartYAxis;

    private Stage primaryStage;
    private DocumentScanner documentScanner;
    private FolderWatcherService folderWatcher;
    private SimilaritySearchEngine searchEngine;
    private OcrService ocrService;
    private LLMProcessor llmProcessor;
    private ORBFeatureExtractor featureExtractor;
    private DocumentDAO documentDAO;
    private FeatureDAO featureDAO;
    private OcrIndexDAO ocrIndexDAO;

    private Path selectedQueryImage;
    private ObservableList<String> folderPaths;
    private ObservableList<SearchResultItem> searchResults;
    private ObservableList<DocumentItem> allDocuments;
    private final ObservableList<ModelMetric> modelMetrics = FXCollections.observableArrayList();
    private final Map<String, ModelPerformanceStats> modelPerformanceByModel = new LinkedHashMap<>();
    private volatile boolean searchInProgress;
    private boolean developerModeEnabled;
    private String activeOcrModelName = "Local (Tesseract)";

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        try {
            initializeComponents();
            setupUI();
            logger.info("SmartDoc controller initialized successfully");
        } catch (Exception e) {
            logger.severe("Failed to initialize SmartDoc controller: " + e.getMessage());
            showError("Initialization Error", "Failed to initialize application: " + e.getMessage());
        }
    }

    private void initializeComponents() throws Exception {
        // Initialize database and DAOs
        DatabaseManager dbManager = DatabaseManager.getInstance();
        documentDAO = new DocumentDAO(dbManager.getConnection());
        featureDAO = new FeatureDAO(dbManager.getConnection());
        ocrIndexDAO = new OcrIndexDAO(dbManager.getConnection());
        try { ocrIndexDAO.ensureSchema(); } catch (Exception ignored) {}

        // Initialize processing components
        featureExtractor = new ORBFeatureExtractor();
        ocrService = new LocalTesseractOcrService();
        activeOcrModelName = ocrService.name();
        llmProcessor = new LLMProcessor();
        searchEngine = new SimilaritySearchEngine();

        // Initialize scanner and watcher
        documentScanner = new DocumentScanner();
        folderWatcher = new FolderWatcherService();

        // Initialize default folders
        folderPaths = FXCollections.observableArrayList();
        folderPaths.addAll(DocumentScanner.getDefaultScanDirectories().stream()
            .map(Path::toString)
            .toList());

        searchResults = FXCollections.observableArrayList();
        allDocuments = FXCollections.observableArrayList();
    }

    private void setupUI() {
        folderListView.setItems(folderPaths);
        resultsListView.setItems(searchResults);
        configureResultsListCellFactory();
        if (documentsListView != null) {
            documentsListView.setItems(allDocuments);
        }

        // Setup result selection listener
        resultsListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                displayDocumentDetails(newVal);
            }
        });

        if (documentsListView != null) {
            documentsListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
                if (newVal != null) {
                    displayDocumentDetails(newVal);
                }
            });
        }

        // OCR Engine selection ComboBox
        if (ocrEngineComboBox != null) {
            ocrEngineComboBox.setItems(FXCollections.observableArrayList(
                "Local (Tesseract)", "Azure Read", "Google Vision"));
            ocrEngineComboBox.getSelectionModel().selectFirst();
            ocrEngineComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
                switch (newV) {
                    case "Azure Read" -> ocrService = new AzureReadOcrService();
                    case "Google Vision" -> ocrService = new GoogleVisionOcrService();
                    default -> ocrService = new LocalTesseractOcrService();
                }
                activeOcrModelName = ocrService.name();
                ensureModelTracked(activeOcrModelName);
                updateStatus("OCR Engine → " + ocrService.name());
            });
        }

        setupDeveloperPerformanceDashboard();

        updateStatus("Ready - Select folders to scan or choose an image to search");
    }

    private void setupDeveloperPerformanceDashboard() {
        if (developerTab == null || modelMetricsTable == null || modelPerformanceChart == null) {
            return;
        }

        developerTab.setDisable(true);

        modelNameColumn.setCellValueFactory(v -> v.getValue().modelProperty());
        accuracyColumn.setCellValueFactory(v -> v.getValue().accuracyProperty());
        latencyColumn.setCellValueFactory(v -> v.getValue().latencyMsProperty());
        successRateColumn.setCellValueFactory(v -> v.getValue().successRateProperty());
        retrievalColumn.setCellValueFactory(v -> v.getValue().retrievalAt10Property());

        modelMetricsTable.setItems(modelMetrics);
        ensureModelTracked("Local (Tesseract)");
        ensureModelTracked("Azure Read");
        ensureModelTracked("Google Vision");
        refreshModelMetricsFromTracking();
        renderPerformanceChart();
    }

    @FXML
    private void openDeveloperPerformance() {
        String expectedPassword = Optional.ofNullable(System.getenv("SMARTDOC_DEV_PASSWORD"))
            .filter(s -> !s.isBlank())
            .orElse("smartdoc-dev");

        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Developer Access");
        dialog.setHeaderText("Enter developer password");
        dialog.setContentText("Password:");

        Optional<String> entered = dialog.showAndWait();
        if (entered.isEmpty()) {
            return;
        }

        if (!expectedPassword.equals(entered.get().trim())) {
            showError("Access Denied", "Invalid developer password.");
            return;
        }

        developerModeEnabled = true;
        developerTab.setDisable(false);
        centerTabPane.getSelectionModel().select(developerTab);
        refreshModelMetricsFromTracking();
        updateStatus("Developer mode enabled: Performance dashboard unlocked.");
    }

    @FXML
    private void refreshPerformanceChart() {
        if (!developerModeEnabled) {
            showError("Access Required", "Unlock developer mode from Tools > Developer Performance.");
            return;
        }
        refreshModelMetricsFromTracking();
        renderPerformanceChart();
        updateStatus("Developer performance chart refreshed.");
    }

    private void ensureModelTracked(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return;
        }
        synchronized (modelPerformanceByModel) {
            modelPerformanceByModel.computeIfAbsent(modelName, k -> new ModelPerformanceStats());
        }
    }

    private String currentModelName() {
        return activeOcrModelName == null || activeOcrModelName.isBlank()
            ? "Unknown"
            : activeOcrModelName;
    }

    private void recordOcrMeasurement(String modelName, long elapsedMs, boolean success, String cleanedText) {
        ensureModelTracked(modelName);
        boolean quality = success && cleanedText != null && cleanedText.trim().length() >= 20;
        ModelPerformanceStats stats;
        synchronized (modelPerformanceByModel) {
            stats = modelPerformanceByModel.get(modelName);
        }
        if (stats != null) {
            stats.recordOcrAttempt(elapsedMs, success, quality);
            pushPerformanceSnapshotToUi();
        }
    }

    private void recordRetrievalMeasurement(String modelName, double retrievalAt10Percent) {
        if (retrievalAt10Percent < 0) {
            return;
        }
        ensureModelTracked(modelName);
        ModelPerformanceStats stats;
        synchronized (modelPerformanceByModel) {
            stats = modelPerformanceByModel.get(modelName);
        }
        if (stats != null) {
            stats.recordRetrievalSample(retrievalAt10Percent);
            pushPerformanceSnapshotToUi();
        }
    }

    private void pushPerformanceSnapshotToUi() {
        Platform.runLater(() -> {
            refreshModelMetricsFromTracking();
            renderPerformanceChart();
        });
    }

    private void refreshModelMetricsFromTracking() {
        List<ModelMetric> snapshot = new ArrayList<>();
        synchronized (modelPerformanceByModel) {
            for (Map.Entry<String, ModelPerformanceStats> entry : modelPerformanceByModel.entrySet()) {
                String model = entry.getKey();
                ModelPerformanceStats s = entry.getValue();
                snapshot.add(new ModelMetric(
                    model,
                    s.getOcrQualityPercent(),
                    s.getAverageLatencyMs(),
                    s.getSuccessRatePercent(),
                    s.getAverageRetrievalAt10Percent()
                ));
            }
        }
        modelMetrics.setAll(snapshot);
    }

    private double computeRetrievalAt10Proxy(Path queryPath, List<SimilaritySearchEngine.SearchResult> results) {
        if (queryPath == null || results == null || results.isEmpty()) {
            return -1;
        }
        String expectedType = llmProcessor.detectDocumentTypeFromFilename(queryPath.getFileName().toString());
        if ("unknown".equalsIgnoreCase(expectedType)) {
            return -1;
        }
        int top = Math.min(10, results.size());
        int typeMatches = 0;
        for (int i = 0; i < top; i++) {
            SimilaritySearchEngine.SearchResult result = results.get(i);
            String resultType = Optional.ofNullable(result.getDocumentType()).orElse("unknown");
            if (expectedType.equalsIgnoreCase(resultType)) {
                typeMatches++;
            }
        }
        return (100.0 * typeMatches) / top;
    }

    private void renderPerformanceChart() {
        if (modelPerformanceChart == null) {
            return;
        }

        XYChart.Series<String, Number> accuracySeries = new XYChart.Series<>();
        accuracySeries.setName("OCR Accuracy %");
        XYChart.Series<String, Number> retrievalSeries = new XYChart.Series<>();
        retrievalSeries.setName("Retrieval@10 %");
        XYChart.Series<String, Number> successSeries = new XYChart.Series<>();
        successSeries.setName("Success Rate %");

        for (ModelMetric metric : modelMetrics) {
            accuracySeries.getData().add(new XYChart.Data<>(metric.getModel(), metric.getAccuracy()));
            retrievalSeries.getData().add(new XYChart.Data<>(metric.getModel(), metric.getRetrievalAt10()));
            successSeries.getData().add(new XYChart.Data<>(metric.getModel(), metric.getSuccessRate()));
        }

        modelPerformanceChart.getData().setAll(accuracySeries, retrievalSeries, successSeries);
    }

    private void configureResultsListCellFactory() {
        if (resultsListView == null) {
            return;
        }

        resultsListView.setCellFactory(list -> new ListCell<>() {
            private final Label nameLabel = new Label();
            private final Label scoreLabel = new Label();
            private final VBox textBox = new VBox(3, nameLabel, scoreLabel);
            private final Button viewButton = new Button("View");
            private final HBox row = new HBox(10, textBox, viewButton);

            {
                nameLabel.setStyle("-fx-font-weight: bold;");
                scoreLabel.setStyle("-fx-text-fill: #2E7D32;");
                viewButton.setStyle("-fx-font-size: 11px; -fx-padding: 2 8 2 8;");
            }

            @Override
            protected void updateItem(SearchResultItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }

                Path path = Path.of(item.getFilePath());
                nameLabel.setText(path.getFileName().toString());
                scoreLabel.setText("Similarity: %.1f%%".formatted(item.getSimilarity() * 100));
                viewButton.setOnAction(event -> {
                    resultsListView.getSelectionModel().select(item);
                    displayDocumentDetails(item);
                });
                setText(null);
                setGraphic(row);
            }
        });
    }

    @FXML
    private void scanFolders() {
        if (folderPaths.isEmpty()) {
            showError("No Folders", "Please add folders to scan first");
            return;
        }

        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressLabel.setText("Scanning folders...");
        updateStatus("Starting folder scan...");

        List<Path> directories = folderPaths.stream()
            .map(Paths::get)
            .toList();

        CompletableFuture<Void> scanFuture = documentScanner.scanDirectories(
            directories,
            this::processDocument,
            this::updateStatus
        );

        scanFuture.thenRun(() -> {
            Platform.runLater(() -> {
                progressBar.setProgress(0.0);
                progressLabel.setText("Scan completed");
                updateStatus("Folder scan completed");
            });
        }).exceptionally(throwable -> {
            Platform.runLater(() -> {
                progressBar.setProgress(0.0);
                progressLabel.setText("Scan failed");
                updateStatus("Scan failed: " + throwable.getMessage());
                showError("Scan Error", "Failed to scan folders: " + throwable.getMessage());
            });
            return null;
        });
    }

    @FXML
    private void startMonitoring() {
        if (folderPaths.isEmpty()) {
            showError("No Folders", "Please add folders to monitor first");
            return;
        }

        List<Path> directories = folderPaths.stream()
            .map(Paths::get)
            .toList();

        folderWatcher.startWatching(directories, this::processDocument);
        updateStatus("Started monitoring " + directories.size() + " directories");
        progressLabel.setText("Monitoring active");
    }

    @FXML
    private void stopMonitoring() {
        folderWatcher.stopWatching();
        updateStatus("Stopped folder monitoring");
        progressLabel.setText("Monitoring stopped");
    }

    @FXML
    private void addFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Folder to Scan");
        chooser.setInitialDirectory(new File(System.getProperty("user.home")));

        File selectedFolder = chooser.showDialog(primaryStage);
        if (selectedFolder != null) {
            String folderPath = selectedFolder.getAbsolutePath();
            if (!folderPaths.contains(folderPath)) {
                folderPaths.add(folderPath);
                updateStatus("Added folder: " + folderPath);
                scanAddedFolder(Paths.get(folderPath));
            } else {
                updateStatus("Folder already added: " + folderPath);
            }
        }
    }

    private void scanAddedFolder(Path folderPath) {
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressLabel.setText("Scanning added folder...");
        updateStatus("Initial scan started for: " + folderPath);

        CompletableFuture<Void> scanFuture = documentScanner.scanDirectories(
            List.of(folderPath),
            this::processDocument,
            this::updateStatus
        );

        scanFuture.thenRun(() -> Platform.runLater(() -> {
            progressBar.setProgress(0.0);
            progressLabel.setText("Initial scan completed");
            updateStatus("Initial scan completed for: " + folderPath);
        })).exceptionally(throwable -> {
            Platform.runLater(() -> {
                progressBar.setProgress(0.0);
                progressLabel.setText("Initial scan failed");
                updateStatus("Initial scan failed for " + folderPath + ": " + throwable.getMessage());
            });
            return null;
        });
    }

    @FXML
    private void removeFolder() {
        String selected = folderListView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            folderPaths.remove(selected);
            updateStatus("Removed folder: " + selected);
        }
    }

    @FXML
    private void selectQueryImage() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Query Image");
        chooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Image Files", "*.jpg", "*.jpeg", "*.png")
        );

        File selectedFile = chooser.showOpenDialog(primaryStage);
        if (selectedFile != null) {
            selectedQueryImage = selectedFile.toPath();
            selectedImageLabel.setText(selectedFile.getName());
            updateStatus("Selected query image: " + selectedFile.getName());
        }
    }

    @FXML
    private void searchSimilar() {
        if (selectedQueryImage == null) {
            showError("No Image", "Please select a query image first");
            return;
        }

        if (searchInProgress) {
            updateStatus("Search already in progress...");
            return;
        }
        searchInProgress = true;
        final String modelUsedForRun = currentModelName();

        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressLabel.setText("Searching...");
        updateStatus("Searching for similar documents...");

        logger.info("Starting async search for: " + selectedQueryImage);
        logger.info("Search engine initialized: " + (searchEngine != null));

        CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("Calling searchEngine.searchSimilarDocuments");
                if (searchEngine == null) {
                    logger.severe("Search engine is null!");
                    return new ArrayList<SimilaritySearchEngine.SearchResult>();
                }

                // Ensure the selected image folder is indexed before searching.
                // This prioritizes DB-backed feature matches over weak fallbacks.
                int newlyIndexed = ensureQueryFolderIndexed(selectedQueryImage);
                if (newlyIndexed > 0) {
                    updateStatus("Indexed " + newlyIndexed + " new files from query folder before search");
                }

                List<SimilaritySearchEngine.SearchResult> results = searchEngine.searchSimilarDocuments(selectedQueryImage);
                logger.info("Search completed, found " + results.size() + " results");
                return results;
            } catch (Exception e) {
                logger.severe("Search failed: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException(e);
            } finally {
                // Run OCR on the query image with the currently selected model so that
                // accuracy / latency / success metrics get recorded even when the document
                // was already indexed. This is the primary way per-model stats accumulate
                // when the user switches engines and clicks "Search Similar".
                try {
                    long ocrStart = System.nanoTime();
                    String ocrResult = ocrService.extractText(selectedQueryImage);
                    ocrResult = ocrResult == null ? "" : OCRProcessor.cleanExtractedText(ocrResult);
                    long ocrElapsed = (System.nanoTime() - ocrStart) / 1_000_000;
                    recordOcrMeasurement(modelUsedForRun, ocrElapsed, !ocrResult.isBlank(), ocrResult);
                    logger.info("OCR benchmark recorded for model '" + modelUsedForRun + "' on query image (" + ocrElapsed + " ms)");
                } catch (Exception ocrEx) {
                    recordOcrMeasurement(modelUsedForRun, 0L, false, "");
                    logger.warning("OCR benchmark failed for model '" + modelUsedForRun + "': " + ocrEx.getMessage());
                }
            }
        }).thenAccept(results -> {
            logger.info("Processing search results: " + results.size() + " items");
            logger.info("Processing search results: " + results.size() + " items");
            double retrievalAt10 = computeRetrievalAt10Proxy(selectedQueryImage, results);
            recordRetrievalMeasurement(modelUsedForRun, retrievalAt10);
            Platform.runLater(() -> {
                displaySearchResults(results);
                progressBar.setProgress(0.0);
                progressLabel.setText("Search completed");
                updateStatus("Found " + results.size() + " similar documents");
                searchInProgress = false;
            });
        }).exceptionally(throwable -> {
            Platform.runLater(() -> {
                progressBar.setProgress(0.0);
                progressLabel.setText("Search failed");
                updateStatus("Search failed: " + throwable.getMessage());
                showError("Search Error", "Failed to search: " + throwable.getMessage());
                searchInProgress = false;
            });
            return null;
        });
    }

    @FXML
    private void searchByOcrText() {
        if (selectedQueryImage == null) {
            showError("No Image", "Please select a query image first");
            return;
        }

        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressLabel.setText("Searching (OCR)...");
        updateStatus("Extracting text from query image and searching OCR index...");

        CompletableFuture.supplyAsync(() -> {
            try {
                long startedAt = System.nanoTime();
                String text = ocrService.extractText(selectedQueryImage);
                text = OCRProcessor.cleanExtractedText(text);
                long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
                recordOcrMeasurement(currentModelName(), elapsedMs, text != null && !text.isBlank(), text);
                if (text.isEmpty()) return List.<Long>of();

                // Build an FTS5-friendly query: OR across top tokens
                String[] rawTokens = text.toLowerCase().split("\\W+");
                java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<>();
                for (String tok : rawTokens) {
                    if (tok == null || tok.isBlank()) continue;
                    // Keep words and numbers with reasonable length
                    if (tok.length() >= 3) uniq.add(tok);
                    else if (tok.matches("\\d{2,}")) uniq.add(tok);
                    if (uniq.size() >= 20) break; // cap tokens
                }
                if (uniq.isEmpty()) return List.<Long>of();
                String query = String.join(" OR ", uniq);
                return ocrIndexDAO.search(query, 50);
            } catch (Exception e) {
                logger.severe("OCR text search failed: " + e.getMessage());
                throw new RuntimeException(e);
            }
        }).thenAccept(ids -> {
            Platform.runLater(() -> {
                try {
                    List<SearchResultItem> items = new ArrayList<>();
                    for (Long id : ids) {
                        com.smartdoc.model.Document doc = documentDAO.getDocumentById(id);
                        if (doc != null) {
                            items.add(new SearchResultItem(new SimilaritySearchEngine.SearchResult(doc, 1.0)));
                        }
                    }
                    searchResults.setAll(items);
                    progressBar.setProgress(0.0);
                    progressLabel.setText("OCR text search completed");
                    updateStatus("Found " + items.size() + " OCR text matches");
                } catch (Exception ex) {
                    showError("Search Error", ex.getMessage());
                }
            });
        }).exceptionally(th -> {
            Platform.runLater(() -> {
                progressBar.setProgress(0.0);
                progressLabel.setText("OCR text search failed");
                updateStatus("OCR text search failed: " + th.getMessage());
            });
            return null;
        });
    }

    @FXML
    private void exitApplication() {
        if (folderWatcher != null) {
            folderWatcher.shutdown();
        }
        Platform.exit();
    }

    @FXML
    private void showAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About SmartDoc");
        alert.setHeaderText("SmartDoc v1.0");
        alert.setContentText("""
                           Fully local, offline document indexing and search application.
                           
                           Features:
                           - ORB feature extraction for image similarity
                           - Tesseract OCR for text extraction
                           - Local LLM analysis via Ollama
                           - Real-time folder monitoring
                           - SQLite local database""");
        alert.showAndWait();
    }

    @FXML
    private void loadAllDocuments() {
        try {
            List<Document> docs = documentDAO.getAllDocuments();
            List<DocumentItem> items = docs.stream()
                .map(DocumentItem::new)
                .toList();
            Platform.runLater(() -> {
                allDocuments.setAll(items);
                updateStatus("Loaded " + items.size() + " documents from DB");
            });
        } catch (Exception e) {
            showError("Load Documents Error", "Failed to load documents: " + e.getMessage());
        }
    }

    private void processDocument(Document document) {
        if (document == null) {
            return;
        }

        try {
            long documentId;

            // Check if document already exists
            if (documentDAO.documentExists(document.getFilePath())) {
                // Document exists, get its ID
                Document existingDoc = documentDAO.getDocumentByPath(document.getFilePath());
                if (existingDoc != null) {
                    documentId = existingDoc.getId();
                    // Fix stale "unknown" types using filename heuristic – no OCR needed.
                    // Runs every Scan Folders call, silently upgrading all previously indexed
                    // documents so type-based similarity search works immediately.
                    String currentType = existingDoc.getDocumentType();
                    if (currentType == null || currentType.isBlank() || "unknown".equalsIgnoreCase(currentType)) {
                        String typeFromName = llmProcessor.detectDocumentTypeFromFilename(document.getFileName());
                        if (!"unknown".equals(typeFromName)) {
                            try {
                                documentDAO.updateTypeAndSummary(documentId, typeFromName, "Classified from filename");
                            } catch (Exception ignored) {}
                        }
                    }
                    Platform.runLater(() -> {
                        updateStatus("Skipped (already indexed): " + document.getFileName());
                    });
                } else {
                    // Should not happen, but skip if we can't get the document
                    logger.warning("Document exists but could not retrieve: " + document.getFilePath());
                    return;
                }
            } else {
                // Save new document to database
                documentId = documentDAO.insertDocument(document);
                Platform.runLater(() -> {
                    updateStatus("Indexed: " + document.getFileName());
                });
            }

            // Process document content in background only for genuinely unprocessed documents.
            // Already-processed docs (is_processed=1) are never re-OCR'd – this prevents
            // the continuous background scan loop and stops type overwrites.
            Document existingDoc = documentDAO.getDocumentByPath(document.getFilePath());
            if (existingDoc == null || !existingDoc.isProcessed()) {
                CompletableFuture.runAsync(() -> {
                    try {
                        processDocumentContent(documentId, document);
                    } catch (Exception e) {
                        logger.warning("Failed to process document " + document.getFilePath() + ": " + e.getMessage());
                    }
                });
            }

        } catch (Exception e) {
            logger.warning("Failed to process document: " + e.getMessage());
        }
    }

    private void processDocumentContent(long documentId, Document document) throws Exception {
        Path filePath = document.getFilePath();

        // OCR Processing
        String ocrText = "";
        long ocrStartedAt = 0L;
        String modelUsedForOcr = currentModelName();
        try {
            ocrStartedAt = System.nanoTime();
            ocrText = ocrService.extractText(filePath);
            ocrText = OCRProcessor.cleanExtractedText(ocrText);
            long elapsedMs = (System.nanoTime() - ocrStartedAt) / 1_000_000;
            recordOcrMeasurement(modelUsedForOcr, elapsedMs, !ocrText.isBlank(), ocrText);
            logger.info("OCR completed for: " + filePath);
            try {
                ocrIndexDAO.upsert(documentId, ocrText);
            } catch (Exception ex) {
                logger.warning("Failed to index OCR text: " + ex.getMessage());
            }
        } catch (Exception e) {
            long elapsedMs = ocrStartedAt == 0L ? 0L : (System.nanoTime() - ocrStartedAt) / 1_000_000;
            recordOcrMeasurement(modelUsedForOcr, elapsedMs, false, "");
            logger.warning("OCR failed for " + filePath + ": " + e.getMessage());
        }

        // LLM Analysis – always persist at minimum the heuristic-detected type so that
        // similarity search fallbacks (type+OCR) can find same-type documents even when
        // Ollama is unavailable.
        String documentType = "unknown";
        String summary = "No OCR text extracted";
        try {
            if (!ocrText.isEmpty()) {
                // Step 1 – keyword heuristic (instant, no Ollama needed)
                String detectedType = llmProcessor.detectDocumentType(ocrText);
                logger.info("Detected document type '" + detectedType + "' for: " + filePath);
                Platform.runLater(() -> updateStatus(
                    "Detected type: " + detectedType + " \u2013 " + document.getFileName()));

                // Persist heuristic type immediately so search always has a type to work with
                documentType = detectedType;
                summary = "Indexed – type: " + detectedType;
                documentDAO.updateTypeAndSummary(documentId, documentType, summary);

                // Step 2 – full LLM analysis (requires Ollama; enriches extracted fields)
                try {
                    LLMProcessor.DocumentAnalysisResult analysis = llmProcessor.analyzeDocument(ocrText);
                    documentType = analysis.documentType;
                    summary = analysis.summary;
                    documentDAO.updateTypeAndSummary(documentId, documentType, summary);
                    logger.info("LLM analysis completed for: " + filePath);
                } catch (Exception llmEx) {
                    logger.warning("LLM analysis skipped (Ollama unavailable); using heuristic type '"
                        + detectedType + "': " + llmEx.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warning("Type/LLM analysis failed for " + filePath + ": " + e.getMessage());
        }

        // Feature extraction
        boolean featureExtractionSuccess = false;
        try {
            ORBFeatureExtractor.FeatureResult features = featureExtractor.extractFeatures(filePath);
            if (features.hasFeatures()) {
                featureDAO.insertFeatures(documentId, features.descriptors, features.keypoints);
                logger.info("Feature extraction completed for: " + filePath);
                featureExtractionSuccess = true;
            }
        } catch (Exception e) {
            logger.warning("Feature extraction failed for " + filePath + ": " + e.getMessage());
        }

        // Mark document as processed if feature extraction succeeded
        if (featureExtractionSuccess) {
            try {
                // Persist discovered metadata and then mark as processed.
                documentDAO.updateTypeAndSummary(documentId, documentType, summary);
                documentDAO.markProcessed(documentId);
                logger.info("Document marked as processed: " + filePath);
            } catch (Exception e) {
                logger.warning("Failed to mark document as processed: " + e.getMessage());
            }
        }
    }

    @FXML
    private void runDiagnostics() {
        CompletableFuture.runAsync(() -> {
            try {
                // 1) OpenCV availability
                String opencvMsg;
                try {
                    nu.pattern.OpenCV.loadLocally();
                    opencvMsg = "OpenCV: available (natives loaded)";
                } catch (Throwable t) {
                    opencvMsg = "OpenCV: unavailable (" + t.getMessage() + ")";
                }

                // 2) Database stats
                DatabaseManager db = DatabaseManager.getInstance();
                updateStatus("Diagnostics → DB path: " + db.getDatabasePath());
                try (var stmt = db.getConnection().createStatement()) {
                    long docs = 0;
                    long feats = 0;
                    try (var rs = stmt.executeQuery("SELECT COUNT(*) AS c FROM documents")) {
                        if (rs.next()) docs = rs.getLong("c");
                    }
                    try (var rs = stmt.executeQuery("SELECT COUNT(*) AS c FROM orb_features")) {
                        if (rs.next()) feats = rs.getLong("c");
                    }

                    updateStatus("Diagnostics → " + opencvMsg);
                    updateStatus("Diagnostics → documents=" + docs + ", orb_features=" + feats);

                    // Show a few feature rows with keypoints
                    try (var rs = stmt.executeQuery(
                        "SELECT d.file_name, f.keypoints_count FROM orb_features f JOIN documents d ON d.id=f.document_id ORDER BY f.id DESC LIMIT 5")) {
                        int i = 0;
                        while (rs.next()) {
                            String fn = rs.getString("file_name");
                            int kp = rs.getInt("keypoints_count");
                            updateStatus("Diagnostics → feature[" + (++i) + "]: " + fn + ", keypoints=" + kp);
                        }
                        if (i == 0) {
                            updateStatus("Diagnostics → No feature rows present yet.");
                        }
                    }
                }
            } catch (Exception ex) {
                updateStatus("Diagnostics error: " + ex.getMessage());
            }
        });
    }

    @FXML
    private void openOcrSettings() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("OCR Settings");
        dialog.setHeaderText("Configure OCR endpoints/keys");

        Label azureEpL = new Label("Azure Endpoint:");
        TextField azureEp = new TextField(System.getenv("AZURE_VISION_ENDPOINT"));
        Label azureKeyL = new Label("Azure Key:");
        TextField azureKey = new TextField(System.getenv("AZURE_VISION_KEY"));

        Label googleKeyL = new Label("Google Vision Key:");
        TextField googleKey = new TextField(System.getenv("GOOGLE_VISION_KEY"));
        Label googleEpL = new Label("Google Endpoint (optional):");
        TextField googleEp = new TextField(System.getenv("GOOGLE_VISION_ENDPOINT"));

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(8);
        grid.addRow(0, azureEpL, azureEp);
        grid.addRow(1, azureKeyL, azureKey);
        grid.addRow(2, googleKeyL, googleKey);
        grid.addRow(3, googleEpL, googleEp);

        ButtonType saveBtnType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveBtnType, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn == saveBtnType) {
                // Apply to existing services if selected
                if (ocrService instanceof AzureReadOcrService az) {
                    az.configure(azureEp.getText(), azureKey.getText());
                }
                if (ocrService instanceof GoogleVisionOcrService gv) {
                    gv.configure(googleKey.getText(), googleEp.getText());
                }
                updateStatus("OCR settings updated for engine: " + ocrService.name());
            }
            return null;
        });

        dialog.showAndWait();
    }

    @FXML
    private void testOrbOnSelectedImage() {
        if (selectedQueryImage == null) {
            showError("No Image", "Please select a query image first");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                updateStatus("Testing ORB on: " + selectedQueryImage.getFileName());
                ORBFeatureExtractor.FeatureResult fr = featureExtractor.extractFeatures(selectedQueryImage);
                int kps = fr.getKeypointsCount();
                int rows = fr.descriptors.rows();
                int cols = fr.descriptors.cols();
                updateStatus("ORB Test → keypoints=" + kps + ", descriptors=" + rows + "x" + cols);

                if (kps == 0 || rows == 0) {
                    updateStatus("ORB Test → No features detected. OpenCV may be unavailable or image lacks salient features.");
                } else {
                    updateStatus("ORB Test → Feature extraction looks good.");
                }
            } catch (Exception ex) {
                updateStatus("ORB Test error: " + ex.getMessage());
            }
        });
    }

    private void displaySearchResults(List<SimilaritySearchEngine.SearchResult> results) {
        searchResults.clear();
        StringBuilder formatted = new StringBuilder();
        int rank = 1;
        for (SimilaritySearchEngine.SearchResult result : results) {
            searchResults.add(new SearchResultItem(result));
            String fileName = Path.of(result.getFilePath()).getFileName().toString();
            formatted.append(rank++)
                .append(". ")
                .append(fileName)
                .append(" - Matching Score: ")
                .append(String.format("%.2f", result.similarity))
                .append("\n");
        }

        if (!results.isEmpty()) {
            updateStatus("Top matches:\n" + formatted);
        }
    }

    private void displayDocumentDetails(SearchResultItem item) {
        docTypeLabel.setText(item.getDocumentType());
        similarityLabel.setText("%.1f%%".formatted(item.getSimilarity() * 100));
        summaryTextArea.setText(item.getSummary());
        filePathTextField.setText(item.getFilePath());
        updateMatchedImagePreview(Path.of(item.getFilePath()));
    }

    private void displayDocumentDetails(DocumentItem item) {
        String type = Optional.ofNullable(item.getDocument().getDocumentType()).orElse("unknown");
        String summary = Optional.ofNullable(item.getDocument().getSummary()).orElse("");
        docTypeLabel.setText(type);
        similarityLabel.setText("-");
        summaryTextArea.setText(summary);
        Path path = item.getDocument().getFilePath();
        filePathTextField.setText(path.toString());
        updateMatchedImagePreview(path);
    }

    private void updateMatchedImagePreview(Path path) {
        if (matchedImageView == null) {
            return;
        }
        if (path == null || !isSupportedImage(path)) {
            matchedImageView.setImage(null);
            return;
        }
        try {
            matchedImageView.setImage(new Image(path.toUri().toString(), 260, 160, true, true, true));
        } catch (Exception e) {
            matchedImageView.setImage(null);
        }
    }

    private boolean isSupportedImage(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
            name.endsWith(".webp") || name.endsWith(".bmp") || name.endsWith(".tif") ||
            name.endsWith(".tiff");
    }

    /**
     * Index all supported files in the query file's parent directory before search.
     * This makes search deterministic by relying on persisted DB features.
     */
    private int ensureQueryFolderIndexed(Path queryImagePath) {
        if (queryImagePath == null) {
            return 0;
        }
        Path parent = queryImagePath.toAbsolutePath().normalize().getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return 0;
        }

        int indexed = 0;
        try (var stream = Files.list(parent)) {
            List<Path> candidates = stream
                .filter(Files::isRegularFile)
                .filter(this::isSupportedImage)
                .map(p -> p.toAbsolutePath().normalize())
                .toList();

            updateStatus("Pre-indexing query folder: " + parent + " (" + candidates.size() + " files)");

            int processed = 0;
            for (Path file : candidates) {
                if (indexSingleFileIfNeeded(file, false)) {
                    indexed++;
                }
                processed++;
                if (processed % 25 == 0) {
                    updateStatus("Pre-index progress: " + processed + "/" + candidates.size());
                }
            }
        } catch (Exception e) {
            logger.warning("Pre-indexing query folder failed: " + e.getMessage());
        }
        return indexed;
    }

    private boolean indexSingleFileIfNeeded(Path filePath, boolean allowReprocessUnresolvedType) {
        try {
            Document existing = documentDAO.getDocumentByPath(filePath);
            boolean unresolvedType = existing != null && (
                existing.getDocumentType() == null
                    || existing.getDocumentType().isBlank()
                    || "unknown".equalsIgnoreCase(existing.getDocumentType())
                    || "image".equalsIgnoreCase(existing.getDocumentType())
            );
            boolean hasFeatures = existing != null && featureDAO.getFeatures(existing.getId()) != null;

            boolean shouldSkipExisting = existing != null
                && existing.isProcessed()
                && hasFeatures
                && (!allowReprocessUnresolvedType || !unresolvedType);
            if (shouldSkipExisting) {
                return false;
            }

            long documentId;
            Document toProcess;
            if (existing != null) {
                documentId = existing.getId();
                toProcess = existing;
            } else {
                BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
                toProcess = new Document(filePath);
                toProcess.setFileSize(attrs.size());
                toProcess.setLastModified(LocalDateTime.ofInstant(attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault()));
                toProcess.setDocumentType("unknown");
                toProcess.setSummary("");
                toProcess.setProcessed(false);
                documentId = documentDAO.insertDocument(toProcess);
            }

            processDocumentContent(documentId, toProcess);
            return true;
        } catch (Exception e) {
            logger.warning("Failed to index file " + filePath + ": " + e.getMessage());
            return false;
        }
    }

    private void updateStatus(String message) {
        Platform.runLater(() -> {
            String timestamp = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            statusTextArea.appendText("[" + timestamp + "] " + message + "\n");
        });
    }

    private void showError(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    public void setPrimaryStage(Stage primaryStage) {
        this.primaryStage = primaryStage;
    }

    /**
     * Wrapper class for search results in ListView
     */
    public static class SearchResultItem {
        private final SimilaritySearchEngine.SearchResult result;

        public SearchResultItem(SimilaritySearchEngine.SearchResult result) {
            this.result = result;
        }

        public String getFilePath() { return result.getFilePath(); }
        public String getDocumentType() { return result.getDocumentType(); }
        public String getSummary() { return result.getSummary(); }
        public double getSimilarity() { return result.similarity; }

        @Override
        public String toString() {
            return "%.1f%% - %s (%s)".formatted(
                getSimilarity() * 100,
                Path.of(getFilePath()).getFileName(),
                getDocumentType());
        }
    }

    /**
     * Wrapper class for documents in ListView
     */
    public static class DocumentItem {
        private final Document document;

        public DocumentItem(Document document) {
            this.document = document;
        }

        public Document getDocument() { return document; }

        @Override
        public String toString() {
            String type = Optional.ofNullable(document.getDocumentType()).orElse("unknown");
            return "%s (%s)".formatted(document.getFileName(), type);
        }
    }

    public static class ModelMetric {
        private final SimpleStringProperty model;
        private final SimpleDoubleProperty accuracy;
        private final SimpleDoubleProperty latencyMs;
        private final SimpleDoubleProperty successRate;
        private final SimpleDoubleProperty retrievalAt10;

        public ModelMetric(String model, double accuracy, double latencyMs, double successRate, double retrievalAt10) {
            this.model = new SimpleStringProperty(model);
            this.accuracy = new SimpleDoubleProperty(accuracy);
            this.latencyMs = new SimpleDoubleProperty(latencyMs);
            this.successRate = new SimpleDoubleProperty(successRate);
            this.retrievalAt10 = new SimpleDoubleProperty(retrievalAt10);
        }

        public String getModel() { return model.get(); }
        public double getAccuracy() { return accuracy.get(); }
        public double getLatencyMs() { return latencyMs.get(); }
        public double getSuccessRate() { return successRate.get(); }
        public double getRetrievalAt10() { return retrievalAt10.get(); }

        public SimpleStringProperty modelProperty() { return model; }
        public SimpleDoubleProperty accuracyProperty() { return accuracy; }
        public SimpleDoubleProperty latencyMsProperty() { return latencyMs; }
        public SimpleDoubleProperty successRateProperty() { return successRate; }
        public SimpleDoubleProperty retrievalAt10Property() { return retrievalAt10; }
    }

    private static class ModelPerformanceStats {
        private long ocrAttempts;
        private long ocrSuccesses;
        private long ocrQualityHits;
        private long totalOcrLatencyMs;
        private long retrievalSamples;
        private double retrievalAt10Total;

        synchronized void recordOcrAttempt(long latencyMs, boolean success, boolean quality) {
            ocrAttempts++;
            if (success) {
                ocrSuccesses++;
            }
            if (quality) {
                ocrQualityHits++;
            }
            totalOcrLatencyMs += Math.max(0, latencyMs);
        }

        synchronized void recordRetrievalSample(double retrievalAt10Percent) {
            retrievalSamples++;
            retrievalAt10Total += Math.max(0.0, retrievalAt10Percent);
        }

        synchronized double getAverageLatencyMs() {
            return ocrAttempts == 0 ? 0.0 : (double) totalOcrLatencyMs / ocrAttempts;
        }

        synchronized double getSuccessRatePercent() {
            return ocrAttempts == 0 ? 0.0 : (100.0 * ocrSuccesses) / ocrAttempts;
        }

        synchronized double getOcrQualityPercent() {
            return ocrAttempts == 0 ? 0.0 : (100.0 * ocrQualityHits) / ocrAttempts;
        }

        synchronized double getAverageRetrievalAt10Percent() {
            return retrievalSamples == 0 ? 0.0 : retrievalAt10Total / retrievalSamples;
        }
    }
}
