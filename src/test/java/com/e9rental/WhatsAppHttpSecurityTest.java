package com.e9rental;
import com.e9rental.connector.WhatsAppMessageStore;
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
    "LOCAL_API_TOKEN=synthetic-whatsapp-api-token-for-testing-only","WHATSAPP_SELECTED_GROUPS=sim-group-rentals,sim-group-operations",
    "WHATSAPP_STORE_URL=jdbc:h2:mem:whatsapp_http;DB_CLOSE_DELAY=-1","WHATSAPP_OUTPUT_FILE=target/whatsapp-http/whatsapp_output.json"})
@ExtendWith(OutputCaptureExtension.class)
class WhatsAppHttpSecurityTest {
    @Autowired TestRestTemplate http;@Autowired WhatsAppMessageStore store;
    HttpEntity<String> request(String json,boolean auth){
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        if(auth)headers.setBearerAuth("synthetic-whatsapp-api-token-for-testing-only");return new HttpEntity<>(json,headers);
    }
    String json(String id,String group,String content)throws Exception{return Fixtures.JSON.writeValueAsString(WhatsAppFixtures.event(id,group,content));}
    List<com.e9rental.model.WhatsAppMessage> rows(){return store.selected("practice-local","sim-account-local",Set.of("sim-group-rentals","sim-group-operations","sim-group-excluded"));}
    @Test void rejectedContentNeverAppearsInPersistenceResponseOrLogs(CapturedOutput captured)throws Exception{
        String sentinel="EXCLUDED_HTTP_SENTINEL_SECRET";
        int before=rows().size();
        var response=http.exchange("/api/whatsapp/simulated/events",HttpMethod.POST,request(json("sim-excluded","sim-group-excluded",sentinel),true),String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(403);assertThat(response.getBody()).doesNotContain(sentinel);
        assertThat(rows()).hasSize(before);assertThat(Fixtures.JSON.writeValueAsString(rows())).doesNotContain(sentinel);
        assertThat(captured.getAll()).doesNotContain(sentinel);
    }
    @Test void rejectsUnauthorizedInvalidDuplicateKeyTrailingAndOversizedPayloads(CapturedOutput captured)throws Exception{
        String sentinel="INVALID_HTTP_SENTINEL";
        assertThat(http.exchange("/api/whatsapp/simulated/events",HttpMethod.POST,request(json("sim-no-auth","sim-group-rentals",sentinel),false),String.class).getStatusCode().value()).isEqualTo(401);
        for(String body:List.of("{broken", "{\"group_id\":\"sim-group-rentals\",\"group_id\":\"sim-group-excluded\",\"content\":\""+sentinel+"\"}", "{} {}")){
            var response=http.exchange("/api/whatsapp/simulated/events",HttpMethod.POST,request(body,true),String.class);
            assertThat(response.getStatusCode().value()).isEqualTo(400);assertThat(response.getBody()).doesNotContain(sentinel);
        }
        assertThat(http.exchange("/api/whatsapp/simulated/events",HttpMethod.POST,request("{\"content\":\""+ "x".repeat(17000)+"\"}",true),String.class).getStatusCode().value()).isEqualTo(413);
        assertThat(Fixtures.JSON.writeValueAsString(rows())).doesNotContain(sentinel);assertThat(captured.getAll()).doesNotContain(sentinel);
    }
    @Test void acceptsOnceRejectsConflictingReplayAndExportsOnlySimulatedData()throws Exception{
        String event=json("sim-http-accepted","sim-group-rentals","I will deliver synthetic equipment.");
        assertThat(http.exchange("/api/whatsapp/simulated/events",HttpMethod.POST,request(event,true),String.class).getStatusCode().value()).isEqualTo(202);
        assertThat(http.exchange("/api/whatsapp/simulated/events",HttpMethod.POST,request(event,true),String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.exchange("/api/whatsapp/simulated/events",HttpMethod.POST,request(json("sim-http-accepted","sim-group-rentals","CONFLICT_HTTP_SENTINEL"),true),String.class).getStatusCode().value()).isEqualTo(409);
        var export=http.exchange("/api/whatsapp/simulated/export",HttpMethod.POST,request("",true),String.class);
        assertThat(export.getStatusCode().value()).isEqualTo(200);assertThat(export.getBody()).contains("\"data_mode\":\"SIMULATED\"","\"live_integration\":false");
        assertThat(Fixtures.JSON.writeValueAsString(rows())).doesNotContain("CONFLICT_HTTP_SENTINEL");
    }
    @Test void invalidSelectedPayloadIsNotPersistedOrLogged(CapturedOutput captured)throws Exception{
        var event=WhatsAppFixtures.event("sim-invalid","sim-group-rentals","INVALID_SELECTED_SENTINEL");event.remove("sender_id");
        var response=http.exchange("/api/whatsapp/simulated/events",HttpMethod.POST,request(Fixtures.JSON.writeValueAsString(event),true),String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(400);assertThat(Fixtures.JSON.writeValueAsString(rows())).doesNotContain("INVALID_SELECTED_SENTINEL");
        assertThat(captured.getAll()).doesNotContain("INVALID_SELECTED_SENTINEL");
    }
    @Test void realMetaWebhookAndLiveModeMarkersAreNotAccepted()throws Exception{
        var realStyle="{\"object\":\"whatsapp_business_account\",\"entry\":[]}";
        assertThat(http.exchange("/api/whatsapp/simulated/events",HttpMethod.POST,request(realStyle,true),String.class).getStatusCode().value()).isEqualTo(400);
        var event=WhatsAppFixtures.event("sim-live","sim-group-rentals","Synthetic");event.put("data_mode","LIVE");
        assertThat(http.exchange("/api/whatsapp/simulated/events",HttpMethod.POST,request(Fixtures.JSON.writeValueAsString(event),true),String.class).getStatusCode().value()).isEqualTo(400);
        assertThat(http.getForEntity("/health",String.class).getStatusCode().value()).isEqualTo(200);
    }
    @Test void unsupportedMediaMethodsAndRealWebhookPathsFailSafely(CapturedOutput captured) {
        var headers=new HttpHeaders();headers.setContentType(MediaType.TEXT_PLAIN);headers.setBearerAuth("synthetic-whatsapp-api-token-for-testing-only");
        var payload=new HttpEntity<>("UNSUPPORTED_MEDIA_SENTINEL",headers);
        var media=http.exchange("/api/whatsapp/simulated/events",HttpMethod.POST,payload,String.class);
        assertThat(media.getStatusCode().value()).isEqualTo(415);assertThat(media.getBody()).doesNotContain("UNSUPPORTED_MEDIA_SENTINEL");
        assertThat(http.exchange("/api/whatsapp/simulated/events",HttpMethod.GET,request("",true),String.class).getStatusCode().value()).isEqualTo(405);
        assertThat(http.exchange("/api/whatsapp/webhook",HttpMethod.GET,request("",true),String.class).getStatusCode().value()).isEqualTo(404);
        assertThat(captured.getAll()).doesNotContain("UNSUPPORTED_MEDIA_SENTINEL");
    }
}
