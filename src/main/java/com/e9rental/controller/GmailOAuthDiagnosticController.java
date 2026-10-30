package com.e9rental.controller;

import com.e9rental.config.LocalApiGuard;
import com.e9rental.connector.GmailFailure;
import com.e9rental.connector.GmailRequestBudget;
import com.e9rental.model.GmailOAuthDiagnosticResult;
import com.e9rental.service.GmailMessageSelectionRegistry;
import com.e9rental.service.GmailOAuthDiagnosticApprovalRegistry;
import com.e9rental.service.GmailOAuthDiagnosticOperationExecutor;
import com.e9rental.service.GmailOAuthDiagnosticExecutionState;
import com.e9rental.service.GmailOAuthDiagnosticService;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Separate two-step confirmation and execution contract for Personal Gmail OAuth diagnostics. */
@RestController
@RequestMapping("/api/gmail/oauth-diagnostic")
public final class GmailOAuthDiagnosticController {
    private static final int MAX_BODY=1024;
    private static final String OPERATION="GMAIL_OAUTH_PROFILE_ONLY";
    private static final String NOTICE="Gmail OAuth profile-only verification may contact Gmail users/me/profile, may refresh the OAuth token and rewrite the encrypted Gmail grant, and will not retrieve email messages or access Drive. It does not create new consent.";
    private final GmailOAuthDiagnosticService service;
    private final GmailOAuthDiagnosticApprovalRegistry approvals;
    private final GmailOAuthDiagnosticOperationExecutor executor;
    private final Set<String> allowedOrigins;
    private final ObjectMapper parser;
    private final Supplier<GmailRequestBudget> budgets;

    @org.springframework.beans.factory.annotation.Autowired
    public GmailOAuthDiagnosticController(GmailOAuthDiagnosticService service,GmailOAuthDiagnosticApprovalRegistry approvals,
            GmailOAuthDiagnosticOperationExecutor executor,
            @Value("${PERSONAL_FRONTEND_ORIGINS:http://127.0.0.1:5176,http://localhost:5176}") String origins,ObjectMapper json) {
        this(service,approvals,executor,origins,json,GmailRequestBudget::oauthProfileDiagnostic);
    }

