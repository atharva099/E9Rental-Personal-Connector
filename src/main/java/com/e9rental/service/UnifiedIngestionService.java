package com.e9rental.service;
import com.e9rental.model.*;
import com.e9rental.connector.*;
import com.e9rental.config.WhatsAppPolicy;
import com.e9rental.config.GoogleSandboxPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
@Service
public class UnifiedIngestionService {
    private final UnifiedRepository repository;
    private final WhatsAppExportService whatsapp;
    private final WhatsAppPolicy policy;
    private final ObjectMapper json;
    private final String gmailFile,driveFile;
    private final GoogleSandboxPolicy sandbox;
    public UnifiedIngestionService(UnifiedRepository repository,WhatsAppExportService whatsapp,WhatsAppPolicy policy,ObjectMapper json,String gmailFile,String driveFile){
        this(repository,whatsapp,policy,json,gmailFile,driveFile,GoogleSandboxPolicy.denied());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public UnifiedIngestionService(UnifiedRepository repository,WhatsAppExportService whatsapp,WhatsAppPolicy policy,ObjectMapper json,
        @Value("${GMAIL_OUTPUT_FILE:D:/E9Rental-Secrets/gmail-runtime/outputs/gmail_output.json}") String gmailFile,
        @Value("${DRIVE_OUTPUT_FILE:${PERSONAL_RUNTIME_DIR:D:/E9Rental-Secrets/gmail-runtime}/outputs/drive_output.json}") String driveFile,GoogleSandboxPolicy sandbox) {
        this.repository=repository;this.whatsapp=whatsapp;this.policy=policy;this.json=json;this.gmailFile=gmailFile;this.driveFile=driveFile;this.sandbox=sandbox;
    }
    /** Fixed configured inputs only; callers cannot supply paths, tenant IDs, or WhatsApp records. */
    public UnifiedRepository.Result ingest(String source) {
        if(sandbox.personalMode() && "GMAIL".equals(source))throw new GmailFailure(403,
            "Gmail snapshot ingestion is unavailable in Live Gmail-only Personal mode",GoogleSandboxPolicy.DENIAL);
        if(sandbox.personalMode() && !Set.of("GMAIL","DRIVE").contains(source))GoogleSandboxPolicy.deny();
        if(sandbox.personalMode() && "DRIVE".equals(source) && !sandbox.driveEnabled())GoogleSandboxPolicy.deny();
        List<SearchRecord> records=new ArrayList<>();
        try {
            switch(source) {
                case "GMAIL" -> {checkInputMode(Path.of(gmailFile),source);records.addAll(gmail(json.readValue(Path.of(gmailFile).toFile(),GmailOutput.class)));}
                case "DRIVE" -> {checkInputMode(Path.of(driveFile),source);records.addAll(drive(json.readValue(Path.of(driveFile).toFile(),DriveOutput.class)));}
                case "WHATSAPP" -> {
                    // Authoritative accepted-message store and current policy; never import arbitrary WhatsApp JSON.
                    for(var m:whatsapp.snapshot().messages()) {
                        var c=m.citation();
                        records.add(new SearchRecord(m.source(),"SIMULATED","unclassified",
                            Objects.toString(m.source().metadata().get("sender_id"),null),Map.of(),
                            new SearchRecord.Citation(c.documentId(),c.sourceVersion(),c.sourceType(),c.locator(),c.sourceUrl())));
                    }
                }
                default -> throw new IllegalArgumentException();
            }
            for(var r:records) validate(r,source);
            return repository.upsert(records);
        } catch(IllegalArgumentException | GmailFailure e){throw e;}
        catch(Exception e){throw new GmailFailure(503,"Unified input unavailable or invalid");}
    }
    public List<SearchRecord> gmail(GmailOutput output) {
        if(!"1.0".equals(output.schemaVersion()) || output.messageCount()!=output.messages().size())throw new IllegalArgumentException();
        List<SearchRecord> records=new ArrayList<>();
        for(var m:output.messages()) {
            var c=m.citation();
            records.add(new SearchRecord(m.source(),output.dataMode(),m.classification(),
                Objects.toString(m.source().metadata().get("from"),null),
                Map.of("classification_reasons",m.classificationReasons(),"attachments",m.attachments(),
                    "inbound",m.inbound(),"unanswered_five_days",m.unansweredFiveDays(),
                    "reference_date",output.referenceDate().toString(),"coverage",Objects.toString(output.coverage(),"UNKNOWN")),
                new SearchRecord.Citation(c.documentId(),c.sourceVersion(),c.sourceType(),c.locator(),c.sourceUrl())));
        }
        return records;
    }
    public List<SearchRecord> drive(DriveOutput output) {
        if(!"1.0".equals(output.schemaVersion()) || output.fileCount()!=output.documents().size())throw new IllegalArgumentException();
        List<SearchRecord> records=new ArrayList<>();
        for(var d:output.documents()) {
            var c=d.citation();
            records.add(new SearchRecord(d.source(),output.dataMode(),d.category(),
                Objects.toString(d.source().metadata().get("owner"),null),
                Map.of("classification_reason",d.classificationReason(),"extraction_status",d.extractionStatus(),
                    "extraction_reason",Objects.toString(d.extractionReason(),""),
                    "coverage",Objects.toString(output.coverage(),"UNKNOWN"),"incomplete_search",output.incompleteSearch()),
                new SearchRecord.Citation(c.documentId(),c.sourceVersion(),c.sourceType(),c.locator(),c.sourceUrl())));
        }
        return records;
    }
    /** Inspect only leading scalar snapshot metadata before any message/document arrays are decoded. */
    private void checkInputMode(Path file,String source) throws java.io.IOException {
        try(var parser=json.getFactory().createParser(file.toFile())) {
            if(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.START_OBJECT)throw new IllegalArgumentException();
            for(int i=0;i<12;i++) {
                if(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.FIELD_NAME)throw new IllegalArgumentException();
                String field=parser.currentName();var token=parser.nextToken();
                if(token==null || !token.isScalarValue())throw new IllegalArgumentException();
                if("data_mode".equals(field)) {
                    String mode=parser.getValueAsString();
                    if("SYNTHETIC".equals(mode)) {
                        if(sandbox.personalMode())GoogleSandboxPolicy.deny();
                        return;
                    }
                    if(!"LIVE".equals(mode))throw new IllegalArgumentException();
                    if("GMAIL".equals(source))sandbox.requireGmail();else sandbox.requireDrive();
                    return;
                }
            }
            throw new IllegalArgumentException();
        }
    }
    private void validate(SearchRecord r,String type) {
        sandbox.checkRecord(r);
        var s=r.source();var c=r.citation();
        if(!policy.tenant().equals(s.tenantId()) || !type.equals(s.sourceType()) ||
            s.documentId()==null || s.documentId().isBlank() || s.connectorAccountId()==null || s.connectorAccountId().isBlank() ||
            s.externalId()==null || s.externalId().isBlank() || s.contentText()==null ||
            !GmailAnalyzer.hash(s.contentText()).equals(s.contentHash()) ||
            !Objects.equals(c.documentId(),s.documentId()) || !Objects.equals(c.sourceVersion(),s.sourceVersion()) ||
            !type.equals(c.sourceType()) || s.sourceVersion()==null || s.sourceVersion().isBlank() || c.locator()==null || c.locator().isBlank() ||
            !Set.of("LIVE","SYNTHETIC","SIMULATED").contains(r.dataMode()) ||
            r.classification()==null || r.classification().isBlank())throw new IllegalArgumentException();
        if("GMAIL".equals(type) && !Set.of("sales","marketing","other").contains(r.classification()))throw new IllegalArgumentException();
        if("DRIVE".equals(type) && !Set.of("sales","marketing","verification","office","accounts").contains(r.classification()))throw new IllegalArgumentException();
    }
}
