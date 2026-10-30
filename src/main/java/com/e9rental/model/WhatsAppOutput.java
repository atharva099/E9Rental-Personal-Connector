package com.e9rental.model;
import java.time.Instant;
import java.util.List;
public record WhatsAppOutput(String schemaVersion,String dataMode,boolean liveIntegration,String coverage,
        Instant generatedAt,int messageCount,List<String> selectedGroups,List<Message> messages,List<GroupSummary> groups) {
    public record Message(SourceRecord source,Citation citation) {}
    public record Citation(String citationId,String documentId,String sourceVersion,String sourceType,String locator,String sourceUrl,Instant retrievedAt) {}
    public record Evidence(String text,List<String> supportingMessageIds,String senderId,int startOffset,int endOffset) {}
    public record Interpretation(String kind,String senderId,String task,String assigneeId,String dueDate,String status,
        int startOffset,int endOffset,String text,List<String> supportingMessageIds) {}
    public record ParticipantInterpretation(String senderId,List<Interpretation> requests,List<Interpretation> commitments,
        List<Interpretation> actionItems,List<Interpretation> decisions) {}
    public record GroupSummary(String groupId,int messageCount,List<Evidence> summary,List<Evidence> commitments,List<Evidence> actionItems,
        String groupName,Long metadataVersion,Instant metadataUpdatedAt,List<WhatsAppGroupMetadata.Participant> participants,
        List<Interpretation> requests,List<Interpretation> decisions,List<ParticipantInterpretation> participantInterpretations) {}
}
