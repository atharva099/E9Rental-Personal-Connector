package com.e9rental.model;
import java.time.Instant;
public record WhatsAppMessage(String documentId,String tenantId,String connectorAccountId,String groupId,
        String senderId,String messageId,Instant timestamp,String content,Instant receivedAt,String fingerprint) {}
