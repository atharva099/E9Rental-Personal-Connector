package com.e9rental.model;
import java.time.Instant;
import java.util.List;
/** Supplied simulation metadata only. Null name/number means unavailable, never inferred. */
public record WhatsAppGroupMetadata(String tenantId,String connectorAccountId,String groupId,String groupName,
        long version,Instant updatedAt,List<Participant> participants,String fingerprint) {
    public record Participant(String senderId,String displayName,String number) {}
}
