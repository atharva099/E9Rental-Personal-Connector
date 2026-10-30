package com.e9rental.service;

import com.e9rental.config.GoogleSandboxPolicy;
import com.e9rental.connector.GmailGateway;
import com.e9rental.connector.GmailFailure;
import com.e9rental.connector.GmailRequestBudget;
import com.e9rental.model.GmailMessageDetail;
import com.e9rental.model.SearchRecord;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** One-use, transient selected-message view. No repository or logging dependency is used. */
@Service
public final class GmailMessageViewService {
    private static final Pattern CONTROL=Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");
    private final GmailMessageSelectionRegistry selections;
    private final GmailGateway gateway;
    private final GmailMessageBodyExtractor bodyExtractor;
    private final GoogleSandboxPolicy sandbox;
    private final String tenant;

    public GmailMessageViewService(GmailMessageSelectionRegistry selections,GmailGateway gateway,
            GmailMessageBodyExtractor bodyExtractor,GoogleSandboxPolicy sandbox,
            @Value("${PRACTICE_TENANT_ID:practice-local}") String tenant) {
        this.selections=selections;this.gateway=gateway;this.bodyExtractor=bodyExtractor;this.sandbox=sandbox;this.tenant=tenant;
    }

    /** Called only for validated direct-Gmail evidence returned by the current Ask request. */
    public String register(SearchRecord record,GmailMessageSelectionRegistry.RequestScope scope) {
        if(record==null || scope==null || !"GMAIL".equals(record.source().sourceType()) ||
                !"LIVE".equals(record.dataMode()) ||
                !tenant.equals(record.source().tenantId()) || !sandbox.gmailRecordAccount().equals(record.source().connectorAccountId()) ||
                !QuestionRecordValidator.valid(record)) return null;
        return selections.register(record.source().externalId(),tenant,sandbox.gmailRecordAccount(),scope);
    }
    /** Issues viewer capabilities for an entire bounded live result page atomically. */
    public List<String> registerAll(List<SearchRecord> records,GmailMessageSelectionRegistry.RequestScope scope) {
        return registerAll(records,scope,null);
    }
    public List<String> registerAll(List<SearchRecord> records,GmailMessageSelectionRegistry.RequestScope scope,
            GmailRequestBudget budget) {
        if(budget!=null)budget.ensureWithinDeadline();
        if(records==null || records.size()>20)throw new GmailFailure(502,"Gmail search exceeded its result limit");
        List<String> ids=new ArrayList<>();List<Integer> positions=new ArrayList<>();
        for(int i=0;i<records.size();i++) {
            SearchRecord record=records.get(i);
            if(record!=null && record.source()!=null && scope!=null && "GMAIL".equals(record.source().sourceType()) && "LIVE".equals(record.dataMode()) &&
                    tenant.equals(record.source().tenantId()) && sandbox.gmailRecordAccount().equals(record.source().connectorAccountId()) &&
                    QuestionRecordValidator.valid(record)) {
                ids.add(record.source().externalId());positions.add(i);
            }
        }
        List<String> issued=ids.isEmpty()?List.of():selections.registerAll(ids,tenant,sandbox.gmailRecordAccount(),scope,budget);
        List<String> result=new ArrayList<>(Collections.nCopies(records.size(),null));
        for(int i=0;i<positions.size();i++)result.set(positions.get(i),issued.get(i));
        if(budget!=null)budget.ensureWithinDeadline();
        return Collections.unmodifiableList(result);
    }
    public String approve(String selectionId,GmailMessageSelectionRegistry.RequestScope scope) {
        sandbox.requireGmail();
        return selections.approve(selectionId,scope,tenant,sandbox.gmailRecordAccount());
    }
    public GmailMessageDetail view(String approvalId,GmailMessageSelectionRegistry.RequestScope scope) {
        return view(approvalId,scope,GmailRequestBudget.singleMessageView());
    }
    public GmailMessageDetail view(String approvalId,GmailMessageSelectionRegistry.RequestScope scope,GmailRequestBudget budget) {
        budget.ensureWithinDeadline();
        var selected=selections.consume(approvalId,scope,tenant,sandbox.gmailRecordAccount(),budget);
        budget.ensureWithinDeadline();
        sandbox.requireGmail();
        JsonNode message=gateway.readSelectedQuestionMessage(selected.messageId(),selected.account(),budget);
        budget.ensureWithinDeadline();
        JsonNode payload=message.path("payload");
        GmailMessageBodyExtractor.Result body=bodyExtractor.extract(payload,budget);
        budget.ensureWithinDeadline();
        Instant receivedAt=null;
        try {
            String millis=message.path("internalDate").asText("");
            if(millis.matches("[0-9]{1,16}"))receivedAt=Instant.ofEpochMilli(Long.parseLong(millis));
        } catch(RuntimeException invalidDate) { /* Missing/invalid provider time is represented as unavailable. */ }
        return new GmailMessageDetail(header(payload,"Subject",512),header(payload,"From",512),header(payload,"To",512),
            receivedAt,body.text(),body.available(),body.incomplete(),body.truncated(),body.attachmentNames(),body.attachmentNamesTruncated());
    }
    private static String header(JsonNode payload,String name,int max) {
        for(JsonNode header:payload.path("headers"))if(name.equalsIgnoreCase(header.path("name").asText())) {
            String value=CONTROL.matcher(header.path("value").asText("")).replaceAll("").strip();
            if(value.length()>max)value=value.substring(0,max);
            return value.isBlank()?null:value;
        }
        return null;
    }

    /** Keeps this service coupled to the same evidence integrity predicate as the Q&A pipeline. */
    private static final class QuestionRecordValidator {
        static boolean valid(SearchRecord record) {
            var source=record.source();var citation=record.citation();
            return source.deletedAt()==null && source.contentText()!=null && source.sourceVersion()!=null && !source.sourceVersion().isBlank() &&
                citation!=null && Objects.equals(source.contentHash(),GmailAnalyzer.hash(source.contentText())) &&
                Objects.equals(source.documentId(),citation.documentId()) && Objects.equals(source.sourceVersion(),citation.sourceVersion()) &&
                Objects.equals(source.sourceType(),citation.sourceType()) && citation.locator()!=null && !citation.locator().isBlank();
        }
    }
}
