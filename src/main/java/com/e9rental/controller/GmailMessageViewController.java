package com.e9rental.controller;

import com.e9rental.config.LocalApiGuard;
import com.e9rental.connector.GmailFailure;
import com.e9rental.connector.GmailRequestBudget;
import com.e9rental.model.GmailMessageDetail;
import com.e9rental.service.GmailMessageSelectionRegistry;
import com.e9rental.service.GmailMessageViewService;
import com.e9rental.service.GmailViewerOperationExecutor;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Protected, same-origin endpoints implementing fresh one-message approval and transient retrieval. */
@RestController
@RequestMapping("/api/gmail/messages")
public final class GmailMessageViewController {
    private final GmailMessageViewService service;
    private final GmailViewerOperationExecutor operations;
    private final ObjectMapper parser;
    private final Set<String> allowedOrigins;
    private final Supplier<GmailRequestBudget> budgetFactory;

    @org.springframework.beans.factory.annotation.Autowired
    public GmailMessageViewController(GmailMessageViewService service,GmailViewerOperationExecutor operations,ObjectMapper json,
            @Value("${PERSONAL_FRONTEND_ORIGINS:http://127.0.0.1:5173,http://localhost:5173,http://127.0.0.1:5176,http://localhost:5176}") String origins) {
        this(service,operations,json,origins,GmailRequestBudget::singleMessageView);
    }
    /** Test seam for short deterministic deadlines; production uses the fixed 18-second budget. */
    public GmailMessageViewController(GmailMessageViewService service,GmailViewerOperationExecutor operations,ObjectMapper json,
            String origins,Supplier<GmailRequestBudget> budgetFactory) {
        this.service=service;this.operations=operations;
        this.budgetFactory=Objects.requireNonNull(budgetFactory);
        this.parser=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.parser.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(256).build());
        this.allowedOrigins=Arrays.stream(origins.split(",")).map(String::strip).filter(s->!s.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if(allowedOrigins.isEmpty())throw new IllegalStateException("At least one local frontend origin is required");
        for(String origin:allowedOrigins)if(!isLoopbackOrigin(origin))throw new IllegalStateException("Viewer origins must be loopback HTTP origins");
    }
    public GmailMessageViewController(GmailMessageViewService service,ObjectMapper json,String origins) {
        this(service,new GmailViewerOperationExecutor(),json,origins);
    }

    /** Called only after a fresh user confirmation for this selected message; no Gmail request is made here. */
    @PostMapping(value="/view-approval",consumes="application/json")
    public ResponseEntity<?> approve(HttpServletRequest request) {
        GmailRequestBudget budget=budgetFactory.get();
        requireLocalOrigin(request);
        var scope=requestScope(request);
        String approval=operations.execute(budget,()->{
            JsonNode body=readBody(request,budget);
            budget.ensureWithinDeadline();
            if(!body.isObject() || body.size()!=1 || !body.path("selection_id").isTextual())throw invalidRequest();
            String issued=service.approve(body.path("selection_id").asText(),scope);
            budget.ensureWithinDeadline();
            return issued;
        });
        return ResponseEntity.ok(Map.of("approval_token",approval));
    }

    /** Consumes a message-scoped approval before making one bounded Gmail profile/message read. */
    @PostMapping(value="/view",consumes="application/json")
    public ResponseEntity<GmailMessageDetail> view(HttpServletRequest request) {
        GmailRequestBudget budget=budgetFactory.get();
        requireLocalOrigin(request);
        var scope=requestScope(request);
        GmailMessageDetail detail=operations.execute(budget,()->{
            JsonNode body=readBody(request,budget);
            budget.ensureWithinDeadline();
            if(!body.isObject() || body.size()!=1 || !body.path("approval_token").isTextual())throw invalidRequest();
            return service.view(body.path("approval_token").asText(),scope,budget);
        });
        return ResponseEntity.ok(detail);
    }

    private JsonNode readBody(HttpServletRequest request) {
        return readBody(request,null);
    }
    /** Reads the viewer body only on its bounded worker, checking the shared budget between bytes. */
    private JsonNode readBody(HttpServletRequest request,GmailRequestBudget budget) {
        try {
            if(budget!=null)budget.ensureWithinDeadline();
            if(budget!=null && request.getContentLengthLong()>1024)throw invalidRequest();
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(128);
            var input=request.getInputStream();
            while(true) {
                if(budget!=null)budget.ensureWithinDeadline();
                int value=input.read();
                if(budget!=null)budget.ensureWithinDeadline();
                if(value<0)break;
                if(bytes.size()>=1024)throw invalidRequest();
                bytes.write(value);
            }
            if(budget!=null)budget.ensureWithinDeadline();
            JsonNode body=parser.readTree(bytes.toByteArray());
            if(budget!=null)budget.ensureWithinDeadline();
            if(body==null)throw invalidRequest();
            return body;
        } catch(IOException|RuntimeException invalid) {
            if(budget!=null)budget.ensureWithinDeadline();
            if(invalid instanceof GmailFailure failure)throw failure;
            throw invalidRequest();
        }
    }
    private static GmailMessageSelectionRegistry.RequestScope requestScope(HttpServletRequest request) {
        String principal=(String)request.getAttribute(LocalApiGuard.PRINCIPAL_ATTRIBUTE);
        String session=request.getHeader("X-Client-Session");
        if(principal==null || session==null)throw invalidRequest();
        try { return new GmailMessageSelectionRegistry.RequestScope(principal,session); }
        catch(IllegalArgumentException invalid) { throw invalidRequest(); }
    }
    private void requireLocalOrigin(HttpServletRequest request) {
        String origin=request.getHeader("Origin");
        if(origin==null || !allowedOrigins.contains(origin))throw invalidRequest();
    }
    private static boolean isLoopbackOrigin(String value) {
        try {
            URI uri=URI.create(value);
            return "http".equals(uri.getScheme()) && uri.getHost()!=null &&
                (uri.getHost().equalsIgnoreCase("localhost") || uri.getHost().equals("127.0.0.1")) &&
                uri.getUserInfo()==null && uri.getPath().isEmpty() && uri.getQuery()==null && uri.getFragment()==null;
        } catch(RuntimeException invalid) { return false; }
    }
    private static GmailFailure invalidRequest() { return new GmailFailure(400,"Invalid Gmail message viewer request"); }
}
