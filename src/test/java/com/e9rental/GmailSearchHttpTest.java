package com.e9rental;

import com.e9rental.model.SearchRecord;
import com.e9rental.service.GmailAnalyzer;
import com.e9rental.service.qa.GmailQuestionRetrievalService;
import com.e9rental.service.qa.GmailQuestionRetriever.RetrievalResult;
import com.e9rental.connector.GmailSearchPlan;
import com.e9rental.connector.GmailRequestBudget;
import com.e9rental.service.GmailSearchApprovalRegistry;
import com.e9rental.service.GmailSearchOperationExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.client.TestRestTemplate;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "LOCAL_API_TOKEN=synthetic-gmail-search-api-token-only",
    "PRACTICE_TENANT_ID=practice-local",
    "GOOGLE_ACCESS_MODE=SANDBOX",
    "SANDBOX_GOOGLE_APPROVED=true",
    "SANDBOX_GMAIL_ACCOUNT=search@example.com",
    "UNIFIED_STORE_URL=jdbc:h2:mem:gmail_search_http;DB_CLOSE_DELAY=-1"
})
class GmailSearchHttpTest {
    private static final String TOKEN="synthetic-gmail-search-api-token-only";
    private static final String SESSION="11111111-1111-4111-8111-111111111111";
    private static final String OTHER_SESSION="22222222-2222-4222-8222-222222222222";
    private static final String ORIGIN="http://127.0.0.1:5176";
    @Autowired TestRestTemplate http;
    @MockitoBean GmailQuestionRetrievalService retrieval;
    @Autowired GmailSearchApprovalRegistry approvals;
    @Autowired GmailSearchOperationExecutor searchExecutor;
    @LocalServerPort int port;

    @BeforeEach void resetProviderSeam() { reset(retrieval); }

    @Test void requiresAuthenticatedOriginAndSessionBeforeIssuingApproval() {
        var noToken=http.postForEntity("/api/gmail/search/approval",filters(),JsonNode.class);
        assertThat(noToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var badOrigin=post("/api/gmail/search/approval",filters(),TOKEN,SESSION,"https://example.invalid");
        assertThat(badOrigin.getStatusCode().is4xxClientError()).isTrue();
        verifyNoInteractions(retrieval);
    }

    @Test void acceptsLowercaseKeywordFromDefaultReact5173Origin() {
        when(retrieval.retrieve(any(GmailSearchPlan.class),any(GmailRequestBudget.class)))
            .thenReturn(new RetrievalResult(List.of(liveRecord()),false,null));
        String origin="http://127.0.0.1:5173";
        var approved=post("/api/gmail/search/approval",Map.of("keyword","linkedin"),TOKEN,SESSION,origin);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        var result=post("/api/gmail/search",Map.of("approval_token",approved.getBody().path("approval_token").asText()),
            TOKEN,SESSION,origin);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(retrieval).retrieve(argThat((GmailSearchPlan plan)->plan.maxMessages()==20 &&
            plan.topicKeywords().equals(List.of("linkedin"))),any(GmailRequestBudget.class));
    }

    @Test void returnsTransientMinimalRowsAndOpaqueViewerSelectionAfterSingleApproval() {
        when(retrieval.retrieve(any(GmailSearchPlan.class),any(GmailRequestBudget.class))).thenReturn(new RetrievalResult(List.of(liveRecord()),true,null));
        var approved=post("/api/gmail/search/approval",filters(),TOKEN,SESSION,ORIGIN);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getHeaders().getCacheControl()).contains("no-store");
        String approval=approved.getBody().path("approval_token").asText();
        assertThat(approval).matches("[A-Za-z0-9_-]{43}");
        verifyNoInteractions(retrieval);

        var result=post("/api/gmail/search",Map.of("approval_token",approval),TOKEN,SESSION,ORIGIN);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getHeaders().getCacheControl()).contains("no-store");
        JsonNode body=result.getBody();
        assertThat(body.path("partial_results").asBoolean()).isTrue();
        assertThat(body.path("coverage_warning").asText()).contains("More matching emails may exist");
        JsonNode row=body.path("results").get(0);
        assertThat(row.path("subject").asText()).isEqualTo("Synthetic subject");
        assertThat(row.path("sender").asText()).isEqualTo("sender@example.test");
        assertThat(row.path("received_at").asText()).isEqualTo("2026-10-19T10:00:00Z");
        assertThat(row.path("excerpt").asText()).isEqualTo("Synthetic message excerpt about invoice.");
        assertThat(row.path("selection_id").asText()).matches("[A-Za-z0-9_-]{43}")
            .isNotEqualTo("synthetic-message-id-1");
        String serialized=body.toString();
        assertThat(serialized).doesNotContain("synthetic-message-id-1","connector_account_id","source_id","source_url","content_hash");
        verify(retrieval).retrieve(argThat((GmailSearchPlan plan)->plan.maxMessages()==20 && plan.senderEmail().equals("sender@example.test") &&
            plan.topicKeywords().equals(List.of("invoice"))),any(GmailRequestBudget.class));

