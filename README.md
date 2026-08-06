# SmartDoc - Fully Local Offline Document Search

SmartDoc is a fully local, offline desktop application for indexing and searching documents using computer vision and natural language processing. It runs completely on your local PC without requiring any cloud services, internet connection, or external APIs.

## Features

### Core Functionality
- **Local Document Indexing**: Scans local drives for documents (.jpg, .png, .pdf)
- **Image Similarity Search**: Uses ORB feature extraction and BFMatcher for visual similarity
- **OCR Text Extraction**: Extracts text from images using Tesseract OCR
- **LLM Document Analysis**: Analyzes documents using local Ollama LLM (TinyLlama/Phi)
- **Real-time Monitoring**: Automatically indexes new/modified documents
- **SQLite Database**: Local storage for documents, features, and metadata

### Technical Highlights
- **CPU-only Processing**: No GPU requirements, no CNNs, no model training
- **Offline Operation**: Everything runs locally, no internet required
- **Modular Architecture**: Clean separation of concerns
- **JavaFX UI**: Modern desktop interface

## 🏗️ **Complete Application Architecture & Flow**

### Overall Architecture
SmartDoc follows a **modular, layered architecture** with clear separation of concerns:

```
SmartDoc Application
├── UI Layer (JavaFX)
├── Processing Layer (Scanner, Vision, OCR, NLP)
├── Search Layer (Similarity Engine)
├── Data Layer (SQLite Database)
└── Watcher Layer (Real-time Monitoring)
```

## 🔄 **Detailed Application Flow**

### Phase 1: Application Startup

**1. Main Entry Point**
```java
// SmartDocApplication.java - Lines 58-61
public static void main(String[] args) {
    launch(args); // Starts JavaFX application
}
```

**2. JavaFX Application Lifecycle**
```java
// SmartDocApplication.java - Lines 20-50
@Override
public void start(Stage primaryStage) {
    // Load FXML UI definition
    FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
    Parent root = loader.load();

    // Setup scene and show window
    Scene scene = new Scene(root, 1200, 800);
    primaryStage.setTitle("SmartDoc - Local Document Search");
    primaryStage.show();
}
```

### Phase 2: Component Initialization

**3. Controller Initialization**
```java
// SmartDocController.java - Lines 65-74
public void initialize(URL location, ResourceBundle resources) {
    try {
        initializeComponents(); // Initialize all backend components
        setupUI();            // Setup JavaFX UI bindings
    } catch (Exception e) {
        showError("Initialization Error", e.getMessage());
    }
}
```

**4. Backend Component Initialization**
```java
// SmartDocController.java - Lines 76-99
private void initializeComponents() throws Exception {
    // Database Layer
    DatabaseManager dbManager = DatabaseManager.getInstance();
    documentDAO = new DocumentDAO(dbManager.getConnection());
    featureDAO = new FeatureDAO(dbManager.getConnection());

    // Processing Components
    featureExtractor = new ORBFeatureExtractor();     // OpenCV ORB features
    ocrProcessor = new OCRProcessor();                // Tesseract OCR
    llmProcessor = new LLMProcessor();                // Ollama LLM
    searchEngine = new SimilaritySearchEngine();       // BFMatcher search

    // File System Components
    documentScanner = new DocumentScanner();           // Directory scanning
    folderWatcher = new FolderWatcherService();        // Real-time monitoring

    // UI Data Models
    folderPaths = FXCollections.observableArrayList();
    searchResults = FXCollections.observableArrayList();
}
```

## 📁 **Document Processing Pipeline**

### Phase 3: Document Discovery & Scanning

**5. Folder Scanning Trigger**
```java
// SmartDocController.java - Lines 116-151
@FXML
private void scanFolders() {
    List<Path> directories = folderPaths.stream().map(Paths::get).toList();

    // Start asynchronous scanning
    CompletableFuture<Void> scanFuture = documentScanner.scanDirectories(
        directories,
        this::processDocument,  // Callback for each found document
        this::updateStatus      // Progress callback
    );
}
```

**6. Directory Traversal & File Discovery**
```java
// DocumentScanner.java - Lines 41-90
public CompletableFuture<Void> scanDirectories(...) {
    return CompletableFuture.runAsync(() -> {
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (isSupportedDocument(file)) {  // Check .jpg, .png, .pdf
                    Document doc = createDocumentFromFile(file, attrs);
                    onDocumentFound.accept(doc);  // Send to processing
                }
                return FileVisitResult.CONTINUE;
            }
        });
    });
}
```

