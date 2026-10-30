package com.e9rental.model;
import java.time.*;
import java.util.*;
public record GmailOutput(String schemaVersion, String dataMode, Instant generatedAt, LocalDate referenceDate,
        String scope, String tenantId, String connectorAccountId, int threadCount, int messageCount, List<MessageResult> messages,
        String coverage, Integer requestedThreadLimit) {
    public record MessageResult(SourceRecord source, String classification, List<String> classificationReasons,
        boolean inbound, boolean unansweredFiveDays, List<Attachment> attachments, Citation citation) {}
    public record Attachment(String filename, String mimeType, long size, String attachmentId,
        boolean businessCritical, List<String> reasons) {}
    public record Citation(String citationId, String documentId, String sourceVersion, String sourceType,
        String title, String locator, String sourceUrl, Instant retrievedAt) {}
}
