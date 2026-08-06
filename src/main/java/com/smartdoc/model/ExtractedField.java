package com.smartdoc.model;

/**
 * Represents an extracted field from a document
 */
public class ExtractedField {
    private String fieldName;
    private String fieldValue;
    private double confidence;

    public ExtractedField() {}

    public ExtractedField(String fieldName, String fieldValue, double confidence) {
        this.fieldName = fieldName;
        this.fieldValue = fieldValue;
        this.confidence = confidence;
    }

    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }

    public String getFieldValue() { return fieldValue; }
    public void setFieldValue(String fieldValue) { this.fieldValue = fieldValue; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    @Override
    public String toString() {
        return "ExtractedField{name='%s', value='%s', confidence=%.2f}".formatted(
            fieldName, fieldValue, confidence);
    }
}