### Phase 4: Document Processing

**7. Document Metadata Storage**
```java
// SmartDocController.java - Lines 276-297
private void processDocument(Document document) {
    // Save basic document info to database
    long documentId = documentDAO.insertDocument(document);

    // Process content asynchronously
    CompletableFuture.runAsync(() -> {
        processDocumentContent(documentId, document);
    });
}
```

**8. Content Processing Pipeline**
```java
// SmartDocController.java - Lines 299-339
private void processDocumentContent(long documentId, Document document) {
    Path filePath = document.getFilePath();

    // Step 1: OCR Processing
    String ocrText = ocrProcessor.extractText(filePath);
    ocrText = OCRProcessor.cleanExtractedText(ocrText);

    // Step 2: LLM Analysis (if OCR successful)
    if (!ocrText.isEmpty()) {
        LLMProcessor.DocumentAnalysisResult analysis = llmProcessor.analyzeDocument(ocrText);
        documentDAO.updateDocumentMetadata(documentId, analysis.documentType, analysis.summary);
    }

    // Step 3: Feature Extraction
    ORBFeatureExtractor.FeatureResult features = featureExtractor.extractFeatures(filePath);
    if (features.hasFeatures()) {
        featureDAO.insertFeatures(documentId, features.descriptors, features.keypoints);
    }
}
```

## 🔍 **Search & Similarity Matching**

### Phase 5: Similarity Search

**9. Search Initiation**
```java
// SmartDocController.java - Lines 214-251
@FXML
private void searchSimilar() {
    // Extract features from query image asynchronously
    CompletableFuture.supplyAsync(() -> {
        return searchEngine.searchSimilarDocuments(selectedQueryImage);
    }).thenAccept(results -> {
        Platform.runLater(() -> displaySearchResults(results));
    });
}
```

**10. Feature Extraction & Comparison**
```java
// SimilaritySearchEngine.java - Lines 47-85
public List<SearchResult> searchSimilarDocuments(Path queryImagePath) {
    // Extract ORB features from query image
    ORBFeatureExtractor.FeatureResult queryFeatures = featureExtractor.extractFeatures(queryImagePath);

    // Get all stored document features from database
    List<FeatureDAO.DocumentFeatures> storedFeatures = featureDAO.getAllFeatures();

    // Compare with each stored document using BFMatcher
    for (FeatureDAO.DocumentFeatures docFeatures : storedFeatures) {
        double similarity = calculateSimilarity(queryFeatures.descriptors, docFeatures.descriptors);
        // Add to results if similarity > threshold
    }
}
```

## 📊 **Code Responsibility Matrix**

### 🎯 Entry Point & Application Lifecycle
| File | Lines | Responsibility |
|------|-------|----------------|
| `SmartDocApplication.java` | 58-61 | JavaFX application entry point |
| `SmartDocApplication.java` | 20-50 | Window setup, FXML loading, scene creation |

### 🖥️ User Interface & Control
| File | Lines | Responsibility |
|------|-------|----------------|
| `SmartDocController.java` | 65-99 | UI initialization, component orchestration |
| `SmartDocController.java` | 116-151 | Folder scanning UI handler |
| `SmartDocController.java` | 214-251 | Similarity search UI handler |
| `SmartDocController.java` | 276-339 | Document processing coordination |
| `main.fxml` | - | JavaFX UI layout definition |

### 📁 Document Discovery & Scanning
| File | Lines | Responsibility |
|------|-------|----------------|
| `DocumentScanner.java` | 41-90 | Directory traversal, file filtering |
| `DocumentScanner.java` | 55-75 | Document metadata extraction |
| `FolderWatcherService.java` | 45-90 | Real-time file system monitoring |
| `FolderWatcherService.java` | 95-130 | Change detection and notification |

### 🔬 Feature Extraction & Computer Vision
| File | Lines | Responsibility |
|------|-------|----------------|
| `ORBFeatureExtractor.java` | 18-33 | OpenCV ORB detector initialization |
| `ORBFeatureExtractor.java` | 41-55 | Feature extraction from images |
| `SimilaritySearchEngine.java` | 47-85 | BFMatcher similarity calculation |
| `SimilaritySearchEngine.java` | 70-95 | Hamming distance computation |

