package com.e9rental;
import com.e9rental.connector.*;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "LOCAL_API_TOKEN=synthetic-local-api-token-for-testing-only",
    "GMAIL_OUTPUT_FILE=target/http-test/gmail_output.json"})
class GmailHttpTest {
    @Autowired TestRestTemplate http;
    @MockitoBean GmailGateway gateway;
    @MockitoBean com.e9rental.service.GmailHistorySyncService syncService;
    @org.junit.jupiter.api.BeforeEach void syntheticGateway() { when(gateway.dataMode()).thenReturn("SYNTHETIC"); }
    HttpEntity<Void> authenticated() {
        var headers=new HttpHeaders(); headers.setBearerAuth("synthetic-local-api-token-for-testing-only"); return new HttpEntity<>(headers);
    }
    @Test void protectsExportAndOAuthStart() {
        assertThat(http.postForEntity("/api/gmail/export",null,String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(http.postForEntity("/api/gmail/oauth/start",null,String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(http.postForEntity("/api/gmail/sync",null,String.class).getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(gateway);
        verifyNoInteractions(syncService);
    }
    @Test void authorizedSyncEndpointIsExplicitAndReturnsOnlyAggregateStatus() {
        when(syncService.synchronize()).thenReturn(new com.e9rental.service.GmailHistorySyncService.SyncResult(
            "SYNCHRONIZED",1,0,0,false,false,3));
        var response=http.exchange("/api/gmail/sync",HttpMethod.POST,authenticated(),String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("SYNCHRONIZED","\"full_resynchronization\":false","\"gmail_rest_attempts\":3");
        assertThat(response.getBody()).doesNotContain("messageId","content","answer");
        verify(syncService,times(1)).synchronize();
    }
    @Test void loopbackRootCallbackRejectsMissingAndForgedStateWithoutBearer() {
        var missing=http.getForEntity("/",String.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        assertThat(missing.getBody()).contains("Invalid or expired");
        var forged=http.getForEntity("/?state=forged&code=forged",String.class);
        assertThat(forged.getStatusCode().value()).isEqualTo(400);
        assertThat(forged.getBody()).contains("Invalid or expired");
    }
    @Test void onlyExactGetLoopbackCallbackIsPublic() {
        assertThat(http.postForEntity("/",null,String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(http.getForEntity("/api/gmail/oauth/callback?state=forged",String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(http.getForEntity("/api/unified/search?keyword=synthetic",String.class).getStatusCode().value()).isEqualTo(401);
    }
    @Test void authorizedExportExercisesServiceAndJsonSerialization() throws Exception {
        when(gateway.read()).thenReturn(new GmailGateway.Mailbox("owner@example.test",Fixtures.threads()));
        var response=http.exchange("/api/gmail/export?referenceDate=2026-10-16",HttpMethod.POST,authenticated(),String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"message_count\":6");
        var output=Fixtures.JSON.readTree(java.nio.file.Path.of("target/http-test/gmail_output.json").toFile());
        assertThat(output.path("data_mode").asText()).isEqualTo("SYNTHETIC");
        assertThat(output.path("messages").get(0).path("source").path("source_created_at").asText()).isEqualTo("2026-10-10T00:00:00Z");
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
    }
    @Test void invalidReferenceDateIsRejected() {
        assertThat(http.exchange("/api/gmail/export?referenceDate=bad",HttpMethod.POST,authenticated(),String.class).getStatusCode().value()).isEqualTo(400);
    }
    @Test void upstreamFailureIsNotSuccessAndDoesNotLeakBody() {
        when(gateway.read()).thenThrow(new GmailFailure(403,"Gmail access denied; check API enablement and account permissions"));
        var response=http.exchange("/api/gmail/export",HttpMethod.POST,authenticated(),String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(502); assertThat(response.getBody()).contains("access denied");
    }
}
