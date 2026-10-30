package com.e9rental.service;
import com.e9rental.connector.*;
import com.e9rental.config.GoogleSandboxPolicy;
import com.e9rental.model.GmailOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
@Service
public class GmailExportService {
    private final GmailGateway gateway;
    private final GmailAnalyzer analyzer;
    private final ObjectMapper json;
    private final Clock clock;
    private final String tenant, reference, outputFile;
    private final GoogleSandboxPolicy sandbox;
    public GmailExportService(GmailGateway gateway,GmailAnalyzer analyzer,ObjectMapper json,Clock clock,
            @Value("${PRACTICE_TENANT_ID:practice-local}") String tenant,
            @Value("${GMAIL_REFERENCE_DATE:}") String reference,
            @Value("${GMAIL_OUTPUT_FILE:D:/E9Rental-Secrets/gmail-runtime/outputs/gmail_output.json}") String outputFile) {
        this(gateway,analyzer,json,clock,tenant,reference,outputFile,GoogleSandboxPolicy.denied());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public GmailExportService(GmailGateway gateway,GmailAnalyzer analyzer,ObjectMapper json,Clock clock,
            @Value("${PRACTICE_TENANT_ID:practice-local}") String tenant,
            @Value("${GMAIL_REFERENCE_DATE:}") String reference,
            @Value("${GMAIL_OUTPUT_FILE:D:/E9Rental-Secrets/gmail-runtime/outputs/gmail_output.json}") String outputFile,
            GoogleSandboxPolicy sandbox) {
        this.gateway=gateway; this.analyzer=analyzer; this.json=json; this.clock=clock; this.tenant=tenant; this.reference=reference; this.outputFile=outputFile;
        this.sandbox=sandbox;
    }
    public synchronized GmailOutput export(LocalDate date) {
        return export(date,0);
    }
    public synchronized GmailOutput export(LocalDate date,int maxThreads) {
        if(sandbox.personalMode())throw new GmailFailure(403,
            "Gmail export is unavailable in Live Gmail-only Personal mode",GoogleSandboxPolicy.DENIAL);
        if (maxThreads<0 || maxThreads>1000) throw new IllegalArgumentException("Invalid verification thread limit");
        LocalDate effective=date!=null ? date : reference.isBlank() ? LocalDate.now(clock) : LocalDate.parse(reference);
        String dataMode=gateway.dataMode();
        if (!"LIVE".equals(dataMode) && !"SYNTHETIC".equals(dataMode)) throw new GmailFailure(500,"Google provider mode is invalid");
        var mailbox=maxThreads==0 ? gateway.read() : gateway.read(maxThreads);
        String account="gmail-"+GmailAnalyzer.hash(mailbox.address().toLowerCase(java.util.Locale.ROOT)).substring(0,24);
        GmailOutput output=analyzer.analyze(mailbox.threads(),mailbox.address(),tenant,account,effective,clock.instant(),dataMode);
        if (maxThreads>0) output=new GmailOutput(output.schemaVersion(),output.dataMode(),output.generatedAt(),output.referenceDate(),
            output.scope(),output.tenantId(),output.connectorAccountId(),output.threadCount(),output.messageCount(),output.messages(),
            "LIMITED_VERIFICATION",maxThreads);
        try {
            Path target=Path.of(outputFile).toAbsolutePath(); Files.createDirectories(target.getParent());
            Path temp=Files.createTempFile(target.getParent(),"gmail-", ".json");
            try { json.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(),output);
                Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING); }
            finally { Files.deleteIfExists(temp); }
        } catch (java.io.IOException e) { throw new GmailFailure(503,"Could not write private Gmail export"); }
        return output;
    }
}