### 📝 Text Processing & AI Analysis
| File | Lines | Responsibility |
|------|-------|----------------|
| `OCRProcessor.java` | 25-45 | Tesseract OCR initialization |
| `OCRProcessor.java` | 47-65 | Text extraction from images |
| `LLMProcessor.java` | 35-55 | Ollama HTTP API communication |
| `LLMProcessor.java` | 75-110 | Document analysis and JSON parsing |

### 💾 Data Persistence
| File | Lines | Responsibility |
|------|-------|----------------|
| `DatabaseManager.java` | 20-45 | SQLite connection management |
| `DatabaseManager.java` | 25-35 | Database schema initialization |
| `DocumentDAO.java` | 25-60 | Document CRUD operations |
| `FeatureDAO.java` | 20-45 | ORB feature storage/retrieval |

### 📋 Data Models
| File | Lines | Responsibility |
|------|-------|----------------|
| `Document.java` | - | Document metadata model |
| `ExtractedField.java` | - | LLM-extracted field model |

## 🔄 **Data Flow Diagram**

```
User Action → UI Controller → Processing Pipeline → Database
     ↓              ↓              ↓              ↓
  Click Scan → scanFolders() → DocumentScanner → DocumentDAO
     ↓              ↓              ↓              ↓
Select Image → searchSimilar() → SimilaritySearch → FeatureDAO
     ↓              ↓              ↓              ↓
View Results → displayResults() → Search Results → UI Update
```

## 🔧 **Key Processing Steps**

### Document Indexing Flow:
1. **Discovery**: `DocumentScanner` finds files → `SmartDocController.processDocument()`
2. **Metadata**: Basic file info stored in `documents` table → `DocumentDAO.insertDocument()`
3. **OCR**: `OCRProcessor` extracts text using Tesseract → `ocrProcessor.extractText()`
4. **Analysis**: `LLMProcessor` analyzes text via Ollama API → `llmProcessor.analyzeDocument()`
5. **Features**: `ORBFeatureExtractor` creates visual fingerprints → `featureExtractor.extractFeatures()`
6. **Storage**: Features stored in `orb_features` table → `FeatureDAO.insertFeatures()`

### Search Flow:
1. **Input**: User selects query image → `SmartDocController.searchSimilar()`
2. **Features**: Extract ORB features from query image → `SimilaritySearchEngine.searchSimilarDocuments()`
3. **Comparison**: Compare against all stored document features → BFMatcher with Hamming distance
4. **Matching**: Filter and rank by similarity score → `calculateSimilarity()`
5. **Display**: Show top matches with metadata → `displaySearchResults()`

## 🎯 **Component Dependencies**

- **UI Layer** (`SmartDocController`) → Depends on all processing components
- **Processing Components** → Depend on Database and external libraries (OpenCV, Tesseract, Ollama)
- **Database Layer** → Independent, provides data access to all components
- **File System Components** → Depend on Java NIO and database for persistence

## 🚀 **Asynchronous Processing**

SmartDoc uses `CompletableFuture` for non-blocking operations:
- **Scanning**: `DocumentScanner.scanDirectories()` runs in background thread
- **Processing**: Each document processed asynchronously in `processDocumentContent()`
- **Search**: Similarity matching runs in background via `CompletableFuture.supplyAsync()`
- **UI Updates**: Always marshalled back to JavaFX Application Thread using `Platform.runLater()`

This ensures the UI remains responsive during intensive CPU operations while maintaining thread safety.

## 📊 **Database Schema**

```sql
-- Documents table: Basic file metadata
CREATE TABLE documents (
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

-- ORB Features table: Visual fingerprints
CREATE TABLE orb_features (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    document_id INTEGER NOT NULL,
    feature_data BLOB NOT NULL,
    keypoints_count INTEGER NOT NULL,
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
);

-- Document tags: Semantic categorization
CREATE TABLE document_tags (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    document_id INTEGER NOT NULL,
    tag TEXT NOT NULL,
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
    UNIQUE(document_id, tag)
);

-- Extracted fields: Key-value pairs from documents
CREATE TABLE extracted_fields (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    document_id INTEGER NOT NULL,
    field_name TEXT NOT NULL,
    field_value TEXT,
    confidence REAL,
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
);
```

## 🏃 **Performance Characteristics**

- **CPU Intensive**: ORB feature extraction and similarity matching
- **Memory Efficient**: Processes one document at a time
- **Scalable**: SQLite can handle thousands of documents
- **Responsive UI**: Asynchronous processing prevents UI blocking
- **Incremental**: Only processes new/changed files

## 🔧 **Error Handling & Resilience**

