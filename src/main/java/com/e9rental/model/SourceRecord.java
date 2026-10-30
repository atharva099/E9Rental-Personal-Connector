package com.e9rental.model;
import java.time.Instant;
import java.util.*;
public record SourceRecord(String documentId, String tenantId, String connectorAccountId, String sourceType,
        String externalId, String parentExternalId, String title, String contentText, String mimeType,
        String contentHash, String sourceVersion, Instant sourceCreatedAt, Instant sourceUpdatedAt,
        Instant occurredAt, Instant ingestedAt, Instant lastSyncedAt, Instant deletedAt, Map<String,Object> metadata) {}
