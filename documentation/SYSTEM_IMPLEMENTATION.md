# SYSTEM IMPLEMENTATION

This section documents how SmartDoc is implemented in terms of technology stack, runtime behavior, and practical code-level flow.

## 6.1 LIBRARIES

The SmartDoc application is implemented as a Java 21 desktop system using JavaFX for UI, SQLite for storage, OpenCV for visual features, and Tesseract for OCR.

| Library | Version | Purpose in SmartDoc |
| --- | --- | --- |
| JavaFX Controls | 21.0.5 | Desktop UI widgets, windows, and scene components |
| JavaFX FXML | 21.0.5 | XML-based view layout loading from `main.fxml` |
| OpenCV (OpenPnP bundle) | 4.9.0-0 | ORB feature extraction and image similarity matching |
| Tess4J (Tesseract binding) | 4.5.4 | OCR text extraction from scanned/image documents |
| SQLite JDBC | 3.42.0.0 | Embedded database connectivity and local persistence |
| OkHttp | 4.12.0 | HTTP communication (used for local LLM/Ollama integration) |
| Jackson Databind | 2.15.2 | JSON parsing and object mapping |
| Commons IO | 2.11.0 | Utility support for file and stream operations |
| SLF4J API | 2.0.7 | Logging abstraction layer |
| Logback Classic | 1.4.11 | Logging backend implementation |
| JUnit Jupiter | 5.9.2 | Unit and integration testing support |

Additional build/runtime tooling:

- Maven Compiler Plugin: compiles project with Java release 21.
- JavaFX Maven Plugin: runs JavaFX app through Maven.
- Maven Shade Plugin: creates executable fat JAR.
- OpenRewrite Plugin: supports Java migration/modernization recipes.

## 6.2 SAMPLE CODE

The following examples are extracted from the current SmartDoc implementation to illustrate the system workflow.

### A) JavaFX Application Startup

```java
@Override
public void start(Stage primaryStage) {
    FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
    Parent root = loader.load();

    SmartDocController controller = loader.getController();
    Scene scene = new Scene(root, 1200, 800);

    primaryStage.setTitle("SmartDoc - Local Document Search");
    primaryStage.setScene(scene);
    primaryStage.setMinWidth(1000);
    primaryStage.setMinHeight(700);

    controller.setPrimaryStage(primaryStage);
    primaryStage.show();
}
```

What it implements:

- Loads the UI layout from FXML.
- Binds controller logic to the stage.
- Initializes the main desktop window and renders the app.

### B) Database Initialization (SQLite)

```java
private void initializeDatabase() throws SQLException {
    this.dbPath = Path.of(System.getProperty("user.home"), "smartdoc.db");
    String url = "jdbc:sqlite:" + dbPath.toString();

    connection = DriverManager.getConnection(url);
    createTables();
}
```

What it implements:

- Creates/opens a local database file in the user home directory.
- Establishes JDBC connection.
- Initializes required tables/indexes for document metadata and features.

### C) OCR Extraction Pipeline

```java
public String extractText(Path imagePath) {
    if (!tesseractAvailable || tesseract == null) {
        return "";
    }

    BufferedImage original = ImageIO.read(new File(imagePath.toString()));
    BufferedImage preprocessed = preprocessForOCR(original);
    String result = tesseract.doOCR(preprocessed);

    return result != null ? result : "";
}
```

What it implements:

- Validates OCR engine availability.
- Reads and preprocesses input image.
- Runs OCR using Tesseract and returns extracted text safely.

### D) Similarity Search (Feature + Ranking)

```java
public List<SearchResult> searchSimilarDocuments(Path queryImagePath) throws Exception {
    ORBFeatureExtractor.FeatureResult queryFeatures = featureExtractor.extractFeatures(queryImagePath);
    List<FeatureDAO.DocumentFeatures> storedFeatures = featureDAO.getAllFeatures();

    List<SearchResult> strongResults = new ArrayList<>();
    List<SearchResult> relatedResults = new ArrayList<>();

    for (FeatureDAO.DocumentFeatures docFeatures : storedFeatures) {
        double similarity = calculateSimilarity(queryFeatures.descriptors, docFeatures.descriptors);
        if (similarity >= MIN_RELATED_SIMILARITY_THRESHOLD) {
            Document document = documentDAO.getDocumentByPath(Path.of(docFeatures.filePath));
            if (document != null) {
                SearchResult result = new SearchResult(document, similarity);
                if (similarity >= MIN_SIMILARITY_THRESHOLD) {
                    strongResults.add(result);
                } else {
                    relatedResults.add(result);
                }
            }
        }
    }

    strongResults.sort((a, b) -> Double.compare(b.similarity, a.similarity));
    relatedResults.sort((a, b) -> Double.compare(b.similarity, a.similarity));
    return strongResults;
}
```

What it implements:

- Extracts ORB features from the query image.
- Compares query descriptors with indexed descriptors.
- Applies threshold-based ranking and sorting for relevance.

## 6.3 SUMMARY

The SmartDoc implementation follows a modular, offline-first architecture where each subsystem has a clear responsibility:

- UI Layer (JavaFX): user interaction, document browsing, and workflow control.
- Data Layer (SQLite + DAO classes): persistent metadata, extracted fields, and feature vectors.
- OCR Layer (Tesseract via Tess4J): text extraction from document images.
- Vision/Search Layer (OpenCV ORB + matcher): image similarity and ranked retrieval.
- Service Layer (watchers/processors/controllers): background monitoring, indexing, and orchestration.

Implementation outcomes:

- Fully local execution with no mandatory cloud dependency.
- Fast retrieval through precomputed visual features and DB indexing.
- Robust document understanding by combining OCR and visual similarity signals.
- Extensible architecture where OCR engines, ranking logic, and UI behavior can be improved independently.