- **OCR Failures**: Gracefully handled, continues with other processing
- **LLM Unavailable**: Falls back to basic document type detection
- **Feature Extraction Errors**: Logs warnings but doesn't stop processing
- **Database Issues**: Application fails fast with clear error messages
- **UI Thread Safety**: All UI updates marshalled to JavaFX Application Thread

## Prerequisites

### Required Software
1. **Java 17+**: JDK with JavaFX support
2. **Tesseract OCR**: Offline OCR engine
   - Download from: https://github.com/UB-Mannheim/tesseract/wiki
   - Install to default location (C:\Program Files\Tesseract-OCR\)
3. **Ollama**: Local LLM server
   - Download from: https://ollama.ai/
   - Install and run model: `ollama pull tinyllama` or `ollama pull phi`

### Dependencies (Auto-downloaded via Maven)
- OpenCV 4.5.1 (ORB feature extraction)
- Tesseract Java wrapper (Tess4J)
- SQLite JDBC
- JavaFX 17
- Jackson (JSON processing)
- OkHttp (HTTP client)

## Installation & Setup

### 1. Build Project
```bash
cd SmartDoc
mvn clean package  # Creates smartdoc-1.0.0.jar with all dependencies
```

### 2. Install Optional Dependencies
- **Tesseract OCR**: Install to default location for OCR functionality
- **Ollama**: Install and download models for AI document analysis
 - **Optional OCR Engines**:
     - Azure Computer Vision Read: set environment `AZURE_VISION_ENDPOINT` and `AZURE_VISION_KEY`
     - PaddleOCR microservice: run local FastAPI at `http://localhost:8000` and set `PADDLE_OCR_URL`

### 3. Run Application
```bash
# Option 1: Using Maven (requires all dependencies in classpath)
mvn javafx:run

# Option 2: Using shaded JAR (recommended)
java --module-path "C:\Users\SHAIK AKRAM\Downloads\javafx-sdk-17.0.17\lib" --add-modules javafx.controls,javafx.fxml -jar target\smartdoc-1.0.0.jar
```

### 4. Configure OCR Engine
- In the UI left panel, select the desired OCR engine under "OCR Engine":
    - "Local (Tesseract)" (offline, fast)
    - "Azure Read" (cloud, high accuracy)
    - "Paddle OCR" (local microservice, high accuracy)
    - "Google Vision" (cloud, high accuracy)

- Environment variables:
    - `TESSDATA_PREFIX` → path to tessdata (macOS: `/opt/homebrew/share/tessdata`)
    - `AZURE_VISION_ENDPOINT` → e.g. `https://<resource-name>.cognitiveservices.azure.com/`
    - `AZURE_VISION_KEY` → Azure key
    - `PADDLE_OCR_URL` → e.g. `http://localhost:8000`

### PaddleOCR Microservice (Optional)
### Google Vision (Optional)
Enable REST OCR via Google Cloud Vision:

```bash
export GOOGLE_VISION_KEY=<your-api-key>
# optional custom endpoint
export GOOGLE_VISION_ENDPOINT=https://vision.googleapis.com/v1/images:annotate
```

In the app, select "Google Vision" as OCR Engine and set the key in "Tools → OCR Settings".
Run a local service for high-accuracy OCR:

```bash
cd paddle_ocr_service
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
# Install CPU PaddlePaddle wheel appropriate for your OS/arch
# macOS (Apple Silicon):
pip install paddlepaddle==2.5.2 --index-url https://pypi.tuna.tsinghua.edu.cn/simple
# Linux (CPU):
# pip install paddlepaddle==2.5.2
python app.py  # Starts on http://localhost:8000
```

Then set:

```bash
export PADDLE_OCR_URL=http://localhost:8000
```

### Azure Read (Optional)
Create an Azure Computer Vision resource and set:

```bash
export AZURE_VISION_ENDPOINT=https://<your-resource>.cognitiveservices.azure.com/
export AZURE_VISION_KEY=<your-key>
```

## Usage

### First Time Setup
1. **Add Folders**: Click folder buttons to add directories to scan
2. **Scan Documents**: Use "File" → "Scan Folders" to index existing documents
3. **Start Monitoring**: Use "File" → "Start Monitoring" for real-time indexing

### Document Search
1. **Select Query Image**: Click "Select Image" and choose a reference image
2. **Search**: Click "Search Similar" to find visually similar documents
3. **View Results**: Browse results with similarity scores, document types, and summaries

