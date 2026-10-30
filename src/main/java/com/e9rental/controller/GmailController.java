package com.e9rental.controller;
import com.e9rental.config.GoogleSandboxPolicy;
import com.e9rental.service.*;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/gmail")
public class GmailController {
    private final OAuthService oauth;
    private final GmailExportService export;
    private final GoogleSandboxPolicy sandbox;
    private final GmailHistorySyncService sync;
    public GmailController(OAuthService oauth,GmailExportService export,GoogleSandboxPolicy sandbox,GmailHistorySyncService sync) { this.oauth=oauth; this.export=export;this.sandbox=sandbox;this.sync=sync; }
    @PostMapping("/oauth/start")
    public Map<String,String> start() { sandbox.requireGmail();return Map.of("authorization_url",oauth.begin()); }
    @PostMapping("/export")
    public Map<String,Object> export(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate referenceDate,
            @RequestParam(defaultValue="0") int maxThreads) {
        var result=export.export(referenceDate,maxThreads);
        return Map.of("status","EXPORTED","data_mode",result.dataMode(),"message_count",result.messageCount(),
            "thread_count",result.threadCount(),"coverage",result.coverage(),"output","configured private export file");
    }
    /** Explicit bearer-authenticated call; no scheduler invokes this operation. */
    @PostMapping("/sync")
    public Map<String,Object> sync() {
        if(sandbox.personalMode())throw new com.e9rental.connector.GmailFailure(403,
            "Gmail history sync is unavailable in Live Gmail-only Personal mode",GoogleSandboxPolicy.DENIAL);
        var result=sync.synchronize();
        return Map.of("status",result.status(),"inserted",result.inserted(),"updated",result.updated(),
            "removed",result.removed(),"replayed",result.replayed(),"full_resynchronization",result.fullResynchronization(),
            "gmail_rest_attempts",result.gmailRestAttempts());
    }
}
