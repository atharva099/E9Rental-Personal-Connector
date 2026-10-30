package com.e9rental.model;
import java.time.Instant;
import java.util.List;
public record DriveOutput(String schemaVersion,String dataMode,String coverage,Instant generatedAt,String scope,
        int requestedFileLimit,int fileCount,int extractedCount,boolean incompleteSearch,List<Document> documents) {
    public record Document(SourceRecord source,String category,String classificationReason,String extractionStatus,
        String extractionReason,Citation citation) {}
    public record Citation(String citationId,String documentId,String sourceVersion,String sourceType,
        String title,String locator,String sourceUrl,Instant retrievedAt) {}
}
