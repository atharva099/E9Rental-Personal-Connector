package com.e9rental.connector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.*;
@Component
public class GoogleTransport {
    private final RestClient client;
    private final RestClient directQuestionClient;
    private final long delayMillis;
    private final String dataMode;
    @org.springframework.beans.factory.annotation.Autowired
    public GoogleTransport(@org.springframework.beans.factory.annotation.Value("${GMAIL_REQUEST_DELAY_MS:200}") long delayMillis) {
        if (delayMillis<0 || delayMillis>10000) throw new IllegalArgumentException("Invalid Gmail request delay");
        this.delayMillis=delayMillis;
        this.dataMode="LIVE";
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10)); factory.setReadTimeout(Duration.ofSeconds(30));
        client = RestClient.builder().requestFactory(factory).build();
        var questionFactory=new SimpleClientHttpRequestFactory();
        questionFactory.setConnectTimeout(Duration.ofSeconds(5));questionFactory.setReadTimeout(Duration.ofSeconds(8));
        directQuestionClient=RestClient.builder().requestFactory(questionFactory).build();
    }
    /** Custom transports are used for local, synthetic provider fixtures only. */
    public GoogleTransport(RestClient client) { this.client = client;this.directQuestionClient=client; this.delayMillis=0; this.dataMode="SYNTHETIC"; }
    public String dataMode() { return dataMode; }
    public JsonNode get(String url, String token) {
        return get(url, token, null, false);
    }
    /** Gmail question retrieval supplies one shared budget for all logical calls and retries. */
    public JsonNode getQuestion(String url, String token, GmailRequestBudget budget) {
        if (budget == null) throw new IllegalArgumentException("Gmail question retrieval budget is required");
        return get(url, token, budget, true);
    }
    /** Direct user questions use one attempt per operation, bounded response bytes and no retries. */
    public JsonNode getQuestionOnce(String url,String token,GmailRequestBudget budget,GmailRequestBudget.Operation operation) {
        if(budget==null || operation==null)throw new IllegalArgumentException("Direct Gmail request budget is required");
        // Do not send another request once this retrieval can no longer accept a
        // bounded response, even if the request/operation budget has room left.
        if(budget.remainingResponseBytes()<1)throw GmailRequestBudget.responseSizeFailure();
        budget.consumeAttempt(operation);
        pause(delayMillis);
        budget.ensureWithinDeadline();
        try {
            return directQuestionClient.get().uri(java.net.URI.create(url)).headers(h->h.setBearerAuth(token)).exchange((request,response)->{
                if(!response.getStatusCode().is2xxSuccessful()) {
                    byte[] errorBody=response.getBody().readNBytes(65536);
                    throw providerFailure(response.getStatusCode().value(),safeReason(errorBody),true);
                }
                int maximum=Math.min(budget.remainingResponseBytes(),budget.maxSingleResponseBytes());
                long contentLength=response.getHeaders().getContentLength();
                if(maximum<1 || contentLength>maximum)throw GmailRequestBudget.responseSizeFailure();
                byte[] payload=response.getBody().readNBytes(maximum+1);
                if(payload.length>maximum)throw GmailRequestBudget.responseSizeFailure();
                budget.recordResponseBytes(payload.length);
                try {
                    ObjectMapper parser=new ObjectMapper();
                    parser.getFactory().setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                        .maxNestingDepth(64).maxStringLength(GmailRequestBudget.DIRECT_QUESTION_MAX_SINGLE_RESPONSE_BYTES).build());
                    return parser.readTree(payload);
                }
                catch(java.io.IOException malformed) {
                    throw new GmailFailure(502,"Invalid Gmail response","UNSPECIFIED",
                        GmailFailure.DiagnosticCategory.GMAIL_UNEXPECTED_RETRIEVAL_FAILURE);
                }
            });
        } catch(GmailFailure failure) { throw failure; }
        catch(ResourceAccessException failure) {
            throw new GmailFailure(503,"Google service unavailable","UNSPECIFIED",networkCategory(failure));
        } catch(RestClientException failure) {
            throw new GmailFailure(503,"Google service unavailable","UNSPECIFIED",
                GmailFailure.DiagnosticCategory.GMAIL_UNEXPECTED_RETRIEVAL_FAILURE);
        }
    }
    private JsonNode get(String url, String token, GmailRequestBudget budget,boolean gmailDiagnostics) {
        for (int attempt=0; ; attempt++) {
            if (budget != null) budget.consumeAttempt();
            pause(delayMillis);
            try { return client.get().uri(java.net.URI.create(url)).headers(h -> h.setBearerAuth(token)).retrieve().body(JsonNode.class); }
            catch (RestClientResponseException e) {
                int code=e.getStatusCode().value();
                boolean quotaRate=code==403 && safeReason(e).equals("RATE_LIMIT");
                int maxRetries=quotaRate ? 5 : 2;
                if ((quotaRate || code==429 || code>=500) && attempt<maxRetries) {
                    pause(quotaRate ? 1000L * (1L << attempt) : 200L * (1L << attempt));
                    continue;
                }
                String reason=safeReason(e);
                throw new GmailFailure(code, code==401 ? "Gmail authorization expired; authorize again" :
                    code==403 ? "Gmail access denied; check API enablement and account permissions" : "Gmail read request failed",
                    reason,gmailDiagnostics?providerCategory(code,reason):GmailFailure.DiagnosticCategory.NONE);
            } catch (ResourceAccessException e) {
                throw new GmailFailure(503,"Google service unavailable","UNSPECIFIED",gmailDiagnostics?networkCategory(e):GmailFailure.DiagnosticCategory.NONE);
            } catch (RestClientException e) {
                throw new GmailFailure(503,"Google service unavailable","UNSPECIFIED",
                    gmailDiagnostics?GmailFailure.DiagnosticCategory.GMAIL_UNEXPECTED_RETRIEVAL_FAILURE:GmailFailure.DiagnosticCategory.NONE);
            }
        }
    }
    private static void pause(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new GmailFailure(503,"Request interrupted"); }
    }
    public byte[] download(String url,String token,int maxBytes) {
        for (int attempt=0; ; attempt++) {
            pause(delayMillis);
            try {
                return client.get().uri(java.net.URI.create(url)).headers(h->h.setBearerAuth(token)).exchange((req,res)->{
                    if (res.getStatusCode().isError()) throw new RestClientResponseException("Google content request failed",
                        res.getStatusCode().value(),"",res.getHeaders(),res.getBody().readNBytes(65536),java.nio.charset.StandardCharsets.UTF_8);
                    if (res.getHeaders().getContentLength()>maxBytes) throw new GmailFailure(400,"Drive file exceeds content size limit");
                    byte[] data=res.getBody().readNBytes(maxBytes+1);
                    if (data.length>maxBytes) throw new GmailFailure(400,"Drive file exceeds content size limit");
                    return data;
                });
            } catch (RestClientResponseException e) {
                int code=e.getStatusCode().value(); boolean rate=code==403 && safeReason(e).equals("RATE_LIMIT");
                if ((rate || code==429 || code>=500) && attempt<(rate ? 5 : 2)) {
                    pause(rate ? 1000L*(1L<<attempt) : 200L*(1L<<attempt)); continue;
                }
                throw new GmailFailure(code,"Drive content request failed",safeReason(e));
            } catch (RestClientException e) { throw new GmailFailure(503,"Drive content service unavailable"); }
        }
    }
    public JsonNode token(MultiValueMap<String,String> form) {
        return token(form,false);
    }
    public JsonNode tokenForGmail(MultiValueMap<String,String> form) {
        return token(form,true);
    }
    private JsonNode token(MultiValueMap<String,String> form,boolean gmailDiagnostics) {
        try { return client.post().uri("https://oauth2.googleapis.com/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(JsonNode.class); }
        catch (RestClientResponseException e) {
            String reason=safeReason(e);
            throw new GmailFailure(401,"OAuth exchange failed; check credentials or authorize again",reason,
                gmailDiagnostics?tokenProviderCategory(e.getStatusCode().value(),reason):GmailFailure.DiagnosticCategory.NONE);
        } catch (ResourceAccessException e) {
            throw new GmailFailure(401,"OAuth exchange failed; check credentials or authorize again","UNSPECIFIED",
                gmailDiagnostics?networkCategory(e):GmailFailure.DiagnosticCategory.NONE);
        } catch (RestClientException e) {
            throw new GmailFailure(401,"OAuth exchange failed; check credentials or authorize again","UNSPECIFIED",
                gmailDiagnostics?GmailFailure.DiagnosticCategory.GMAIL_UNEXPECTED_RETRIEVAL_FAILURE:GmailFailure.DiagnosticCategory.NONE);
        }
    }
    private static String safeReason(RestClientResponseException failure) {
        return safeReason(failure.getResponseBodyAsByteArray());
    }
    private static String safeReason(byte[] responseBody) {
        try {
            JsonNode error=new ObjectMapper().readTree(responseBody).path("error");
            java.util.List<String> reasons=new java.util.ArrayList<>();
            for (JsonNode entry:error.path("errors")) reasons.add(entry.path("reason").asText());
            for (JsonNode entry:error.path("details")) reasons.add(entry.path("reason").asText());
            for (String reason:reasons) {
                switch(reason) {
                    case "accessNotConfigured", "SERVICE_DISABLED" -> { return "SERVICE_DISABLED"; }
                    case "insufficientPermissions", "ACCESS_TOKEN_SCOPE_INSUFFICIENT" -> { return "INSUFFICIENT_SCOPE"; }
                    case "domainPolicy" -> { return "DOMAIN_POLICY"; }
                    case "rateLimitExceeded", "userRateLimitExceeded" -> { return "RATE_LIMIT"; }
                    case "quotaExceeded", "dailyLimitExceeded" -> { return "QUOTA_EXCEEDED"; }
                    default -> { }
                }
            }
        } catch (Exception ignored) { /* Never expose raw provider bodies or messages. */ }
        return "UNSPECIFIED";
    }

    private static GmailFailure providerFailure(int code,String reason,boolean diagnostic) {
        String safe=reason==null?"UNSPECIFIED":reason;
        return new GmailFailure(code,code==401?"Gmail authorization expired; authorize again":
            code==403?"Gmail access denied; check API enablement and account permissions":"Gmail read request failed",
            safe,diagnostic?providerCategory(code,safe):GmailFailure.DiagnosticCategory.NONE);
    }

    private static GmailFailure.DiagnosticCategory providerCategory(int status,String reason) {
        if(status==401)return GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_AUTH_FAILURE;
        if(status==429 || (status==403 && "RATE_LIMIT".equals(reason)))
            return GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_RATE_LIMIT;
        if(status==403 && "QUOTA_EXCEEDED".equals(reason))
            return GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_QUOTA_EXCEEDED;
        if(status==403)return GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_PERMISSION_FAILURE;
        if(status>=500)return GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_UNAVAILABLE;
        return GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_REQUEST_FAILURE;
    }

    private static GmailFailure.DiagnosticCategory tokenProviderCategory(int status,String reason) {
        if(status==429 || (status==403 && "RATE_LIMIT".equals(reason)))
            return GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_RATE_LIMIT;
        if(status==403 && "QUOTA_EXCEEDED".equals(reason))
            return GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_QUOTA_EXCEEDED;
        if(status==400 || status==401 || status==403)
            return GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_AUTH_FAILURE;
        if(status>=500)return GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_UNAVAILABLE;
        return GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_REQUEST_FAILURE;
    }

    private static GmailFailure.DiagnosticCategory networkCategory(ResourceAccessException failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause())
            if(cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException)
                return GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT;
        return GmailFailure.DiagnosticCategory.GMAIL_NETWORK_FAILURE;
    }
}
