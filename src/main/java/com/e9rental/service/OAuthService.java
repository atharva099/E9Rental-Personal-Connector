package com.e9rental.service;
import com.e9rental.connector.*;
import com.e9rental.model.GmailOAuthDiagnosticResult.SideEffectStatus;
import com.e9rental.model.GmailOAuthDiagnosticResult.RefreshFailureCategory;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
@Service
@org.springframework.context.annotation.Primary
public class OAuthService {
    public static final String SCOPE="https://www.googleapis.com/auth/gmail.readonly";
    public record GmailQuestionToken(String accessToken,int refreshAttempts) {}
    /** Internal diagnostic material; the access token is never serialized or logged. */
    public record OAuthDiagnosticAccess(String accessToken,String localGrantStructure,String storedScopeMarker,
            String providerAuthorization,String providerScope,SideEffectStatus tokenRefreshStatus,
            SideEffectStatus grantPersistenceStatus,RefreshFailureCategory refreshFailureCategory) {
        public OAuthDiagnosticAccess(String accessToken,String localGrantStructure,String storedScopeMarker,
                String providerAuthorization,String providerScope,SideEffectStatus tokenRefreshStatus,
                SideEffectStatus grantPersistenceStatus) {
            this(accessToken,localGrantStructure,storedScopeMarker,providerAuthorization,providerScope,
                tokenRefreshStatus,grantPersistenceStatus,RefreshFailureCategory.NONE);
        }
    }
    private final GoogleTransport transport;
    private final GmailViewerTransport viewerTransport;
    private final EncryptedTokenStore store;
    private final ObjectMapper json;
    private final Clock clock;
    private final String clientId, clientSecret, redirect;
    private final String requestedScope, callbackPath;
    private final boolean bootstrapContinuation;
    private String state, verifier;
    private Instant stateExpires=Instant.EPOCH;
    private String accessToken, refreshToken;
    private String lastProviderScope;
    private Instant expires=Instant.EPOCH;
    private final ReentrantLock tokenStateLock=new ReentrantLock();
    @org.springframework.beans.factory.annotation.Autowired
    public OAuthService(GoogleTransport transport,GmailViewerTransport viewerTransport, EncryptedTokenStore store, ObjectMapper json, Clock clock,
            @Value("${GOOGLE_CLIENT_ID:}") String clientId, @Value("${GOOGLE_CLIENT_SECRET:}") String clientSecret,
            @Value("${GOOGLE_REDIRECT_URI:http://127.0.0.1:8080/}") String redirect,
            @Value("${PERSONAL_BOOTSTRAP_CONTINUATION:false}") boolean bootstrapContinuation) {
        this(transport,viewerTransport,store,json,clock,clientId,clientSecret,redirect,SCOPE,"/",bootstrapContinuation);
    }
    public OAuthService(GoogleTransport transport, EncryptedTokenStore store, ObjectMapper json, Clock clock,
            String clientId,String clientSecret,String redirect) {
        this(transport,new GmailViewerTransport(),store,json,clock,clientId,clientSecret,redirect,SCOPE,"/",false);
    }
    protected OAuthService(GoogleTransport transport, EncryptedTokenStore store, ObjectMapper json, Clock clock,
            String clientId,String clientSecret,String redirect,String requestedScope,String callbackPath) {
        this(transport,new GmailViewerTransport(),store,json,clock,clientId,clientSecret,redirect,requestedScope,callbackPath,false);
    }
    public OAuthService(GoogleTransport transport, EncryptedTokenStore store, ObjectMapper json, Clock clock,
            String clientId,String clientSecret,String redirect,String requestedScope,String callbackPath,boolean bootstrapContinuation) {
        this(transport,new GmailViewerTransport(),store,json,clock,clientId,clientSecret,redirect,requestedScope,callbackPath,bootstrapContinuation);
    }
    public OAuthService(GoogleTransport transport,GmailViewerTransport viewerTransport,EncryptedTokenStore store, ObjectMapper json, Clock clock,
            String clientId,String clientSecret,String redirect,String requestedScope,String callbackPath,boolean bootstrapContinuation) {
        this.transport=transport;this.viewerTransport=viewerTransport; this.store=store; this.json=json; this.clock=clock;
        this.clientId=clientId; this.clientSecret=clientSecret; this.redirect=redirect;
        this.requestedScope=requestedScope; this.callbackPath=callbackPath; this.bootstrapContinuation=bootstrapContinuation;
    }
    public String begin() {
        tokenStateLock.lock();try{return beginLocked();}finally{tokenStateLock.unlock();}
    }
    private String beginLocked() {
        if (clientId.isBlank() || clientId.equals("replace_me")) throw new GmailFailure(503,"Configure GOOGLE_CLIENT_ID before authorization");
        URI uri=URI.create(redirect);
        if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort()<1 ||
                !callbackPath.equals(uri.getPath()) || uri.getQuery()!=null || uri.getFragment()!=null || uri.getUserInfo()!=null)
            throw new GmailFailure(503,"OAuth redirect must use the local 127.0.0.1 callback");
        store.validate(); state=random(); verifier=random(); stateExpires=clock.instant().plusSeconds(600);
        return "https://accounts.google.com/o/oauth2/v2/auth?client_id="+enc(clientId)+"&redirect_uri="+enc(redirect)+
            "&response_type=code&scope="+enc(requestedScope)+"&access_type=offline&prompt=consent&include_granted_scopes=false"+
            "&state="+state+"&code_challenge="+challenge(verifier)+"&code_challenge_method=S256";
    }
    public void callback(String received, String code, String error) {
        tokenStateLock.lock();try{callbackLocked(received,code,error);}finally{tokenStateLock.unlock();}
    }
    private void callbackLocked(String received,String code,String error) {
        if (state==null || received==null || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
                received.getBytes(StandardCharsets.UTF_8)) || !clock.instant().isBefore(stateExpires))
            throw new GmailFailure(400,"Invalid or expired OAuth state");
        String proof=verifier; state=null; verifier=null;
        if (error!=null || code==null || code.isBlank()) throw new GmailFailure(400,"OAuth authorization was not granted");
        var form=base(); form.add("grant_type","authorization_code"); form.add("code",code);
        form.add("redirect_uri",redirect); form.add("code_verifier",proof);
        accept(exchangeToken(form),true);
    }
    public String accessToken() {
        tokenStateLock.lock();try{return accessTokenLocked(null);}finally{tokenStateLock.unlock();}
    }
    private String accessTokenLocked(GmailRequestBudget viewerBudget) {
        if(viewerBudget!=null)viewerBudget.ensureWithinDeadline();
        if (accessToken!=null && clock.instant().isBefore(expires.minusSeconds(60))) return accessToken;
        if (refreshToken==null) {
            byte[] saved=store.load();
            if (saved!=null) {
                try {
                    JsonNode stored=json.readTree(saved);
                    if (!requestedScope.equals(stored.path("scope").asText())) throw new GmailFailure(401,"Stored OAuth scope is invalid; authorize again");
                    refreshToken=stored.path("refresh_token").asText(null);
                }
                catch (Exception e) { throw new GmailFailure(503,"Invalid encrypted token store"); }
            }
        }
        if (refreshToken==null) throw new GmailFailure(401,"Authorize "+(requestedScope.equals(SCOPE) ? "Gmail" : "Drive")+" locally first");
        var form=base(); form.add("grant_type","refresh_token"); form.add("refresh_token",refreshToken);
        JsonNode response=viewerBudget==null?exchangeToken(form):viewerTransport.refresh(form,viewerBudget);
        if(viewerBudget!=null)viewerBudget.ensureWithinDeadline();
        accept(response,false); if(viewerBudget!=null)viewerBudget.ensureWithinDeadline();return accessToken;
    }

    /**
     * Reads and validates the encrypted Gmail grant under the OAuth state lock, then
     * uses the normal refresh-token contract only when the cached access token is
     * expired. This is intentionally separate from ordinary Gmail approvals.
     */
    public OAuthDiagnosticAccess accessTokenForOAuthDiagnostic(GmailRequestBudget budget) {
        return accessTokenForOAuthDiagnostic(budget,new GmailOAuthDiagnosticExecutionState());
    }

    public OAuthDiagnosticAccess accessTokenForOAuthDiagnostic(GmailRequestBudget budget,GmailOAuthDiagnosticExecutionState diagnosticState) {
        if(budget==null)throw new IllegalArgumentException("A bounded OAuth diagnostic budget is required");
        if(diagnosticState==null)throw new IllegalArgumentException("OAuth diagnostic state is required");
        try {
            if(!tokenStateLock.tryLock(budget.remainingNanos(),TimeUnit.NANOSECONDS))throw diagnosticTimeout();
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw diagnosticTimeout();
        }
        try {
            budget.ensureWithinDeadline();
            final byte[] saved;
            try { saved=store.load(); }
            catch(RuntimeException unreadable) { return invalidDiagnosticGrant(); }
            if(saved==null)return invalidDiagnosticGrant();
            final JsonNode grant;
            try {
                grant=json.readTree(saved);
                if(grant==null || !grant.isObject() || grant.size()!=2 ||
                        !grant.path("refresh_token").isTextual() || grant.path("refresh_token").asText().isBlank() ||
                        !grant.path("scope").isTextual())return invalidDiagnosticGrant();
            } catch(Exception malformed) { return invalidDiagnosticGrant(); }
            String storedScope=grant.path("scope").asText();
            if(!SCOPE.equals(storedScope))return diagnosticAccess(null,"PASS","FAIL","UNVERIFIED","UNVERIFIED",diagnosticState);
            refreshToken=grant.path("refresh_token").asText();
            if(accessToken!=null && clock.instant().isBefore(expires.minusSeconds(60))) {
                String providerScope=SCOPE.equals(lastProviderScope)?"PASS":"UNVERIFIED";
                return diagnosticAccess(accessToken,"PASS","PASS","UNVERIFIED",providerScope,diagnosticState);
            }
            diagnosticState.beginRefresh(budget);
            var form=base();form.add("grant_type","refresh_token");form.add("refresh_token",refreshToken);
            final JsonNode response;
            try {response=viewerTransport.refresh(form,budget);}
            catch(GmailFailure rejected) {
                RefreshFailureCategory category=refreshFailureCategory(rejected);
                boolean explicitRejection=switch(category) {
                    case PROVIDER_REJECTED,PROVIDER_RATE_LIMITED,PROVIDER_QUOTA_EXCEEDED -> true;
                    default -> false;
                };
                diagnosticState.refreshFailed(category,explicitRejection?SideEffectStatus.NO:SideEffectStatus.UNKNOWN);
                String authorization=category==RefreshFailureCategory.PROVIDER_REJECTED?"FAIL":"UNVERIFIED";
                return diagnosticAccess(null,"PASS","PASS",authorization,"UNVERIFIED",diagnosticState);
            }
            diagnosticState.refreshSucceeded();
            String returnedScope=response.path("scope").asText("");
            try {budget.ensureWithinDeadline();}
            catch(GmailFailure expired) {
                return diagnosticAccess(null,"PASS","PASS","PASS",
                    SCOPE.equals(returnedScope)?"PASS":"UNVERIFIED",diagnosticState);
            }
            if(!returnedScope.isBlank() && !SCOPE.equals(returnedScope))
                return diagnosticAccess(null,"PASS","PASS","PASS","FAIL",diagnosticState);
            try {accept(response,false,diagnosticState,budget);}
            catch(GmailFailure invalidResponse) {
                return diagnosticAccess(null,"PASS","PASS","UNVERIFIED",
                    SCOPE.equals(returnedScope)?"PASS":"UNVERIFIED",diagnosticState);
            }
            return diagnosticAccess(accessToken,"PASS","PASS","UNVERIFIED",
                SCOPE.equals(returnedScope)?"PASS":"UNVERIFIED",diagnosticState);
        } finally { tokenStateLock.unlock(); }
    }

    private static OAuthDiagnosticAccess invalidDiagnosticGrant() {
        return new OAuthDiagnosticAccess(null,"FAIL","UNVERIFIED","UNVERIFIED","UNVERIFIED",SideEffectStatus.NO,
            SideEffectStatus.NO,RefreshFailureCategory.NONE);
    }

    private static OAuthDiagnosticAccess diagnosticAccess(String token,String structure,String marker,String authorization,
            String scope,GmailOAuthDiagnosticExecutionState state) {
        return new OAuthDiagnosticAccess(token,structure,marker,authorization,scope,
            state.tokenRefreshStatus(),state.grantPersistenceStatus(),state.refreshFailureCategory());
    }

    private static RefreshFailureCategory refreshFailureCategory(GmailFailure failure) {
        if(failure.diagnosticCategory()==GmailFailure.DiagnosticCategory.NONE && failure.status()==401)
            return RefreshFailureCategory.PROVIDER_REJECTED;
        return switch(failure.diagnosticCategory()) {
            case GMAIL_REQUEST_TIMEOUT -> RefreshFailureCategory.TIMEOUT;
            case GMAIL_NETWORK_FAILURE -> RefreshFailureCategory.NETWORK_FAILURE;
            case GMAIL_PROVIDER_AUTH_FAILURE -> RefreshFailureCategory.PROVIDER_REJECTED;
            case GMAIL_PROVIDER_PERMISSION_FAILURE, GMAIL_PROVIDER_REQUEST_FAILURE -> RefreshFailureCategory.UNVERIFIED;
            case GMAIL_PROVIDER_RATE_LIMIT -> RefreshFailureCategory.PROVIDER_RATE_LIMITED;
            case GMAIL_PROVIDER_QUOTA_EXCEEDED -> RefreshFailureCategory.PROVIDER_QUOTA_EXCEEDED;
            case GMAIL_PROVIDER_UNAVAILABLE -> RefreshFailureCategory.UPSTREAM_UNAVAILABLE;
            case GMAIL_UNEXPECTED_RETRIEVAL_FAILURE -> RefreshFailureCategory.INVALID_PROVIDER_RESPONSE;
            default -> RefreshFailureCategory.OTHER_FAILURE;
        };
    }

    private static GmailFailure diagnosticTimeout() {
        return new GmailFailure(503,"Gmail OAuth profile verification timed out","UNSPECIFIED",
            GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT);
    }
    /** Returns the question-time Gmail token with a separate count for its optional OAuth refresh. */
    public GmailQuestionToken accessTokenForGmailQuestion() {
        tokenStateLock.lock();try{return questionTokenLocked();}finally{tokenStateLock.unlock();}
    }
    private GmailQuestionToken questionTokenLocked() {
        boolean refreshNeeded=accessToken==null || !clock.instant().isBefore(expires.minusSeconds(60));
        String value=accessTokenLocked(null);
        return new GmailQuestionToken(value,refreshNeeded?1:0);
    }
    /** Deadline-aware viewer acquisition shares the same token-state lock as OAuth, Ask and sync. */
    public GmailQuestionToken accessTokenForGmailViewer(GmailRequestBudget budget) {
        if(budget==null)throw new IllegalArgumentException("Viewer deadline is required");
        long remaining=budget.remainingNanos();
        try {
            if(!tokenStateLock.tryLock(remaining,TimeUnit.NANOSECONDS))throw viewerTimeout();
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw viewerTimeout();
        }
        try {
            budget.ensureWithinDeadline();
            boolean refreshNeeded=accessToken==null || !clock.instant().isBefore(expires.minusSeconds(60));
            String value=accessTokenLocked(budget);
            budget.ensureWithinDeadline();
            return new GmailQuestionToken(value,refreshNeeded?1:0);
        } finally {tokenStateLock.unlock();}
    }
    private static GmailFailure viewerTimeout() {
        return new GmailFailure(503,"Gmail message request timed out","UNSPECIFIED",
            GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT);
    }
    private JsonNode exchangeToken(LinkedMultiValueMap<String,String> form) {
        return requestedScope.equals(SCOPE) ? transport.tokenForGmail(form) : transport.token(form);
    }
    private void accept(JsonNode response, boolean requireScope) { accept(response,requireScope,null,null); }
    private void accept(JsonNode response,boolean requireScope,GmailOAuthDiagnosticExecutionState diagnosticState,GmailRequestBudget budget) {
        String scope=response.path("scope").asText("");
        if ((requireScope && scope.isBlank()) || (!scope.isBlank() && !scope.equals(requestedScope)))
            throw new GmailFailure(401,"OAuth must grant only "+requestedScope.substring(requestedScope.lastIndexOf('/')+1)+"; prior grants must not be combined");
        String next=response.path("access_token").asText("");
        if (next.isBlank()) throw new GmailFailure(401,"OAuth did not return an access token");
        String refresh=response.path("refresh_token").asText(refreshToken);
        if (refresh==null || refresh.isBlank()) throw new GmailFailure(401,"OAuth did not return an offline refresh token; revoke prior grant and retry");
        try {
            byte[] encoded=json.writeValueAsBytes(Map.of("refresh_token",refresh,"scope",requestedScope));
            if(diagnosticState!=null)diagnosticState.beginGrantPersistence(budget);
            if(bootstrapContinuation && requireScope) store.saveIfAbsent(encoded); else store.save(encoded);
            if(diagnosticState!=null)diagnosticState.grantPersistenceSucceeded();
        }
        catch (java.io.IOException e) { throw new GmailFailure(503,"Cannot encode OAuth token"); }
        refreshToken=refresh; accessToken=next;
        lastProviderScope=scope.isBlank()?null:scope;
        expires=clock.instant().plusSeconds(response.path("expires_in").asLong(3600));
    }
    private LinkedMultiValueMap<String,String> base() {
        var form=new LinkedMultiValueMap<String,String>(); form.add("client_id",clientId);
        if (!clientSecret.isBlank()) form.add("client_secret",clientSecret); return form;
    }
    private static String enc(String s) { return URLEncoder.encode(s,StandardCharsets.UTF_8); }
    private static String random() { byte[] b=new byte[32]; new SecureRandom().nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static String challenge(String s) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
