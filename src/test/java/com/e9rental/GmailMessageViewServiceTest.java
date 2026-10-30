package com.e9rental;

import com.e9rental.connector.GmailGateway;
import com.e9rental.connector.GmailRequestBudget;
import com.e9rental.config.GoogleSandboxPolicy;
import com.e9rental.model.*;
import com.e9rental.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GmailMessageViewServiceTest {
    private static final String TENANT="practice-local";
    private static final String PRINCIPAL="a".repeat(64);
    private static final String SESSION="00000000-0000-4000-8000-000000000001";
    private static final GmailMessageSelectionRegistry.RequestScope SCOPE=new GmailMessageSelectionRegistry.RequestScope(PRINCIPAL,SESSION);
    private static final String BODY="Synthetic full email body with readable paragraphs.";
    private final GoogleSandboxPolicy sandbox=GmailGatewayTest.sandbox();
    private final GmailGateway gateway=mock(GmailGateway.class);
    private final GmailMessageSelectionRegistry registry=new GmailMessageSelectionRegistry(java.time.Clock.fixed(
        Instant.parse("2026-10-20T10:00:00Z"),java.time.ZoneOffset.UTC));
    private final GmailMessageViewService service=new GmailMessageViewService(registry,gateway,new GmailMessageBodyExtractor(),sandbox,TENANT);

    @Test void returnsTransientMinimalDetailOnlyAfterSearchSelectionAndFreshApproval() throws Exception {
        SearchRecord record=record("synthetic-message-id",sandbox.gmailRecordAccount(),BODY);
        String selection=service.register(record,SCOPE);
        assertThat(selection).isNotBlank().doesNotContain("synthetic-message-id");
        String approval=service.approve(selection,SCOPE);
        when(gateway.readSelectedQuestionMessage(eq("synthetic-message-id"),eq(sandbox.gmailRecordAccount()),any())).thenReturn(message());
        GmailMessageDetail result=service.view(approval,SCOPE);
        assertThat(result.subject()).isEqualTo("Synthetic subject");
        assertThat(result.sender()).isEqualTo("Synthetic Sender <sender@example.test>");
        assertThat(result.recipient()).isEqualTo("Recipient <recipient@example.test>");
        assertThat(result.receivedAt()).isEqualTo(Instant.ofEpochMilli(1792411200000L));
        assertThat(result.bodyText()).isEqualTo(BODY);assertThat(result.bodyAvailable()).isTrue();
        assertThat(result.incomplete()).isFalse();assertThat(result.bodyTruncated()).isFalse();
        String serialized=Fixtures.JSON.writeValueAsString(result);
        assertThat(serialized).doesNotContain("synthetic-message-id","connectorAccountId","messageId","sourceId","attachmentId");
        verify(gateway,times(1)).readSelectedQuestionMessage(eq("synthetic-message-id"),eq(sandbox.gmailRecordAccount()),any());
    }
    @Test void missingApprovalOrForeignSearchRecordNeverCallsGmail() {
        assertThatThrownBy(()->service.view("unapproved",SCOPE)).isInstanceOf(RuntimeException.class);
        assertThat(service.register(record("foreign-id","foreign-account",BODY),SCOPE)).isNull();
        assertThat(service.register(record("synthetic-mode-id",sandbox.gmailRecordAccount(),BODY,"SYNTHETIC"),SCOPE)).isNull();
        verifyNoInteractions(gateway);
    }
    @Test void approvalCannotCrossAuthenticatedPrincipalOrBrowserSession() {
        String selection=service.register(record("synthetic-message-2",sandbox.gmailRecordAccount(),BODY),SCOPE);
        var foreignPrincipal=new GmailMessageSelectionRegistry.RequestScope("b".repeat(64),SESSION);
        var foreignSession=new GmailMessageSelectionRegistry.RequestScope(PRINCIPAL,"00000000-0000-4000-8000-000000000002");
        assertThatThrownBy(()->service.approve(selection,foreignPrincipal)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->service.approve(selection,foreignSession)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(gateway);
    }
    @Test void tenantAndMessageIdentityAreNeverReturnedInDetailDto() throws Exception {
        String selection=service.register(record("synthetic-message-3",sandbox.gmailRecordAccount(),BODY),SCOPE);
        String approval=service.approve(selection,SCOPE);
        when(gateway.readSelectedQuestionMessage(anyString(),anyString(),any())).thenReturn(message());
        String serialized=Fixtures.JSON.writeValueAsString(service.view(approval,SCOPE));
        assertThat(serialized).doesNotContain(TENANT,"synthetic-message-3",sandbox.gmailRecordAccount());
    }

    @Test void timeoutDoesNotRestoreConsumedApprovalOrExposeLateMessage() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        String selection=service.register(record("synthetic-timeout-id",sandbox.gmailRecordAccount(),BODY),SCOPE);
        String approval=service.approve(selection,SCOPE);
        when(gateway.readSelectedQuestionMessage(eq("synthetic-timeout-id"),eq(sandbox.gmailRecordAccount()),any()))
            .thenAnswer(call->{entered.countDown();boolean done=false;while(!done)try{done=release.await(20,java.util.concurrent.TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}
                return message();});
        try(var executor=new GmailViewerOperationExecutor()) {
            var budget=GmailRequestBudget.singleMessageView(java.time.Duration.ofMillis(100));
            assertThatThrownBy(()->executor.execute(budget,()->service.view(approval,SCOPE,budget)))
                .isInstanceOf(RuntimeException.class).hasMessage("Gmail message request timed out");
            assertThat(entered.await(100,java.util.concurrent.TimeUnit.MILLISECONDS)).isTrue();
            release.countDown();
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
            while(executor.availableAdmissions()<4 && System.nanoTime()<end)Thread.sleep(5);
            assertThatThrownBy(()->service.view(approval,SCOPE)).isInstanceOf(RuntimeException.class);
            verify(gateway,times(1)).readSelectedQuestionMessage(eq("synthetic-timeout-id"),eq(sandbox.gmailRecordAccount()),any());
        }
    }

    private SearchRecord record(String id,String account,String body) {
        return record(id,account,body,"LIVE");
    }
    private SearchRecord record(String id,String account,String body,String dataMode) {
        var source=new SourceRecord("doc-"+id,TENANT,account,"GMAIL",id,"thread-"+id,"Synthetic subject",body,"text/plain",
            com.e9rental.service.GmailAnalyzer.hash(body),"v1",Instant.now(),null,Instant.now(),Instant.now(),Instant.now(),null,Map.of("synthetic",true));
        return new SearchRecord(source,dataMode,"other",null,Map.of(),new SearchRecord.Citation(source.documentId(),"v1","GMAIL","locator",null));
    }
    private JsonNode message() throws Exception {
        String data=Base64.getUrlEncoder().withoutPadding().encodeToString(BODY.getBytes(StandardCharsets.UTF_8));
        return Fixtures.JSON.readTree("""
            {"id":"synthetic-message-id","internalDate":"1792411200000","payload":{"mimeType":"text/plain","headers":[
            {"name":"Subject","value":"Synthetic subject"},{"name":"From","value":"Synthetic Sender <sender@example.test>"},
            {"name":"To","value":"Recipient <recipient@example.test>"}],"body":{"data":"%s"}}}
            """.formatted(data));
    }
}
