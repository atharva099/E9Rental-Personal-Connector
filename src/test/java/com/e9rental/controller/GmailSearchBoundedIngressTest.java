package com.e9rental.controller;

import com.e9rental.config.LocalApiGuard;
import com.e9rental.connector.GmailFailure;
import com.e9rental.connector.GmailRequestBudget;
import com.e9rental.service.GmailDirectSearchService;
import com.e9rental.service.GmailSearchOperationExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** Embedded-container coverage for slow and incomplete Live Gmail Search request bodies. */
class GmailSearchBoundedIngressTest {
    private static final String PRINCIPAL="a".repeat(64);
    private static final String SESSION="00000000-0000-4000-8000-000000000001";
    private static final String ORIGIN="http://127.0.0.1:5176";

    @Test void slowAndIncompleteBodiesTimeOutBeforeApprovalOrGmailAndRemainResourceBounded() throws Exception {
        var service=mock(GmailDirectSearchService.class);
        var executor=new GmailSearchOperationExecutor();
        Duration approvalDeadline=Duration.ofMillis(180),searchDeadline=Duration.ofMillis(250);
        Supplier<GmailRequestBudget> approvalBudgets=()->GmailRequestBudget.searchApproval(approvalDeadline);
        Supplier<GmailRequestBudget> searchBudgets=()->GmailRequestBudget.directSearch(searchDeadline);
        var controller=new GmailSearchController(service,executor,
            "http://127.0.0.1:5176,http://localhost:5176",new ObjectMapper(),approvalBudgets,searchBudgets);
        Tomcat tomcat=new Tomcat();
        var base=Files.createTempDirectory("gmail-search-ingress-");
        Socket approvalSocket=null,searchSocket=null;
        try {
            tomcat.setBaseDir(base.toString());tomcat.setPort(0);tomcat.getConnector().setPort(0);
            // Use a short container socket timeout so this synthetic incomplete-body test releases its readers promptly.
            tomcat.getConnector().setProperty("connectionTimeout","1000");
            var context=tomcat.addContext("",base.toString());
            Tomcat.addServlet(context,"search-test",new HttpServlet() {
                @Override protected void doPost(HttpServletRequest request,HttpServletResponse response) throws IOException {
                    request.setAttribute(LocalApiGuard.PRINCIPAL_ATTRIBUTE,PRINCIPAL);
                    try {
                        ResponseEntity<?> result=request.getRequestURI().endsWith("/approval")
                            ? controller.approve(request):controller.search(request);
                        write(response,result.getStatusCode().value(),result.getBody());
                    } catch(GmailFailure failure) {
                        write(response,failure.status(),Map.of("error",safeError(failure),"error_category",failure.diagnosticCategory().name()));
                    }
                }
            });
            context.addServletMappingDecoded("/api/gmail/search/*","search-test");tomcat.start();
            int port=tomcat.getConnector().getLocalPort();

            long started=System.nanoTime();
            approvalSocket=openIncomplete(port,"/api/gmail/search/approval","PRIVATE_APPROVAL_BODY_SENTINEL");
            String approvalResponse=readResponse(approvalSocket);
            assertThat(approvalResponse).contains(" 503 ","GMAIL_SEARCH_REQUEST_TIMEOUT")
                .doesNotContain("PRIVATE_APPROVAL_BODY_SENTINEL");
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(2));

            searchSocket=openIncomplete(port,"/api/gmail/search","PRIVATE_SEARCH_BODY_SENTINEL");
            String searchResponse=readResponse(searchSocket);
            assertThat(searchResponse).contains(" 503 ","GMAIL_SEARCH_REQUEST_TIMEOUT")
                .doesNotContain("PRIVATE_SEARCH_BODY_SENTINEL");
            assertThat(executor.activeWorkers()).isEqualTo(GmailSearchOperationExecutor.MAX_CONCURRENT);
            assertThat(executor.availableAdmissions()).isZero();

            String rejected=sendComplete(port,"/api/gmail/search/approval","{\"keyword\":\"invoice\"}");
            assertThat(rejected).contains(" 503 ","GMAIL_SEARCH_CAPACITY_EXHAUSTED")
                .doesNotContain("invoice");
            verifyNoInteractions(service);
        } finally {
            if(approvalSocket!=null)try {approvalSocket.close();}catch(IOException ignored){}
            if(searchSocket!=null)try {searchSocket.close();}catch(IOException ignored){}
            awaitAdmissions(executor,GmailSearchOperationExecutor.MAX_CONCURRENT);
            executor.close();
            try {tomcat.stop();} finally {
                try {tomcat.destroy();} finally {
                    try(var paths=Files.walk(base)) {paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path->{try {Files.deleteIfExists(path);}catch(IOException ignored){}});}
                    catch(IOException ignored) { /* Test-only cleanup is best effort. */ }
                }
            }
        }
    }

    private static void write(HttpServletResponse response,int status,Object body) throws IOException {
        byte[] bytes=new ObjectMapper().writeValueAsBytes(body==null?Map.of("ok",true):body);
        response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control","no-store");response.setContentLength(bytes.length);response.getOutputStream().write(bytes);
    }
    private static String safeError(GmailFailure failure) {
        return failure.diagnosticCategory()==GmailFailure.DiagnosticCategory.GMAIL_SEARCH_REQUEST_TIMEOUT
            ? "Live Gmail search timed out before completion. No results were returned."
            : failure.diagnosticCategory()==GmailFailure.DiagnosticCategory.GMAIL_SEARCH_CAPACITY_EXHAUSTED
                ? "Live Gmail search is busy; try again later.":"Request rejected";
    }
    private static Socket openIncomplete(int port,String path,String privateSentinel) throws IOException {
        Socket socket=new Socket(InetAddress.getByName("127.0.0.1"),port);socket.setSoTimeout(2500);
        String prefix="{\"body\":\""+privateSentinel;
        String headers="POST "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+port+
            "\r\nAuthorization: Bearer synthetic-local-api-token-value-0000000000000000"+
            "\r\nContent-Type: application/json\r\nX-Client-Session: "+SESSION+"\r\nOrigin: "+ORIGIN+
            "\r\nContent-Length: "+(prefix.getBytes(StandardCharsets.UTF_8).length+512)+"\r\nConnection: keep-alive\r\n\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(prefix.getBytes(StandardCharsets.UTF_8));socket.getOutputStream().flush();
        return socket;
    }
    private static String sendComplete(int port,String path,String body) throws IOException {
        try(Socket socket=new Socket(InetAddress.getByName("127.0.0.1"),port)) {
            socket.setSoTimeout(2500);byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            String headers="POST "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+port+
                "\r\nAuthorization: Bearer synthetic-local-api-token-value-0000000000000000"+
                "\r\nContent-Type: application/json\r\nX-Client-Session: "+SESSION+"\r\nOrigin: "+ORIGIN+
                "\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
            return readResponse(socket);
        }
    }
    private static String readResponse(Socket socket) throws IOException {
        BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.ISO_8859_1));
        StringBuilder result=new StringBuilder();String line=input.readLine();if(line==null)throw new IOException("HTTP response was not received");
        result.append(line).append('\n');int contentLength=0;
        while((line=input.readLine())!=null && !line.isEmpty()) {
            result.append(line).append('\n');if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))
                contentLength=Integer.parseInt(line.substring(line.indexOf(':')+1).strip());
        }
        result.append('\n');for(int i=0;i<contentLength;i++) {
            int value=input.read();if(value<0)throw new IOException("Incomplete HTTP response body");result.append((char)value);
        }
        return result.toString();
    }
    private static void awaitAdmissions(GmailSearchOperationExecutor executor,int expected) throws InterruptedException {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(executor.availableAdmissions()!=expected && System.nanoTime()<end)Thread.sleep(10);
        assertThat(executor.availableAdmissions()).isEqualTo(expected);
    }
}