### Supported Document Types
- **Images**: .jpg, .jpeg, .png
- **PDFs**: Scanned PDFs (processed as images)

## System Requirements

- **OS**: Windows 10/11 (tested)
- **RAM**: 4GB minimum, 8GB recommended
- **Storage**: SQLite database stored in user home directory
- **CPU**: Multi-core CPU (feature extraction is CPU-intensive)

## Troubleshooting

### Common Issues

**Application won't start:**
- Ensure JavaFX SDK path is correct in the command
- Check that all required modules are added: `javafx.controls,javafx.fxml`

**"No suitable driver found for jdbc:sqlite":**
- You're using the wrong JAR. Use `target/smartdoc-1.0.0.jar` (shaded) instead of `target/original-smartdoc-1.0.0.jar`

**Tesseract not found:**
- Install Tesseract OCR to default location
- Set TESSDATA_PREFIX environment variable if needed

**Ollama connection failed:**
- Ensure Ollama is running: `ollama serve`
- Check model is downloaded: `ollama list`
- Verify endpoint: http://localhost:11434

**Out of memory:**
- Increase JVM heap: `java -Xmx4g -jar smartdoc.jar`
- Reduce ORB features in `ORBFeatureExtractor.java`

**Slow performance:**
- Feature extraction is CPU-intensive
- Consider limiting scan directories
- Close other CPU-intensive applications

### Logs
- Application logs errors and warnings to console
- Check console output for detailed error messages
- Database operations are logged for troubleshooting

## Development

### Project Structure
```
src/main/java/com/smartdoc/
├── SmartDocApplication.java    # Main JavaFX app
├── db/                         # Database layer
├── scanner/                    # Document scanning
├── vision/                     # ORB feature extraction
├── ocr/                        # Tesseract integration
├── nlp/                        # Ollama LLM integration
├── search/                     # Similarity search
├── watcher/                    # File system monitoring
├── ui/                         # JavaFX UI
└── model/                      # Data models

src/main/resources/
└── fxml/main.fxml             # UI layout
```

### Building from Source
```bash
# Compile
mvn clean compile

# Run with Maven (requires all dependencies)
mvn javafx:run

# Build JAR
mvn package

# Run with shaded JAR
java --module-path "path/to/javafx/lib" --add-modules javafx.controls,javafx.fxml -jar target/smartdoc-1.0.0.jar
```

### Adding New Features
1. Create new package in appropriate module
2. Implement functionality with proper error handling
3. Integrate with controller and UI
4. Add unit tests
5. Update documentation

## Contributing

1. Fork the repository
2. Create feature branch
3. Implement changes with tests
4. Submit pull request

## License

This project is open source. See LICENSE file for details.

## Support

For issues and questions:
- Check troubleshooting section
- Review logs for error details
- Ensure all prerequisites are installed
- Test with sample documents first

---

**Note**: This application processes documents locally and does not transmit any data externally. All processing happens on your machine using local resources only.

