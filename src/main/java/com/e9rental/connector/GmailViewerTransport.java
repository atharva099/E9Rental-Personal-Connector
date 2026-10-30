package com.e9rental.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

/** Viewer-only transport. Existing Ask, sync, export and OAuth transports are intentionally unchanged. */
@Component
public final class GmailViewerTransport {
    private static final int MAX_OAUTH_RESPONSE_BYTES=64*1024;
    private static final String DEFAULT_GMAIL_BASE="https://gmail.googleapis.com/gmail/v1/users/me";
    private static final String DEFAULT_TOKEN_URI="https://oauth2.googleapis.com/token";
    private final HttpClient client;
    private final ObjectMapper json=new ObjectMapper();
    private final URI gmailBase;
    private final URI tokenUri;

    public GmailViewerTransport() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build(),URI.create(DEFAULT_GMAIL_BASE),URI.create(DEFAULT_TOKEN_URI));
    }

    /** Injectable endpoints/client are used only by synthetic transport tests. */
    GmailViewerTransport(HttpClient client,URI gmailBase,URI tokenUri) {
        this.client=client;this.gmailBase=gmailBase;this.tokenUri=tokenUri;
        this.json.getFactory().setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
            .maxNestingDepth(64).maxStringLength(GmailRequestBudget.DIRECT_QUESTION_MAX_SINGLE_RESPONSE_BYTES).build());
    }

    public URI gmailUri(String path) {
        String suffix=path.startsWith("/")?path.substring(1):path;
        return URI.create(gmailBase.toString().replaceAll("/+$","")+"/"+suffix);
    }

    public JsonNode get(URI uri,String accessToken,GmailRequestBudget budget,GmailRequestBudget.Operation operation) {
        budget.ensureWithinDeadline();
        if(budget.remainingResponseBytes()<1)throw GmailRequestBudget.responseSizeFailure();
        budget.consumeAttempt(operation);
        HttpRequest request=baseRequest(uri,budget).header("Authorization","Bearer "+accessToken).GET().build();
        return executeJson(request,budget,Math.min(budget.remainingResponseBytes(),budget.maxSingleResponseBytes()),false);
    }

    public JsonNode refresh(MultiValueMap<String,String> form,GmailRequestBudget budget) {
        budget.ensureWithinDeadline();
        StringBuilder encoded=new StringBuilder();
        for(Map.Entry<String,List<String>> entry:form.entrySet())for(String value:entry.getValue()) {
            if(encoded.length()>0)encoded.append('&');
            encoded.append(URLEncoder.encode(entry.getKey(),StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(value,StandardCharsets.UTF_8));
        }
        HttpRequest request=baseRequest(tokenUri,budget).header("Content-Type","application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(encoded.toString(),StandardCharsets.UTF_8)).build();
        return executeJson(request,budget,MAX_OAUTH_RESPONSE_BYTES,true);
    }

    private HttpRequest.Builder baseRequest(URI uri,GmailRequestBudget budget) {
        long remaining=budget.remainingNanos();
        if(remaining==Long.MAX_VALUE)throw new IllegalArgumentException("A bounded viewer deadline is required");
        return HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(Math.max(1,remaining)));
    }

    private JsonNode executeJson(HttpRequest request,GmailRequestBudget budget,int maxBytes,boolean oauth) {
        long remainingBeforeDispatch=budget.remainingNanos();
        if(maxBytes<1)throw GmailRequestBudget.responseSizeFailure();
        CompletableFuture<HttpResponse<byte[]>> response=client.sendAsync(request,
            info->new BoundedBodySubscriber(maxBytes));
        try {
            long remainingAfterDispatch=budget.remainingNanos();
            HttpResponse<byte[]> result=response.get(Math.min(remainingBeforeDispatch,remainingAfterDispatch),TimeUnit.NANOSECONDS);
            budget.ensureWithinDeadline();
            byte[] bytes=result.body();
            if(bytes.length>maxBytes)throw GmailRequestBudget.responseSizeFailure();
            if(!oauth)budget.recordResponseBytes(bytes.length);
            if(result.statusCode()<200 || result.statusCode()>=300)throw providerFailure(result.statusCode(),safeReason(bytes));
            try {
                JsonNode parsed=json.readTree(bytes);
                if(parsed==null || !parsed.isObject())throw new java.io.IOException("invalid JSON shape");
                budget.ensureWithinDeadline();
                return parsed;
            } catch(java.io.IOException malformed) {
                throw new GmailFailure(502,oauth?"OAuth refresh response is invalid":"Gmail response is invalid","UNSPECIFIED",
                    GmailFailure.DiagnosticCategory.GMAIL_UNEXPECTED_RETRIEVAL_FAILURE);
            }
        } catch(TimeoutException timeout) {
            response.cancel(true);
            throw timeoutFailure();
        } catch(GmailFailure failure) {
            // A deadline check can fail after sendAsync has created an exchange. Always
            // make a cancellation attempt before propagating any post-dispatch failure.
            response.cancel(true);
            throw failure;
        } catch(InterruptedException interrupted) {
            response.cancel(true);Thread.currentThread().interrupt();throw timeoutFailure();
        } catch(ExecutionException failed) {
            Throwable cause=failed.getCause();
            for(Throwable current=cause;current!=null;current=current.getCause()) {
                if(current instanceof GmailFailure failure)throw failure;
                if(current instanceof ResponseTooLarge)throw GmailRequestBudget.responseSizeFailure();
                if(current instanceof java.net.http.HttpTimeoutException)throw timeoutFailure();
            }
            budget.ensureWithinDeadline();
            throw new GmailFailure(503,oauth?"OAuth refresh failed":"Gmail request failed","UNSPECIFIED",
                GmailFailure.DiagnosticCategory.GMAIL_NETWORK_FAILURE);
        }
    }

    private static GmailFailure timeoutFailure() {
        return new GmailFailure(503,"Gmail message request timed out","UNSPECIFIED",
            GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT);
    }

    private static GmailFailure providerFailure(int status,String reason) {
        GmailFailure.DiagnosticCategory category=switch(status) {
            case 401 -> GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_AUTH_FAILURE;
            case 403 -> "RATE_LIMIT".equals(reason)?GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_RATE_LIMIT:
                GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_PERMISSION_FAILURE;
            case 429 -> GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_RATE_LIMIT;
            case 500,502,503,504 -> GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_UNAVAILABLE;
            default -> GmailFailure.DiagnosticCategory.GMAIL_PROVIDER_REQUEST_FAILURE;
        };
        int safeStatus=status==401?401:status==403?403:status==429?429:status>=500?503:502;
        throw new GmailFailure(safeStatus,"Gmail message retrieval failed",reason,category);
    }

    private static String safeReason(byte[] bytes) {
        try {
            JsonNode root=new ObjectMapper().readTree(bytes).path("error");
            for(JsonNode item:root.path("errors")) {
                String value=item.path("reason").asText("");
                if(value.equals("rateLimitExceeded")||value.equals("userRateLimitExceeded"))return "RATE_LIMIT";
                if(value.equals("quotaExceeded")||value.equals("dailyLimitExceeded"))return "QUOTA_EXCEEDED";
                if(value.equals("insufficientPermissions"))return "INSUFFICIENT_SCOPE";
            }
        } catch(Exception ignored) { /* Never expose provider response content. */ }
        return "UNSPECIFIED";
    }

    private static final class ResponseTooLarge extends RuntimeException {}

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum;
        private final ByteArrayOutputStream output=new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();
        private Flow.Subscription subscription;
        BoundedBodySubscriber(int maximum){this.maximum=maximum;}
        @Override public java.util.concurrent.CompletionStage<byte[]> getBody(){return body;}
        @Override public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        @Override public void onNext(List<ByteBuffer> items) {
            try {
                for(ByteBuffer item:items) {
                    int size=item.remaining();
                    if(size>maximum-output.size())throw new ResponseTooLarge();
                    byte[] bytes=new byte[size];item.get(bytes);output.write(bytes);
                }
                subscription.request(1);
            } catch(RuntimeException | java.io.IOException tooLarge) {
                subscription.cancel();body.completeExceptionally(new ResponseTooLarge());
            }
        }
        @Override public void onError(Throwable failure){body.completeExceptionally(failure);}
        @Override public void onComplete(){body.complete(output.toByteArray());}
    }
}