    public GmailOAuthDiagnosticController(GmailOAuthDiagnosticService service,GmailOAuthDiagnosticApprovalRegistry approvals,
            GmailOAuthDiagnosticOperationExecutor executor,String origins,ObjectMapper json,
            Supplier<GmailRequestBudget> budgets) {
        this.service=service;this.approvals=approvals;this.executor=executor;this.budgets=budgets;
        this.parser=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.parser.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(256).build());
        this.allowedOrigins=Arrays.stream(origins.split(",")).map(String::strip).filter(s->!s.isBlank()).collect(Collectors.toUnmodifiableSet());
        if(allowedOrigins.isEmpty())throw new IllegalStateException("At least one local frontend origin is required");
        for(String origin:allowedOrigins)if(!isLoopbackOrigin(origin))throw new IllegalStateException("OAuth diagnostic origins must be loopback HTTP origins");
    }

    /** Fixed disclosure shown before the user requests the separate diagnostic approval. */
    @GetMapping("/confirmation")
    public ResponseEntity<Map<String,String>> confirmation(HttpServletRequest request) {
        requireLocalRequest(request);service.requirePersonalGmail();
        return noStore(ResponseEntity.ok(Map.of("operation","GMAIL_OAUTH_PROFILE_ONLY","notice",NOTICE)));
    }

    /** Must be called only after the user explicitly confirms the separate diagnostic disclosure. */
    @PostMapping(value="/approval",consumes="application/json",produces="application/json")
    public ResponseEntity<?> approve(HttpServletRequest request) {
        requireLocalRequest(request);
        service.requirePersonalGmail();
        GmailRequestBudget budget=GmailRequestBudget.operationDeadline(java.time.Duration.ofSeconds(5));
        return executor.execute(budget,()->{
            JsonNode body=readBody(request,budget);
            if(!body.isObject() || body.size()!=5 || !OPERATION.equals(body.path("operation").asText()) ||
                    !trueField(body,"confirmed") || !trueField(body,"acknowledge_profile_contact") ||
                    !trueField(body,"acknowledge_refresh_and_grant_persistence") || !trueField(body,"acknowledge_no_messages"))
                throw invalid();
            String token=approvals.issue(requestScope(request),service.tenant(),service.approvedAccountBinding());
            return noStore(ResponseEntity.ok(Map.of("approval_token",token)));
        });
    }

    /** Consumes the diagnostic-only capability before reading the grant or contacting Google. */
    @PostMapping(value="/verify",consumes="application/json",produces="application/json")
    public ResponseEntity<GmailOAuthDiagnosticResult> verify(HttpServletRequest request) {
        requireLocalRequest(request);
        GmailRequestBudget budget=budgets.get();
        GmailOAuthDiagnosticExecutionState state=new GmailOAuthDiagnosticExecutionState();
        GmailOAuthDiagnosticResult result=executor.executeDiagnostic(budget,state,()->{
            JsonNode body=readBody(request,budget);
            if(!body.isObject() || body.size()!=1 || !body.path("approval_token").isTextual())throw invalid();
            String token=body.path("approval_token").textValue();
            if(token==null || !token.matches("[A-Za-z0-9_-]{43}"))throw invalid();
            approvals.consume(token,requestScope(request),service.tenant(),service.approvedAccountBinding());
            budget.ensureWithinDeadline();
            return service.verify(budget,state);
        });
        return noStore(ResponseEntity.ok(result));
    }

    private JsonNode readBody(HttpServletRequest request,GmailRequestBudget budget) {
        if(request.getContentLengthLong()>MAX_BODY)throw tooLarge();
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream(128);byte[] chunk=new byte[256];int count;
            while(true) {
                budget.ensureWithinDeadline();
                count=request.getInputStream().read(chunk,0,Math.min(chunk.length,MAX_BODY+1-bytes.size()));
                budget.ensureWithinDeadline();
                if(count<0)break;
                if(count==0)continue;
                if(bytes.size()+count>MAX_BODY)throw tooLarge();
                bytes.write(chunk,0,count);
            }
            budget.ensureWithinDeadline();JsonNode result=parser.readTree(bytes.toByteArray());budget.ensureWithinDeadline();
            if(result==null)throw invalid();return result;
        } catch(GmailFailure failure){throw failure;}
        catch(IOException|RuntimeException malformed){throw invalid();}
    }

    private void requireLocalRequest(HttpServletRequest request) {
        String origin=request.getHeader("Origin");
        if(origin==null || !allowedOrigins.contains(origin))throw invalid();
        requestScope(request);
    }
    private static GmailMessageSelectionRegistry.RequestScope requestScope(HttpServletRequest request) {
        String principal=(String)request.getAttribute(LocalApiGuard.PRINCIPAL_ATTRIBUTE);
        String session=request.getHeader("X-Client-Session");
        if(principal==null || session==null)throw invalid();
        try{return new GmailMessageSelectionRegistry.RequestScope(principal,session);}
        catch(IllegalArgumentException invalid){throw invalid();}
    }
    private static boolean isLoopbackOrigin(String value) {
        try {URI uri=URI.create(value);return "http".equals(uri.getScheme()) && uri.getHost()!=null &&
            (uri.getHost().equalsIgnoreCase("localhost") || uri.getHost().equals("127.0.0.1")) &&
            uri.getUserInfo()==null && uri.getPath().isEmpty() && uri.getQuery()==null && uri.getFragment()==null;}
        catch(RuntimeException invalid){return false;}
    }
    private static boolean trueField(JsonNode body,String name){return body.path(name).isBoolean() && body.path(name).asBoolean();}
    private static <T> ResponseEntity<T> noStore(ResponseEntity<T> response) {
        HttpHeaders headers=new HttpHeaders();headers.putAll(response.getHeaders());headers.setCacheControl(CacheControl.noStore());
        return new ResponseEntity<>(response.getBody(),headers,response.getStatusCode());
    }
    private static GmailFailure invalid(){return new GmailFailure(400,"Invalid Gmail OAuth diagnostic request");}
    private static GmailFailure tooLarge(){return new GmailFailure(413,"Gmail OAuth diagnostic request is too large");}
}
