package com.e9rental.controller;

import com.e9rental.config.LocalApiGuard;
import com.e9rental.connector.GmailFailure;
import com.e9rental.connector.GmailRequestBudget;
import com.e9rental.model.GmailSearchFilters;
import com.e9rental.model.GmailSearchResponse;
import com.e9rental.service.GmailDirectSearchService;
import com.e9rental.service.GmailMessageSelectionRegistry;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Protected two-step authorization and execution API for transient direct Gmail search. */
@RestController
@RequestMapping("/api/gmail/search")
public final class GmailSearchController {
    private static final int MAX_REQUEST_BYTES=1024;
    private static final Set<String> FILTER_FIELDS=Set.of("keyword","sender","start_date","end_date");
    private final GmailDirectSearchService service;
    private final com.e9rental.service.GmailSearchOperationExecutor executor;
    private final Supplier<GmailRequestBudget> approvalBudgets,searchBudgets;
    private final Set<String> allowedOrigins;
    private final ObjectMapper parser;

    @org.springframework.beans.factory.annotation.Autowired
    public GmailSearchController(GmailDirectSearchService service,com.e9rental.service.GmailSearchOperationExecutor executor,
            @Value("${PERSONAL_FRONTEND_ORIGINS:http://127.0.0.1:5176,http://localhost:5176}") String origins,
            ObjectMapper json) {
        this(service,executor,origins,json,GmailRequestBudget::searchApproval,GmailRequestBudget::directSearch);
    }