### Modules
- **scanner/**: Document discovery and scanning
- **vision/**: ORB feature extraction using OpenCV
- **ocr/**: Text extraction using Tesseract
- **nlp/**: LLM analysis via Ollama HTTP API
- **search/**: Similarity search using BFMatcher
- **db/**: SQLite database operations
- **watcher/**: Real-time file system monitoring
- **ui/**: JavaFX user interface

## Prerequisites

### Required Software
1. **Java 17+**: JDK with JavaFX support
2. **Tesseract OCR**: Offline OCR engine
   - Download from: https://github.com/UB-Mannheim/tesseract/wiki
   - Install to default location (C:\Program Files\Tesseract-OCR\)
3. **Ollama**: Local LLM server
   - Download from: https://ollama.ai/
   - Install TinyLlama or Phi model: `ollama pull tinyllama` or `ollama pull phi`

### Dependencies (Auto-downloaded via Maven)
- OpenCV 4.8.0
- Tesseract Java wrapper (Tess4J)
- SQLite JDBC
- JavaFX 17
- Jackson (JSON processing)
- OkHttp (HTTP client)

## Installation & Setup

### 1. Clone/Build Project
```bash
cd SmartDoc
mvn clean compile
```

### 2. Install Dependencies
- Install Tesseract OCR to default location
- Install Ollama and pull a model:
  ```bash
  ollama pull tinyllama  # or phi
  ```

### 3. Run Application
```bash
mvn javafx:run
```
Or build executable JAR:
```bash
mvn package
java -jar target/smartdoc-1.0.0.jar
```

## Usage

### First Time Setup
1. **Scan Folders**: Click "File" → "Scan Folders" to index existing documents
2. **Start Monitoring**: Click "File" → "Start Monitoring" for real-time indexing

### Document Search
1. **Select Query Image**: Click "Select Image" and choose a reference image
2. **Search**: Click "Search Similar" to find visually similar documents
3. **View Results**: Browse results with similarity scores, document types, and summaries

### Supported Document Types
- **Images**: .jpg, .jpeg, .png
- **PDFs**: Scanned PDFs (processed as images)

### System Requirements
- **OS**: Windows 10/11 (tested)
- **RAM**: 4GB minimum, 8GB recommended
- **Storage**: SQLite database stored in user home directory
- **CPU**: Multi-core CPU (feature extraction is CPU-intensive)

## Technical Details

### Image Similarity Search
```java
// ORB Feature Extraction - vision/ORBFeatureExtractor.java
ORB orb = ORB.create();
orb.setMaxFeatures(500);
orb.detectAndCompute(image, new Mat(), keypoints, descriptors);

// Similarity Matching - search/SimilaritySearchEngine.java
BFMatcher matcher = BFMatcher.create(Core.NORM_HAMMING, true);
matcher.match(queryDescriptors, storedDescriptors, matches);
```

### OCR Processing
```java
// Text Extraction - ocr/OCRProcessor.java
Tesseract tesseract = new Tesseract();
String text = tesseract.doOCR(imageFile);
```

### LLM Analysis
```java
// Document Analysis - nlp/LLMProcessor.java
String prompt = buildAnalysisPrompt(ocrText);
String response = callOllamaAPI(prompt);
// Parses JSON response for document_type, fields, tags, summary
```

### Real-time Monitoring
```java
// File System Watching - watcher/FolderWatcherService.java
WatchService watchService = FileSystems.getDefault().newWatchService();
directory.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
```

## Configuration

### Database Location
- SQLite database: `~/smartdoc.db`
- Contains tables: documents, orb_features, document_tags, extracted_fields

### OCR Configuration
- Tesseract data path: Auto-detected
- Language: English (configurable)
- Page segmentation: Automatic with OSD

### LLM Configuration
- Ollama endpoint: http://localhost:11434
- Model: tinyllama (configurable)
- Timeout: 60 seconds for analysis

## Troubleshooting

### Common Issues

**OpenCV not loading:**
- Ensure OpenCV native libraries are in classpath
- Check Maven dependency resolution

**Tesseract not found:**
- Install Tesseract to default location
- Set TESSDATA_PREFIX environment variable if needed

**Ollama connection failed:**
- Ensure Ollama is running: `ollama serve`
- Check model is downloaded: `ollama list`
- Verify endpoint: http://localhost:11434

**Out of memory:**
- Increase JVM heap: `java -Xmx4g -jar smartdoc.jar`
- Reduce ORB features: Modify ORBFeatureExtractor settings

**Slow performance:**
- Feature extraction is CPU-intensive
- Consider limiting scan directories
- Close other CPU-intensive applications

### Logs
- Application logs errors and warnings
- Check console output for detailed error messages
- Database operations are logged for troubleshooting

## Development

### Project Structure
```
src/main/java/com/smartdoc/
├── SmartDocApplication.java    # Main JavaFX app
├── db/                         # Database layer
├── scanner/                    # Document scanning
├── vision/                     # ORB feature extraction
├── ocr/                        # Tesseract integration
├── nlp/                        # Ollama LLM integration
├── search/                     # Similarity search
├── watcher/                    # File system monitoring
├── ui/                         # JavaFX UI
└── model/                      # Data models
```

### Building from Source
```bash
# Compile
mvn clean compile

# Run tests
mvn test

# Build JAR
mvn package

# Run with JavaFX
mvn javafx:run
```

### Adding New Features
1. Create new package in appropriate module
2. Implement functionality with proper error handling
3. Integrate with controller and UI
4. Add unit tests
5. Update documentation

## License

This project is open source. See LICENSE file for details.

## Contributing

1. Fork the repository
2. Create feature branch
3. Implement changes with tests
4. Submit pull request

## Support

For issues and questions:
- Check troubleshooting section
- Review logs for error details
- Ensure all prerequisites are installed
- Test with sample documents first

---

**Note**: This application processes documents locally and does not transmit any data externally. All processing happens on your machine using local resources only.