        var reused=post("/api/gmail/search",Map.of("approval_token",approval),TOKEN,SESSION,ORIGIN);
        assertThat(reused.getStatusCode().is2xxSuccessful()).isFalse();
        verify(retrieval,times(1)).retrieve(any(GmailSearchPlan.class),any(GmailRequestBudget.class));
    }

    @Test void sanitizesSearchPreviewsWithoutChangingMessageContentOrCoverage() {
        String longUrl="https://tracking.example.test/click?campaign="+"x".repeat(1200);
        String html="<html><head><style>.offer{color:red}</style></head><body><p>Invoice &amp; details</p>"+
            "<script>privateCall()</script><a href=\"https://tracking.example.test/offer\">View offer</a> Please visit "+
            longUrl+"</body></html>";
        String plain="Contact reader@example.test. Release notes remain readable. "+longUrl;
        String boundary="x".repeat(300);
        List<SearchRecord> records=List.of(liveRecord("synthetic-html-preview",html),
            liveRecord("synthetic-plain-preview",plain),liveRecord("synthetic-boundary-preview",boundary));
        when(retrieval.retrieve(any(GmailSearchPlan.class),any(GmailRequestBudget.class)))
            .thenReturn(new RetrievalResult(records,false,null));

        var approved=post("/api/gmail/search/approval",filters(),TOKEN,SESSION,ORIGIN);
        var response=post("/api/gmail/search",Map.of("approval_token",approved.getBody().path("approval_token").asText()),TOKEN,SESSION,ORIGIN);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var rows=response.getBody().path("results");
        assertThat(rows).hasSize(3);
        String htmlPreview=rows.get(0).path("excerpt").asText();
        assertThat(htmlPreview).contains("Invoice & details","View offer","Please visit")
            .doesNotContain("<html","<p>","<a ","<script>","privateCall","color:red","tracking.example.test","https://");
        assertThat(htmlPreview.length()).isLessThanOrEqualTo(240);
        String plainPreview=rows.get(1).path("excerpt").asText();
        assertThat(plainPreview).contains("reader@example.test","Release notes remain readable")
            .doesNotContain("tracking.example.test","https://");
        assertThat(plainPreview.length()).isLessThanOrEqualTo(240);
        assertThat(rows.get(2).path("excerpt").asText()).hasSize(240);
        assertThat(records.get(0).source().contentText()).isEqualTo(html);
        assertThat(records.get(1).source().contentText()).isEqualTo(plain);
        assertThat(records.get(2).source().contentText()).isEqualTo(boundary);
        assertThat(response.getBody().path("partial_results").asBoolean()).isFalse();
        verify(retrieval,times(1)).retrieve(any(GmailSearchPlan.class),any(GmailRequestBudget.class));
    }

    @Test void approvalCannotCrossBrowserSessionAndInvalidFiltersNeverReachRetrieval() {
        var approved=post("/api/gmail/search/approval",filters(),TOKEN,SESSION,ORIGIN);
        String approval=approved.getBody().path("approval_token").asText();
        var foreign=post("/api/gmail/search",Map.of("approval_token",approval),TOKEN,OTHER_SESSION,ORIGIN);
        assertThat(foreign.getStatusCode().is2xxSuccessful()).isFalse();
        var injected=post("/api/gmail/search/approval",Map.of("keyword","invoice OR in:anywhere"),TOKEN,SESSION,ORIGIN);
        assertThat(injected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var unknown=post("/api/gmail/search/approval",Map.of("keyword","invoice","query","in:anywhere"),TOKEN,SESSION,ORIGIN);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(retrieval);
    }

    @Test void boundedJsonBodiesRejectOversizedMalformedAndDeepPayloadsBeforeApprovalOrRetrieval() throws Exception {
        int initialApprovals=approvals.sizeForTests();
        String oversized="{\"keyword\":\""+"x".repeat(1100)+"\"}";
        String excessiveString="{\"keyword\":\""+"x".repeat(600)+"\"}";
        String malformed="{\"keyword\":\"invoice\"";
        String deeplyNested="{\"keyword\":"+"[".repeat(10)+"\"invoice\""+"]".repeat(10)+"}";
        String duplicateApproval="{\"keyword\":\"invoice\",\"keyword\":\"salary\"}";
        for(var testCase:List.of(new RawCase(oversized,HttpStatus.PAYLOAD_TOO_LARGE),
                new RawCase(excessiveString,HttpStatus.BAD_REQUEST),new RawCase(malformed,HttpStatus.BAD_REQUEST),
                new RawCase(deeplyNested,HttpStatus.BAD_REQUEST),new RawCase(duplicateApproval,HttpStatus.BAD_REQUEST))) {
            var response=postRaw("/api/gmail/search/approval",testCase.json(),TOKEN,SESSION,ORIGIN);
            assertThat(response.getStatusCode()).isEqualTo(testCase.status());
            assertThat(response.getHeaders().getCacheControl()).contains("no-store");
            assertThat(response.getBody().toString()).doesNotContain("salary","invoice");
            assertThat(approvals.sizeForTests()).isEqualTo(initialApprovals);
        }
        assertThat(postChunkedStatus("/api/gmail/search/approval",oversized))
            .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(approvals.sizeForTests()).isEqualTo(initialApprovals);
        when(retrieval.retrieve(any(GmailSearchPlan.class),any(GmailRequestBudget.class))).thenReturn(new RetrievalResult(List.of(),false,null));
        var issued=post("/api/gmail/search/approval",filters(),TOKEN,SESSION,ORIGIN);
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token=issued.getBody().path("approval_token").asText();
        int afterIssue=approvals.sizeForTests();
        String invalidSearchOversized="{\"approval_token\":\""+token+"\",\"padding\":\""+"x".repeat(1100)+"\"}";
        String invalidSearchLongString="{\"approval_token\":\""+token+"\",\"padding\":\""+"x".repeat(600)+"\"}";
        String duplicateSearch="{\"approval_token\":\""+token+"\",\"approval_token\":\""+token+"\"}";
        for(var testCase:List.of(new RawCase(invalidSearchOversized,HttpStatus.PAYLOAD_TOO_LARGE),
                new RawCase(invalidSearchLongString,HttpStatus.BAD_REQUEST),new RawCase(malformed,HttpStatus.BAD_REQUEST),
                new RawCase(deeplyNested,HttpStatus.BAD_REQUEST),new RawCase(duplicateSearch,HttpStatus.BAD_REQUEST))) {
            var response=postRaw("/api/gmail/search",testCase.json(),TOKEN,SESSION,ORIGIN);
            assertThat(response.getStatusCode()).isEqualTo(testCase.status());
            assertThat(response.getHeaders().getCacheControl()).contains("no-store");
            assertThat(response.getBody().toString()).doesNotContain(token);
            assertThat(approvals.sizeForTests()).isEqualTo(afterIssue);
        }
        String chunkedSearch="{\"approval_token\":\""+token+"\",\"padding\":\""+"x".repeat(1100)+"\"}";
        assertThat(postChunkedStatus("/api/gmail/search",chunkedSearch))
            .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(approvals.sizeForTests()).isEqualTo(afterIssue);
        var valid=post("/api/gmail/search",Map.of("approval_token",token),TOKEN,SESSION,ORIGIN);
        assertThat(valid.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(retrieval,times(1)).retrieve(any(GmailSearchPlan.class),any(GmailRequestBudget.class));
    }

    @Test void maximumDateIsRejectedBeforeApprovalOrProviderRetrieval() {
        int initialApprovals=approvals.sizeForTests();
        var response=post("/api/gmail/search/approval",Map.of("keyword","invoice",
            "start_date","9999-12-31","end_date","9999-12-31"),TOKEN,SESSION,ORIGIN);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(approvals.sizeForTests()).isEqualTo(initialApprovals);
        verifyNoInteractions(retrieval);
    }

    @Test void twoDistinctApprovalsMayRunConcurrentlyAndThirdIsRejectedUntilCapacityIsReleased() throws Exception {
        int initialApprovals=approvals.sizeForTests();
        List<String> tokens=new java.util.ArrayList<>();
        for(int i=0;i<3;i++) {
            var response=post("/api/gmail/search/approval",filters(),TOKEN,SESSION,ORIGIN);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            tokens.add(response.getBody().path("approval_token").asText());
        }
        CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);
        when(retrieval.retrieve(any(GmailSearchPlan.class),any(GmailRequestBudget.class))).thenAnswer(invocation->{
            entered.countDown();
            if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("synthetic search gate timed out");
            return new RetrievalResult(List.of(),false,null);
        });
        ExecutorService callers=Executors.newFixedThreadPool(3);
        try {
            List<Future<ResponseEntity<JsonNode>>> running=List.of(
                callers.submit(()->post("/api/gmail/search",Map.of("approval_token",tokens.get(0)),TOKEN,SESSION,ORIGIN)),
                callers.submit(()->post("/api/gmail/search",Map.of("approval_token",tokens.get(1)),TOKEN,SESSION,ORIGIN)));
            assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            assertThat(searchExecutor.activeWorkers()).isEqualTo(2);
            assertThat(searchExecutor.availableAdmissions()).isZero();
            assertThat(approvals.sizeForTests()).isEqualTo(initialApprovals+1);

            var overloaded=post("/api/gmail/search",Map.of("approval_token",tokens.get(2)),TOKEN,SESSION,ORIGIN);
            assertThat(overloaded.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(overloaded.getBody().path("error_category").asText()).isEqualTo("GMAIL_SEARCH_CAPACITY_EXHAUSTED");
            assertThat(overloaded.getBody().path("error").asText()).doesNotContain("invoice","sender@example.test",tokens.get(2));
            assertThat(approvals.sizeForTests()).isEqualTo(initialApprovals+1);
            verify(retrieval,times(2)).retrieve(any(GmailSearchPlan.class),any(GmailRequestBudget.class));

            release.countDown();
            for(Future<ResponseEntity<JsonNode>> response:running)
                assertThat(response.get(4,TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
            var afterRelease=post("/api/gmail/search",Map.of("approval_token",tokens.get(2)),TOKEN,SESSION,ORIGIN);
            assertThat(afterRelease.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(approvals.sizeForTests()).isEqualTo(initialApprovals);
            assertThat(searchExecutor.availableAdmissions()).isEqualTo(GmailSearchOperationExecutor.MAX_CONCURRENT);
        } finally {
            release.countDown();callers.shutdownNow();
            assertThat(callers.awaitTermination(3,TimeUnit.SECONDS)).isTrue();
        }
    }

    private static Map<String,Object> filters() {
        return Map.of("keyword","invoice","sender","sender@example.test","start_date","2026-10-19","end_date","2026-10-19");
    }
    private ResponseEntity<JsonNode> post(String path,Object body,String token,String session,String origin) {
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);headers.set("X-Client-Session",session);headers.setOrigin(origin);
        return http.postForEntity(path,new HttpEntity<>(body,headers),JsonNode.class);
    }
    private ResponseEntity<JsonNode> postRaw(String path,String body,String token,String session,String origin) {
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);headers.set("X-Client-Session",session);headers.setOrigin(origin);
        return http.postForEntity(path,new HttpEntity<>(body,headers),JsonNode.class);
    }
    private int postChunkedStatus(String path,String body) throws IOException {
        try(Socket socket=new Socket(InetAddress.getByName("127.0.0.1"),port)) {
            socket.setSoTimeout(3000);
            OutputStream output=socket.getOutputStream();
            String headers="POST "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+port+
                "\r\nAuthorization: Bearer "+TOKEN+"\r\nContent-Type: application/json\r\n"+
                "X-Client-Session: "+SESSION+"\r\nOrigin: "+ORIGIN+
                "\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            for(int offset=0;offset<bytes.length;) {
                int length=Math.min(256,bytes.length-offset);
                output.write((Integer.toHexString(length)+"\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(bytes,offset,length);output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                offset+=length;
            }
            output.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));output.flush();
            try(BufferedReader response=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII))) {
                String status=response.readLine();
                if(status==null || !status.startsWith("HTTP/1."))throw new IOException("Synthetic HTTP response status unavailable");
                return Integer.parseInt(status.split(" ",3)[1]);
            }
        }
    }
    private record RawCase(String json,HttpStatus status) {}
    private static SearchRecord liveRecord() {
        String content="Synthetic message excerpt about invoice.";
        return liveRecord("synthetic-message-id-1",content);
    }
    private static SearchRecord liveRecord(String id,String content) {
        String docId="doc-"+id;
        var source=new com.e9rental.model.SourceRecord(docId,"practice-local",
            "gmail-"+GmailAnalyzer.hash("search@example.com").substring(0,24),"GMAIL",id,null,
            "Synthetic subject",content,"text/plain",GmailAnalyzer.hash(content),"version-1",null,null,
            Instant.parse("2026-10-19T10:00:00Z"),null,null,null,Map.of("from","sender@example.test"));
        var citation=new SearchRecord.Citation(docId,"version-1","GMAIL","message-1#body","synthetic://source");
        return new SearchRecord(source,"LIVE","other","sender@example.test",Map.of(),citation);
    }
}
