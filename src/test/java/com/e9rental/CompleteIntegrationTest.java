package com.e9rental;
import com.e9rental.connector.*;
import com.e9rental.service.*;
import com.e9rental.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.system.*;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Real HTTP/controller/service/storage pipeline; ONLY Google gateway boundaries are synthetic. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "SANDBOX_GOOGLE_APPROVED=true","SANDBOX_GMAIL_ACCOUNT=owner@example.test","SANDBOX_DRIVE_ACCOUNT_ID=synthetic-account","SANDBOX_DRIVE_ROOT_FOLDER_ID=synthetic-folder",
    "LOCAL_API_TOKEN=synthetic-integration-only-local-api-token",
    "GMAIL_OUTPUT_FILE=target/integration/gmail.json","DRIVE_OUTPUT_FILE=target/integration/drive.json",
    "WHATSAPP_STORE_URL=jdbc:h2:mem:complete_integration_wa;DB_CLOSE_DELAY=-1",
    "UNIFIED_STORE_URL=jdbc:h2:mem:complete_integration;DB_CLOSE_DELAY=-1",
    "WHATSAPP_SELECTED_GROUPS=sim-group-rentals"})
@ExtendWith(OutputCaptureExtension.class)
class CompleteIntegrationTest {
    @Autowired TestRestTemplate http;
    @MockitoBean GmailGateway gmail;
    @MockitoBean DriveGateway drive;
    @org.junit.jupiter.api.BeforeEach void syntheticProvider() { when(gmail.dataMode()).thenReturn("SYNTHETIC"); when(drive.dataMode()).thenReturn("SYNTHETIC"); }
    HttpEntity<String> auth(String body) {
        var headers=new HttpHeaders();headers.setBearerAuth("synthetic-integration-only-local-api-token");
        headers.setContentType(MediaType.APPLICATION_JSON);return new HttpEntity<>(body,headers);
    }
    ResponseEntity<String> post(String path,String body){return http.postForEntity(path,auth(body),String.class);}
    @Test void connectorExportsAcceptedMessagesUnifiedSearchAndGroundedAnswersIntegrateOverHttp(CapturedOutput logs)throws Exception {
        com.fasterxml.jackson.databind.JsonNode thread;
        try(var in=getClass().getResourceAsStream("/fixtures/gmail-demo-invoice.json")){thread=Fixtures.JSON.readTree(in);}
        when(gmail.read(1)).thenReturn(new GmailGateway.Mailbox("owner@example.test",List.of(thread)));
        var file=DriveFixtures.file("synthetic-integration-file",DriveExtractor.DOCS,"Synthetic rental invoice");
        when(drive.list(1)).thenReturn(new DriveGateway.ListedFiles("synthetic-account",List.of(file),false));
        when(drive.content(file)).thenReturn("Rental invoice amount is USD 1200.".getBytes(StandardCharsets.UTF_8));
        assertThat(post("/api/gmail/export?maxThreads=1","").getStatusCode().value()).isEqualTo(200);
        assertThat(post("/api/drive/export?maxFiles=1","").getStatusCode().value()).isEqualTo(200);
        String accepted=Fixtures.JSON.writeValueAsString(WhatsAppFixtures.event("sim-integration-message","sim-group-rentals",
            "We will review the rental invoice amount USD 1200."));
        assertThat(post("/api/whatsapp/simulated/events",accepted).getStatusCode().value()).isEqualTo(202);
        assertThat(post("/api/whatsapp/simulated/events",accepted).getStatusCode().value()).isEqualTo(200);
        String excluded=Fixtures.JSON.writeValueAsString(WhatsAppFixtures.event("sim-excluded","sim-group-excluded","INTEGRATION_EXCLUDED_SENTINEL"));
        assertThat(post("/api/whatsapp/simulated/events",excluded).getStatusCode().value()).isEqualTo(403);
        for(String source:List.of("GMAIL","DRIVE","WHATSAPP")) {
            var first=post("/api/unified/ingest?source="+source,"");
            assertThat(first.getStatusCode().value()).isEqualTo(200);
            assertThat(Fixtures.JSON.readTree(first.getBody()).path("inserted").asInt()).isEqualTo(1);
            var again=post("/api/unified/ingest?source="+source,"");
            assertThat(Fixtures.JSON.readTree(again.getBody()).path("unchanged").asInt()).isEqualTo(1);
        }
        var search=http.exchange("/api/unified/search?keyword=invoice&limit=100",HttpMethod.GET,auth(""),String.class);
        assertThat(search.getStatusCode().value()).isEqualTo(200);
        var rows=Fixtures.JSON.readTree(search.getBody());assertThat(rows.size()).isEqualTo(3);
        assertThat(search.getBody()).doesNotContain("INTEGRATION_EXCLUDED_SENTINEL");
        var answer=Fixtures.JSON.readValue(post("/api/qa/ask",
            "{\"question\":\"Show rental invoice records across Gmail Drive and WhatsApp\"}").getBody(),QuestionAnswer.class);
        assertThat(answer.status()).isEqualTo("EVIDENCE_FOUND");assertThat(answer.evidence()).hasSize(3);
        assertThat(answer.evidence()).extracting(QuestionAnswer.Evidence::sourceType).containsExactlyInAnyOrder("GMAIL","DRIVE","WHATSAPP");
        for(var evidence:answer.evidence()) {
            var row=java.util.stream.StreamSupport.stream(rows.spliterator(),false)
                .filter(r->r.path("source").path("document_id").asText().equals(evidence.documentId())).findFirst().orElseThrow();
            assertThat(evidence.sourceId()).isEqualTo(row.path("source").path("external_id").asText());
            assertThat(evidence.sourceVersion()).isEqualTo(row.path("citation").path("source_version").asText());
            assertThat(evidence.locator()).isEqualTo(row.path("citation").path("locator").asText());
            assertThat(evidence.excerpt()).isEqualTo(row.path("source").path("content_text").asText().substring(evidence.startOffset(),evidence.endOffset()));
        }
        var invoice=Fixtures.JSON.readValue(post("/api/qa/ask","{\"question\":\"What is the rental invoice amount in Gmail?\"}").getBody(),QuestionAnswer.class);
        assertThat(invoice.evidence()).singleElement().satisfies(e->assertThat(e.sourceId()).isEqualTo("demo-invoice-1200"));
        var missing=Fixtures.JSON.readValue(post("/api/qa/ask","{\"question\":\"Who is the CEO of Neptune?\"}").getBody(),QuestionAnswer.class);
        assertThat(missing.status()).isEqualTo("INSUFFICIENT_EVIDENCE");assertThat(missing.evidence()).isEmpty();
        assertThat(http.exchange("/api/unified/search?account=unauthorized-account",HttpMethod.GET,auth(""),String.class).getBody()).isEqualTo("[]");
        assertThat(http.getForEntity("/health",String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(logs.getAll()).doesNotContain("USD 1200","INTEGRATION_EXCLUDED_SENTINEL","owner@example.test");
    }
}
