package com.e9rental;
import com.e9rental.connector.DriveGateway;
import com.e9rental.service.DriveExtractor;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "LOCAL_API_TOKEN=synthetic-drive-api-token-for-testing-only","DRIVE_OUTPUT_FILE=target/drive-http/drive_output.json",
    "SANDBOX_GOOGLE_APPROVED=true","SANDBOX_GMAIL_ACCOUNT=owner@example.test",
    "SANDBOX_DRIVE_ACCOUNT_ID=synthetic-drive-owner","SANDBOX_DRIVE_ROOT_FOLDER_ID=synthetic-personal-root"})
class DriveHttpTest {
    @Autowired TestRestTemplate http;@MockitoBean DriveGateway gateway;
    @org.junit.jupiter.api.BeforeEach void syntheticGateway() { when(gateway.dataMode()).thenReturn("SYNTHETIC"); }
    HttpEntity<Void> auth() {var h=new HttpHeaders();h.setBearerAuth("synthetic-drive-api-token-for-testing-only");return new HttpEntity<>(h);}
    @Test void protectsDriveOperationsAndChecksCallbackState() {
        assertThat(http.postForEntity("/api/drive/export",null,String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(http.postForEntity("/api/drive/oauth/start",null,String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(http.getForEntity("/api/drive/oauth/callback?state=forged&code=forged",String.class).getStatusCode().value()).isEqualTo(400);
    }
    @Test void protectedExportExercisesRealMappingAndSerialization() throws Exception {
        var file=DriveFixtures.file("f",DriveExtractor.DOCS,"Quotation");
        when(gateway.list(5)).thenReturn(new DriveGateway.ListedFiles("synthetic",List.of(file),false));
        when(gateway.content(file)).thenReturn("Quotation for synthetic rental".getBytes(StandardCharsets.UTF_8));
        var r=http.exchange("/api/drive/export?maxFiles=5",HttpMethod.POST,auth(),String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);assertThat(r.getBody()).contains("\"file_count\":1","\"extracted_count\":1");
        var data=Fixtures.JSON.readTree(java.nio.file.Path.of("target/drive-http/drive_output.json").toFile());
        assertThat(data.path("data_mode").asText()).isEqualTo("SYNTHETIC");
        assertThat(data.path("documents").get(0).path("source").path("source_type").asText()).isEqualTo("DRIVE");
    }
    @Test void healthAndGmailRoutesRemainAvailable() {
        assertThat(http.getForEntity("/health",String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(http.getForEntity("/api/gmail/oauth/callback?state=forged",String.class).getStatusCode().value()).isEqualTo(401);
    }
}
