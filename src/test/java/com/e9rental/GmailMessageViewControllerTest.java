package com.e9rental;

import com.e9rental.config.LocalApiGuard;
import com.e9rental.connector.GmailFailure;
import com.e9rental.controller.GmailMessageViewController;
import com.e9rental.model.GmailMessageDetail;
import com.e9rental.service.GmailMessageSelectionRegistry;
import com.e9rental.service.GmailMessageViewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GmailMessageViewControllerTest {
    private static final String PRINCIPAL="a".repeat(64);
    private static final String SESSION="00000000-0000-4000-8000-000000000001";
    private final GmailMessageViewService service=mock(GmailMessageViewService.class);
    private final GmailMessageViewController controller=new GmailMessageViewController(service,Fixtures.JSON,
        "http://127.0.0.1:5176,http://localhost:5176");

    @Test void freshSameOriginApprovalIsBoundToCurrentBearerAndBrowserSession() throws Exception {
        var request=request("{\"selection_id\":\"synthetic-selection-handle\"}");
        when(service.approve("synthetic-selection-handle",new GmailMessageSelectionRegistry.RequestScope(PRINCIPAL,SESSION)))
            .thenReturn("synthetic-one-use-approval");
        var response=controller.approve(request);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat((java.util.Map<String,String>)response.getBody()).containsEntry("approval_token","synthetic-one-use-approval");
        verify(service,times(1)).approve("synthetic-selection-handle",new GmailMessageSelectionRegistry.RequestScope(PRINCIPAL,SESSION));
    }
    @Test void crossOriginAndMalformedApprovalRequestsAreRejectedBeforeService() {
        var cross=request("{\"selection_id\":\"synthetic-selection-handle\"}");cross.removeHeader("Origin");cross.addHeader("Origin","http://attacker.invalid");
        assertThatThrownBy(()->controller.approve(cross)).isInstanceOf(GmailFailure.class);
        var malformed=request("{\"selection_id\":\"a\",\"extra\":true}");
        assertThatThrownBy(()->controller.approve(malformed)).isInstanceOf(GmailFailure.class);
        verifyNoInteractions(service);
    }
    @Test void detailOperationsRequireTheSearchSessionCapability() {
        var request=request("{\"approval_token\":\"synthetic-approval\"}");request.removeHeader("X-Client-Session");
        assertThatThrownBy(()->controller.view(request)).isInstanceOf(GmailFailure.class);
        verifyNoInteractions(service);
    }
    @Test void detailResponseContainsOnlyTheMinimalTransientDto() throws Exception {
        var detail=new GmailMessageDetail("Synthetic subject","sender@example.test",null,Instant.parse("2026-10-19T12:00:00Z"),
            "Synthetic body",true,false,false,List.of("invoice.pdf"),false);
        when(service.view(eq("synthetic-one-use-approval"),eq(new GmailMessageSelectionRegistry.RequestScope(PRINCIPAL,SESSION)),any())).thenReturn(detail);
        var request=request("{\"approval_token\":\"synthetic-one-use-approval\"}");
        var response=controller.view(request);
        assertThat(response.getStatusCode().value()).isEqualTo(200);assertThat(response.getBody()).isEqualTo(detail);
        String json=Fixtures.JSON.writeValueAsString(response.getBody());
        assertThat(json).contains("Synthetic subject","Synthetic body","invoice.pdf").doesNotContain("synthetic-one-use-approval","sourceId","messageId","accountId");
        verify(service,times(1)).view(eq("synthetic-one-use-approval"),eq(new GmailMessageSelectionRegistry.RequestScope(PRINCIPAL,SESSION)),any());
    }
    @Test void viewerControllerStopsWaitingAtItsLocalDeadlineAndDropsLateDetail() throws Exception {
        try(var operations=new com.e9rental.service.GmailViewerOperationExecutor()) {
        var shortDeadlineController=new GmailMessageViewController(service,operations,Fixtures.JSON,
            "http://127.0.0.1:5176,http://localhost:5176",
            ()->com.e9rental.connector.GmailRequestBudget.singleMessageView(java.time.Duration.ofMillis(100)));
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var request=request("{\"approval_token\":\"synthetic-one-use-approval\"}");
        when(service.view(eq("synthetic-one-use-approval"),eq(new GmailMessageSelectionRegistry.RequestScope(PRINCIPAL,SESSION)),any()))
            .thenAnswer(call->{entered.countDown();boolean done=false;while(!done)try{done=release.await(20,java.util.concurrent.TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}
                return new GmailMessageDetail("PRIVATE_LATE_SUBJECT",null,null,null,"PRIVATE_LATE_BODY",true,false,false,List.of(),false);});
        long started=System.nanoTime();
        assertThatThrownBy(()->shortDeadlineController.view(request)).isInstanceOf(GmailFailure.class)
            .hasMessage("Gmail message request timed out");
        assertThat(entered.await(100,java.util.concurrent.TimeUnit.MILLISECONDS)).isTrue();
        assertThat(java.time.Duration.ofNanos(System.nanoTime()-started)).isLessThan(java.time.Duration.ofSeconds(2));
        release.countDown();Thread.sleep(50);
        verify(service,times(1)).view(eq("synthetic-one-use-approval"),eq(new GmailMessageSelectionRegistry.RequestScope(PRINCIPAL,SESSION)),any());
        }
    }
    @Test void slowRequestBodyTimesOutBeforeApprovalOrProviderWork() throws Exception {
        try(var operations=new com.e9rental.service.GmailViewerOperationExecutor()) {
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            var bounded=controllerWithDeadline(operations,java.time.Duration.ofMillis(120));
            long started=System.nanoTime();
            assertThatThrownBy(()->bounded.view(requestWithStream(blockAfter(new byte[0],entered,release),-1)))
                .isInstanceOf(GmailFailure.class).hasMessage("Gmail message request timed out");
            assertThat(entered.await(100,TimeUnit.MILLISECONDS)).isTrue();
            assertThat(java.time.Duration.ofNanos(System.nanoTime()-started)).isLessThan(java.time.Duration.ofSeconds(1));
            verifyNoInteractions(service);
            release.countDown();
        }
    }
    @Test void freshApprovalEndpointAlsoBoundsSlowRequestBodies() throws Exception {
        try(var operations=new com.e9rental.service.GmailViewerOperationExecutor()) {
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            var bounded=controllerWithDeadline(operations,java.time.Duration.ofMillis(120));
            assertThatThrownBy(()->bounded.approve(requestWithStream(blockAfter(new byte[0],entered,release),-1)))
                .isInstanceOf(GmailFailure.class).hasMessage("Gmail message request timed out");
            assertThat(entered.await(100,TimeUnit.MILLISECONDS)).isTrue();
            verifyNoInteractions(service);
            release.countDown();
        }
    }
    @Test void embeddedTomcatReturnsTimeoutWhileClientBodyIsIncomplete() throws Exception {
        var operations=new com.e9rental.service.GmailViewerOperationExecutor();
        var bounded=controllerWithDeadline(operations,java.time.Duration.ofMillis(150));
        var tomcat=new org.apache.catalina.startup.Tomcat();
        var base=Files.createTempDirectory("gmail-viewer-body-http-");
        Socket socket=null;
        try {
            tomcat.setBaseDir(base.toString());tomcat.setPort(0);tomcat.getConnector().setPort(0);
            var context=tomcat.addContext("",base.toString());
            TomcatServletMapping.add(context,"viewer",new HttpServlet(){
                @Override protected void doPost(HttpServletRequest req,HttpServletResponse res) throws IOException {
                    req.setAttribute(LocalApiGuard.PRINCIPAL_ATTRIBUTE,PRINCIPAL);
                    try {
                        var detail=bounded.view(req);res.setStatus(200);res.setContentType("application/json");
                        res.getWriter().write(Fixtures.JSON.writeValueAsString(detail));
                    } catch(GmailFailure failure) {
                        res.setStatus(503);res.setContentType("application/json");
                        res.getWriter().write("{\"error\":\"Gmail request timed out before completion.\",\"error_category\":\""+
                            failure.diagnosticCategory().name()+"\"}");
                    }
                }
            });
            context.addServletMappingDecoded("/api/gmail/messages/view","viewer");tomcat.start();
            int port=tomcat.getConnector().getLocalPort();socket=new Socket("127.0.0.1",port);socket.setSoTimeout(2000);
            String partial="{\"approval_token\":\"PRIVATE_SLOW_BODY_SENTINEL";
            String headers="POST /api/gmail/messages/view HTTP/1.1\r\nHost: 127.0.0.1\r\n"+
                "Authorization: Bearer synthetic-local-api-token-value-0000000000000000\r\n"+
                "Origin: http://127.0.0.1:5176\r\nX-Client-Session: "+SESSION+"\r\n"+
                "Content-Type: application/json\r\nContent-Length: "+(partial.getBytes(StandardCharsets.UTF_8).length+32)+"\r\nConnection: close\r\n\r\n";
            long started=System.nanoTime();
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(partial.getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
            String response=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            assertThat(java.time.Duration.ofNanos(System.nanoTime()-started)).isLessThan(java.time.Duration.ofSeconds(1));
            assertThat(response).contains("503","GMAIL_REQUEST_TIMEOUT","Gmail request timed out before completion")
                .doesNotContain("PRIVATE_SLOW_BODY_SENTINEL");
            verifyNoInteractions(service);
        } finally {
            if(socket!=null)socket.close();
            operations.close();
            try {tomcat.stop();} finally {
                try {tomcat.destroy();} finally {
                    try(var paths=Files.walk(base)) {
                        paths.sorted(java.util.Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(IOException ignored){}});
                    } catch(IOException ignored) { /* Test-only temporary directory cleanup is best-effort. */ }
                }
            }
        }
    }
    @Test void incompleteRequestBodyTimesOutWithoutConsumingApproval() throws Exception {
        try(var operations=new com.e9rental.service.GmailViewerOperationExecutor()) {
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            byte[] prefix="{\"approval_token\":\"synthetic".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var bounded=controllerWithDeadline(operations,java.time.Duration.ofMillis(120));
            assertThatThrownBy(()->bounded.view(requestWithStream(blockAfter(prefix,entered,release),-1)))
                .isInstanceOf(GmailFailure.class).hasMessage("Gmail message request timed out");
            assertThat(entered.await(100,TimeUnit.MILLISECONDS)).isTrue();
            verifyNoInteractions(service);
            release.countDown();
        }
    }
    @Test void oversizedAndMalformedBodiesFailBeforeApprovalOrProviderWork() {
        try(var operations=new com.e9rental.service.GmailViewerOperationExecutor()) {
            var bounded=controllerWithDeadline(operations,java.time.Duration.ofSeconds(1));
            var oversized=requestWithStream(new ServletInputStream() {
                @Override public int read() { fail("declared oversized body must be rejected before reading");return -1; }
                @Override public boolean isFinished(){return false;}
                @Override public boolean isReady(){return true;}
                @Override public void setReadListener(ReadListener listener){}
            },1025);
            assertThatThrownBy(()->bounded.view(oversized)).isInstanceOf(GmailFailure.class);
            assertThatThrownBy(()->bounded.view(request("{not-json"))).isInstanceOf(GmailFailure.class);
            verifyNoInteractions(service);
        }
    }
    @Test void chunkedBodyOverTheSmallLimitIsRejectedAndViewerBodyReadersStayBounded() throws Exception {
        try(var operations=new com.e9rental.service.GmailViewerOperationExecutor()) {
            var bounded=controllerWithDeadline(operations,java.time.Duration.ofSeconds(5));
            assertThatThrownBy(()->bounded.view(requestWithStream(new ServletInputStream() {
                private int remaining=1025;
                @Override public int read(){return remaining-- > 0?'x':-1;}
                @Override public boolean isFinished(){return remaining<0;}
                @Override public boolean isReady(){return true;}
                @Override public void setReadListener(ReadListener listener){}
            },-1))).isInstanceOf(GmailFailure.class);

            var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
            var callers=java.util.concurrent.Executors.newFixedThreadPool(4);
            try {
                var pending=new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for(int i=0;i<2;i++)pending.add(submitSlowBodyCall(callers,bounded,entered,release));
                assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
                long waitForWorkers=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
                while((operations.activeWorkers()<2 || operations.availableAdmissions()!=2) && System.nanoTime()<waitForWorkers)Thread.yield();
                assertThat(operations.activeWorkers()).isEqualTo(2);
                assertThat(operations.queuedWorkers()).isZero();

                pending.add(submitSlowBodyCall(callers,bounded,entered,release));
                long waitForFirstQueued=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
                while((operations.queuedWorkers()<1 || operations.availableAdmissions()!=1) && System.nanoTime()<waitForFirstQueued)Thread.yield();
                assertThat(operations.queuedWorkers()).isEqualTo(1);
                assertThat(operations.availableAdmissions()).isEqualTo(1);

                pending.add(submitSlowBodyCall(callers,bounded,entered,release));
                long waitForSecondQueued=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
                while((operations.queuedWorkers()<2 || operations.availableAdmissions()!=0) && System.nanoTime()<waitForSecondQueued)Thread.yield();
                assertThat(operations.queuedWorkers()).isEqualTo(2);
                assertThat(operations.availableAdmissions()).isZero();
                assertThatThrownBy(()->bounded.view(request("{\"approval_token\":\"x\"}")))
                    .isInstanceOf(GmailFailure.class).extracting(e->((GmailFailure)e).diagnosticCategory())
                    .isEqualTo(GmailFailure.DiagnosticCategory.GMAIL_VIEWER_CAPACITY_EXHAUSTED);
                verifyNoInteractions(service);
                release.countDown();
                for(var future:pending)future.get(1,TimeUnit.SECONDS);
            } finally {release.countDown();callers.shutdownNow();}
        }
    }
    @Test void globalApiGuardRemainsBearerProtectedAndSetsNoStoreHeaders() throws Exception {
        String token="synthetic-local-api-token-value-0000000000000000";
        LocalApiGuard guard=new LocalApiGuard(token);
        var request=new MockHttpServletRequest("POST","/api/gmail/messages/view");request.addHeader("Authorization","Bearer "+token);
        var response=new MockHttpServletResponse();
        guard.doFilter(request,response,(req,res)->{});
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(request.getAttribute(LocalApiGuard.PRINCIPAL_ATTRIBUTE)).isEqualTo(PRINCIPAL_FOR(token));
        assertThat(request.getAttribute(LocalApiGuard.PRINCIPAL_ATTRIBUTE)).isNotEqualTo(token);
        var deniedRequest=new MockHttpServletRequest("POST","/api/gmail/messages/view");
        var deniedResponse=new MockHttpServletResponse();guard.doFilter(deniedRequest,deniedResponse,(req,res)->fail("guard must reject missing bearer"));
        assertThat(deniedResponse.getStatus()).isEqualTo(401);assertThat(deniedResponse.getHeader("Cache-Control")).isEqualTo("no-store");
    }
    private static String PRINCIPAL_FOR(String token) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static MockHttpServletRequest request(String json) {
        var request=new MockHttpServletRequest("POST","/api/gmail/messages/view-approval");
        request.addHeader("Origin","http://127.0.0.1:5176");request.addHeader("X-Client-Session",SESSION);
        request.setAttribute(LocalApiGuard.PRINCIPAL_ATTRIBUTE,PRINCIPAL);request.setContent(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));return request;
    }
    private GmailMessageViewController controllerWithDeadline(com.e9rental.service.GmailViewerOperationExecutor operations,java.time.Duration deadline) {
        return new GmailMessageViewController(service,operations,Fixtures.JSON,"http://127.0.0.1:5176,http://localhost:5176",
            ()->com.e9rental.connector.GmailRequestBudget.singleMessageView(deadline));
    }
    private static MockHttpServletRequest requestWithStream(ServletInputStream input,long contentLength) {
        var request=new MockHttpServletRequest("POST","/api/gmail/messages/view") {
            @Override public ServletInputStream getInputStream(){return input;}
            @Override public long getContentLengthLong(){return contentLength;}
        };
        request.addHeader("Origin","http://127.0.0.1:5176");request.addHeader("X-Client-Session",SESSION);
        request.setAttribute(LocalApiGuard.PRINCIPAL_ATTRIBUTE,PRINCIPAL);return request;
    }
    private static ServletInputStream blockAfter(byte[] prefix,CountDownLatch entered,CountDownLatch release) {
        return new ServletInputStream() {
            private final ByteArrayInputStream available=new ByteArrayInputStream(prefix);
            @Override public int read() throws IOException {
                if(available.available()>0)return available.read();
                entered.countDown();
                try {release.await();return -1;}
                catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new IOException("synthetic slow body interrupted");}
            }
            @Override public boolean isFinished(){return available.available()==0 && release.getCount()==0;}
            @Override public boolean isReady(){return true;}
            @Override public void setReadListener(ReadListener listener){}
        };
    }
    private static java.util.concurrent.Future<?> submitSlowBodyCall(java.util.concurrent.ExecutorService callers,
            GmailMessageViewController bounded,CountDownLatch entered,CountDownLatch release) {
        return callers.submit(()->{
            try {bounded.view(requestWithStream(blockAfter(new byte[0],entered,release),-1));}
            catch(GmailFailure expected) { /* Slow synthetic body is rejected or times out. */ }
        });
    }
    private static final class TomcatServletMapping {
        static void add(org.apache.catalina.Context context,String name,HttpServlet servlet) {
            org.apache.catalina.startup.Tomcat.addServlet(context,name,servlet);
        }
    }
}
