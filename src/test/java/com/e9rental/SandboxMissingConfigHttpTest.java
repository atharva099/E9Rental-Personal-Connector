package com.e9rental;
import com.e9rental.connector.GoogleTransport;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "SANDBOX_GOOGLE_APPROVED=false","LOCAL_API_TOKEN=synthetic-m1-default-deny-api-token",
    "GMAIL_OUTPUT_FILE=target/m1-default/gmail.json","DRIVE_OUTPUT_FILE=target/m1-default/drive.json",
    "UNIFIED_STORE_URL=jdbc:h2:mem:m1_default","WHATSAPP_STORE_URL=jdbc:h2:mem:m1_default_wa"})
class SandboxMissingConfigHttpTest {
    @Autowired TestRestTemplate http;@MockitoBean GoogleTransport transport;
    @org.junit.jupiter.api.BeforeEach void productionTransportMode() { when(transport.dataMode()).thenReturn("LIVE"); }
    @Test void missingApprovalReturnsSafe503WithoutReadingGoogleOrLiveContent()throws Exception {
        var h=new HttpHeaders();h.setBearerAuth("synthetic-m1-default-deny-api-token");var auth=new HttpEntity<>("",h);
        Files.createDirectories(Path.of("target/m1-default"));
        for(String source:List.of("gmail","drive")) {
            assertThat(http.exchange("/api/"+source+"/export",HttpMethod.POST,auth,String.class).getStatusCode().value()).isEqualTo(503);
            assertThat(http.exchange("/api/"+source+"/oauth/start",HttpMethod.POST,auth,String.class).getStatusCode().value()).isEqualTo(503);
            Files.writeString(Path.of("target/m1-default/"+source+".json"),"{\"data_mode\":\"LIVE\",\"documents\":[UNREAD_INVALID_CANARY");
            assertThat(http.exchange("/api/unified/ingest?source="+source.toUpperCase(java.util.Locale.ROOT),HttpMethod.POST,auth,String.class).getStatusCode().value()).isEqualTo(503);
        }
        verify(transport,never()).get(anyString(),anyString());verify(transport,never()).download(anyString(),anyString(),anyInt());
        assertThat(http.getForEntity("/health",String.class).getStatusCode().value()).isEqualTo(200);
    }
}
