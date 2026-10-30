package com.e9rental;

import com.e9rental.connector.*;
import com.e9rental.service.*;
import com.e9rental.model.QuestionAnswer;
import java.nio.file.*;
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
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

/** Synthetic provider replies, actual gateways/controllers/export/index/QA; no live Google access. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "SANDBOX_GOOGLE_APPROVED=true","SANDBOX_GMAIL_ACCOUNT=owner@example.test",
    "SANDBOX_DRIVE_ACCOUNT_ID=synthetic-permission","SANDBOX_DRIVE_ROOT_FOLDER_ID=synthetic-folder",
    "LOCAL_API_TOKEN=synthetic-m1-boundary-api-token-only","PRACTICE_TENANT_ID=test-tenant",
    "GMAIL_OUTPUT_FILE=target/m1-http/gmail.json","DRIVE_OUTPUT_FILE=target/m1-http/drive.json",
    "UNIFIED_STORE_URL=jdbc:h2:mem:m1_http;DB_CLOSE_DELAY=-1","WHATSAPP_STORE_URL=jdbc:h2:mem:m1_http_wa;DB_CLOSE_DELAY=-1"})
@ExtendWith(OutputCaptureExtension.class)
class SandboxBoundaryHttpTest {
    @Autowired TestRestTemplate http;
    @MockitoBean GoogleTransport transport;
    @MockitoBean(name="OAuthService") OAuthService gmail;
    @MockitoBean DriveOAuthService drive;
    HttpEntity<String> auth(String body){var h=new HttpHeaders();h.setBearerAuth("synthetic-m1-boundary-api-token-only");h.setContentType(MediaType.APPLICATION_JSON);return new HttpEntity<>(body,h);}
    ResponseEntity<String> post(String path,String body){return http.exchange(path,HttpMethod.POST,auth(body),String.class);}
    @Test void rejectedProviderContentCannotReachExportsIndexLogsOrAnswers(CapturedOutput logs)throws Exception {
        assertThat(http.postForEntity("/api/gmail/export",null,String.class).getStatusCode().value()).isEqualTo(401);
        assertThat(http.postForEntity("/api/drive/export",null,String.class).getStatusCode().value()).isEqualTo(401);verifyNoInteractions(transport,gmail,drive);
        when(transport.dataMode()).thenReturn("SYNTHETIC");
        when(gmail.accessToken()).thenReturn("synthetic-gmail-token");when(drive.accessToken()).thenReturn("synthetic-drive-token");
        when(transport.get(contains("/profile"),anyString())).thenReturn(Fixtures.JSON.readTree("{\"emailAddress\":\"owner@example.test\"}"));
        when(transport.get(contains("/threads?"),anyString())).thenReturn(Fixtures.JSON.readTree("{\"threads\":[{\"id\":\"m1-thread\"}]}"));
        try(var in=getClass().getResourceAsStream("/fixtures/gmail-demo-invoice.json")){
            when(transport.get(contains("/threads/m1-thread?"),anyString())).thenReturn(Fixtures.JSON.readTree(in));
        }
        when(transport.get(contains("/about?"),anyString())).thenReturn(Fixtures.JSON.readTree("{\"user\":{\"permissionId\":\"synthetic-permission\"}}"));
        when(transport.get(contains("/files/synthetic-folder?"),anyString())).thenReturn(SandboxBoundaryTest.root());
        var file=DriveFixtures.file("m1-file",DriveExtractor.DOCS,"Synthetic rental invoice");
        var page=Fixtures.JSON.createObjectNode();page.putArray("files").add(file);
        when(transport.get(contains("/files?"),anyString())).thenReturn(page);
        when(transport.get(contains("/files/m1-file?"),anyString())).thenReturn(file);
        when(transport.download(anyString(),anyString(),anyInt())).thenReturn("Rental invoice amount is USD 1200.".getBytes(StandardCharsets.UTF_8));
        assertThat(post("/api/gmail/export?maxThreads=1","").getStatusCode().value()).isEqualTo(200);
        assertThat(post("/api/drive/export?maxFiles=1","").getStatusCode().value()).isEqualTo(200);
        byte[] gmailBefore=Files.readAllBytes(Path.of("target/m1-http/gmail.json")),driveBefore=Files.readAllBytes(Path.of("target/m1-http/drive.json"));
        for(String source:List.of("GMAIL","DRIVE"))assertThat(post("/api/unified/ingest?source="+source,"").getStatusCode().value()).isEqualTo(200);
        var rows=Fixtures.JSON.readTree(http.exchange("/api/unified/search?limit=100",HttpMethod.GET,auth(""),String.class).getBody());assertThat(rows.size()).isEqualTo(2);
        var answer=Fixtures.JSON.readValue(post("/api/qa/ask","{\"question\":\"What is rental invoice amount across Gmail and Drive?\"}").getBody(),QuestionAnswer.class);
        assertThat(answer.status()).isEqualTo("EVIDENCE_FOUND");assertThat(answer.evidence()).hasSize(2);
        for(var evidence:answer.evidence()){
            var row=java.util.stream.StreamSupport.stream(rows.spliterator(),false).filter(n->n.path("source").path("document_id").asText().equals(evidence.documentId())).findFirst().orElseThrow();
            var s=row.path("source");assertThat(evidence.sourceVersion()).isEqualTo(s.path("source_version").asText());
            assertThat(evidence.sourceId()).isEqualTo(s.path("external_id").asText());assertThat(evidence.excerpt()).isEqualTo(s.path("content_text").asText().substring(evidence.startOffset(),evidence.endOffset()));
            assertThat(GmailAnalyzer.hash(s.path("content_text").asText())).isEqualTo(s.path("content_hash").asText());
        }
        clearInvocations(transport,gmail,drive);
        when(transport.get(contains("/profile"),anyString())).thenReturn(Fixtures.JSON.readTree("{\"emailAddress\":\"unauthorized@example.test\"}"));
        var denied=post("/api/gmail/export?maxThreads=1","");assertThat(denied.getStatusCode().value()).isEqualTo(403);assertThat(denied.getBody()).doesNotContain("unauthorized");
        verify(transport,never()).get(contains("/threads"),anyString());
        var excluded=file.deepCopy();excluded.putArray("parents").add("outside-folder");excluded.put("name","M1_REJECTED_CANARY");
        var badPage=Fixtures.JSON.createObjectNode();badPage.putArray("files").add(excluded);when(transport.get(contains("/files?"),anyString())).thenReturn(badPage);
        assertThat(post("/api/drive/export?maxFiles=1","").getStatusCode().value()).isEqualTo(403);
        verify(transport,never()).download(anyString(),anyString(),anyInt());
        // An observable move during download must also abort export before classification/write.
        when(transport.get(contains("/files?"),anyString())).thenReturn(page);
        when(transport.get(contains("/files/m1-file?"),anyString())).thenReturn(file,excluded);
        when(transport.download(anyString(),anyString(),anyInt())).thenReturn("M1_REJECTED_CANARY".getBytes(StandardCharsets.UTF_8));
        assertThat(post("/api/drive/export?maxFiles=1","").getStatusCode().value()).isEqualTo(403);
        assertThat(Files.readAllBytes(Path.of("target/m1-http/gmail.json"))).isEqualTo(gmailBefore);assertThat(Files.readAllBytes(Path.of("target/m1-http/drive.json"))).isEqualTo(driveBefore);
        var search=http.exchange("/api/unified/search?keyword=M1_REJECTED_CANARY",HttpMethod.GET,auth(""),String.class);assertThat(search.getBody()).isEqualTo("[]");
        var missing=Fixtures.JSON.readValue(post("/api/qa/ask","{\"question\":\"Show M1 REJECTED CANARY records\"}").getBody(),QuestionAnswer.class);assertThat(missing.evidence()).isEmpty();assertThat(missing.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(logs.getAll()).doesNotContain("M1_REJECTED_CANARY","unauthorized@example.test","Rental invoice amount is USD 1200.");
    }
}
