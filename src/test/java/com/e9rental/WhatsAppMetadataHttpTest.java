package com.e9rental;
import com.e9rental.connector.*;
import com.e9rental.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.system.*;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"LOCAL_API_TOKEN=synthetic-m4-only-token-at-least-32-chars","PRACTICE_TENANT_ID=test-tenant","WHATSAPP_SIMULATED_ACCOUNT=sim-account-test","WHATSAPP_GROUP_CONFIG_FILE=config/whatsapp-simulated-groups.json","WHATSAPP_SELECTED_GROUPS=sim-group-excluded","WHATSAPP_STORE_URL=jdbc:h2:mem:m4_http_messages;DB_CLOSE_DELAY=-1","UNIFIED_STORE_URL=jdbc:h2:mem:m4_http_index;DB_CLOSE_DELAY=-1","WHATSAPP_OUTPUT_FILE=target/m4-http/whatsapp.json"})
@ExtendWith(OutputCaptureExtension.class)
class WhatsAppMetadataHttpTest {
    @Autowired TestRestTemplate http;@Autowired WhatsAppMessageStore store;
    HttpEntity<String> request(String body,boolean authorized){var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);if(authorized)h.setBearerAuth("synthetic-m4-only-token-at-least-32-chars");return new HttpEntity<>(body,h);}
    ResponseEntity<String> post(String path,String body){return http.postForEntity(path,request(body,true),String.class);}
    @Test void metadataEndpointRequiresAuthAndRejectsMalformedEnvelopes()throws Exception{
        assertThat(http.postForEntity("/api/whatsapp/simulated/groups",request("{}",false),String.class).getStatusCode().value()).isEqualTo(401);
        for(String body:List.of("{broken","{} {}","{\"group_id\":\"sim-group-rentals\",\"group_id\":\"sim-group-excluded\"}","{}"))assertThat(post("/api/whatsapp/simulated/groups",body).getStatusCode().value()).isEqualTo(400);
        assertThat(post("/api/whatsapp/simulated/groups"," ".repeat(16385)).getStatusCode().value()).isEqualTo(413);
    }
    @Test void fileSelectedMetadataUpdatesAndMessageCitationsIntegrateOverProtectedHttp()throws Exception{
        var group=WhatsAppMetadataTest.metadata("sim-group-rentals",1);String body=Fixtures.JSON.writeValueAsString(group);assertThat(post("/api/whatsapp/simulated/groups",body).getStatusCode().value()).isEqualTo(202);assertThat(post("/api/whatsapp/simulated/groups",body).getStatusCode().value()).isEqualTo(200);
        group.put("version",2);group.put("group_name","Synthetic revised Site team");assertThat(post("/api/whatsapp/simulated/groups",Fixtures.JSON.writeValueAsString(group)).getStatusCode().value()).isEqualTo(202);assertThat(post("/api/whatsapp/simulated/groups",body).getStatusCode().value()).isEqualTo(409);
        var message=WhatsAppFixtures.event("sim-m4-citation","sim-group-rentals","We will inspect the synthetic crane.");String event=Fixtures.JSON.writeValueAsString(message);assertThat(post("/api/whatsapp/simulated/events",event).getStatusCode().value()).isEqualTo(202);assertThat(post("/api/whatsapp/simulated/events",event).getStatusCode().value()).isEqualTo(200);
        assertThat(post("/api/whatsapp/simulated/export","").getStatusCode().value()).isEqualTo(200);assertThat(post("/api/unified/ingest?source=WHATSAPP","").getStatusCode().value()).isEqualTo(200);
        var a=Fixtures.JSON.readValue(post("/api/qa/ask","{\"question\":\"What commitments were made about synthetic crane in WhatsApp?\"}").getBody(),QuestionAnswer.class);assertThat(a.status()).isEqualTo("EVIDENCE_FOUND");assertThat(a.evidence()).singleElement().satisfies(e->{assertThat(e.sourceId()).isEqualTo("sim-m4-citation");assertThat(e.excerpt()).isEqualTo("We will inspect the synthetic crane.");});
        var rows=Fixtures.JSON.readTree(http.exchange("/api/unified/search?source=WHATSAPP",HttpMethod.GET,request("",true),String.class).getBody());assertThat(rows.get(0).path("source").path("metadata").path("group_name").asText()).isEqualTo("Synthetic revised Site team");
    }
    @Test void excludedMetadataAndMessagesNeverAppearInStoreIndexExportOrLogs(CapturedOutput logs)throws Exception{
        var metadata=WhatsAppMetadataTest.metadata("sim-group-excluded",1);metadata.put("group_name","M4_HTTP_EXCLUDED_CANARY");metadata.put("participants","M4_HTTP_EXCLUDED_CANARY");var response=post("/api/whatsapp/simulated/groups",Fixtures.JSON.writeValueAsString(metadata));assertThat(response.getStatusCode().value()).isEqualTo(403);assertThat(response.getBody()).doesNotContain("M4_HTTP_EXCLUDED_CANARY");
        assertThat(post("/api/whatsapp/simulated/events",Fixtures.JSON.writeValueAsString(WhatsAppFixtures.event("sim-m4-excluded","sim-group-excluded","M4_HTTP_EXCLUDED_CANARY"))).getStatusCode().value()).isEqualTo(403);assertThat(store.selectedMetadata("test-tenant","sim-account-test",Set.of("sim-group-excluded"))).isEmpty();assertThat(store.selected("test-tenant","sim-account-test",Set.of("sim-group-excluded"))).isEmpty();assertThat(logs.getAll()).doesNotContain("M4_HTTP_EXCLUDED_CANARY");
        var search=http.exchange("/api/unified/search?keyword=M4_HTTP_EXCLUDED_CANARY",HttpMethod.GET,request("",true),String.class);assertThat(Fixtures.JSON.readTree(search.getBody()).isEmpty()).isTrue();
    }
}
