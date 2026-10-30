package com.e9rental;
import com.e9rental.connector.*;
import com.e9rental.model.*;
import com.e9rental.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.system.*;
import org.springframework.http.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"LOCAL_API_TOKEN=synthetic-m3-api-only-token-at-least-32","PRACTICE_TENANT_ID=test-tenant","WHATSAPP_SIMULATED_ACCOUNT=sim-account-test","QA_SALES_SNAPSHOT_COMPLETE=true","QA_REFERENCE_INSTANT=2026-10-17T12:00:00Z","GMAIL_REFERENCE_DATE=2026-10-17","WHATSAPP_SITE_TEAM_GROUP=sim-group-rentals","WHATSAPP_SELECTED_GROUPS=sim-group-rentals,sim-group-operations","UNIFIED_STORE_URL=jdbc:h2:mem:m3_http;DB_CLOSE_DELAY=-1","WHATSAPP_STORE_URL=jdbc:h2:mem:m3_http_wa;DB_CLOSE_DELAY=-1","SANDBOX_GOOGLE_APPROVED=false"})
@ExtendWith(OutputCaptureExtension.class)
class BoundedReasoningHttpTest {
    @Autowired TestRestTemplate http;@Autowired UnifiedRepository repository;@Autowired UnifiedIngestionService ingestion;@Autowired WhatsAppIngestionService whatsapp;
    HttpEntity<String> auth(String body){var h=new HttpHeaders();h.setBearerAuth("synthetic-m3-api-only-token-at-least-32");h.setContentType(MediaType.APPLICATION_JSON);return new HttpEntity<>(body,h);}
    QuestionAnswer ask(String q)throws Exception{var response=http.postForEntity("/api/qa/ask",auth(Fixtures.JSON.writeValueAsString(java.util.Map.of("question",q))),String.class);assertThat(response.getStatusCode().value()).isEqualTo(200);return Fixtures.JSON.readValue(response.getBody(),QuestionAnswer.class);}
    @Test void protectedThreeExampleQuestionsMissingEvidenceAndIsolationWorkOverHttp(CapturedOutput logs)throws Exception{
        var unauth=new HttpHeaders();unauth.setContentType(MediaType.APPLICATION_JSON);assertThat(http.postForEntity("/api/qa/ask",new HttpEntity<>("{\"question\":\"What were our sales last month?\"}",unauth),String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(ask("What were our sales last month?").status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        repository.upsert(ingestion.gmail(ReasoningFixtures.gmail()));repository.upsert(ingestion.drive(ReasoningFixtures.drive()));for(var e:ReasoningFixtures.events())whatsapp.ingest(e);ingestion.ingest("WHATSAPP");
        var total=ask("What were our sales last month?");assertThat(total.answer()).contains("USD 1500");assertThat(total.evidence()).hasSize(2);
        var pending=ask("Which sales emails are still pending a reply?");assertThat(pending.evidence()).extracting(QuestionAnswer.Evidence::sourceId).containsExactlyInAnyOrder("pending-old","pending-boundary");
        var agreements=ask("What did the site team agree on in the WhatsApp group this week?");assertThat(agreements.evidence()).extracting(QuestionAnswer.Evidence::sourceId).containsExactlyInAnyOrder("sim-agree-1","sim-agree-2");
        for(var a:java.util.List.of(total,pending,agreements))for(var e:a.evidence()){
            var rows=repository.search(new UnifiedRepository.Query(null,e.sourceType(),null,e.connectorAccountId(),null,null,null,null,null,100,0));var row=rows.stream().filter(r->r.source().documentId().equals(e.documentId())).findFirst().orElseThrow();assertThat(row.source().contentText().substring(e.startOffset(),e.endOffset())).isEqualTo(e.excerpt());assertThat(row.source().sourceVersion()).isEqualTo(e.sourceVersion());
        }
        assertThat(ask("What is the CEO salary?").status()).isEqualTo("INSUFFICIENT_EVIDENCE");assertThat(ask("Show M3 excluded canary records").evidence()).isEmpty();
        assertThat(http.postForEntity("/api/qa/ask",auth("{\"question\":123}"),String.class).getStatusCode().value()).isEqualTo(400);
        assertThat(logs.getAll()).doesNotContain("M3_EXCLUDED_CANARY","Confirmed sale invoice INV-A","synthetic-m3-api-only-token-at-least-32");
    }
}
