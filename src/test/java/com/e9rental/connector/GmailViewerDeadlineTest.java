package com.e9rental.connector;

import com.e9rental.service.GmailMessageBodyExtractor;
import com.e9rental.service.EncryptedTokenStore;
import com.e9rental.service.OAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.util.LinkedMultiValueMap;

class GmailViewerDeadlineTest {
    @Test void deadlineExpiringImmediatelyAfterDispatchCancelsFutureAndBlocksLaterProviderCalls() {
        AtomicLong nanos=new AtomicLong();
        long deadline=TimeUnit.SECONDS.toNanos(1);
        var budget=GmailRequestBudget.singleMessageView(Duration.ofSeconds(1),nanos::get);
        AtomicBoolean cancellationAttempted=new AtomicBoolean();
        CompletableFuture<HttpResponse<byte[]>> pending=new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                cancellationAttempted.set(true);
                return super.cancel(mayInterruptIfRunning);
            }
        };
        HttpClient client=mock(HttpClient.class);
        try {
            doAnswer(invocation->{nanos.set(deadline);return pending;})
                .when(client).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        } catch(Exception impossible) {
            throw new AssertionError(impossible);
        }
        var transport=new GmailViewerTransport(client,URI.create("https://gmail.googleapis.test/gmail/v1/users/me"),
            URI.create("https://oauth.googleapis.test/token"));
        URI profile=URI.create("https://gmail.googleapis.test/gmail/v1/users/me/profile");

        assertThatThrownBy(()->transport.get(profile,"synthetic-token",budget,GmailRequestBudget.Operation.PROFILE))
            .isInstanceOf(GmailFailure.class).hasMessage("Gmail question retrieval time limit exceeded").hasNoCause();
        assertThat(cancellationAttempted).isTrue();
        assertThat(pending).isCancelled();
        verify(client,times(1)).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));

        assertThatThrownBy(()->transport.get(profile,"synthetic-token",budget,GmailRequestBudget.Operation.PROFILE))
            .isInstanceOf(GmailFailure.class);
        verifyNoMoreInteractions(client);
    }

    @Test void slowProfileResponseTimesOutWithoutRetryOrMessageRequest() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        try(var server=server(requests,700,"{\"emailAddress\":\"synthetic@example.test\"}")) {
            var transport=transport(server.http);
            var budget=GmailRequestBudget.singleMessageView(Duration.ofMillis(300));
            long started=System.nanoTime();
            assertThatThrownBy(()->transport.get(uri(server.http,"/profile"),"synthetic-token",budget,GmailRequestBudget.Operation.PROFILE))
                .isInstanceOf(GmailFailure.class).hasMessage("Gmail message request timed out")
                .extracting(e->((GmailFailure)e).diagnosticCategory()).isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_REQUEST_TIMEOUT);
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofMillis(650));
            assertThat(requests).hasValue(1);
            assertThat(budget.snapshot().totalAttempts()).isEqualTo(1);
        }
    }

    @Test void slowFullMessageResponseTimesOutOnceAndReturnsNoBody() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        try(var server=server(requests,700,"{\"id\":\"private-synthetic-body\"}")) {
            var transport=transport(server.http);
            var budget=GmailRequestBudget.singleMessageView(Duration.ofMillis(300));
            assertThatThrownBy(()->transport.get(uri(server.http,"/messages/x?format=full"),"synthetic-token",budget,
                GmailRequestBudget.Operation.MESSAGE)).isInstanceOf(GmailFailure.class)
                .hasMessage("Gmail message request timed out").hasNoCause();
            assertThat(requests).hasValue(1);
            assertThat(budget.snapshot().messageRequests()).isEqualTo(1);
        }
    }

    @Test void slowOAuthRefreshTimesOutWithOneTokenRequestAndNoSensitiveResponse() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        try(var server=server(requests,700,"{\"access_token\":\"synthetic-late-secret\"}")) {
            var transport=transport(server.http);
            var form=new LinkedMultiValueMap<String,String>();form.add("grant_type","refresh_token");form.add("refresh_token","synthetic-refresh");
            var budget=GmailRequestBudget.singleMessageView(Duration.ofMillis(300));
            assertThatThrownBy(()->transport.refresh(form,budget)).isInstanceOf(GmailFailure.class)
                .hasMessage("Gmail message request timed out").hasNoCause();
            assertThat(requests).hasValue(1);
            assertThat(budget.snapshot().totalAttempts()).isZero(); // OAuth is separate from the Gmail REST budget.
        }
    }

    @Test void OAuthServiceDoesNotAcceptOrPersistALateRefreshResult() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        try(var server=server(requests,700,"{\"access_token\":\"synthetic-late-secret\",\"refresh_token\":\"synthetic-refresh\",\"expires_in\":3600}")) {
            var viewerTransport=transport(server.http);
            var store=mock(EncryptedTokenStore.class);
            when(store.load()).thenReturn(("{\"refresh_token\":\"synthetic-refresh\",\"scope\":\""+OAuthService.SCOPE+"\"}").getBytes(StandardCharsets.UTF_8));
            var oauth=new OAuthService(mock(GoogleTransport.class),viewerTransport,store,new ObjectMapper(),java.time.Clock.systemUTC(),
                "synthetic-client","synthetic-secret","http://127.0.0.1:1/",OAuthService.SCOPE,"/",false);
            var budget=GmailRequestBudget.singleMessageView(Duration.ofMillis(300));
            assertThatThrownBy(()->oauth.accessTokenForGmailViewer(budget)).isInstanceOf(GmailFailure.class);
            verify(store,never()).save(any(byte[].class));
            assertThat(requests).hasValue(1);
        }
    }

    @Test void mimeAndBodyCheckpointsObserveOneInjectedMonotonicBudget() throws Exception {
        AtomicLong nanos=new AtomicLong();
        var budget=GmailRequestBudget.singleMessageView(Duration.ofMillis(25),()->nanos.getAndAdd(TimeUnit.MILLISECONDS.toNanos(10)));
        String encoded=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("synthetic body".getBytes(StandardCharsets.UTF_8));
        var payload=new ObjectMapper().readTree("{\"mimeType\":\"multipart/mixed\",\"parts\":[{\"mimeType\":\"text/plain\",\"body\":{\"data\":\""+encoded+"\"}}]}");
        assertThatThrownBy(()->new GmailMessageBodyExtractor().extract(payload,budget)).isInstanceOf(GmailFailure.class)
            .hasMessage("Gmail question retrieval time limit exceeded");

        AtomicLong bodyNanos=new AtomicLong();
        var bodyBudget=GmailRequestBudget.singleMessageView(Duration.ofMillis(45),()->bodyNanos.getAndAdd(TimeUnit.MILLISECONDS.toNanos(10)));
        var bodyPayload=new ObjectMapper().readTree("{\"mimeType\":\"text/plain\",\"body\":{\"data\":\""+encoded+"\"}}");
        assertThatThrownBy(()->new GmailMessageBodyExtractor().extract(bodyPayload,bodyBudget)).isInstanceOf(GmailFailure.class);
    }

    @Test void alreadyExpiredBudgetMakesNoProviderCall() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        try(var server=server(requests,0,"{}")) {
            var budget=GmailRequestBudget.singleMessageView(Duration.ofMillis(1));Thread.sleep(15);
            assertThatThrownBy(()->transport(server.http).get(uri(server.http,"/profile"),"synthetic-token",budget,GmailRequestBudget.Operation.PROFILE))
                .isInstanceOf(GmailFailure.class);
            assertThat(requests).hasValue(0);
        }
    }

    @Test void singleResponseStreamingLimitRejectsOversizedBodyWithoutRetry() throws Exception {
        AtomicInteger requests=new AtomicInteger();
        String response="{\"padding\":\""+"x".repeat(GmailRequestBudget.DIRECT_QUESTION_MAX_SINGLE_RESPONSE_BYTES)+"\"}";
        try(var server=server(requests,0,response)) {
            var transport=transport(server.http);
            var budget=GmailRequestBudget.singleMessageView(Duration.ofSeconds(3));
            assertThatThrownBy(()->transport.get(uri(server.http,"/messages/x?format=full"),"synthetic-token",budget,
                GmailRequestBudget.Operation.MESSAGE)).isInstanceOf(GmailFailure.class)
                .extracting(failure->((GmailFailure)failure).diagnosticCategory())
                .isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_RESPONSE_SIZE_LIMIT_EXCEEDED);
            assertThat(requests).hasValue(1);
        }
    }

    private static GmailViewerTransport transport(HttpServer server) {
        return new GmailViewerTransport(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
            uri(server,"/gmail/v1/users/me"),uri(server,"/oauth/token"));
    }
    private static java.net.URI uri(HttpServer server,String path) {
        return java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+path);
    }
    private static TestServer server(AtomicInteger count,long delay,String response) throws Exception {
        HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        CountDownLatch responded=new CountDownLatch(1);
        http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"synthetic-provider");t.setDaemon(true);return t;}));
        http.createContext("/",exchange->{
            count.incrementAndGet();
            try {
                if(delay>0)Thread.sleep(delay);
                byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");
                exchange.sendResponseHeaders(200,bytes.length);
                try(var out=exchange.getResponseBody()){out.write(bytes);}
            } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
            catch(Exception ignored) { /* Client cancellation is expected in timeout cases. */ }
            finally {responded.countDown();exchange.close();}
        });
        http.start();return new TestServer(http,responded);
    }
    private record TestServer(HttpServer http,CountDownLatch responded) implements AutoCloseable {
        @Override public void close() throws Exception {responded.await(2,TimeUnit.SECONDS);http.stop(0);}
    }
}