    GmailSearchController(GmailDirectSearchService service,com.e9rental.service.GmailSearchOperationExecutor executor,
            String origins,ObjectMapper json,Supplier<GmailRequestBudget> approvalBudgets,
            Supplier<GmailRequestBudget> searchBudgets) {
        this.service=service;this.executor=executor;
        this.approvalBudgets=approvalBudgets;this.searchBudgets=searchBudgets;
        this.parser=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.parser.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(8).maxStringLength(512).maxNumberLength(64).build());
        this.allowedOrigins=Arrays.stream(origins.split(",")).map(String::strip).filter(s->!s.isBlank())
            .collect(Collectors.toUnmodifiableSet());
        if(allowedOrigins.isEmpty())throw new IllegalStateException("At least one local frontend origin is required");
        for(String origin:allowedOrigins)if(!isLoopbackOrigin(origin))throw new IllegalStateException("Search origins must be loopback HTTP origins");
    }

    /** Called after a fresh user confirmation. It validates and stores filters but makes no provider request. */
    @PostMapping(value="/approval",consumes="application/json",produces="application/json")
    public ResponseEntity<?> approve(HttpServletRequest request) {
        GmailRequestBudget budget=approvalBudgets.get();
        return executor.execute(budget,()->{
            budget.ensureWithinDeadline();requireLocalOrigin(request);
            GmailMessageSelectionRegistry.RequestScope scope=requestScope(request);
            GmailSearchFilters filters=parseFilters(readJson(request,budget));
            budget.ensureWithinDeadline();
            String token=service.approve(filters,scope,budget);
            budget.ensureWithinDeadline();
            return noStore(ResponseEntity.ok(Map.of("approval_token",token)));
        });
    }

    /** The one-use query-scoped capability is consumed before OAuth or Gmail access. */
    @PostMapping(consumes="application/json",produces="application/json")
    public ResponseEntity<GmailSearchResponse> search(HttpServletRequest request) {
        GmailRequestBudget budget=searchBudgets.get();
        return executor.execute(budget,()->{
            budget.ensureWithinDeadline();requireLocalOrigin(request);
            GmailMessageSelectionRegistry.RequestScope scope=requestScope(request);
            JsonNode body=readJson(request,budget);
            if(body==null || !body.isObject() || body.size()!=1 || !body.path("approval_token").isTextual())throw invalid();
            String token=body.path("approval_token").textValue();
            if(token==null || !token.matches("[A-Za-z0-9_-]{43}"))throw invalid();
            budget.ensureWithinDeadline();
            return noStore(ResponseEntity.ok(service.search(token,scope,budget)));
        });
    }

    private static GmailSearchFilters parseFilters(JsonNode body) {
        if(body==null || !body.isObject() || body.size()>FILTER_FIELDS.size() || body.isEmpty() ||
                body.fieldNames().hasNext() && !containsOnlyAllowed(body))throw invalid();
        return new GmailSearchFilters(text(body,"keyword",128),text(body,"sender",254),
            text(body,"start_date",10),text(body,"end_date",10));
    }
    private static boolean containsOnlyAllowed(JsonNode body) {
        var fields=body.fieldNames();while(fields.hasNext())if(!FILTER_FIELDS.contains(fields.next()))return false;return true;
    }
    private static String text(JsonNode body,String key,int max) {
        JsonNode value=body.get(key);
        if(value==null || value.isNull())return null;
        if(!value.isTextual() || value.textValue().length()>max)throw invalid();
        return value.textValue();
    }
    private JsonNode readJson(HttpServletRequest request,GmailRequestBudget budget) {
        if(request.getContentLengthLong()>MAX_REQUEST_BYTES)throw tooLarge();
        try {
            ByteArrayOutputStream bounded=new ByteArrayOutputStream(MAX_REQUEST_BYTES);
            byte[] chunk=new byte[256];
            int count;
            while(true) {
                budget.ensureWithinDeadline();
                count=request.getInputStream().read(chunk,0,Math.min(chunk.length,MAX_REQUEST_BYTES+1-bounded.size()));
                budget.ensureWithinDeadline();
                if(count==-1)break;
                if(count==0)continue;
                if(bounded.size()+count>MAX_REQUEST_BYTES)throw tooLarge();
                bounded.write(chunk,0,count);
            }
            budget.ensureWithinDeadline();
            JsonNode parsed=parser.readTree(bounded.toByteArray());
            budget.ensureWithinDeadline();
            return parsed;
        } catch(GmailFailure rejected) { throw rejected; }
        catch(IOException | RuntimeException malformed) { throw invalid(); }
    }
    private static GmailMessageSelectionRegistry.RequestScope requestScope(HttpServletRequest request) {
        String principal=(String)request.getAttribute(LocalApiGuard.PRINCIPAL_ATTRIBUTE);
        String session=request.getHeader("X-Client-Session");
        if(principal==null || session==null)throw invalid();
        try { return new GmailMessageSelectionRegistry.RequestScope(principal,session); }
        catch(IllegalArgumentException badScope) { throw invalid(); }
    }
    private void requireLocalOrigin(HttpServletRequest request) {
        String origin=request.getHeader("Origin");if(origin==null || !allowedOrigins.contains(origin))throw invalid();
    }
    private static boolean isLoopbackOrigin(String value) {
        try {
            URI uri=URI.create(value);
            return "http".equals(uri.getScheme()) && uri.getHost()!=null &&
                (uri.getHost().equalsIgnoreCase("localhost") || uri.getHost().equals("127.0.0.1")) &&
                uri.getUserInfo()==null && uri.getPath().isEmpty() && uri.getQuery()==null && uri.getFragment()==null;
        } catch(RuntimeException invalid) { return false; }
    }
    private static <T> ResponseEntity<T> noStore(ResponseEntity<T> response) {
        HttpHeaders headers=new HttpHeaders();headers.putAll(response.getHeaders());headers.setCacheControl(CacheControl.noStore());
        return new ResponseEntity<>(response.getBody(),headers,response.getStatusCode());
    }
    private static GmailFailure invalid() { return new GmailFailure(400,"Invalid Gmail search request"); }
    private static GmailFailure tooLarge() { return new GmailFailure(413,"Gmail search request is too large"); }
}
