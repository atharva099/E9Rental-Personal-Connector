package com.e9rental;
import com.e9rental.connector.*;
import com.e9rental.model.*;
import com.e9rental.service.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.system.*;
import org.springframework.http.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "LOCAL_API_TOKEN=synthetic-question-answer-api-token-only",
    "PRACTICE_TENANT_ID=practice-local",
    "UNIFIED_STORE_URL=jdbc:h2:mem:qa_http;DB_CLOSE_DELAY=-1"})
@ExtendWith(OutputCaptureExtension.class)
class QuestionAnswerHttpTest {
    @Autowired TestRestTemplate http;@Autowired UnifiedRepository repository;
    HttpEntity<String> request(String body,boolean auth) {
        var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);
        if(auth)h.setBearerAuth("synthetic-question-answer-api-token-only");return new HttpEntity<>(body,h);
    }
    @BeforeEach void seed() {
        var s=new SourceRecord("qa-http","practice-local","synthetic-mailbox","GMAIL","qa-http","thread","Synthetic invoice",
            "Synthetic rental invoice amount is USD 1200.","text/plain",GmailAnalyzer.hash("Synthetic rental invoice amount is USD 1200."),
            "v1",Fixtures.NOW,null,Fixtures.NOW,Fixtures.NOW,Fixtures.NOW,null,Map.of("synthetic",true));
        repository.upsert(List.of(new SearchRecord(s,"SYNTHETIC","accounts","synthetic-participant",Map.of(),
            new SearchRecord.Citation("qa-http","v1","GMAIL","synthetic:qa-http",null))));
    }
    @Test void bearerProtectedAnswerContainsVerifiableEvidenceAndNoStore(CapturedOutput output) throws Exception {
        String body="{\"question\":\"What is rental invoice amount in Gmail?\"}";
        assertThat(http.postForEntity("/api/qa/ask",request(body,false),String.class).getStatusCode().value()).isEqualTo(401);
        var response=http.postForEntity("/api/qa/ask",request(body,true),String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var a=Fixtures.JSON.readValue(response.getBody(),QuestionAnswer.class);
        assertThat(a.status()).isEqualTo("EVIDENCE_FOUND");assertThat(a.evidence()).hasSize(1);assertThat(a.llmGenerated()).isFalse();
        var serialized=Fixtures.JSON.readTree(response.getBody());
        assertThat(serialized.has("partial_results")).isTrue();assertThat(serialized.path("partial_results").asBoolean()).isFalse();
        assertThat(a.evidence().getFirst().sourceId()).isEqualTo("qa-http");assertThat(a.evidence().getFirst().locator()).isEqualTo("synthetic:qa-http");
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(output.getAll()).doesNotContain("USD 1200","What is rental invoice");
    }
    @Test void emptyMalformedDuplicateTrailingAndUnknownFieldsFailSafely(CapturedOutput output) {
        for(String body:List.of("","null","[]","{}","{\"question\":123}","{\"question\":\"\"}","{\"question\":\"one\",\"question\":\"two\"}",
            "{\"question\":\"private-sentinel\"} {}","{\"question\":\"private-sentinel\",\"tenant\":\"other\"}",
            "{\"question\":\"private-sentinel\",\"searchGmailNow\":\"true\"}","{\"question\":\"private-sentinel\",\"searchGmailNow\":true,\"tenant\":\"other\"}","{broken")) {
            var response=http.postForEntity("/api/qa/ask",request(body,true),String.class);
            assertThat(response.getStatusCode().value()).isEqualTo(400);assertThat(response.getBody()).doesNotContain("private-sentinel","Exception");
        }
        assertThat(output.getAll()).doesNotContain("private-sentinel");
    }
    @Test void explicitLiveGmailRequestIsProtectedAndFailsClosedWhenSandboxIsNotApproved() {
        var unauthenticated=http.postForEntity("/api/qa/ask",request("{\"question\":\"Show Gmail records about invoice\",\"searchGmailNow\":true}",false),String.class);
        assertThat(unauthenticated.getStatusCode().value()).isEqualTo(401);
        var denied=http.postForEntity("/api/qa/ask",request("{\"question\":\"Show Gmail records about invoice\",\"searchGmailNow\":true}",true),String.class);
        assertThat(denied.getStatusCode().value()).isEqualTo(503);
        assertThat(denied.getBody()).doesNotContain("owner@example.test","token","private");

        var googleTopic=http.postForEntity("/api/qa/ask",request(
            "{\"question\":\"Find Gmail emails about Google\",\"searchGmailNow\":true}",true),String.class);
        assertThat(googleTopic.getStatusCode().value()).isEqualTo(503);
        assertThat(googleTopic.getBody()).doesNotContain("owner@example.test","token","private");
    }
    @Test void sizeMediaMethodLimitsAndInsufficientEvidenceResponse() throws Exception {
        assertThat(http.postForEntity("/api/qa/ask",request("{\"question\":\""+"x".repeat(5000)+"\"}",true),String.class).getStatusCode().value()).isEqualTo(413);
        var h=new HttpHeaders();h.setBearerAuth("synthetic-question-answer-api-token-only");h.setContentType(MediaType.TEXT_PLAIN);
        assertThat(http.postForEntity("/api/qa/ask",new HttpEntity<>("question",h),String.class).getStatusCode().value()).isEqualTo(415);
        assertThat(http.exchange("/api/qa/ask",HttpMethod.GET,request("",true),String.class).getStatusCode().value()).isEqualTo(405);
        var missing=http.postForEntity("/api/qa/ask",request("{\"question\":\"Who is CEO of Neptune?\"}",true),QuestionAnswer.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(200);assertThat(missing.getBody().status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(missing.getBody().evidence()).isEmpty();assertThat(http.getForEntity("/health",String.class).getStatusCode().value()).isEqualTo(200);
    }
}
