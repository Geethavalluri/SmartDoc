# System Result

## User Interface

The SmartDoc user interface was designed to provide a simple and efficient workflow for offline document indexing and retrieval.

The main screen contains two major panels:

1. **Document Search Panel (Left Side)**
- Allows users to add and manage scan directories.
- Supports folder selection for bulk indexing.
- Displays indexed folder paths for quick verification.

2. **Search Results Panel (Right Side)**
- Displays ranked document matches for the selected query image.
- Shows similarity percentages for each matched file.
- Provides a direct **View** action for opening matched documents.

Additional interface elements include status updates for indexing progress, scan completion messages, detected document type logs, and top-match result summaries. This design helps users understand each stage of processing without requiring technical commands.

Overall, the interface enables non-technical users to perform document search tasks through a clear click-based workflow.

## Summary

The SmartDoc system successfully integrates image feature matching, OCR-assisted text processing, and local database indexing to support fully offline document retrieval.

System testing confirms that the application:
- Indexes document images from selected directories.
- Retrieves relevant matches using similarity scores.
- Supports hybrid retrieval through visual similarity and OCR-based signals.
- Handles exact duplicate detection using SHA-256 hashing.
- Provides fallback similarity support using aHash when OpenCV-based processing is limited.

The experimental outcomes demonstrate that SmartDoc is stable and practical for local document management scenarios, especially where internet-independent search is required. The system delivers consistent retrieval performance with an accessible interface, making it suitable for academic and real-world desktop use.

## OCR Model Performance Matrix

SmartDoc supports multiple OCR providers, including Azure OCR and Google Vision OCR. Since the application allows model selection, model-wise benchmarking can be included in system results.

The performance matrix can be prepared using the same validation dataset for all models.

| Model | Avg OCR Accuracy (%) | Avg Processing Time (ms/image) | Success Rate (%) | Notes |
|------|-----------------------|--------------------------------|------------------|-------|
| Azure OCR |  |  |  | Good for structured IDs and printed text |
| Google Vision OCR |  |  |  | Strong on varied layouts |
| Local Tesseract OCR |  |  |  | Fully offline baseline |

Recommended metrics:
- OCR Accuracy: Character-level or word-level match against expected text.
- Processing Time: End-to-end OCR latency per image.
- Success Rate: Percentage of images processed without OCR failure.
- Retrieval Impact: Top-k search relevance after OCR indexing.

This matrix should be included in the System Result section to justify model selection using measurable outcomes.

## Model Performance Graph

Yes, the model performance graph can be included in the same UI.

Suggested graph types:
1. Bar chart for Accuracy and Success Rate by model.
2. Line or bar chart for Processing Time by model.

Suggested placement in UI:
- Add a new tab named "Model Analytics" next to Search Results.
- Show chart + summary table in that tab.
- Add a "Refresh Metrics" action to regenerate results after benchmark runs.

For documentation, include:
- Figure: Model performance comparison chart.
- Table: Numerical matrix values used to build the chart.
- One-paragraph interpretation of why the selected model performs best for SmartDoc use cases.
