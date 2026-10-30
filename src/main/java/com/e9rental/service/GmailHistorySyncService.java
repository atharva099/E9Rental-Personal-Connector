package com.e9rental.service;

import com.e9rental.config.GoogleSandboxPolicy;
import com.e9rental.connector.GmailFailure;
import com.e9rental.connector.GmailGateway;
import com.e9rental.model.SearchRecord;
import com.e9rental.model.GmailOutput;
import com.e9rental.service.GmailSyncCheckpointService.HistoryBatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Executes one explicitly requested, single-page Gmail history sync. */
@Service
public class GmailHistorySyncService {
    private final GmailGateway gateway;
    private final GmailSyncCheckpointService checkpoints;
    private final GmailAnalyzer analyzer;
    private final GoogleSandboxPolicy sandbox;
    private final ObjectMapper json;
    private final Clock clock;
    private final String tenant;

    public GmailHistorySyncService(GmailGateway gateway, GmailSyncCheckpointService checkpoints, GmailAnalyzer analyzer,
            GoogleSandboxPolicy sandbox, ObjectMapper json, Clock clock,
            @Value("${PRACTICE_TENANT_ID:practice-local}") String tenant) {
        this.gateway=gateway;this.checkpoints=checkpoints;this.analyzer=analyzer;this.sandbox=sandbox;
        this.json=json;this.clock=clock;this.tenant=tenant;
    }

    public SyncResult synchronize() {
        if(sandbox.personalMode())throw new GmailFailure(403,
            "Gmail history sync is unavailable in Live Gmail-only Personal mode",GoogleSandboxPolicy.DENIAL);
        sandbox.requireGmail();
        try {
            Optional<String> previous=checkpoints.currentHistoryId();
            GmailGateway.SyncSession session=gateway.beginHistorySync();
            sandbox.checkGmailAccount(session.address());
            if(previous.isEmpty()) {
                var applied=checkpoints.apply(new HistoryBatch(null,session.profileHistoryId(),List.of(),List.of()));
                return new SyncResult("BASELINE_ESTABLISHED",0,0,0,applied.replayed(),false,session.attemptsUsed());
            }
            String start=previous.get();
            GmailGateway.HistoryChanges changes=session.changesSince(start);
            int ordering=new BigInteger(changes.historyId()).compareTo(new BigInteger(start));
            if(ordering<0 || (ordering==0 && (!changes.changedMessageIds().isEmpty() || !changes.removedMessageIds().isEmpty())))
                throw providerResponseFailure();
            if(ordering==0) return new SyncResult("NO_CHANGES",0,0,0,false,false,session.attemptsUsed());

            List<SearchRecord> records=new ArrayList<>();
            Set<String> changedIds=new HashSet<>(changes.changedMessageIds());
            if(changedIds.size()!=changes.changedMessageIds().size())throw providerResponseFailure();
            for(String id:changes.changedMessageIds()) {
                if(checkpoints.isTombstoned(id)) continue;
                var message=session.readCurrentMessage(id);
                var thread=json.createObjectNode();thread.put("id",message.path("threadId").asText());
                var messages=thread.putArray("messages");messages.add(message);
                GmailOutput output=analyzer.analyze(List.of(thread),session.address(),tenant,sandbox.gmailRecordAccount(),
                    LocalDate.ofInstant(clock.instant(),ZoneOffset.UTC),clock.instant(),gateway.dataMode());
                if(output.messages().size()!=1 || !id.equals(output.messages().get(0).source().externalId()))throw providerResponseFailure();
                SearchRecord record=toSearchRecord(output,output.messages().get(0));
                sandbox.checkRecord(record);records.add(record);
            }
            for(String id:changes.removedMessageIds())if(changedIds.contains(id))throw providerResponseFailure();
            var applied=checkpoints.apply(new HistoryBatch(start,changes.historyId(),records,changes.removedMessageIds()));
            return new SyncResult("SYNCHRONIZED",applied.records().inserted(),applied.records().updated(),
                applied.records().removed(),applied.replayed(),false,session.attemptsUsed());
        } catch(GmailFailure failure) {
            if(GoogleSandboxPolicy.DENIAL.equals(failure.reason()))throw failure;
            throw failure;
        } catch(RuntimeException failure) {
            throw new GmailFailure(503,"Gmail synchronization failed; the last committed checkpoint and records were preserved","UNSPECIFIED",
                GmailFailure.DiagnosticCategory.GMAIL_UNEXPECTED_RETRIEVAL_FAILURE);
        }
    }

    private static SearchRecord toSearchRecord(GmailOutput output,GmailOutput.MessageResult message) {
        var source=message.source();var citation=message.citation();
        return new SearchRecord(source,output.dataMode(),message.classification(),Objects.toString(source.metadata().get("from"),null),
            Map.of("classification_reasons",message.classificationReasons(),"attachments",message.attachments(),
                "inbound",message.inbound(),"unanswered_five_days",message.unansweredFiveDays(),
                "reference_date",output.referenceDate().toString(),"coverage","USER_TRIGGERED_HISTORY_SYNC"),
            new SearchRecord.Citation(citation.documentId(),citation.sourceVersion(),citation.sourceType(),citation.locator(),citation.sourceUrl()));
    }

    private static GmailFailure providerResponseFailure() {
        return new GmailFailure(502,"Invalid Gmail sync provider response","UNSPECIFIED",
            GmailFailure.DiagnosticCategory.GMAIL_SYNC_PROVIDER_RESPONSE_INVALID);
    }

    public record SyncResult(String status,int inserted,int updated,int removed,boolean replayed,
            boolean fullResynchronization,int gmailRestAttempts) {}
}
