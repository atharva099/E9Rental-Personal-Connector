package com.e9rental;
import com.e9rental.connector.*;
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
    "LOCAL_API_TOKEN=synthetic-unified-api-token-for-testing-only","WHATSAPP_SELECTED_GROUPS=sim-group-rentals",
    "UNIFIED_STORE_URL=jdbc:h2:mem:unified_http;DB_CLOSE_DELAY=-1",
    "WHATSAPP_STORE_URL=jdbc:h2:mem:unified_wa_http;DB_CLOSE_DELAY=-1",
    "GMAIL_OUTPUT_FILE=target/missing-unified-gmail.json"})
@ExtendWith(OutputCaptureExtension.class)
class UnifiedHttpTest {
    @Autowired TestRestTemplate http;@Autowired WhatsAppIngestionService intake;
    HttpEntity<Void> auth(){var h=new HttpHeaders();h.setBearerAuth("synthetic-unified-api-token-for-testing-only");return new HttpEntity<>(h);}
    @Test void requiresBearerForSearchAndIngestion() {
        assertThat(http.getForEntity("/api/unified/search",String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(http.postForEntity("/api/unified/ingest?source=WHATSAPP",null,String.class).getStatusCode().value()).isEqualTo(401);
    }
    @Test void protectedIngestionSearchCitationsAndHealthWork(CapturedOutput output) {
        intake.ingest(WhatsAppFixtures.event("sim-unified-http","sim-group-rentals","Synthetic unified rental evidence"));
        intake.ingest(WhatsAppFixtures.event("sim-unified-excluded","sim-group-excluded","EXCLUDED_UNIFIED_HTTP_SENTINEL"));
        var ingest=http.exchange("/api/unified/ingest?source=WHATSAPP",HttpMethod.POST,auth(),String.class);
        assertThat(ingest.getStatusCode().value()).isEqualTo(200);
        var search=http.exchange("/api/unified/search?keyword=rental&source=WHATSAPP",HttpMethod.GET,auth(),String.class);
        assertThat(search.getStatusCode().value()).isEqualTo(200);
        assertThat(search.getBody()).contains("sim-unified-http","citation","SIMULATED").doesNotContain("EXCLUDED_UNIFIED_HTTP_SENTINEL");
        assertThat(search.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(http.getForEntity("/health",String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(output.getAll()).doesNotContain("EXCLUDED_UNIFIED_HTTP_SENTINEL","Synthetic unified rental evidence");
    }
    @Test void safeInvalidQueryAndMissingInputErrors(CapturedOutput output) {
        for(var path:List.of("/api/unified/search?limit=101","/api/unified/search?source=INVALID","/api/unified/search?from=bad",
                "/api/unified/ingest?source=INVALID")) {
            var method=path.contains("ingest")?HttpMethod.POST:HttpMethod.GET;
            assertThat(http.exchange(path,method,auth(),String.class).getStatusCode().value()).isEqualTo(400);
        }
        var missing=http.exchange("/api/unified/ingest?source=GMAIL",HttpMethod.POST,auth(),String.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(503);
        assertThat(missing.getBody()).doesNotContain("target/","Exception");
    }
}
